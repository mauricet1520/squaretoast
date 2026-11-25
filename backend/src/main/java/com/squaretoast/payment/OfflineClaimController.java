package com.squaretoast.payment;

import com.squaretoast.domain.payment.GatewayException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public: a guest pays a check an offline POS signed into a QR code. The claim is the authorization; the server
 * verifies it and charges its signed total. The guest chooses only the tip. Card entry is Stripe's Payment Element.
 */
@RestController
class OfflineClaimController {

    record PayRequest(@Nullable String claim, @Nullable UUID idempotencyKey, @Nullable Long tipCents) {}

    record Started(UUID paymentId, @Nullable String clientSecret, String publishableKey, long totalCents) {}

    record Problem(String error) {}

    private static final class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private final OfflineClaimPayments claimPayments;
    private final PublishableKey publishableKey;

    OfflineClaimController(OfflineClaimPayments claimPayments, PublishableKey publishableKey) {
        this.claimPayments = claimPayments;
        this.publishableKey = publishableKey;
    }

    @PostMapping(path = "/claims/payments", version = "1")
    Started pay(@RequestBody PayRequest req) {
        UUID key = req.idempotencyKey();
        Long tip = req.tipCents();
        String claim = req.claim();
        if (key == null || tip == null || tip < 0 || claim == null) {
            throw new BadRequest();
        }
        String pk = publishableKey.require();
        OfflineClaimPayments.Result r = claimPayments.pay(claim, key, tip);
        return new Started(r.payment().paymentId(), r.payment().clientSecret(), pk, r.claimTotalCents());
    }

    @ExceptionHandler
    ResponseEntity<Void> badRequest(BadRequest e) {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler
    ResponseEntity<Problem> invalid(OfflineClaims.Invalid e) {
        return ResponseEntity.badRequest().body(new Problem("INVALID_CLAIM"));
    }

    @ExceptionHandler
    ResponseEntity<Problem> replayed(OfflineClaimPayments.Replayed e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Problem("CLAIM_REPLAYED"));
    }

    @ExceptionHandler
    ResponseEntity<Problem> superseded(OfflineClaimPayments.Superseded e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Problem("CLAIM_SUPERSEDED"));
    }

    @ExceptionHandler
    ResponseEntity<Problem> nothingDue(PaymentException.NothingDue e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Problem("NOTHING_DUE"));
    }

    @ExceptionHandler
    ResponseEntity<Problem> notConfigured(PaymentException.NotConfigured e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Problem("NOT_CONFIGURED"));
    }

    @ExceptionHandler
    ResponseEntity<Problem> idempotency(PaymentException.IdempotencyConflict e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Problem("IDEMPOTENCY_KEY_REUSED"));
    }

    @ExceptionHandler
    ResponseEntity<Void> notFound(PaymentException.NotFound e) {
        return ResponseEntity.notFound().build();
    }

    /** Only the processor's error code leaves the server, never its message. */
    @ExceptionHandler
    ResponseEntity<Problem> gateway(GatewayException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new Problem(e.code()));
    }
}
