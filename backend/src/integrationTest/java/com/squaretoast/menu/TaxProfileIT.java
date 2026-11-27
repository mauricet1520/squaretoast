package com.squaretoast.menu;

import static org.assertj.core.api.Assertions.assertThat;

import com.squaretoast.testsupport.MenuFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/** Slice 14 (S14-3): the POS downloads its location's tax setup so it can total a check while offline. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TaxProfileIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired DataSource dataSource;
    @LocalServerPort int port;

    MenuFixture f;
    UUID loc;
    UUID salad;
    LocalDate today;

    @BeforeEach
    void seed() {
        f = new MenuFixture(dataSource, port);
        loc = f.location(); // state US, America/New_York
        salad = f.menuItem(loc, "Salad", 1100);
        f.exec("update menu_item set temp = 'COLD' where id = ?", salad);
        today = LocalDate.now(ZoneId.of("America/New_York"));
    }

    private String rule(String state, @Nullable String channel, int ppm, LocalDate from, @Nullable LocalDate to) {
        String jurisdiction = "J-" + UUID.randomUUID();
        f.exec("""
                insert into tax_rule(id, state_code, jurisdiction_code, tax_category, fulfillment_channel, rate_ppm, effective_from, effective_to)
                values (?, ?, ?, 'FOOD', ?, ?, ?, ?)""", UUID.randomUUID(), state, jurisdiction, channel, ppm, from, to);
        return jurisdiction;
    }

    private HttpResponse<String> profile(UUID location, @Nullable String token) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(f.baseUrl() + "/locations/" + location + "/tax-profile")).GET();
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> jurisdictions(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.get("rules").forEach(r -> out.add(r.get("jurisdictionCode").asString()));
        return out;
    }

    @Test
    void returnsTheLocationsTaxSetupForTheNextSevenDaysAndItsItems() {
        String j = rule("US", "TAKEOUT", 72_500, LocalDate.of(2020, 1, 1), null);

        HttpResponse<String> r = profile(loc, f.tokenFor(loc));

        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode b = MenuFixture.body(r);
        assertThat(b.get("locationId").asString()).isEqualTo(loc.toString());
        assertThat(b.get("stateCode").asString()).isEqualTo("US");
        assertThat(b.get("currency").asString()).isEqualTo("USD");
        assertThat(b.get("timezone").asString()).isEqualTo("America/New_York");
        assertThat(b.get("ca8080Applies").asBoolean()).isFalse();
        assertThat(b.get("caSeparateAccountingElected").asBoolean()).isFalse();
        assertThat(b.has("mandatoryServiceChargeTaxable")).isTrue();
        assertThat(b.get("validFrom").asString()).isEqualTo(today.toString());
        assertThat(b.get("validUntil").asString()).isEqualTo(today.plusDays(7).toString());

        @Nullable JsonNode found = null;
        for (JsonNode rule : b.get("rules")) {
            if (rule.get("jurisdictionCode").asString().equals(j)) {
                found = rule;
            }
        }
        JsonNode mine = java.util.Objects.requireNonNull(found, "the location's rule is sent");
        assertThat(mine.get("taxCategory").asString()).isEqualTo("FOOD");
        assertThat(mine.get("channel").asString()).isEqualTo("TAKEOUT");
        assertThat(mine.get("ratePpm").asInt()).isEqualTo(72_500);
        assertThat(mine.get("effectiveFrom").asString()).isEqualTo("2020-01-01");
        assertThat(mine.get("effectiveTo").isNull()).isTrue();
        assertThat(mine.has("stateCode")).as("every rule is the location's state").isFalse();

        JsonNode items = b.get("items");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).get("menuItemId").asString()).isEqualTo(salad.toString());
        assertThat(items.get(0).get("taxCategory").asString()).isEqualTo("FOOD");
        assertThat(items.get(0).get("temp").asString()).isEqualTo("COLD");
    }

    @Test
    void onlyRulesInForceDuringTheWindowAndInTheLocationsStateAreSent() {
        String old = rule("US", null, 10_000, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 6, 1));
        String endsToday = rule("US", null, 10_000, LocalDate.of(2020, 1, 1), today);
        String endsSoon = rule("US", "DINE_IN", 10_000, LocalDate.of(2020, 1, 1), today.plusDays(2));
        String startsSoon = rule("US", "DINE_IN", 10_000, today.plusDays(3), null);
        String startsLater = rule("US", null, 10_000, today.plusDays(7), null);
        String otherState = rule("CA", null, 10_000, LocalDate.of(2020, 1, 1), null);

        List<String> sent = jurisdictions(MenuFixture.body(profile(loc, f.tokenFor(loc))));

        assertThat(sent).contains(endsSoon, startsSoon).doesNotContain(old, endsToday, startsLater, otherState);
    }

    @Test
    void anotherLocationsProfileIsNotFoundAndItsItemsAreNeverListed() {
        UUID other = f.location();
        f.menuItem(other, "Secret special", 2500);

        assertThat(profile(other, f.tokenFor(loc)).statusCode()).isEqualTo(404);
        JsonNode items = MenuFixture.body(profile(loc, f.tokenFor(loc))).get("items");
        assertThat(items.size()).isEqualTo(1);
    }

    @Test
    void aMissingTokenIsUnauthorized() {
        assertThat(profile(loc, null).statusCode()).isEqualTo(401);
    }
}
