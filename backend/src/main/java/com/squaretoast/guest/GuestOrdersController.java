package com.squaretoast.guest;

import com.squaretoast.device.DevicePrincipal;
import com.squaretoast.payment.CheckBills;
import com.squaretoast.payment.CheckBills.Bill;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slice 12: the guest QR orders of the device's own location from the last 12 hours, newest first, so staff can
 * accept them on the POS and open their checks. The location comes from the device token, never from the request.
 */
@RestController
class GuestOrdersController {

    static final int MAX_ORDERS = 50;

    private final JdbcClient db;
    private final CheckBills bills;

    GuestOrdersController(JdbcClient db, CheckBills bills) {
        this.db = db;
        this.bills = bills;
    }

    @GetMapping(path = "/guest-orders", version = "1")
    GuestOrderList list(@RequestAttribute(DevicePrincipal.ATTRIBUTE) DevicePrincipal device) {
        UUID location = device.locationId();
        record Row(UUID id, String table, OffsetDateTime openedAt, String status) {}
        List<Row> rows = db.sql("""
                select o.id, t.label, o.opened_at, o.status from orders o join dining_table t on t.id = o.table_id
                where o.location_id = ? and o.origin_device_id is null and o.status in ('OPEN', 'COMMITTED')
                  and o.opened_at > now() - interval '12 hours'
                order by o.opened_at desc, o.id
                limit ?""")
                .params(location, MAX_ORDERS)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("label"),
                        rs.getObject("opened_at", OffsetDateTime.class), rs.getString("status")))
                .list();
        return new GuestOrderList(rows.stream().map(r -> new GuestOrder(r.id(), r.table(), r.openedAt().toInstant().toString(),
                r.status().equals("OPEN") ? "WAITING" : "ACCEPTED", items(r.id()),
                r.status().equals("OPEN") ? List.of() : checks(r.id(), location))).toList());
    }

    /** "2× Burger (Cheddar)", from the server's own name snapshots. */
    private List<String> items(UUID order) {
        record Line(UUID id, int qty, String name) {}
        return db.sql("select id, qty, name_snapshot from order_line where order_id = ? order by name_snapshot, id")
                .params(order)
                .query((rs, n) -> new Line(rs.getObject("id", UUID.class), rs.getInt("qty"), rs.getString("name_snapshot")))
                .list().stream().map(l -> {
                    List<String> mods = db.sql("select name_snapshot from order_line_modifier where order_line_id = ? order by name_snapshot, id")
                            .params(l.id()).query(String.class).list();
                    return l.qty() + "× " + l.name() + (mods.isEmpty() ? "" : " (" + String.join(", ", mods) + ")");
                }).toList();
    }

    private List<CheckSummary> checks(UUID order, UUID location) {
        record C(UUID id, String label) {}
        return db.sql("select id, label from guest_check where order_id = ? and status <> 'VOID' order by label, id")
                .params(order)
                .query((rs, n) -> new C(rs.getObject("id", UUID.class), rs.getString("label")))
                .list().stream().map(c -> {
                    Bill b = bills.bill(c.id(), location);
                    return new CheckSummary(c.id(), c.label(), b.status(), b.dueCents(), b.currency());
                }).toList();
    }

    record GuestOrderList(List<GuestOrder> orders) {}

    record GuestOrder(UUID orderId, String table, String openedAt, String status, List<String> items, List<CheckSummary> checks) {}

    record CheckSummary(UUID checkId, String label, String status, long dueCents, String currency) {}
}
