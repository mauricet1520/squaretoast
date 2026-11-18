package com.squaretoast.payment;

import com.squaretoast.domain.money.CurrencyCode;
import com.squaretoast.domain.money.Money;
import com.squaretoast.domain.payment.ApplicationFeeCalculator;
import com.squaretoast.domain.payment.ChargeRequest;
import com.squaretoast.domain.payment.GatewayIntent;
import com.squaretoast.domain.payment.PaymentGatewayPort;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Starts a destination charge for a check. The payment row (keyed by the caller's idempotency key) is committed
 * before Stripe is called and the intent is attached after, so no DB transaction is held open across the network
 * and a retry with the same key either returns the stored intent or re-asks Stripe with the same Idempotency-Key.
 */
@Service
public class PaymentService {

    private final JdbcClient db;
    private final PaymentGatewayPort gateway;

    PaymentService(JdbcClient db, PaymentGatewayPort gateway) {
        this.db = db;
        this.gateway = gateway;
    }

    private record Existing(UUID id, UUID checkId, String method, long amountCents, long tipCents, long feeCents,
                            String currency, @Nullable String intentRef, @Nullable String clientSecret) {}

    private record Context(String checkStatus, String currency, @Nullable String connectedAccount, UUID merchantId) {}

    private record Spread(int bps, long fixedCents) {}

    public StartedPayment start(CardPaymentCommand cmd) {
        Optional<Existing> existing = find(cmd.idempotencyKey());
        if (existing.isPresent()) {
            Existing e = existing.get();
            if (!e.checkId().equals(cmd.checkId()) || !e.method().equals(cmd.method().name())
                    || e.amountCents() != cmd.amountCents() || e.tipCents() != cmd.tipCents()) {
                throw new PaymentException.IdempotencyConflict();
            }
            if (e.intentRef() != null) {
                return new StartedPayment(e.id(), e.intentRef(), e.clientSecret());
            }
        }

        Context ctx = context(cmd.checkId());
        if (!ctx.checkStatus().equals("OPEN")) {
            throw new PaymentException.CheckNotOpen();
        }
        String account = ctx.connectedAccount();
        if (account == null) {
            throw new PaymentException.NotConfigured("no Stripe account");
        }

        UUID paymentId;
        ChargeRequest request;
        if (existing.isPresent()) {
            Existing e = existing.get();
            CurrencyCode currency = CurrencyCode.of(e.currency());
            paymentId = e.id();
            request = new ChargeRequest(cmd.idempotencyKey(), cmd.checkId(), Money.ofCents(e.amountCents(), currency),
                    Money.ofCents(e.tipCents(), currency), Money.ofCents(e.feeCents(), currency), account);
        } else {
            CurrencyCode currency = CurrencyCode.of(ctx.currency());
            Money amount = Money.ofCents(cmd.amountCents(), currency);
            Spread spread = spread(ctx.merchantId(), cmd.method());
            Money fee = ApplicationFeeCalculator.fee(amount, spread.bps(), spread.fixedCents());
            request = new ChargeRequest(cmd.idempotencyKey(), cmd.checkId(), amount, Money.ofCents(cmd.tipCents(), currency),
                    fee, account);
            paymentId = UUID.randomUUID();
            int inserted = db.sql("""
                    insert into payment(id, check_id, processor, method, amount_cents, tip_cents, application_fee_cents,
                                        currency, status, idempotency_key)
                    values (?, ?, 'STRIPE', ?, ?, ?, ?, ?, 'PENDING', ?)
                    on conflict (idempotency_key) do nothing""")
                    .params(paymentId, cmd.checkId(), cmd.method().name(), amount.cents(), request.tip().cents(),
                            fee.cents(), currency.code(), cmd.idempotencyKey())
                    .update();
            if (inserted == 0) {
                return start(cmd); // a concurrent start took the key first; the next pass sees its row
            }
        }

        GatewayIntent intent = cmd.method() == CardMethod.CARD_PRESENT
                ? gateway.createCardPresentIntent(request)
                : gateway.createCardNotPresentIntent(request);
        int attached = db.sql("""
                update payment set processor_intent_ref = ?, client_secret = ?
                where id = ? and processor_intent_ref is null""")
                .params(Arrays.asList(intent.intentRef(), intent.clientSecret(), paymentId)).update();
        if (attached == 0) {
            Existing winner = find(cmd.idempotencyKey()).orElseThrow();
            if (winner.intentRef() != null) {
                return new StartedPayment(winner.id(), winner.intentRef(), winner.clientSecret());
            }
        }
        return new StartedPayment(paymentId, intent.intentRef(), intent.clientSecret());
    }

    private Optional<Existing> find(UUID idempotencyKey) {
        return db.sql("""
                select id, check_id, method, amount_cents, tip_cents, application_fee_cents, currency,
                       processor_intent_ref, client_secret
                from payment where idempotency_key = ?""")
                .params(idempotencyKey)
                .query((rs, n) -> new Existing(rs.getObject("id", UUID.class), rs.getObject("check_id", UUID.class),
                        rs.getString("method"), rs.getLong("amount_cents"), rs.getLong("tip_cents"),
                        rs.getLong("application_fee_cents"), rs.getString("currency"),
                        rs.getString("processor_intent_ref"), rs.getString("client_secret")))
                .optional();
    }

    private Context context(UUID checkId) {
        return db.sql("""
                select c.status, l.currency, l.merchant_id, pa.external_account_id
                from guest_check c
                join orders o on o.id = c.order_id
                join location l on l.id = o.location_id
                left join processor_account pa on pa.merchant_id = l.merchant_id and pa.processor = 'STRIPE'
                where c.id = ?""")
                .params(checkId)
                .query((rs, n) -> new Context(rs.getString("status"), rs.getString("currency"),
                        rs.getString("external_account_id"), rs.getObject("merchant_id", UUID.class)))
                .optional()
                .orElseThrow(PaymentException.NotFound::new);
    }

    private Spread spread(UUID merchantId, CardMethod method) {
        String prefix = method == CardMethod.CARD_PRESENT ? "cp" : "cnp";
        return db.sql("""
                select p.%1$s_spread_bps as bps, p.%1$s_fixed_cents as fixed
                from merchant_pricing mp join pricing_plan p on p.id = mp.plan_id
                where mp.merchant_id = ? and p.active
                  and mp.effective_from <= now() and (mp.effective_to is null or mp.effective_to > now())""".formatted(prefix))
                .params(merchantId)
                .query((rs, n) -> new Spread(rs.getInt("bps"), rs.getLong("fixed")))
                .optional()
                .orElseThrow(() -> new PaymentException.NotConfigured("no pricing plan in effect"));
    }
}
