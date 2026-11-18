package com.squaretoast.testsupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Seeds menu/inventory/order rows with plain JDBC and calls the versioned API over real HTTP. */
public final class MenuFixture {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    private final DataSource ds;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    public MenuFixture(DataSource ds, int port) {
        this.ds = ds;
        this.baseUrl = "http://localhost:" + port + "/api/v1";
    }

    public String baseUrl() {
        return baseUrl;
    }

    // ---------------------------------------------------------------- seeding

    public UUID location() {
        UUID merchant = UUID.randomUUID();
        UUID loc = UUID.randomUUID();
        exec("insert into merchant(id, legal_name) values (?, 'Test Bistro')", merchant);
        exec("insert into location(id, merchant_id, name, state_code, timezone) values (?, ?, 'Main', 'US', 'America/New_York')",
                loc, merchant);
        return loc;
    }

    public UUID trackedInventory(UUID location, String name, int onHand) {
        UUID id = UUID.randomUUID();
        exec("insert into inventory_item(id, location_id, name, tracked, on_hand) values (?, ?, ?, true, ?)", id, location, name, onHand);
        return id;
    }

    public UUID untrackedInventory(UUID location, String name) {
        UUID id = UUID.randomUUID();
        exec("insert into inventory_item(id, location_id, name, tracked) values (?, ?, ?, false)", id, location, name);
        return id;
    }

    public UUID menuItem(UUID location, String name, long priceCents) {
        UUID id = UUID.randomUUID();
        exec("insert into menu_item(id, location_id, name, price_cents, tax_category) values (?, ?, ?, ?, 'FOOD')",
                id, location, name, priceCents);
        return id;
    }

    public UUID group(UUID location, String name, int min, int max) {
        UUID id = UUID.randomUUID();
        exec("insert into modifier_group(id, location_id, name, min_select, max_select) values (?, ?, ?, ?, ?)", id, location, name, min, max);
        return id;
    }

    public void link(UUID menuItem, UUID group) {
        exec("insert into menu_item_modifier_group(menu_item_id, modifier_group_id) values (?, ?)", menuItem, group);
    }

    public UUID option(UUID group, String name, long deltaCents) {
        UUID id = UUID.randomUUID();
        exec("insert into modifier_option(id, modifier_group_id, name, price_delta_cents) values (?, ?, ?, ?)", id, group, name, deltaCents);
        return id;
    }

    public void itemConsumes(UUID menuItem, UUID inventory, int qty) {
        exec("insert into inventory_consumption(id, menu_item_id, inventory_item_id, qty) values (?, ?, ?, ?)",
                UUID.randomUUID(), menuItem, inventory, qty);
    }

    public void optionConsumes(UUID option, UUID inventory, int qty) {
        exec("insert into inventory_consumption(id, modifier_option_id, inventory_item_id, qty) values (?, ?, ?, ?)",
                UUID.randomUUID(), option, inventory, qty);
    }

    /** An OPEN dine-in order at {@code location} with no lines yet. */
    public UUID openOrder(UUID location) {
        UUID order = UUID.randomUUID();
        exec("insert into orders(id, location_id, fulfillment_channel) values (?, ?, 'DINE_IN')", order, location);
        exec("insert into course(id, order_id, course_no) values (?, ?, 1)", UUID.randomUUID(), order);
        return order;
    }

    public UUID addLine(UUID order, UUID menuItem, int qty, UUID... options) {
        UUID line = UUID.randomUUID();
        exec("""
                insert into order_line(id, order_id, course_id, menu_item_id, qty, unit_price_cents, tax_category, temp, name_snapshot)
                select ?, ?, c.id, m.id, ?, m.price_cents, m.tax_category, m.temp, m.name
                from course c, menu_item m where c.order_id = ? and c.course_no = 1 and m.id = ?""",
                line, order, qty, order, menuItem);
        for (UUID opt : options) {
            exec("""
                    insert into order_line_modifier(id, order_line_id, modifier_option_id, price_delta_cents, name_snapshot)
                    select ?, ?, o.id, o.price_delta_cents, o.name from modifier_option o where o.id = ?""",
                    UUID.randomUUID(), line, opt);
        }
        return line;
    }

    public UUID order(UUID location, UUID menuItem, int qty, UUID... options) {
        UUID order = openOrder(location);
        addLine(order, menuItem, qty, options);
        return order;
    }

    public Integer onHand(UUID inventory) {
        return queryOne("select on_hand from inventory_item where id = ?", inventory, Integer.class);
    }

    public String status(UUID order) {
        return queryOne("select status from orders where id = ?", order, String.class);
    }

    // ---------------------------------------------------------------- HTTP

    /** Slice 6: these endpoints need a device of the same location, so each location gets one on first use. */
    public HttpResponse<String> commit(UUID order) {
        UUID location = queryOne("select location_id from orders where id = ?", order, UUID.class);
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/orders/" + order + "/commit"))
                .header("Authorization", "Bearer " + tokenFor(location))
                .POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    public HttpResponse<String> eightySix(UUID location, UUID inventory, boolean eightySixed) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + "/locations/" + location + "/inventory/" + inventory + "/eighty-six"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + tokenFor(location))
                .PUT(HttpRequest.BodyPublishers.ofString("{\"eightySixed\":" + eightySixed + "}")).build());
    }

    private final java.util.Map<UUID, String> tokens = new java.util.concurrent.ConcurrentHashMap<>();

    /** A device row for {@code location}, inserted directly with a known token (stored hashed, as enrollment does). */
    public String tokenFor(UUID location) {
        return tokens.computeIfAbsent(location, l -> {
            String token = "tok" + UUID.randomUUID().toString().replace("-", "");
            exec("insert into device(id, location_id, public_key_spki, token_hash, label) values (?, ?, ?, ?, 'fixture')",
                    UUID.randomUUID(), l, new byte[] {1}, DeviceFixture.sha256(token));
            return token;
        });
    }

    public JsonNode menu(UUID location) {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(baseUrl + "/locations/" + location + "/menu"))
                .header("Authorization", "Bearer " + tokenFor(location)).GET().build());
        if (r.statusCode() != 200) {
            throw new AssertionError("GET menu -> " + r.statusCode() + " " + r.body());
        }
        return JSON.readTree(r.body());
    }

    public static JsonNode body(HttpResponse<String> r) {
        return JSON.readTree(r.body());
    }

    /** Finds {@code items[id]} in a menu response. */
    public static JsonNode menuItem(JsonNode menu, UUID id) {
        for (JsonNode item : menu.get("items")) {
            if (item.get("id").asString().equals(id.toString())) {
                return item;
            }
        }
        throw new AssertionError("menu has no item " + id + ": " + menu);
    }

    /** Finds an option under a given item in a menu response. */
    public static JsonNode menuOption(JsonNode menu, UUID item, UUID option) {
        for (JsonNode group : menuItem(menu, item).get("modifierGroups")) {
            for (JsonNode opt : group.get("options")) {
                if (opt.get("id").asString().equals(option.toString())) {
                    return opt;
                }
            }
        }
        throw new AssertionError("item " + item + " has no option " + option);
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- JDBC

    public void exec(String sql, Object... params) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.execute();
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private <T> T queryOne(String sql, Object param, Class<T> type) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new AssertionError("no row for " + param);
                }
                return rs.getObject(1, type);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }
}
