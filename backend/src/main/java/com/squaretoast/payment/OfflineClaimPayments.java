package com.squaretoast.payment;

import com.squaretoast.payment.OfflineClaims.Claim;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Charges a verified offline claim: the signed total, less what is paid or in flight under other keys, plus the
 * guest's tip. Like a check payment, the amount is decided under a row lock (the claim's) and the PENDING
 * payment is committed before Stripe is called.
 */
@Service
class OfflineClaimPayments {

    /** The nonce was already signed into a claim for another check. */
    static final class Replayed extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** A different claim for this check is being paid, has been paid, or is newer. */
    static final class Superseded extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    record Result(StartedPayment payment, long claimTotalCents) {}

    @SuppressWarnings("ArrayRecordComponent")
    private record Stored(UUID location, byte[] payload, Instant expiresAt) {}

    private final OfflineClaims claims;
    private final PaymentService payments;
    private final JdbcClient db;
    private final TransactionTemplate tx;

    OfflineClaimPayments(OfflineClaims claims, PaymentService payments, JdbcClient db, PlatformTransactionManager txm) {
        this.claims = claims;
        this.payments = payments;
        this.db = db;
        this.tx = new TransactionTemplate(txm);
    }

    Result pay(String token, UUID key, long tipCents) {
        PaymentException.TipOutOfRange.check(tipCents);
        Claim c = claims.verify(token);
        payments.releaseStale(c.checkId()); // abandoned payments stop holding the balance (calls Stripe, no tx open)
        long amount = Objects.requireNonNull(tx.execute(s -> {
            payments.lockTarget(c.checkId()); // shared with the synced server check of the same id
            int freshNonce = db.sql("""
                    insert into offline_claim_nonce(device_id, nonce, check_id) values (?, ?, ?)
                    on conflict (device_id, nonce) do nothing""")
                    .params(c.deviceId(), c.nonce(), c.checkId()).update();
            if (freshNonce == 0) {
                UUID owner = db.sql("select check_id from offline_claim_nonce where device_id = ? and nonce = ?")
                        .params(c.deviceId(), c.nonce()).query(UUID.class).single();
                if (!owner.equals(c.checkId())) {
                    throw new Replayed();
                }
            }

            db.sql("""
                    insert into offline_check_claim(check_id, device_id, location_id, claim_sig, payload, expires_at,
                                                    amount_cents, currency, status, reconciled_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING',
                            -- the POS may have synced this check before the guest scanned the claim
                            (select now() from guest_check g join orders o on o.id = g.order_id
                             where g.id = ? and o.location_id = ?))
                    on conflict (check_id) do nothing""")
                    .params(c.checkId(), c.deviceId(), c.locationId(), c.signature(), c.payload(),
                            Timestamp.from(c.expiresAt()), c.totalCents(), c.currency(), c.checkId(), c.locationId())
                    .update();
            Stored stored = db.sql("""
                    select location_id, payload, expires_at from offline_check_claim where check_id = ? for update""")
                    .params(c.checkId())
                    .query((rs, n) -> new Stored(rs.getObject("location_id", UUID.class), rs.getBytes("payload"),
                            rs.getTimestamp("expires_at").toInstant()))
                    .single();
            if (!stored.location().equals(c.locationId())) {
                throw new OfflineClaims.Invalid(); // that check id was claimed at another location
            }
            if (!OfflineClaims.samePayload(c, stored.payload())) {
                boolean busy = db.sql("""
                        select count(*) from payment where claim_check_id = ?
                        and status in ('SUCCEEDED', 'PENDING', 'REQUIRES_ACTION')""")
                        .params(c.checkId()).query(Long.class).single() > 0;
                if (busy || !c.expiresAt().isAfter(stored.expiresAt())) {
                    throw new Superseded();
                }
                db.sql("""
                        update offline_check_claim set device_id = ?, claim_sig = ?, payload = ?, expires_at = ?,
                               amount_cents = ?, currency = ?, status = 'PENDING'
                        where check_id = ?""")
                        .params(c.deviceId(), c.signature(), c.payload(), Timestamp.from(c.expiresAt()), c.totalCents(),
                                c.currency(), c.checkId())
                        .update();
            }

            OptionalLong reserved = payments.reservedAmount(key);
            if (reserved.isPresent()) {
                return reserved.getAsLong();
            }
            long paid = db.sql("""
                    select coalesce(sum(amount_cents - tip_cents), 0) from payment
                    where (claim_check_id = ? or check_id = ?) and (status = 'SUCCEEDED'
                       or (status in ('PENDING', 'REQUIRES_ACTION') and idempotency_key <> ?))""")
                    .params(c.checkId(), c.checkId(), key).query(Long.class).single();
            long due = c.totalCents() - paid;
            if (due <= 0) {
                throw new PaymentException.NothingDue();
            }
            long total = Math.addExact(due, tipCents);
            payments.reserve(new CardPaymentCommand(key, c.checkId(), CardMethod.QR_CNP, total, tipCents, true));
            return total;
        }));
        StartedPayment p = payments.start(new CardPaymentCommand(key, c.checkId(), CardMethod.QR_CNP, amount, tipCents, true));
        return new Result(p, c.totalCents());
    }
}
