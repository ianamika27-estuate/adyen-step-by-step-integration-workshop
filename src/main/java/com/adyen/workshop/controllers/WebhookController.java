package com.adyen.workshop.controllers;

import com.adyen.model.notification.NotificationRequest;
import com.adyen.model.notification.NotificationRequestItem;
import com.adyen.util.HMACValidator;
import com.adyen.workshop.configurations.ApplicationConfiguration;
import org.apache.coyote.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.security.SignatureException;
import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for receiving Adyen webhook notifications
 */
@RestController
public class WebhookController {
    private final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final ApplicationConfiguration applicationConfiguration;

    private final HMACValidator hmacValidator;

    // In-memory storage for tokens (recurring detail references)
    // In production, this should be stored in a database
    private static final Map<String, String> tokenStorage = new HashMap<>();

    @Autowired
    public WebhookController(ApplicationConfiguration applicationConfiguration, HMACValidator hmacValidator) {
        this.applicationConfiguration = applicationConfiguration;
        this.hmacValidator = hmacValidator;
    }

    // Step 16 - Validate the HMAC signature using the ADYEN_HMAC_KEY
    @PostMapping("/webhooks")
    public ResponseEntity<String> webhooks(@RequestBody String json) throws Exception {
        log.info("Received: {}", json);
        var notificationRequest = NotificationRequest.fromJson(json);
        var notificationRequestItem = notificationRequest.getNotificationItems().stream().findFirst();

        try {
            NotificationRequestItem item = notificationRequestItem.get();

            // Step 16 - Validate the HMAC signature using the ADYEN_HMAC_KEY
            if (!hmacValidator.validateHMAC(item, this.applicationConfiguration.getAdyenHmacKey())) {
                log.warn("Could not validate HMAC signature for incoming webhook message: {}", item);
                return ResponseEntity.unprocessableEntity().build();
            }

            // Step 17 - Handle webhooks
            String eventCode = item.getEventCode();
            log.info("Handling webhook event: {}", eventCode);

            switch (eventCode) {
                // Tokenization webhooks
                case "RECURRING_CONTRACT":
                    // Handle RECURRING_CONTRACT webhook
                    // This webhook contains the recurringDetailReference (token) we need for future payments
                    handleRecurringContractWebhook(item);
                    break;
                case "AUTHORISATION":
                    // Handle AUTHORISATION webhook
                    handleAuthorisationWebhook(item);
                    break;
                // Preauthorisation webhooks
                case "AUTHORISATION_ADJUSTMENT":
                    // Handle authorization adjustment webhook
                    handleAuthorisationAdjustmentWebhook(item);
                    break;
                case "CAPTURE":
                    // Handle capture webhook
                    handleCaptureWebhook(item);
                    break;
                case "CAPTURE_FAILED":
                    // Handle capture failed webhook
                    handleCaptureFailedWebhook(item);
                    break;
                case "TECHNICAL_CANCEL":
                    // Handle technical cancellation webhook
                    handleTechnicalCancelWebhook(item);
                    break;
                case "CANCELLATION":
                    // Handle cancellation webhook
                    handleCancellationWebhook(item);
                    break;
                case "REFUND":
                    // Handle refund webhook
                    handleRefundWebhook(item);
                    break;
                case "REFUND_FAILED":
                    // Handle refund failed webhook
                    handleRefundFailedWebhook(item);
                    break;
                case "REFUNDED_REVERSED":
                    // Handle refunded reversed webhook
                    handleRefundedReversedWebhook(item);
                    break;
                default:
                    log.info("Unhandled webhook event code: {}", eventCode);
            }

            // Success, log it for now
            log.info("Received webhook with event {}", item.toString());

            return ResponseEntity.accepted().build();
        } catch (SignatureException e) {
            // Handle invalid signature
            return ResponseEntity.unprocessableEntity().build();
        } catch (Exception e) {
            // Handle all other errors
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Handle RECURRING_CONTRACT webhook
     * This webhook contains the recurringDetailReference which is the token we need
     */
    private void handleRecurringContractWebhook(NotificationRequestItem item) {
        log.info("Processing RECURRING_CONTRACT webhook");
        
        String recurringDetailReference = item.getAdditionalData() != null 
            ? item.getAdditionalData().get("recurring.recurringDetailReference") 
            : null;
        
        if (recurringDetailReference != null) {
            // Store the token for later use
            // In production, store this in a database with the shopper reference
            String shopperReference = item.getAdditionalData().get("recurring.shopperReference");
            tokenStorage.put(shopperReference, recurringDetailReference);
            
            log.info("Stored token for shopper {}: {}", shopperReference, recurringDetailReference);
        } else {
            log.warn("No recurringDetailReference found in RECURRING_CONTRACT webhook");
        }
    }

    /**
     * Handle AUTHORISATION webhook
     * This webhook indicates a payment has been authorized
     */
    private void handleAuthorisationWebhook(NotificationRequestItem item) {
        log.info("Processing AUTHORISATION webhook");
        log.info("Payment reference: {}", item.getPspReference());
        log.info("Merchant reference: {}", item.getMerchantReference());
        
        log.info("Authorization processed for reference: {}", item.getPspReference());
    }

    /**
     * Handle AUTHORISATION_ADJUSTMENT webhook
     * This webhook indicates an authorization amount adjustment
     */
    private void handleAuthorisationAdjustmentWebhook(NotificationRequestItem item) {
        log.info("Processing AUTHORISATION_ADJUSTMENT webhook");
        log.info("PSP Reference: {}", item.getPspReference());
        log.info("Merchant Reference: {}", item.getMerchantReference());
        log.info("Success: {}", item.isSuccess());
        
        if (item.isSuccess()) {
            log.info("Authorization adjustment successful for reference: {}", item.getPspReference());
        } else {
            log.warn("Authorization adjustment failed for reference: {}", item.getPspReference());
        }
    }

    /**
     * Handle CAPTURE webhook
     * This webhook indicates a payment has been captured
     */
    private void handleCaptureWebhook(NotificationRequestItem item) {
        log.info("Processing CAPTURE webhook");
        log.info("PSP Reference: {}", item.getPspReference());
        log.info("Merchant Reference: {}", item.getMerchantReference());
        log.info("Success: {}", item.isSuccess());
        
        if (item.isSuccess()) {
            log.info("Payment captured successfully for reference: {}", item.getPspReference());
        } else {
            log.warn("Payment capture failed for reference: {}", item.getPspReference());
        }
    }

    /**
     * Handle CAPTURE_FAILED webhook
     * This webhook indicates a capture failure
     */
    private void handleCaptureFailedWebhook(NotificationRequestItem item) {
        log.warn("Processing CAPTURE_FAILED webhook");
        log.warn("PSP Reference: {}", item.getPspReference());
        log.warn("Merchant Reference: {}", item.getMerchantReference());
        log.warn("Reason: {}", item.getReason());
        log.warn("Payment capture failed for reference: {}", item.getPspReference());
    }

    /**
     * Handle TECHNICAL_CANCEL webhook
     * This webhook indicates a technical cancellation
     */
    private void handleTechnicalCancelWebhook(NotificationRequestItem item) {
        log.info("Processing TECHNICAL_CANCEL webhook");
        log.info("PSP Reference: {}", item.getPspReference());
        log.info("Merchant Reference: {}", item.getMerchantReference());
        log.info("Success: {}", item.isSuccess());
        
        if (item.isSuccess()) {
            log.info("Technical cancellation successful for reference: {}", item.getPspReference());
        } else {
            log.warn("Technical cancellation failed for reference: {}", item.getPspReference());
        }
    }

    /**
     * Handle CANCELLATION webhook
     * This webhook indicates a payment has been cancelled
     */
    private void handleCancellationWebhook(NotificationRequestItem item) {
        log.info("Processing CANCELLATION webhook");
        log.info("PSP Reference: {}", item.getPspReference());
        log.info("Merchant Reference: {}", item.getMerchantReference());
        log.info("Success: {}", item.isSuccess());
        
        if (item.isSuccess()) {
            log.info("Payment cancellation successful for reference: {}", item.getPspReference());
        } else {
            log.warn("Payment cancellation failed for reference: {}", item.getPspReference());
        }
    }

    /**
     * Handle REFUND webhook
     * This webhook indicates a payment has been refunded
     */
    private void handleRefundWebhook(NotificationRequestItem item) {
        log.info("Processing REFUND webhook");
        log.info("PSP Reference: {}", item.getPspReference());
        log.info("Merchant Reference: {}", item.getMerchantReference());
        log.info("Success: {}", item.isSuccess());
        
        if (item.isSuccess()) {
            log.info("Payment refunded successfully for reference: {}", item.getPspReference());
        } else {
            log.warn("Payment refund failed for reference: {}", item.getPspReference());
        }
    }

    /**
     * Handle REFUND_FAILED webhook
     * This webhook indicates a refund failure
     */
    private void handleRefundFailedWebhook(NotificationRequestItem item) {
        log.warn("Processing REFUND_FAILED webhook");
        log.warn("PSP Reference: {}", item.getPspReference());
        log.warn("Merchant Reference: {}", item.getMerchantReference());
        log.warn("Reason: {}", item.getReason());
        log.warn("Payment refund failed for reference: {}", item.getPspReference());
    }

    /**
     * Handle REFUNDED_REVERSED webhook
     * This webhook indicates a refund reversal
     */
    private void handleRefundedReversedWebhook(NotificationRequestItem item) {
        log.warn("Processing REFUNDED_REVERSED webhook");
        log.warn("PSP Reference: {}", item.getPspReference());
        log.warn("Merchant Reference: {}", item.getMerchantReference());
        log.warn("Reason: {}", item.getReason());
        log.warn("Refund reversal for reference: {}", item.getPspReference());
    }

    /**
     * Get stored token for a shopper
     * This is used internally by the subscription payment endpoint
     */
    public static String getToken(String shopperReference) {
        return tokenStorage.get(shopperReference);
    }
}