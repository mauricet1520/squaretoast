package com.squaretoast.payment;

import com.squaretoast.domain.money.CurrencyCode;
import com.squaretoast.domain.money.Money;
import com.squaretoast.domain.order.SplitAllocator;
import com.squaretoast.domain.tax.FoodTemp;
import com.squaretoast.domain.tax.FulfillmentChannel;
import com.squaretoast.domain.tax.ServiceCharge;
import com.squaretoast.domain.tax.TaxContext;
import com.squaretoast.domain.tax.TaxEngine;
import com.squaretoast.domain.tax.TaxRule;
import com.squaretoast.domain.tax.TaxableLine;
import java.math.BigInteger;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What a guest check still owes, computed only from server data: its share of each line (split exactly with
 * {@link SplitAllocator}), its share of service charges, tax on that share, minus payments already made or
 * in flight. The tip is never part of the balance and is never taxed.
 */
@Component
class CheckBalance {

    record Owed(Money subtotal, Money serviceCharges, Money tax, Money alreadyPaid, Money due, List<LineShare> lines) {}

    /** This check's exact cents of one order line. */
    record LineShare(UUID lineId, long cents) {}

    private record Header(UUID order, UUID location, String channel, String state, String currency, String timezone,
                          boolean ca8080, boolean caSeparate, boolean mandatoryTaxable) {}

    private record LineRow(UUID id, long amount, String taxCategory, String temp) {}

    private record Allocation(UUID check, int numerator, int denominator) {}

    private record Charge(String kind, long amountCents, int rateBps, boolean amountIsFixed, boolean taxable) {}

    private final JdbcClient db;

    CheckBalance(JdbcClient db) {
        this.db = db;
    }

    /** Throws {@link PaymentException.NotFound} unless the check belongs to {@code location}. */
    Owed owed(UUID checkId, UUID location, UUID excludingIdempotencyKey) {
        Header h = db.sql("""
                select o.id as order_id, o.location_id, o.fulfillment_channel, l.state_code, l.currency, l.timezone,
                       l.ca_80_80_applies, l.ca_separate_accounting_elected, p.mandatory_service_charge_taxable
                from guest_check g join orders o on o.id = g.order_id join location l on l.id = o.location_id
                join state_compliance_profile p on p.state_code = l.state_code
                where g.id = ?""")
                .params(checkId)
                .query((rs, n) -> new Header(rs.getObject("order_id", UUID.class), rs.getObject("location_id", UUID.class),
                        rs.getString("fulfillment_channel"), rs.getString("state_code"), rs.getString("currency"),
                        rs.getString("timezone"), rs.getBoolean("ca_80_80_applies"),
                        rs.getBoolean("ca_separate_accounting_elected"), rs.getBoolean("mandatory_service_charge_taxable")))
                .optional()
                .filter(x -> x.location().equals(location))
                .orElseThrow(PaymentException.NotFound::new);
        CurrencyCode currency = CurrencyCode.of(h.currency());

        List<LineRow> lines = db.sql("""
                select l.id, l.qty * (l.unit_price_cents + coalesce(sum(m.price_delta_cents), 0)) as amount,
                       l.tax_category, l.temp
                from order_line l left join order_line_modifier m on m.order_line_id = l.id
                where l.order_id = ? group by l.id order by l.id""")
                .params(h.order())
                .query((rs, n) -> new LineRow(rs.getObject("id", UUID.class), rs.getLong("amount"),
                        rs.getString("tax_category"), rs.getString("temp")))
                .list();

        long orderSubtotal = 0;
        long checkSubtotal = 0;
        List<TaxableLine> taxable = new ArrayList<>();
        List<LineShare> shares = new ArrayList<>();
        for (LineRow line : lines) {
            orderSubtotal += line.amount();
            long share = shareOf(checkId, line);
            if (share > 0) {
                checkSubtotal += share;
                shares.add(new LineShare(line.id(), share));
                taxable.add(new TaxableLine(line.taxCategory(), FoodTemp.valueOf(line.temp()), Money.ofCents(share, currency)));
            }
        }

        List<ServiceCharge> charges = new ArrayList<>();
        long chargeTotal = 0;
        for (Charge c : charges(h.order())) {
            long orderAmount = c.amountIsFixed() ? c.amountCents() : Math.floorDiv(Math.multiplyExact(orderSubtotal, c.rateBps()), 10_000);
            // A check carries the charge in proportion to its share of the order subtotal.
            long share = orderSubtotal == 0 ? 0
                    : SplitAllocator.allocate(orderAmount, List.of(checkSubtotal, orderSubtotal - checkSubtotal)).get(0);
            chargeTotal += share;
            Money m = Money.ofCents(share, currency);
            charges.add(switch (c.kind()) {
                case "MANDATORY" -> ServiceCharge.mandatory(m);
                case "AUTO_GRATUITY" -> ServiceCharge.autoGratuity(m);
                default -> ServiceCharge.deliveryFee(m, c.taxable());
            });
        }

        LocalDate today = LocalDate.now(ZoneId.of(h.timezone()));
        TaxContext ctx = new TaxContext(h.state(), today, FulfillmentChannel.valueOf(h.channel()), h.ca8080(),
                h.caSeparate(), h.mandatoryTaxable(), currency);
        Money tax = TaxEngine.compute(ctx, rules(h.state()), taxable, charges, Money.zero(currency)).tax();

        long paid = db.sql("""
                select coalesce(sum(amount_cents - tip_cents), 0) from payment
                where check_id = ? and (status = 'SUCCEEDED'
                   or (status in ('PENDING', 'REQUIRES_ACTION') and idempotency_key <> ?))""")
                .params(checkId, excludingIdempotencyKey).query(Long.class).single();

        Money subtotal = Money.ofCents(checkSubtotal, currency);
        Money chargeMoney = Money.ofCents(chargeTotal, currency);
        Money alreadyPaid = Money.ofCents(paid, currency);
        Money due = subtotal.plus(chargeMoney).plus(tax).minus(alreadyPaid);
        return new Owed(subtotal, chargeMoney, tax, alreadyPaid, due, List.copyOf(shares));
    }

