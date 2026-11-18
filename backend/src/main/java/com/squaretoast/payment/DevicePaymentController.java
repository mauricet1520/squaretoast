package com.squaretoast.payment;

import com.squaretoast.device.DevicePrincipal;
import com.squaretoast.domain.payment.GatewayException;
import com.squaretoast.domain.payment.PaymentGatewayPort;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payment endpoints for authenticated POS devices. The device chooses only the tip; the amount is the check's
 * server-computed balance (closes the Slice 4 "amount from the caller" risk).
 */
@RestController
class DevicePaymentController {

    /** No amount field: anything a client sends for one is ignored. */
    record StartRequest(@Nullable UUID idempotencyKey, @Nullable String method, @Nullable Long tipCents) {}

    record Started(UUID paymentId, @Nullable String clientSecret) {}

    record ConnectionToken(String secret) {}

    record Problem(String error) {}

    private static final class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private final CheckBalance balances;
    private final PaymentService payments;
    private final PaymentGatewayPort gateway;
    private final JdbcClient db;
    private final TransactionTemplate tx;

    DevicePaymentController(CheckBalance balances, PaymentService payments, PaymentGatewayPort gateway, JdbcClient db,
                            PlatformTransactionManager txm) {
        this.balances = balances;
        this.payments = payments;
        this.gateway = gateway;
        this.db = db;
        this.tx = new TransactionTemplate(txm);
    }

    @PostMapping(path = "/checks/{checkId}/payments", version = "1")
    ResponseEntity<?> start(@RequestAttribute(DevicePrincipal.ATTRIBUTE) DevicePrincipal device,
                            @PathVariable UUID checkId, @RequestBody StartRequest req) {
        UUID key = req.idempotencyKey();
        Long tip = req.tipCents();
        CardMethod method = parse(req.method());
        if (key == null || tip == null || tip < 0 || method == null) {
            throw new BadRequest();
        }
        // Under the check's row lock: read the balance and record the PENDING payment, then commit. Concurrent
        // payments on one check serialise here, so the second sees the first as in flight. Stripe is called after.
        long amount = Objects.requireNonNull(tx.execute(s -> {
            boolean locked = db.sql("""
                    select g.id from guest_check g join orders o on o.id = g.order_id
                    where g.id = ? and o.location_id = ? for update of g""")
                    .params(checkId, device.locationId()).query(UUID.class).optional()
                    .isPresent();
            if (!locked) {
                throw new PaymentException.NotFound();
            }
            OptionalLong reserved = payments.reservedAmount(key);
            if (reserved.isPresent()) {
                return reserved.getAsLong(); // a retry: the original amount, even if the check is now settled
            }
            CheckBalance.Owed owed = balances.owed(checkId, device.locationId(), key);
            if (owed.due().cents() <= 0) {
                throw new NothingDue();
            }
            long total = Math.addExact(owed.due().cents(), tip);
            payments.reserve(new CardPaymentCommand(key, checkId, method, total, tip));
            return total;
        }));
        StartedPayment p = payments.start(new CardPaymentCommand(key, checkId, method, amount, tip));
        return ResponseEntity.ok(new Started(p.paymentId(), p.clientSecret()));
    }

    private static final class NothingDue extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    @ExceptionHandler
    ResponseEntity<Problem> nothingDue(NothingDue e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Problem("NOTHING_DUE"));
    }

    /** A platform-account token scoped to the location's Terminal Location (destination charges). */
    @PostMapping(path = "/terminal/connection_token", version = "1")
    ResponseEntity<?> connectionToken(@RequestAttribute(DevicePrincipal.ATTRIBUTE) DevicePrincipal device) {
        String terminalLocation = db.sql("select stripe_terminal_location_id from location where id = ?")
                .params(device.locationId()).query(String.class).optional().orElse(null);
        if (terminalLocation == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new Problem("TERMINAL_NOT_CONFIGURED"));
        }
        return ResponseEntity.ok(new ConnectionToken(gateway.createConnectionToken(terminalLocation)));
    }

    private static @Nullable CardMethod parse(@Nullable String method) {
        if (method == null) {
            return null;
        }
        try {
            return CardMethod.valueOf(method);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @ExceptionHandler
    ResponseEntity<Void> badRequest(BadRequest e) {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler
    ResponseEntity<Void> notFound(PaymentException.NotFound e) {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler
    ResponseEntity<Problem> checkNotOpen(PaymentException.CheckNotOpen e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Problem("CHECK_NOT_OPEN"));
    }

    @ExceptionHandler
    ResponseEntity<Problem> notConfigured(PaymentException.NotConfigured e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Problem("NOT_CONFIGURED"));
    }

    @ExceptionHandler
    ResponseEntity<Problem> idempotency(PaymentException.IdempotencyConflict e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Problem("IDEMPOTENCY_KEY_REUSED"));
    }

    /** Only the processor's error code leaves the server, never its message. */
    @ExceptionHandler
    ResponseEntity<Problem> gateway(GatewayException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new Problem(e.code()));
    }
}
