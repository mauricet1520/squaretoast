package com.squaretoast.menu;

import static org.assertj.core.api.Assertions.assertThat;

import com.squaretoast.testsupport.MenuFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import javax.sql.DataSource;
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

/** Slice 11: the POS lists the location's stock so staff can 86 it. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class InventoryListIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired DataSource dataSource;
    @LocalServerPort int port;

    MenuFixture f;
    UUID loc;
    UUID patty;
    UUID lettuce;

    @BeforeEach
    void stock() {
        f = new MenuFixture(dataSource, port);
        loc = f.location();
        patty = f.trackedInventory(loc, "Patty", 7);
        lettuce = f.untrackedInventory(loc, "Lettuce");
    }

    HttpResponse<String> list(UUID location, String token) {
        try {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(f.baseUrl() + "/locations/" + location + "/inventory"))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void listsTheLocationsStockSortedByNameWithCountsAnd86State() {
        HttpResponse<String> r = list(loc, f.tokenFor(loc));
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode items = MenuFixture.body(r).get("items");
        assertThat(items.size()).isEqualTo(2);
        JsonNode first = items.get(0);
        assertThat(first.get("name").asString()).isEqualTo("Lettuce");
        assertThat(first.get("id").asString()).isEqualTo(lettuce.toString());
        assertThat(first.get("tracked").asBoolean()).isFalse();
        assertThat(first.get("onHand").isNull()).isTrue();
        assertThat(first.get("eightySixed").asBoolean()).isFalse();
        JsonNode second = items.get(1);
        assertThat(second.get("name").asString()).isEqualTo("Patty");
        assertThat(second.get("tracked").asBoolean()).isTrue();
        assertThat(second.get("onHand").asInt()).isEqualTo(7);
    }

    @Test
    void anEightySixShowsUpInTheList() {
        assertThat(f.eightySix(loc, patty, true).statusCode()).isEqualTo(204);
        JsonNode items = MenuFixture.body(list(loc, f.tokenFor(loc))).get("items");
        assertThat(items.get(1).get("eightySixed").asBoolean()).isTrue();
        assertThat(items.get(0).get("eightySixed").asBoolean()).isFalse();
    }

    @Test
    void anotherLocationsStockIsNotFoundAndNeedsAToken() {
        UUID other = f.location();
        f.trackedInventory(other, "Secret", 3);
        assertThat(list(other, f.tokenFor(loc)).statusCode()).isEqualTo(404);
        assertThat(list(loc, "nope").statusCode()).isEqualTo(401);
    }
}
