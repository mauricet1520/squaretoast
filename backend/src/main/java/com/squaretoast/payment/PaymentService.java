package com.squaretoast.payment;

import com.squaretoast.domain.money.CurrencyCode;
import com.squaretoast.domain.money.Money;
import com.squaretoast.domain.payment.ApplicationFeeCalculator;
import com.squaretoast.domain.payment.ChargeRequest;
import com.squaretoast.domain.payment.GatewayException;
import com.squaretoast.domain.payment.GatewayIntent;
import com.squaretoast.domain.payment.PaymentGatewayPort;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    private record Existing(UUID id, UUID checkId, boolean claim, String method, String status, long amountCents,
                            long tipCents, long feeCents, String currency, @Nullable String intentRef,
                            @Nullable String clientSecret) {}

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** Statuses that still hold part of a balance: the guest or reader may yet complete them. */
    static final Set<String> IN_FLIGHT = Set.of("PENDING", "REQUIRES_ACTION");

    /** After this long an unfinished payment is treated as abandoned and canceled at the processor. */
    static final int STALE_AFTER_MINUTES = 15;

    public enum Withdrawal { WITHDRAWN, NOT_FOUND, NOT_CANCELABLE }

    /**
     * Serialises every payment decision for one check id, whether it arrives as a server check or as the offline
     * claim that names the same id, so the two paths can never both see a balance as unpaid.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockTarget(UUID checkId) {
        db.sql("select pg_advisory_xact_lock(hashtextextended(cast(? as text), 1))").params("payment:" + checkId)
                .query((rs, n) -> n).list();
    }

    /**
     * Cancels this check's unfinished payments older than {@link #STALE_AFTER_MINUTES} at the processor, so an
     * abandoned payment stops holding the balance. One the processor will not cancel (it may have just succeeded)
     * keeps holding it. Calls the processor, so call it outside any transaction.
     */
    public void releaseStale(UUID checkId) {
        record Stale(UUID id, @Nullable String intent) {}
        List<Stale> stale = db.sql("""
                select id, processor_intent_ref from payment
                where (check_id = ? or claim_check_id = ?) and status in ('PENDING', 'REQUIRES_ACTION')
                  and created_at < now() - make_interval(mins => ?)""")
                .params(checkId, checkId, STALE_AFTER_MINUTES)
                .query((rs, n) -> new Stale(rs.getObject("id", UUID.class), rs.getString("processor_intent_ref")))
                .list();
        stale.forEach(s -> cancel(s.id(), s.intent()));
    }

    /**
     * A guest withdraws their own unconfirmed QR payment, e.g. to change the tip. Knowing the payment's random
     * idempotency key is the proof; POS card-present payments cannot be withdrawn this way.
     */
    public Withdrawal withdraw(UUID idempotencyKey) {
        Optional<Existing> found = find(idempotencyKey);
        if (found.isEmpty() || !found.get().method().equals(CardMethod.QR_CNP.name())) {
            return Withdrawal.NOT_FOUND;
        }
        Existing e = found.get();
        if (!IN_FLIGHT.contains(e.status())) {
            return Withdrawal.NOT_CANCELABLE;
        }
        return cancel(e.id(), e.intentRef()) ? Withdrawal.WITHDRAWN : Withdrawal.NOT_CANCELABLE;
    }

    /** Cancels at the processor first; the row is marked CANCELED only once the processor agreed. */
    private boolean cancel(UUID paymentId, @Nullable String intentRef) {
        if (intentRef != null) {
            try {
                gateway.cancelIntent(intentRef);
            } catch (GatewayException e) {
                log.info("processor would not cancel intent {} ({})", intentRef, e.code());
                return false;
            }
        }
        db.sql("update payment set status = 'CANCELED' where id = ? and status in ('PENDING', 'REQUIRES_ACTION')")
                .params(paymentId).update();
        return true;
    }

    private record Context(String checkStatus, String currency, @Nullable String connectedAccount, UUID merchantId) {}

    private record Spread(int bps, long fixedCents) {}

    public StartedPayment start(CardPaymentCommand cmd) {
        Optional<Existing> existing = find(cmd.idempotencyKey());
        if (existing.isPresent()) {
            Existing e = existing.get();
            if (!e.checkId().equals(cmd.checkId()) || e.claim() != cmd.offlineClaim() || !e.method().equals(cmd.method().name())
                    || e.amountCents() != cmd.amountCents() || e.tipCents() != cmd.tipCents()) {
                throw new PaymentException.IdempotencyConflict();
            }
            if (e.intentRef() != null) {
                return new StartedPayment(e.id(), e.intentRef(), e.clientSecret());
            }
            if (!IN_FLIGHT.contains(e.status())) {
                throw new PaymentException.IdempotencyConflict(); // released as stale before Stripe was reached
            }
        }

        Context ctx = openContext(cmd);
        String account = Objects.requireNonNull(ctx.connectedAccount());

        UUID paymentId;
        ChargeRequest request;
        if (existing.isPresent()) {
            Existing e = existing.get();
            CurrencyCode currency = CurrencyCode.of(e.currency());
            paymentId = e.id();
            request = new ChargeRequest(cmd.idempotencyKey(), cmd.checkId(), Money.ofCents(e.amountCents(), currency),
                    Money.ofCents(e.tipCents(), currency), Money.ofCents(e.feeCents(), currency), account);
        } else {
            Optional<Pending> pending = insertPending(cmd, ctx, account);
            if (pending.isEmpty()) {
                return start(cmd); // a concurrent start took the key first; the next pass sees its row
            }
            paymentId = pending.get().id();
            request = pending.get().request();
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

    /**
     * Records the PENDING payment inside the caller's transaction, without calling the processor. Callers that
     * compute the amount under a lock (the check balance) reserve first, commit, then call {@link #start}.
     * Does nothing if this idempotency key already has a payment.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(CardPaymentCommand cmd) {
        if (find(cmd.idempotencyKey()).isPresent()) {
            return;
        }
        Context ctx = openContext(cmd);
        insertPending(cmd, ctx, Objects.requireNonNull(ctx.connectedAccount()));
    }

    /** The amount already recorded for this idempotency key, if any. */
    public OptionalLong reservedAmount(UUID idempotencyKey) {
        return find(idempotencyKey).map(e -> OptionalLong.of(e.amountCents())).orElse(OptionalLong.empty());
    }

    private record Pending(UUID id, ChargeRequest request) {}

    private Context openContext(CardPaymentCommand cmd) {
        Context ctx = cmd.offlineClaim() ? claimContext(cmd.checkId()) : context(cmd.checkId());
        if (!ctx.checkStatus().equals("OPEN")) {
            throw new PaymentException.CheckNotOpen();
        }
        if (ctx.connectedAccount() == null) {
            throw new PaymentException.NotConfigured("no Stripe account");
        }
        return ctx;
    }

    /** Empty when a concurrent request inserted this idempotency key first. */
    private Optional<Pending> insertPending(CardPaymentCommand cmd, Context ctx, String account) {
        CurrencyCode currency = CurrencyCode.of(ctx.currency());
        Money amount = Money.ofCents(cmd.amountCents(), currency);
        Spread spread = spread(ctx.merchantId(), cmd.method());
        Money fee = ApplicationFeeCalculator.fee(amount, spread.bps(), spread.fixedCents());
        ChargeRequest request = new ChargeRequest(cmd.idempotencyKey(), cmd.checkId(), amount,
                Money.ofCents(cmd.tipCents(), currency), fee, account);
        UUID paymentId = UUID.randomUUID();
        int inserted = db.sql("""
                insert into payment(id, check_id, claim_check_id, processor, method, amount_cents, tip_cents,
                                    application_fee_cents, currency, status, idempotency_key)
                values (?, ?, ?, 'STRIPE', ?, ?, ?, ?, ?, 'PENDING', ?)
                on conflict (idempotency_key) do nothing""")
                .params(Arrays.asList(paymentId, cmd.offlineClaim() ? null : cmd.checkId(),
                        cmd.offlineClaim() ? cmd.checkId() : null, cmd.method().name(), amount.cents(),
                        request.tip().cents(), fee.cents(), currency.code(), cmd.idempotencyKey()))
                .update();
        return inserted == 0 ? Optional.empty() : Optional.of(new Pending(paymentId, request));
    }

    private Optional<Existing> find(UUID idempotencyKey) {
        return db.sql("""
                select id, coalesce(check_id, claim_check_id) as target, claim_check_id is not null as claim, method, status,
                       amount_cents, tip_cents, application_fee_cents, currency, processor_intent_ref, client_secret
                from payment where idempotency_key = ?""")
                .params(idempotencyKey)
                .query((rs, n) -> new Existing(rs.getObject("id", UUID.class), rs.getObject("target", UUID.class),
                        rs.getBoolean("claim"), rs.getString("method"), rs.getString("status"), rs.getLong("amount_cents"), rs.getLong("tip_cents"),
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

    /** An offline claim is charged like an open check of the claim's location. */
    private Context claimContext(UUID claimCheckId) {
        return db.sql("""
                select l.currency, l.merchant_id, pa.external_account_id
                from offline_check_claim c
                join location l on l.id = c.location_id
                left join processor_account pa on pa.merchant_id = l.merchant_id and pa.processor = 'STRIPE'
                where c.check_id = ?""")
                .params(claimCheckId)
                .query((rs, n) -> new Context("OPEN", rs.getString("currency"), rs.getString("external_account_id"),
                        rs.getObject("merchant_id", UUID.class)))
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
