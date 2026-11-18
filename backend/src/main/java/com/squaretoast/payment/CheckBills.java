package com.squaretoast.payment;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** A check's itemised bill (or receipt) from the same server computation that payments charge. */
@Component
public class CheckBills {

    public record BillLine(String name, int qty, List<String> modifiers, long amountCents) {}

    /** {@code paidCents} includes payments in flight, so {@code dueCents} is what a new payment would charge. */
    public record Bill(UUID checkId, String status, String currency, long subtotalCents, long serviceChargeCents,
                       long taxCents, long paidCents, long dueCents, List<BillLine> lines) {}

    private record LineInfo(String name, int qty) {}

    private final CheckBalance balances;
    private final JdbcClient db;

    CheckBills(CheckBalance balances, JdbcClient db) {
        this.balances = balances;
        this.db = db;
    }

    /** Throws {@link PaymentException.NotFound} unless the check is at {@code location}. */
    public Bill bill(UUID checkId, UUID location) {
        // A fresh key matches no payment, so every pending payment counts as paid here.
        CheckBalance.Owed owed = balances.owed(checkId, location, UUID.randomUUID());
        String status = db.sql("select status from guest_check where id = ?").params(checkId).query(String.class).single();
        List<BillLine> lines = new ArrayList<>();
        for (CheckBalance.LineShare share : owed.lines()) {
            LineInfo info = db.sql("select name_snapshot, qty from order_line where id = ?").params(share.lineId())
                    .query((rs, n) -> new LineInfo(rs.getString("name_snapshot"), rs.getInt("qty"))).single();
            List<String> modifiers = db.sql("""
                    select name_snapshot from order_line_modifier where order_line_id = ? order by name_snapshot, id""")
                    .params(share.lineId()).query(String.class).list();
            lines.add(new BillLine(info.name(), info.qty(), modifiers, share.cents()));
        }
        return new Bill(checkId, status, owed.due().currency().code(), owed.subtotal().cents(),
                owed.serviceCharges().cents(), owed.tax().cents(), owed.alreadyPaid().cents(),
                Math.max(0, owed.due().cents()), List.copyOf(lines));
    }
}
