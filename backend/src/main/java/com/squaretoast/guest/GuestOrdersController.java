package com.squaretoast.guest;

import com.squaretoast.device.DevicePrincipal;
import com.squaretoast.payment.CheckBills;
import com.squaretoast.payment.CheckBills.Bill;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(GuestOrdersController.class);

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
        Map<UUID, List<String>> items = items(rows.stream().map(Row::id).toList());
        return new GuestOrderList(rows.stream().map(r -> new GuestOrder(r.id(), r.table(), r.openedAt().toInstant().toString(),
                r.status().equals("OPEN") ? "WAITING" : "ACCEPTED", items.getOrDefault(r.id(), List.of()),
                r.status().equals("OPEN") ? List.of() : checks(r.id(), location))).toList());
    }

    /** "2× Burger (Cheddar)" per order, from the server's own name snapshots, in one query for all orders. */
    private Map<UUID, List<String>> items(List<UUID> orders) {
        Map<UUID, List<String>> out = new HashMap<>();
        if (orders.isEmpty()) {
            return out;
        }
        db.sql("""
                select l.order_id, l.qty, l.name_snapshot,
                       string_agg(m.name_snapshot, ', ' order by m.name_snapshot, m.id) as mods
                from order_line l left join order_line_modifier m on m.order_line_id = l.id
                where l.order_id in (:orders)
                group by l.id, l.order_id, l.qty, l.name_snapshot
                order by l.name_snapshot, l.id""")
                .param("orders", orders)
                .query(rs -> {
                    String mods = rs.getString("mods");
                    out.computeIfAbsent(rs.getObject("order_id", UUID.class), k -> new ArrayList<>())
                            .add(rs.getInt("qty") + "× " + rs.getString("name_snapshot") + (mods == null ? "" : " (" + mods + ")"));
                });
        return out;
    }

    private List<CheckSummary> checks(UUID order, UUID location) {
        record C(UUID id, String label) {}
        return db.sql("select id, label from guest_check where order_id = ? and status <> 'VOID' order by label, id")
                .params(order)
                .query((rs, n) -> new C(rs.getObject("id", UUID.class), rs.getString("label")))
                .list().stream().flatMap(c -> {
                    try {
                        Bill b = bills.bill(c.id(), location);
                        return Stream.of(new CheckSummary(c.id(), c.label(), b.status(), b.dueCents(), b.currency()));
                    } catch (RuntimeException e) {
                        // e.g. voided between the two queries: leave that one check out rather than fail the whole list
                        log.warn("guest-orders: skipping check {}: {}", c.id(), e.toString());
                        return Stream.<CheckSummary>empty();
                    }
                }).toList();
    }

    record GuestOrderList(List<GuestOrder> orders) {}

    record GuestOrder(UUID orderId, String table, String openedAt, String status, List<String> items, List<CheckSummary> checks) {}

    record CheckSummary(UUID checkId, String label, String status, long dueCents, String currency) {}
}
