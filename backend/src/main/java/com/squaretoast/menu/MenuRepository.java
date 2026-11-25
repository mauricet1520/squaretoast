package com.squaretoast.menu;

import com.squaretoast.domain.menu.Consumption;
import com.squaretoast.domain.menu.InventoryLevel;
import com.squaretoast.domain.menu.MenuItem;
import com.squaretoast.domain.menu.ModifierGroup;
import com.squaretoast.domain.menu.ModifierOption;
import com.squaretoast.domain.money.CurrencyCode;
import com.squaretoast.domain.money.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Loads a location's menu, linked modifier groups, consumption and inventory levels with plain SQL. */
@Repository
public class MenuRepository {

    public static final RowMapper<InventoryLevel> LEVEL = (rs, n) -> new InventoryLevel(
            uuid(rs, "id"), rs.getBoolean("tracked"), rs.getObject("on_hand", Integer.class), rs.getBoolean("manually_86ed"));

    private final JdbcClient jdbc;

    MenuRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LocationMenu> load(UUID location) {
        Optional<String> currency = jdbc.sql("select currency from location where id = ?").param(location)
                .query(String.class).optional();
        if (currency.isEmpty()) {
            return Optional.empty();
        }

        Map<UUID, InventoryLevel> levels = new HashMap<>();
        jdbc.sql("select id, tracked, on_hand, manually_86ed from inventory_item where location_id = ?")
                .param(location).query(LEVEL).list().forEach(l -> levels.put(l.id(), l));

        Map<UUID, List<Consumption>> itemConsumes = new HashMap<>();
        Map<UUID, List<Consumption>> optionConsumes = new HashMap<>();
        jdbc.sql("""
                select c.menu_item_id, c.modifier_option_id, c.inventory_item_id, c.qty
                from inventory_consumption c
                left join menu_item m on m.id = c.menu_item_id
                left join modifier_option o on o.id = c.modifier_option_id
                left join modifier_group g on g.id = o.modifier_group_id
                where m.location_id = ? or g.location_id = ?
                order by c.id""")
                .params(location, location)
                .query((rs, n) -> {
                    Consumption c = new Consumption(uuid(rs, "inventory_item_id"), rs.getInt("qty"));
                    UUID item = nullableUuid(rs, "menu_item_id");
                    UUID option = nullableUuid(rs, "modifier_option_id");
                    if (item != null) {
                        itemConsumes.computeIfAbsent(item, k -> new ArrayList<>()).add(c);
                    } else if (option != null) {
                        optionConsumes.computeIfAbsent(option, k -> new ArrayList<>()).add(c);
                    }
                    return c;
                }).list();

        Map<UUID, List<ModifierOption>> optionsByGroup = new HashMap<>();
        jdbc.sql("""
                select o.id, o.modifier_group_id, o.name, o.price_delta_cents, o.active
                from modifier_option o join modifier_group g on g.id = o.modifier_group_id
                where g.location_id = ? order by o.name, o.id""")
                .param(location)
                .query((rs, n) -> {
                    UUID id = uuid(rs, "id");
                    ModifierOption o = new ModifierOption(id, rs.getString("name"), rs.getLong("price_delta_cents"),
                            rs.getBoolean("active"), optionConsumes.getOrDefault(id, List.of()));
                    optionsByGroup.computeIfAbsent(uuid(rs, "modifier_group_id"), k -> new ArrayList<>()).add(o);
                    return o;
                }).list();

        Map<UUID, ModifierGroup> groups = new HashMap<>();
        jdbc.sql("select id, name, min_select, max_select from modifier_group where location_id = ?")
                .param(location)
                .query((rs, n) -> {
                    UUID id = uuid(rs, "id");
                    return new ModifierGroup(id, rs.getString("name"), rs.getInt("min_select"), rs.getInt("max_select"),
                            optionsByGroup.getOrDefault(id, List.of()));
                }).list().forEach(g -> groups.put(g.id(), g));

        Map<UUID, List<ModifierGroup>> groupsByItem = new HashMap<>();
        jdbc.sql("""
                select l.menu_item_id, l.modifier_group_id
                from menu_item_modifier_group l
                join menu_item m on m.id = l.menu_item_id
                join modifier_group g on g.id = l.modifier_group_id
                where m.location_id = ? and g.location_id = ?
                order by l.sort, g.name, g.id""")
                .params(location, location)
                .query((rs, n) -> {
                    ModifierGroup g = groups.get(uuid(rs, "modifier_group_id"));
                    if (g != null) {
                        groupsByItem.computeIfAbsent(uuid(rs, "menu_item_id"), k -> new ArrayList<>()).add(g);
                    }
                    return n;
                }).list();

        CurrencyCode cc = CurrencyCode.of(currency.get());
        List<MenuItem> items = jdbc.sql("select id, name, price_cents, active from menu_item where location_id = ? order by name, id")
                .param(location)
                .query((rs, n) -> {
                    UUID id = uuid(rs, "id");
                    return new MenuItem(id, rs.getString("name"), Money.ofCents(rs.getLong("price_cents"), cc), rs.getBoolean("active"),
                            itemConsumes.getOrDefault(id, List.of()), groupsByItem.getOrDefault(id, List.of()));
                }).list();

        return Optional.of(new LocationMenu(location, cc, items, new LinkedHashMap<>(levels)));
    }

    static UUID uuid(ResultSet rs, String column) throws SQLException {
        UUID v = rs.getObject(column, UUID.class);
        if (v == null) {
            throw new SQLException("unexpected null " + column);
        }
        return v;
    }

    private static @Nullable UUID nullableUuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }
}
