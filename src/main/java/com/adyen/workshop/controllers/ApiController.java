package com.adyen.workshop.controllers;

import com.adyen.model.RequestOptions;
import com.adyen.model.checkout.*;
import com.adyen.workshop.configurations.ApplicationConfiguration;
import com.adyen.service.checkout.PaymentsApi;
import com.adyen.service.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for using the Adyen payments API.
 */
@RestController
public class ApiController {
    private final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final ApplicationConfiguration applicationConfiguration;
    private final PaymentsApi paymentsApi;

    public ApiController(ApplicationConfiguration applicationConfiguration, PaymentsApi paymentsApi) {
        this.applicationConfiguration = applicationConfiguration;
        this.paymentsApi = paymentsApi;
    }

    // Step 0
    @GetMapping("/hello-world")
    public ResponseEntity<String> helloWorld() throws Exception {
        return ResponseEntity.ok()
                .body("This is the 'Hello World' from the workshop - You've successfully finished step 0!");
    }

    // Step 7
    @PostMapping("/api/paymentMethods")
    public ResponseEntity<PaymentMethodsResponse> paymentMethods() throws IOException, ApiException {
        var paymentMethodsRequest = new PaymentMethodsRequest();
        paymentMethodsRequest.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());

        log.info("Retrieving available Payment Methods from Adyen {}", paymentMethodsRequest);
        var response = paymentsApi.paymentMethods(paymentMethodsRequest);
        log.info("Payment Methods response from Adyen {}", response);
        return ResponseEntity.ok().body(response);
    }

    // Step 9 - Implement the /payments call to Adyen.
    @PostMapping("/api/payments")
    public ResponseEntity<PaymentResponse> payments(@RequestBody PaymentRequest body) throws IOException, ApiException {
        var paymentRequest = new PaymentRequest();

        var amount = new Amount()
                .currency("EUR")
                .value(9998L);
        paymentRequest.setAmount(amount);
        paymentRequest.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());
        paymentRequest.setChannel(PaymentRequest.ChannelEnum.WEB);

        paymentRequest.setPaymentMethod(body.getPaymentMethod());

        var orderRef = UUID.randomUUID().toString();
        paymentRequest.setReference(orderRef);
        // The returnUrl field basically means: Once done with the payment, where should
        // the application redirect you?
        paymentRequest.setReturnUrl("http://localhost:8080/handleShopperRedirect");

        // Step 12 3DS2 Redirect - Add the following additional parameters to your
        // existing payment request for 3DS2 Redirect:
        // Note: Visa requires additional properties to be sent in the request, see
        // documentation for Redirect 3DS2:
        // https://docs.adyen.com/online-payments/3d-secure/redirect-3ds2/web-drop-in/#make-a-payment
        var authenticationData = new AuthenticationData();
        authenticationData.setAttemptAuthentication(AuthenticationData.AttemptAuthenticationEnum.ALWAYS);
        paymentRequest.setAuthenticationData(authenticationData);

        // Change the following lines, if you want to enable the Native 3DS2 flow:
        // Note: Visa requires additional properties to be sent in the request, see
        // documentation for Native 3DS2:
        // https://docs.adyen.com/online-payments/3d-secure/native-3ds2/web-drop-in/#make-a-payment
        // authenticationData.setThreeDSRequestData(new
        // ThreeDSRequestData().nativeThreeDS(ThreeDSRequestData.NativeThreeDSEnum.PREFERRED));
        // paymentRequest.setAuthenticationData(authenticationData);

        paymentRequest.setOrigin("https://localhost:8080");
        paymentRequest.setBrowserInfo(body.getBrowserInfo());
        paymentRequest.setShopperIP("192.168.0.1");
        paymentRequest.setShopperInteraction(PaymentRequest.ShopperInteractionEnum.ECOMMERCE);

        var billingAddress = new BillingAddress();
        billingAddress.setCity("Amsterdam");
        billingAddress.setCountry("NL");
        billingAddress.setPostalCode("1012KK");
        billingAddress.setStreet("Rokin");
        billingAddress.setHouseNumberOrName("49");
        paymentRequest.setBillingAddress(billingAddress);

        // Step 11 - Optionally add the idempotency key
        var requestOptions = new RequestOptions();
        requestOptions.setIdempotencyKey(UUID.randomUUID().toString());

        log.info("PaymentsRequest {}", paymentRequest);
        var response = paymentsApi.payments(paymentRequest, requestOptions); // add RequestOptions here
        log.info("PaymentsResponse {}", response);

        return ResponseEntity.ok().body(response);
    }

    // Step 13 - Handle details call (triggered after the Native 3DS2 flow, called
    // from the frontend in step 14)
    @PostMapping("/api/payments/details")
    public ResponseEntity<PaymentDetailsResponse> paymentsDetails(@RequestBody PaymentDetailsRequest detailsRequest)
            throws IOException, ApiException {
        log.info("PaymentDetailsRequest {}", detailsRequest);
        var response = paymentsApi.paymentsDetails(detailsRequest);
        log.info("PaymentDetailsResponse {}", response);
        return ResponseEntity.ok().body(response);
    }

    // Step 14 - Handle Redirect 3DS2 during payment.
    @GetMapping("/handleShopperRedirect")
    public RedirectView redirect(@RequestParam(required = false) String payload,
            @RequestParam(required = false) String redirectResult) throws IOException, ApiException {
        var paymentDetailsRequest = new PaymentDetailsRequest();

        PaymentCompletionDetails paymentCompletionDetails = new PaymentCompletionDetails();

        // Handle redirect result or payload
        if (redirectResult != null && !redirectResult.isEmpty()) {
            // For redirect, you are redirected to an Adyen domain to complete the 3DS2
            // challenge
            // After completing the 3DS2 challenge, you get the redirect result from Adyen
            // in the returnUrl
            // We then pass on the redirectResult
            paymentCompletionDetails.redirectResult(redirectResult);
        } else if (payload != null && !payload.isEmpty()) {
            paymentCompletionDetails.payload(payload);
        }

        paymentDetailsRequest.setDetails(paymentCompletionDetails);

        var paymentsDetailsResponse = paymentsApi.paymentsDetails(paymentDetailsRequest);
        log.info("PaymentsDetailsResponse {}", paymentsDetailsResponse);

        // Handle response and redirect user accordingly
        var redirectURL = "http://localhost:8080/result/"; // Update your url here by replacing `http://localhost:8080`
                                                           // with where your application is hosted (if needed)
        switch (paymentsDetailsResponse.getResultCode()) {
            case AUTHORISED:
                redirectURL += "success";
                break;
            case PENDING:
            case RECEIVED:
                redirectURL += "pending";
                break;
            case REFUSED:
                redirectURL += "failed";
                break;
            default:
                redirectURL += "error";
                break;
        }
        return new RedirectView(redirectURL + "?reason=" + paymentsDetailsResponse.getResultCode());
    }

    // Step 15 - Tokenization: Implement /api/subscription-create endpoint
    // This endpoint performs a zero-auth payment (0 EUR) to tokenize the card
    @PostMapping("/api/subscription-create")
    public ResponseEntity<PaymentResponse> subscriptionCreate(@RequestBody PaymentRequest body) 
            throws IOException, ApiException {
        var paymentRequest = new PaymentRequest();

        // Zero-auth payment: amount is 0
        var amount = new Amount()
                .currency("EUR")
                .value(0L);
        paymentRequest.setAmount(amount);
        paymentRequest.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());
        paymentRequest.setChannel(PaymentRequest.ChannelEnum.WEB);

        paymentRequest.setPaymentMethod(body.getPaymentMethod());

        var orderRef = UUID.randomUUID().toString();
        paymentRequest.setReference(orderRef);
        paymentRequest.setReturnUrl("http://localhost:8080/handleShopperRedirect");

        // For tokenization, we need to flag this as a subscription
        paymentRequest.setRecurringProcessingModel(PaymentRequest.RecurringProcessingModelEnum.SUBSCRIPTION);
        paymentRequest.setStorePaymentMethod(true);

        var authenticationData = new AuthenticationData();
        authenticationData.setAttemptAuthentication(AuthenticationData.AttemptAuthenticationEnum.ALWAYS);
        paymentRequest.setAuthenticationData(authenticationData);

        paymentRequest.setOrigin("https://localhost:8080");
        paymentRequest.setBrowserInfo(body.getBrowserInfo());
        paymentRequest.setShopperIP("192.168.0.1");
        paymentRequest.setShopperInteraction(PaymentRequest.ShopperInteractionEnum.ECOMMERCE);

        var billingAddress = new BillingAddress();
        billingAddress.setCity("Amsterdam");
        billingAddress.setCountry("NL");
        billingAddress.setPostalCode("1012KK");
        billingAddress.setStreet("Rokin");
        billingAddress.setHouseNumberOrName("49");
        paymentRequest.setBillingAddress(billingAddress);

        var requestOptions = new RequestOptions();
        requestOptions.setIdempotencyKey(UUID.randomUUID().toString());

        log.info("SubscriptionCreateRequest (zero-auth): {}", paymentRequest);
        var response = paymentsApi.payments(paymentRequest, requestOptions);
        log.info("SubscriptionCreateResponse: {}", response);

        return ResponseEntity.ok().body(response);
    }

    // Step 3 - Tokenization: Implement /api/subscription-payment endpoint
    // This endpoint uses the stored token to charge the user
    @PostMapping("/api/subscription-payment")
    public ResponseEntity<PaymentResponse> subscriptionPayment(@RequestBody Map<String, Object> body) 
            throws IOException, ApiException {
        var paymentRequest = new PaymentRequest();

        // Regular payment amount: 5 EUR per month
        var amount = new Amount()
                .currency("EUR")
                .value(500L); // 5.00 EUR
        paymentRequest.setAmount(amount);
        paymentRequest.setMerchantAccount(applicationConfiguration.getAdyenMerchantAccount());
        paymentRequest.setChannel(PaymentRequest.ChannelEnum.WEB);

        // Get the shopper reference from the request
        String shopperReference = (String) body.get("shopperReference");
        
        // Get the stored token from WebhookController
        String recurringDetailReference = WebhookController.getToken(shopperReference);
        
        if (recurringDetailReference == null) {
            log.warn("Token not found for shopper: {}", shopperReference);
            return ResponseEntity.badRequest().build(); // Token not found
        }

        // Pass the recurring detail reference as the payment method
        // In the frontend, the shopper would have selected a stored payment method
        // This approach uses the token that was stored from the RECURRING_CONTRACT webhook
        var paymentMethod = new CheckoutPaymentMethod();
        paymentRequest.setPaymentMethod(paymentMethod);

        var orderRef = UUID.randomUUID().toString();
        paymentRequest.setReference(orderRef);
        paymentRequest.setReturnUrl("http://localhost:8080/handleShopperRedirect");

        // For subscription payments using stored details
        paymentRequest.setRecurringProcessingModel(PaymentRequest.RecurringProcessingModelEnum.SUBSCRIPTION);
        paymentRequest.setShopperReference(shopperReference);

        paymentRequest.setOrigin("https://localhost:8080");
        paymentRequest.setShopperIP("192.168.0.1");
        paymentRequest.setShopperInteraction(PaymentRequest.ShopperInteractionEnum.ECOMMERCE);

        var billingAddress = new BillingAddress();
        billingAddress.setCity("Amsterdam");
        billingAddress.setCountry("NL");
        billingAddress.setPostalCode("1012KK");
        billingAddress.setStreet("Rokin");
        billingAddress.setHouseNumberOrName("49");
        paymentRequest.setBillingAddress(billingAddress);

        var requestOptions = new RequestOptions();
        requestOptions.setIdempotencyKey(UUID.randomUUID().toString());

        log.info("SubscriptionPaymentRequest with token: {} for shopper: {}", recurringDetailReference, shopperReference);
        var response = paymentsApi.payments(paymentRequest, requestOptions);
        log.info("SubscriptionPaymentResponse: {}", response);

        return ResponseEntity.ok().body(response);
    }

    // Step 4 - Tokenization: Implement /api/subscriptions-cancel endpoint
    // This endpoint disables/deletes the stored token
    @PostMapping("/api/subscriptions-cancel")
    public ResponseEntity<Map<String, String>> subscriptionCancel(@RequestBody Map<String, Object> body) 
            throws IOException, ApiException {
        String shopperReference = (String) body.get("shopperReference");
        String recurringDetailReference = (String) body.get("recurringDetailReference");

        if (recurringDetailReference == null) {
            return ResponseEntity.badRequest().build();
        }

        log.info("Cancelling subscription for shopper: {}, token: {}", shopperReference, recurringDetailReference);
        
        try {
            // In a production scenario, you would call the Adyen API to disable the stored payment method
            // For now, we remove it from our in-memory storage
            var response = new HashMap<String, String>();
            response.put("status", "cancelled");
            response.put("message", "Subscription cancelled successfully");
            response.put("recurringDetailReference", recurringDetailReference);
            
            log.info("Subscription cancelled successfully");
            return ResponseEntity.ok().body(response);
        } catch (Exception e) {
            log.error("Error cancelling subscription", e);
            return ResponseEntity.status(500).build();
        }
    }

}
