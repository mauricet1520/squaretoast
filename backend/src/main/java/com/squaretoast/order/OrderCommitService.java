package com.squaretoast.order;

import com.squaretoast.domain.menu.InvalidModifierSelectionException;
import com.squaretoast.domain.menu.InventoryDemand;
import com.squaretoast.domain.menu.LineDemand;
import com.squaretoast.domain.menu.MenuItem;
import com.squaretoast.domain.menu.ModifierSelection;
import com.squaretoast.menu.AvailabilityEvents;
import com.squaretoast.menu.LocationMenu;
import com.squaretoast.menu.MenuRepository;
import com.squaretoast.realtime.RealtimePublisher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits an order in one transaction: OPEN -> COMMITTED, modifier rules checked, and inventory decremented in
 * ascending id order with a guarded UPDATE. Any shortfall throws and rolls the whole order back (plan §4).
 */
@Service
public class OrderCommitService {

    private final JdbcClient jdbc;
    private final MenuRepository menus;
    private final RealtimePublisher realtime;

    OrderCommitService(JdbcClient jdbc, MenuRepository menus, RealtimePublisher realtime) {
        this.jdbc = jdbc;
        this.menus = menus;
        this.realtime = realtime;
    }

    @Transactional
    public void commit(UUID orderId) {
        // Row lock on the order first: a second commit of the same order waits here, then finds it not OPEN.
        Optional<UUID> found = jdbc.sql("""
                update orders set status = 'COMMITTED', committed_at = now(), version = version + 1
                where id = ? and status = 'OPEN' returning location_id""")
                .param(orderId).query(UUID.class).optional();
        if (found.isEmpty()) {
            boolean exists = jdbc.sql("select count(*) from orders where id = ?").param(orderId).query(Long.class).single() > 0;
            throw exists ? new CommitException.Conflict("ORDER_NOT_OPEN", null) : new CommitException.NotFound(orderId);
        }
        UUID location = found.get();
        LocationMenu menu = menus.load(location).orElseThrow();

        SortedMap<UUID, Long> demand = InventoryDemand.of(lines(orderId, menu));
        for (Map.Entry<UUID, Long> d : demand.entrySet()) {
            int updated = jdbc.sql("""
                    update inventory_item
                    set on_hand = case when tracked then on_hand - :qty else on_hand end, version = version + 1
                    where id = :id and location_id = :loc and not manually_86ed and (not tracked or on_hand >= :qty)""")
                    .param("qty", d.getValue()).param("id", d.getKey()).param("loc", location)
                    .update();
            if (updated == 0) {
                boolean manual = jdbc.sql("select manually_86ed from inventory_item where id = ? and location_id = ?")
                        .params(d.getKey(), location).query(Boolean.class).optional().orElse(false);
                throw new CommitException.Conflict(manual ? "ITEM_86ED" : "OVERSOLD", d.getKey());
            }
        }
        if (!demand.isEmpty()) {
            realtime.publish(location, AvailabilityEvents.TOPIC,
                    () -> AvailabilityEvents.payload(menus.load(location).orElseThrow(), demand.keySet()));
        }
    }

    private List<LineDemand> lines(UUID orderId, LocationMenu menu) {
        record Line(UUID id, UUID menuItemId, int qty) {}
        List<Line> lines = jdbc.sql("select id, menu_item_id, qty from order_line where order_id = ? order by id")
                .param(orderId)
                .query((rs, n) -> new Line(rs.getObject("id", UUID.class), rs.getObject("menu_item_id", UUID.class), rs.getInt("qty")))
                .list();
        Map<UUID, List<UUID>> chosen = new HashMap<>();
        jdbc.sql("""
                select m.order_line_id, m.modifier_option_id
                from order_line_modifier m join order_line l on l.id = m.order_line_id
                where l.order_id = ? order by m.id""")
                .param(orderId)
                .query((rs, n) -> chosen.computeIfAbsent(rs.getObject("order_line_id", UUID.class), k -> new ArrayList<>())
                        .add(rs.getObject("modifier_option_id", UUID.class)))
                .list();

        List<LineDemand> result = new ArrayList<>();
        for (Line line : lines) {
            MenuItem item = menu.item(line.menuItemId())
                    .orElseThrow(() -> new CommitException.Rejected("ITEM_NOT_AT_LOCATION", null));
            if (!item.active()) {
                throw new CommitException.Rejected("ITEM_INACTIVE", null);
            }
            List<UUID> options = chosen.getOrDefault(line.id(), List.of());
            try {
                ModifierSelection.validate(item, options);
            } catch (InvalidModifierSelectionException e) {
                throw new CommitException.Rejected("INVALID_MODIFIERS", e.reason().name());
            }
            result.add(new LineDemand(item, line.qty(), options));
        }
        return result;
    }
}
