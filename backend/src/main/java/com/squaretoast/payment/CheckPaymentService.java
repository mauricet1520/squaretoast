package com.squaretoast.payment;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pays a guest check its server-computed balance plus the caller's tip, for the POS (card present) and the
 * guest's phone (QR). Under the check's row lock: read the balance and record the PENDING payment, then commit.
 * Concurrent payments on one check serialise there, so the second sees the first as in flight. Stripe is called
 * after the commit.
 */
@Service
public class CheckPaymentService {

    private final CheckBalance balances;
    private final PaymentService payments;
    private final JdbcClient db;
    private final TransactionTemplate tx;

    CheckPaymentService(CheckBalance balances, PaymentService payments, JdbcClient db, PlatformTransactionManager txm) {
        this.balances = balances;
        this.payments = payments;
        this.db = db;
        this.tx = new TransactionTemplate(txm);
    }

    /** Throws {@link PaymentException.NotFound} unless the check is at {@code location}. */
    public StartedPayment start(UUID checkId, UUID location, UUID key, CardMethod method, long tipCents) {
        long amount = Objects.requireNonNull(tx.execute(s -> {
            boolean locked = db.sql("""
                    select g.id from guest_check g join orders o on o.id = g.order_id
                    where g.id = ? and o.location_id = ? for update of g""")
                    .params(checkId, location).query(UUID.class).optional()
                    .isPresent();
            if (!locked) {
                throw new PaymentException.NotFound();
            }
            OptionalLong reserved = payments.reservedAmount(key);
            if (reserved.isPresent()) {
                return reserved.getAsLong(); // a retry: the original amount, even if the check is now settled
            }
            CheckBalance.Owed owed = balances.owed(checkId, location, key);
            if (owed.due().cents() <= 0) {
                throw new PaymentException.NothingDue();
            }
            long total = Math.addExact(owed.due().cents(), tipCents);
            payments.reserve(new CardPaymentCommand(key, checkId, method, total, tipCents));
            return total;
        }));
        return payments.start(new CardPaymentCommand(key, checkId, method, amount, tipCents));
    }
}
