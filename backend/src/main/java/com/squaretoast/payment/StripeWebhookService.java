package com.squaretoast.payment;

import com.squaretoast.domain.ledger.CardSettlement;
import com.squaretoast.domain.money.CurrencyCode;
import com.squaretoast.domain.money.Money;
import com.squaretoast.domain.payment.PaymentGatewayPort;
import com.squaretoast.ledger.LedgerService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
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

    private final JdbcClient db;
    private final JsonMapper json;
    private final PaymentGatewayPort gateway;
    private final LedgerService ledger;

    StripeWebhookService(JdbcClient db, JsonMapper json, PaymentGatewayPort gateway, LedgerService ledger) {
        this.db = db;
        this.json = json;
        this.gateway = gateway;
        this.ledger = ledger;
    }

    private record LockedPayment(UUID id, long amountCents, long tipCents, long feeCents, String currency, UUID locationId) {}

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
        }
        db.sql("update webhook_event set processed_at = now() where event_id = ?").params(eventId).update();
    }

    private void settleCharge(JsonNode charge) {
        String intentRef = text(charge.path("payment_intent"));
        if (!"succeeded".equals(text(charge.path("status"))) || intentRef == null) {
            return;
        }
        Optional<LockedPayment> found = db.sql("""
                select p.id, p.amount_cents, p.tip_cents, p.application_fee_cents, p.currency, o.location_id
                from payment p
                join guest_check c on c.id = p.check_id
                join orders o on o.id = c.order_id
                where p.processor = 'STRIPE' and p.processor_intent_ref = ?
                for update of p""")
                .params(intentRef)
                .query((rs, n) -> new LockedPayment(rs.getObject("id", UUID.class), rs.getLong("amount_cents"),
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
        db.sql("update payment set status = 'SUCCEEDED' where id = ? and status in ('PENDING', 'REQUIRES_ACTION')")
                .params(p.id()).update();

        String balanceTxn = text(charge.path("balance_transaction"));
        if (balanceTxn == null || ledger.hasEntry(CardSettlement.KIND, intentRef)) {
            return; // fee not known yet (a later charge.updated carries it), or already settled
        }
        CurrencyCode currency = CurrencyCode.of(p.currency());
        Money processorFee = gateway.processorFee(balanceTxn);
        ledger.append(p.locationId(), CardSettlement.txn(intentRef, Money.ofCents(p.amountCents(), currency),
                Money.ofCents(p.tipCents(), currency), Money.ofCents(p.feeCents(), currency), processorFee));
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