    /** This check's exact cents of a line, splitting the line across every check (and any unallocated part). */
    private long shareOf(UUID checkId, LineRow line) {
        List<Allocation> allocations = db.sql("""
                select check_id, numerator, denominator from check_allocation where order_line_id = ? order by check_id""")
                .params(line.id())
                .query((rs, n) -> new Allocation(rs.getObject("check_id", UUID.class), rs.getInt("numerator"), rs.getInt("denominator")))
                .list();
        int mine = -1;
        BigInteger lcm = BigInteger.ONE;
        for (int i = 0; i < allocations.size(); i++) {
            BigInteger d = BigInteger.valueOf(allocations.get(i).denominator());
            lcm = lcm.divide(lcm.gcd(d)).multiply(d);
            if (allocations.get(i).check().equals(checkId)) {
                mine = i;
            }
        }
        if (mine < 0) {
            return 0;
        }
        List<Long> weights = new ArrayList<>();
        BigInteger allocated = BigInteger.ZERO;
        for (Allocation a : allocations) {
            BigInteger w = lcm.divide(BigInteger.valueOf(a.denominator())).multiply(BigInteger.valueOf(a.numerator()));
            weights.add(w.longValueExact());
            allocated = allocated.add(w);
        }
        if (allocated.compareTo(lcm) < 0) {
            weights.add(lcm.subtract(allocated).longValueExact()); // the part no check has claimed yet
        }
        return SplitAllocator.allocate(line.amount(), weights).get(mine);
    }

    private List<Charge> charges(UUID order) {
        return db.sql("select kind, amount_cents, rate_bps, taxable from service_charge where order_id = ? order by id")
                .params(order)
                .query((rs, n) -> {
                    long amount = rs.getLong("amount_cents");
                    boolean fixed = !rs.wasNull();
                    return new Charge(rs.getString("kind"), amount, rs.getInt("rate_bps"), fixed, rs.getBoolean("taxable"));
                })
                .list();
    }

    private List<TaxRule> rules(String state) {
        return db.sql("""
                select state_code, jurisdiction_code, tax_category, fulfillment_channel, rate_ppm, effective_from, effective_to
                from tax_rule where state_code = ?""")
                .params(state)
                .query((rs, n) -> {
                    String channel = rs.getString("fulfillment_channel");
                    Date to = rs.getDate("effective_to");
                    return new TaxRule(rs.getString("state_code"), rs.getString("jurisdiction_code"),
                            rs.getString("tax_category"), channel == null ? null : FulfillmentChannel.valueOf(channel),
                            rs.getInt("rate_ppm"), rs.getDate("effective_from").toLocalDate(), to == null ? null : to.toLocalDate());
                })
                .list();
    }
}
