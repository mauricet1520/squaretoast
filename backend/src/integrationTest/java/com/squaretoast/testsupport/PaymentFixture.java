package com.squaretoast.testsupport;

import com.squaretoast.payment.adapter.stripe.StripeSignatures;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Seeds a Stripe-connected location with a pricing plan and an open check; posts signed webhooks. */
public final class PaymentFixture {

    public static final String CONNECTED_ACCOUNT = "acct_1TestMerchant";

    private final JdbcClient db;
    private final String baseUrl;
    private final String webhookSecret;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    public PaymentFixture(DataSource ds, int port, String webhookSecret) {
        this.db = JdbcClient.create(ds);
        this.baseUrl = "http://localhost:" + port + "/api/v1";
        this.webhookSecret = webhookSecret;
    }

    /** A location whose merchant is onboarded with Stripe on a plan with the given card-present/not-present spreads. */
    public UUID location(int cpBps, long cpFixed, int cnpBps, long cnpFixed) {
        UUID merchant = UUID.randomUUID();
        UUID loc = UUID.randomUUID();
        UUID plan = UUID.randomUUID();
        db.sql("insert into merchant(id, legal_name) values (?, 'Pay Bistro')").params(merchant).update();
        db.sql("insert into location(id, merchant_id, name, state_code, timezone) values (?, ?, 'Main', 'US', 'America/New_York')")
                .params(loc, merchant).update();
        db.sql("insert into processor_account(id, merchant_id, processor, external_account_id) values (?, ?, 'STRIPE', ?)")
                .params(UUID.randomUUID(), merchant, CONNECTED_ACCOUNT).update();
        db.sql("""
                insert into pricing_plan(id, code, cp_spread_bps, cp_fixed_cents, cnp_spread_bps, cnp_fixed_cents)
                values (?, ?, ?, ?, ?, ?)""")
                .params(plan, "plan-" + plan, cpBps, cpFixed, cnpBps, cnpFixed).update();
        // An expired plan first, so the lookup must pick the one effective now.
        UUID oldPlan = UUID.randomUUID();
        db.sql("insert into pricing_plan(id, code, cp_spread_bps, cnp_spread_bps) values (?, ?, 9999, 9999)")
                .params(oldPlan, "old-" + oldPlan).update();
        db.sql("insert into merchant_pricing(merchant_id, plan_id, effective_from, effective_to) values (?, ?, now() - interval '30 days', now() - interval '1 day')")
                .params(merchant, oldPlan).update();
        db.sql("insert into merchant_pricing(merchant_id, plan_id, effective_from) values (?, ?, now() - interval '1 day')")
                .params(merchant, plan).update();
        return loc;
    }

    /** A location whose merchant has no processor account. */
    public UUID locationWithoutProcessor() {
        UUID merchant = UUID.randomUUID();
        UUID loc = UUID.randomUUID();
        db.sql("insert into merchant(id, legal_name) values (?, 'Cash Only')").params(merchant).update();
        db.sql("insert into location(id, merchant_id, name, state_code, timezone) values (?, ?, 'Main', 'US', 'America/New_York')")
                .params(loc, merchant).update();
        return loc;
    }

    public UUID openCheck(UUID location) {
        UUID order = UUID.randomUUID();
        UUID check = UUID.randomUUID();
        db.sql("insert into orders(id, location_id, fulfillment_channel, status) values (?, ?, 'DINE_IN', 'COMMITTED')")
                .params(order, location).update();
        db.sql("insert into guest_check(id, order_id, label) values (?, ?, 'Seat 1')").params(check, order).update();
        return check;
    }

    public void setCheckStatus(UUID check, String status) {
        db.sql("update guest_check set status = ? where id = ?").params(status, check).update();
    }

    public Map<String, Object> payment(UUID idempotencyKey) {
        return db.sql("select * from payment where idempotency_key = ?").params(idempotencyKey).query().singleRow();
    }

    public UUID idempotencyKeyOf(UUID paymentId) {
        return db.sql("select idempotency_key from payment where id = ?").params(paymentId).query(UUID.class).single();
    }

    public long paymentCount(UUID check) {
        return db.sql("select count(*) from payment where check_id = ?").params(check).query(Long.class).single();
    }

    public long settlementCount(String intentRef) {
        return db.sql("select count(*) from journal_txn where kind = 'CARD_SETTLEMENT' and source_ref = ?")
                .params(intentRef).query(Long.class).single();
    }

    /** account -> signed cents of the single settlement txn for {@code intentRef}. */
    public Map<String, Long> settlementLines(String intentRef) {
        List<Map<String, Object>> rows = db.sql("""
                select l.account, l.amount_cents, l.currency, t.location_id
                from journal_line l join journal_txn t on t.id = l.txn_id
                where t.kind = 'CARD_SETTLEMENT' and t.source_ref = ?""").params(intentRef).query().listOfRows();
        Map<String, Long> out = new java.util.TreeMap<>();
        for (Map<String, Object> r : rows) {
            out.merge((String) r.get("account"), ((Number) r.get("amount_cents")).longValue(), Long::sum);
        }
        return out;
    }

    public @Nullable UUID settlementLocation(String intentRef) {
        return db.sql("select location_id from journal_txn where kind = 'CARD_SETTLEMENT' and source_ref = ?")
                .params(intentRef).query(UUID.class).optional().orElse(null);
    }

    public long webhookEventCount(String eventId) {
        return db.sql("select count(*) from webhook_event where event_id = ?").params(eventId).query(Long.class).single();
    }

    public Map<String, Object> webhookEvent(String eventId) {
        return db.sql("select * from webhook_event where event_id = ?").params(eventId).query().singleRow();
    }

    // ---------------------------------------------------------------- webhooks

    public static String chargeEvent(String eventId, String type, String intentRef, long amountCents,
                                     @Nullable String balanceTransaction) {
        String bt = balanceTransaction == null ? "null" : "\"" + balanceTransaction + "\"";
        return """
                {"id":"%s","object":"event","type":"%s","livemode":false,"created":1800000000,
                 "data":{"object":{"id":"ch_%s","object":"charge","amount":%d,"currency":"usd","status":"succeeded",
                 "payment_intent":"%s","balance_transaction":%s,
                 "payment_method_details":{"type":"card_present","card_present":{"brand":"visa","last4":"4242"}}}}}"""
                .formatted(eventId, type, intentRef, amountCents, intentRef, bt);
    }

    /** A payment_intent.* event, e.g. payment_failed after a decline or canceled. */
    public static String intentEvent(String eventId, String type, String intentRef, String status) {
        return """
                {"id":"%s","object":"event","type":"%s","livemode":false,"created":1800000000,
                 "data":{"object":{"id":"%s","object":"payment_intent","status":"%s"}}}"""
                .formatted(eventId, type, intentRef, status);
    }

    /** Makes a payment look as if it was started {@code minutes} ago. */
    public void agePayment(UUID idempotencyKey, int minutes) {
        db.sql("update payment set created_at = now() - make_interval(mins => ?) where idempotency_key = ?")
                .params(minutes, idempotencyKey).update();
    }

    public HttpResponse<String> postWebhook(String payload) {
        return postWebhook(payload, StripeSignatures.header(webhookSecret, System.currentTimeMillis() / 1000, payload));
    }

    public HttpResponse<String> postWebhook(String payload, @Nullable String signature) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/webhooks/stripe"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (signature != null) {
            b.header("Stripe-Signature", signature);
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
