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

            // Step 17 - Handle RECURRING_CONTRACT and AUTHORISATION webhooks for tokenization
            String eventCode = item.getEventCode();
            log.info("Handling webhook event: {}", eventCode);

            if ("RECURRING_CONTRACT".equals(eventCode)) {
                // Step 2 - Handle RECURRING_CONTRACT webhook
                // This webhook contains the recurringDetailReference (token) we need for future payments
                handleRecurringContractWebhook(item);
            } else if ("AUTHORISATION".equals(eventCode)) {
                // Step 2 - Handle AUTHORISATION webhook
                handleAuthorisationWebhook(item);
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
     * Get stored token for a shopper
     * This is used internally by the subscription payment endpoint
     */
    public static String getToken(String shopperReference) {
        return tokenStorage.get(shopperReference);
    }
}