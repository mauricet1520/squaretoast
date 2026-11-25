package com.squaretoast.payment;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public: a guest withdraws their own unconfirmed QR payment, e.g. to change the tip. The payment's random
 * idempotency key, known only to the page that started it, is the proof. Stripe cancels the intent first.
 */
@RestController
class PaymentCancelController {

    record CancelRequest(@Nullable UUID idempotencyKey) {}

    record Problem(String error) {}

    private final PaymentService payments;

    PaymentCancelController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping(path = "/payments/cancel", version = "1")
    ResponseEntity<?> cancel(@RequestBody CancelRequest req) {
        UUID key = req.idempotencyKey();
        if (key == null) {
            return ResponseEntity.badRequest().build();
        }
        return switch (payments.withdraw(key)) {
            case WITHDRAWN -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case NOT_CANCELABLE -> ResponseEntity.status(HttpStatus.CONFLICT).body(new Problem("NOT_CANCELABLE"));
        };
    }
}
