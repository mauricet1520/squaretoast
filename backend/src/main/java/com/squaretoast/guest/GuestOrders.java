package com.squaretoast.guest;

import com.squaretoast.domain.menu.Availability;
import com.squaretoast.domain.menu.MenuItem;
import com.squaretoast.domain.menu.ModifierOption;
import com.squaretoast.menu.LocationMenu;
import com.squaretoast.menu.MenuRepository;
import com.squaretoast.realtime.RealtimePublisher;
import com.squaretoast.realtime.Topics;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Places a guest's QR order (plan S6-5). Prices come from the menu, never the guest. The order stays OPEN with
 * every course on HOLD and takes no inventory until staff accept it from the POS (the device-only commit), so a
 * public endpoint can neither 86 the kitchen's stock nor print tickets.
 */
@Service
class GuestOrders {

    static final int MAX_QTY = 20;
    static final int MAX_LINES = 30;

    record Table(UUID id, UUID location, String label) {}

    record LineRequest(@Nullable UUID menuItemId, @Nullable Integer qty, @Nullable List<UUID> optionIds) {}

    record Placed(UUID orderId, UUID checkId, String status) {}

    /** A guest order the server will not take; {@code code} is INVALID_ORDER or ITEM_UNAVAILABLE. */
    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String code;

