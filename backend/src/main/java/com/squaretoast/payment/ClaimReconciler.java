package com.squaretoast.payment;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ties an offline claim to the check the POS synced under the same id (plan S6-7, S14-7). A paid claim becomes
 * RECONCILED, unless its signed total is not what the server computes for that check: then it is MISMATCH, so the
 * owner can look. The money taken is never touched here.
 */
@Component
public class ClaimReconciler {

    private static final Logger log = LoggerFactory.getLogger(ClaimReconciler.class);
    private static final UUID NO_KEY = new UUID(0, 0);

    private final JdbcClient db;
    private final CheckBalance balance;

    ClaimReconciler(JdbcClient db, CheckBalance balance) {
        this.db = db;
        this.balance = balance;
    }

    /** The POS synced {@code check} with all its lines: mark its claim (if any) reconciled. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void checkSynced(UUID check, UUID location) {
        db.sql("""
                update offline_check_claim set reconciled_at = now(),
                       status = case when status = 'PAID' then 'RECONCILED' else status end
                where check_id = ? and location_id = ?""")
                .params(check, location).update();
        flagMismatch(check, location);
    }

    /** A RECONCILED claim whose signed total differs from the synced check's total becomes MISMATCH. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void flagMismatch(UUID check, UUID location) {
        Optional<Long> signed = db.sql("""
                select amount_cents from offline_check_claim where check_id = ? and location_id = ? and status = 'RECONCILED'""")
                .params(check, location).query(Long.class).optional();
        if (signed.isEmpty()) {
            return;
        }
        CheckBalance.Owed owed = balance.owed(check, location, NO_KEY);
        long total = owed.subtotal().plus(owed.serviceCharges()).plus(owed.tax()).cents();
        if (total != signed.get()) {
            db.sql("update offline_check_claim set status = 'MISMATCH' where check_id = ? and status = 'RECONCILED'")
                    .params(check).update();
            log.warn("Offline claim for check {} does not match its synced total; flagged for review", check);
        }
    }
}
