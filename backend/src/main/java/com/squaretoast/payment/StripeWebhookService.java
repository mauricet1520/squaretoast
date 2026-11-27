package com.squaretoast.payment;

import com.squaretoast.domain.ledger.CardSettlement;
import com.squaretoast.domain.money.CurrencyCode;
import com.squaretoast.domain.money.Money;
import com.squaretoast.domain.payment.PaymentGatewayPort;
import com.squaretoast.ledger.LedgerService;
import com.squaretoast.realtime.RealtimePublisher;
import com.squaretoast.realtime.Topics;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handles an already signature-verified Stripe event exactly once. Dedup is the webhook_event primary key: a replay
 * finds the row and is acknowledged without work; a concurrent duplicate waits on the first delivery's row lock.
 * Everything runs in one transaction, so a failure rolls the dedup row back too and Stripe's retry is processed.
 * Only a SHA-256 of the payload is stored: charge objects carry card details (brand, last4) we do not keep.
 */
@Service
public class StripeWebhookService {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);
    private static final Set<String> CHARGE_EVENTS = Set.of("charge.succeeded", "charge.updated");

    /** A declined or canceled intent stops holding the check's balance at once. */
    private static final Map<String, String> INTENT_ENDED =
            Map.of("payment_intent.payment_failed", "FAILED", "payment_intent.canceled", "CANCELED");

    private void endIntent(JsonNode intent, String status) {
        String ref = text(intent.path("id"));
        if (ref == null) {
            return;
        }
        db.sql("""
                update payment set status = ?
                where processor = 'STRIPE' and processor_intent_ref = ? and status in ('PENDING', 'REQUIRES_ACTION')""")
                .params(status, ref).update();
    }

    private final JdbcClient db;
    private final JsonMapper json;
    private final PaymentGatewayPort gateway;
    private final LedgerService ledger;
    private final RealtimePublisher realtime;
    private final ClaimReconciler claims;

    StripeWebhookService(JdbcClient db, JsonMapper json, PaymentGatewayPort gateway, LedgerService ledger,
                         RealtimePublisher realtime, ClaimReconciler claims) {
        this.db = db;
        this.json = json;
        this.gateway = gateway;
        this.ledger = ledger;
        this.realtime = realtime;
        this.claims = claims;
    }

    private record LockedPayment(UUID id, UUID checkId, long amountCents, long tipCents, long feeCents, String currency,
                                 UUID locationId) {}

    /** Slice 13: only which check (or offline claim check) changed; the POS re-reads the bill itself. */
    record CheckChanged(UUID checkId) {}

    @Transactional
    public void handle(byte[] payload) {
        JsonNode event;
        try {
            event = json.readTree(payload);
        } catch (JacksonException e) {
            throw new MalformedWebhookException();
        }
        String eventId = text(event.path("id"));
        String type = text(event.path("type"));
        if (!event.isObject() || eventId == null || type == null) {
            throw new MalformedWebhookException();
        }
        int fresh = db.sql("""
                insert into webhook_event(event_id, processor, type, payload_sha256) values (?, 'STRIPE', ?, ?)
                on conflict (event_id) do nothing""")
                .params(eventId, type, sha256(payload)).update();
        if (fresh == 0) {
            return;
        }
        if (CHARGE_EVENTS.contains(type)) {
            settleCharge(event.path("data").path("object"));
        } else if (INTENT_ENDED.containsKey(type)) {
            endIntent(event.path("data").path("object"), INTENT_ENDED.get(type));
        }
        db.sql("update webhook_event set processed_at = now() where event_id = ?").params(eventId).update();
    }

    private void settleCharge(JsonNode charge) {
        String intentRef = text(charge.path("payment_intent"));
        if (!"succeeded".equals(text(charge.path("status"))) || intentRef == null) {
            return;
        }
        Optional<LockedPayment> found = db.sql("""
                select p.id, coalesce(p.check_id, p.claim_check_id) as check_id, p.amount_cents, p.tip_cents, p.application_fee_cents, p.currency,
                       coalesce(o.location_id, cl.location_id) as location_id
                from payment p
                left join guest_check c on c.id = p.check_id
                left join orders o on o.id = c.order_id
                left join offline_check_claim cl on cl.check_id = p.claim_check_id
                where p.processor = 'STRIPE' and p.processor_intent_ref = ?
                for update of p""")
                .params(intentRef)
                .query((rs, n) -> new LockedPayment(rs.getObject("id", UUID.class), rs.getObject("check_id", UUID.class), rs.getLong("amount_cents"),
                        rs.getLong("tip_cents"), rs.getLong("application_fee_cents"), rs.getString("currency"),
                        rs.getObject("location_id", UUID.class)))
                .optional();
        if (found.isEmpty()) {
            return; // not a SquareToast payment
        }
        LockedPayment p = found.get();
        JsonNode amount = charge.path("amount");
        if (!amount.isIntegralNumber() || amount.longValue() != p.amountCents()
                || !p.currency().toLowerCase(Locale.ROOT).equals(text(charge.path("currency")))) {
            log.warn("Stripe charge for intent {} does not match payment {}; not settled", intentRef, p.id());
            return;
        }
        // A succeeded charge is money taken: record it even if the intent was marked failed after an earlier decline.
        int settled = db.sql("update payment set status = 'SUCCEEDED' where id = ? and status in ('PENDING', 'REQUIRES_ACTION', 'FAILED', 'CANCELED')")
                .params(p.id()).update();
        // An offline claim is paid once what has settled under it and its synced check, tips aside, covers its
        // total. If the POS has already synced that check, the claim is reconciled at the same time.
        db.sql("""
                update offline_check_claim c
                set status = case when c.reconciled_at is not null then 'RECONCILED' else 'PAID' end
                where c.status = 'PENDING'
                  and c.check_id = (select coalesce(claim_check_id, check_id) from payment where id = ?)
                  and (select coalesce(sum(s.amount_cents - s.tip_cents), 0) from payment s
                       where s.status = 'SUCCEEDED' and (s.claim_check_id = c.check_id or s.check_id = c.check_id))
                      >= c.amount_cents""")
                .params(p.id()).update();
        claims.flagMismatch(p.checkId(), p.locationId()); // a claim paid after its check synced (S14-7)

        // Skipped when the fee is not known yet (a later charge.updated carries it) or is already settled.
        String balanceTxn = text(charge.path("balance_transaction"));
        if (balanceTxn != null && !ledger.hasEntry(CardSettlement.KIND, intentRef)) {
            CurrencyCode currency = CurrencyCode.of(p.currency());
            Money processorFee = gateway.processorFee(balanceTxn);
            ledger.append(p.locationId(), CardSettlement.txn(intentRef, Money.ofCents(p.amountCents(), currency),
                    Money.ofCents(p.tipCents(), currency), Money.ofCents(p.feeCents(), currency), processorFee));
        }
        // Last, after the Stripe fee call and the claim row lock: the location-wide publish lock is then held only
        // until commit, and taken in the same order as sync (claim row, then location), so the two cannot deadlock.
        if (settled > 0) { // first time only: a replayed or updated charge says nothing new
            realtime.publish(p.locationId(), Topics.CHECK, () -> new CheckChanged(p.checkId()));
        }
    }

    private static @Nullable String text(JsonNode node) {
        return node.isString() && !node.stringValue().isEmpty() ? node.stringValue() : null;
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