        Refused(String code) {
            super(code, null, false, false);
            this.code = code;
        }
    }

    private record Item(UUID id, long priceCents, String taxCategory, String temp, String name, int course) {}

    private record Option(UUID id, String name, long deltaCents, UUID group) {}

    private record Group(UUID id, int min, int max) {}

    private record Priced(Item item, int qty, List<Option> options) {}

    private record Existing(@Nullable UUID table, @Nullable UUID device) {}

    private final JdbcClient db;
    private final MenuRepository menus;
    private final RealtimePublisher realtime;

    GuestOrders(JdbcClient db, MenuRepository menus, RealtimePublisher realtime) {
        this.db = db;
        this.menus = menus;
        this.realtime = realtime;
    }

    Optional<Table> table(String qrToken) {
        return db.sql("select id, location_id, label from dining_table where qr_token = ?").params(qrToken)
                .query((rs, n) -> new Table(rs.getObject("id", UUID.class), rs.getObject("location_id", UUID.class),
                        rs.getString("label")))
                .optional();
    }

    /** Resending the same {@code orderId} from the same table returns the order already placed. */
    @Transactional
    Placed place(Table table, UUID orderId, List<LineRequest> request) {
        Optional<Placed> again = existing(table, orderId);
        if (again.isPresent()) {
            return again.get();
        }
        List<Priced> lines = price(table.location(), request);
        int inserted = db.sql("""
                insert into orders(id, location_id, table_id, fulfillment_channel) values (?, ?, ?, 'DINE_IN')
                on conflict (id) do nothing""")
                .params(orderId, table.location(), table.id()).update();
        if (inserted == 0) {
            return existing(table, orderId).orElseThrow(() -> new Refused("INVALID_ORDER"));
        }
        UUID seat = UUID.randomUUID();
        db.sql("insert into seat(id, order_id, seat_no) values (?, ?, 1)").params(seat, orderId).update();
        Map<Integer, UUID> courses = new TreeMap<>();
        for (Priced p : lines) {
            courses.computeIfAbsent(p.item().course(), c -> UUID.randomUUID());
        }
        courses.forEach((no, id) ->
                db.sql("insert into course(id, order_id, course_no) values (?, ?, ?)").params(id, orderId, no).update());
        UUID check = UUID.randomUUID();
        db.sql("insert into guest_check(id, order_id, label) values (?, ?, 'Check 1')").params(check, orderId).update();
        for (Priced p : lines) {
            UUID line = UUID.randomUUID();
            Item i = p.item();
            db.sql("""
                    insert into order_line(id, order_id, seat_id, course_id, menu_item_id, qty, unit_price_cents,
                                           tax_category, temp, name_snapshot)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(line, orderId, seat, Objects.requireNonNull(courses.get(i.course())), i.id(), p.qty(), i.priceCents(),
                            i.taxCategory(), i.temp(), i.name())
                    .update();
            for (Option o : p.options()) {
                db.sql("""
                        insert into order_line_modifier(id, order_line_id, modifier_option_id, price_delta_cents, name_snapshot)
                        values (?, ?, ?, ?, ?)""")
                        .params(UUID.randomUUID(), line, o.id(), o.deltaCents(), o.name()).update();
            }
            db.sql("insert into check_allocation(check_id, order_line_id, numerator, denominator) values (?, ?, 1, 1)")
                    .params(check, line).update();
        }
        realtime.publish(table.location(), Topics.ORDER,
                () -> new Announced(orderId, table.label(), "AWAITING_ACCEPTANCE", lines.size()));
        return new Placed(orderId, check, "OPEN");
    }

    /** What the kitchen and POS hear about a new guest order. */
    record Announced(UUID orderId, String table, String status, int lineCount) {}

    private Optional<Placed> existing(Table table, UUID orderId) {
        Optional<Existing> found = db.sql("select table_id, origin_device_id from orders where id = ?").params(orderId)
                .query((rs, n) -> new Existing(rs.getObject("table_id", UUID.class), rs.getObject("origin_device_id", UUID.class)))
                .optional();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (found.get().device() != null || !table.id().equals(found.get().table())) {
            throw new Refused("INVALID_ORDER"); // the id belongs to another order; say no more than that
        }
        UUID check = db.sql("select id from guest_check where order_id = ? order by label, id limit 1").params(orderId)
                .query(UUID.class).single();
        String status = db.sql("select status from orders where id = ?").params(orderId).query(String.class).single();
        return Optional.of(new Placed(orderId, check, status));
    }

    private List<Priced> price(UUID location, List<LineRequest> request) {
        if (request.isEmpty() || request.size() > MAX_LINES) {
            throw new Refused("INVALID_ORDER");
        }
        List<Priced> priced = new ArrayList<>();
        for (LineRequest r : request) {
            UUID itemId = r.menuItemId();
            Integer qty = r.qty();
            List<UUID> optionIds = r.optionIds() == null ? List.of() : r.optionIds();
            if (itemId == null || qty == null || qty < 1 || qty > MAX_QTY || optionIds.contains(null)
                    || new HashSet<>(optionIds).size() != optionIds.size()) {
                throw new Refused("INVALID_ORDER");
            }
            Item item = db.sql("""
                    select id, price_cents, tax_category, temp, name, default_course from menu_item
                    where id = ? and location_id = ? and active and visible_qr""")
                    .params(itemId, location)
                    .query((rs, n) -> new Item(rs.getObject("id", UUID.class), rs.getLong("price_cents"),
                            rs.getString("tax_category"), rs.getString("temp"), rs.getString("name"), rs.getInt("default_course")))
                    .optional().orElseThrow(() -> new Refused("INVALID_ORDER"));
            List<Option> options = new ArrayList<>();
            for (UUID id : optionIds) {
                options.add(db.sql("""
                        select o.id, o.name, o.price_delta_cents, o.modifier_group_id from modifier_option o
                        join menu_item_modifier_group mg on mg.modifier_group_id = o.modifier_group_id
                        where o.id = ? and mg.menu_item_id = ? and o.active""")
                        .params(id, itemId)
                        .query((rs, n) -> new Option(rs.getObject("id", UUID.class), rs.getString("name"),
                                rs.getLong("price_delta_cents"), rs.getObject("modifier_group_id", UUID.class)))
                        .optional().orElseThrow(() -> new Refused("INVALID_ORDER")));
            }
            List<Group> groups = db.sql("""
                    select g.id, g.min_select, g.max_select from modifier_group g
                    join menu_item_modifier_group mg on mg.modifier_group_id = g.id where mg.menu_item_id = ?""")
                    .params(itemId)
                    .query((rs, n) -> new Group(rs.getObject("id", UUID.class), rs.getInt("min_select"), rs.getInt("max_select")))
                    .list();
            Map<UUID, Integer> chosen = new HashMap<>();
            options.forEach(o -> chosen.merge(o.group(), 1, Integer::sum));
            for (Group g : groups) {
                int n = chosen.getOrDefault(g.id(), 0);
                if (n < g.min() || n > g.max()) {
                    throw new Refused("INVALID_ORDER");
                }
            }
            long unit = item.priceCents() + options.stream().mapToLong(Option::deltaCents).sum();
            if (unit < 0) {
                throw new Refused("INVALID_ORDER"); // a "no cheese" credit larger than the item
            }
            priced.add(new Priced(item, qty, options));
        }
        requireAvailable(location, priced);
        return priced;
    }

    /** Refuses 86'd items and options now, for a clear answer; commit checks stock again when staff accept. */
    private void requireAvailable(UUID location, List<Priced> lines) {
        LocationMenu menu = menus.load(location).orElseThrow(() -> new Refused("INVALID_ORDER"));
        Availability a = Availability.of(menu.levels());
        for (Priced p : lines) {
            MenuItem item = menu.item(p.item().id()).orElseThrow(() -> new Refused("INVALID_ORDER"));
            if (!a.item(item)) {
                throw new Refused("ITEM_UNAVAILABLE");
            }
            for (Option o : p.options()) {
                ModifierOption opt = item.modifierGroups().stream().flatMap(g -> g.options().stream())
                        .filter(x -> x.id().equals(o.id())).findFirst()
                        .orElseThrow(() -> new Refused("INVALID_ORDER"));
                if (!a.option(opt)) {
                    throw new Refused("ITEM_UNAVAILABLE");
                }
            }
        }
    }
}
