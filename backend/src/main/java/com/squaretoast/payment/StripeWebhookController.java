package com.squaretoast.payment;

import com.squaretoast.payment.adapter.stripe.InvalidWebhookSignatureException;
import com.squaretoast.payment.adapter.stripe.StripeWebhookVerifier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stripe webhook endpoint. The raw bytes are verified before anything is parsed. 200 = processed or already seen;
 * 400 = not from Stripe or not an event; 5xx = handling failed and nothing was kept, so Stripe retries.
 */
@RestController
class StripeWebhookController {

    private final StripeWebhookVerifier verifier;
    private final StripeWebhookService webhooks;

    StripeWebhookController(StripeWebhookVerifier verifier, StripeWebhookService webhooks) {
        this.verifier = verifier;
        this.webhooks = webhooks;
    }

    @PostMapping(path = "/webhooks/stripe", version = "1")
    ResponseEntity<Void> receive(@RequestBody byte[] payload,
                                 @RequestHeader(name = "Stripe-Signature", required = false) @Nullable String signature) {
        verifier.verify(payload, signature);
        webhooks.handle(payload);
        return ResponseEntity.ok().build();
    }

    @ExceptionHandler
    ResponseEntity<Void> badSignature(InvalidWebhookSignatureException e) {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler
    ResponseEntity<Void> malformed(MalformedWebhookException e) {
        return ResponseEntity.badRequest().build();
    }
}
