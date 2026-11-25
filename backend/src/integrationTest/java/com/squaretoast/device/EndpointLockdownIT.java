package com.squaretoast.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.squaretoast.testsupport.DeviceFixture;
import com.squaretoast.testsupport.DeviceFixture.Device;
import com.squaretoast.testsupport.MenuFixture;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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

/** Slice 6 closes the Slice 5b finding: menu, 86 and order commit need a device of the same location. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class EndpointLockdownIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired DataSource dataSource;
    @LocalServerPort int port;

    DeviceFixture d;
    MenuFixture m;
    UUID loc;
    UUID buns;
    UUID order;
    Device here;
    Device elsewhere;

    @BeforeEach
    void seed() {
        d = new DeviceFixture(dataSource, port);
        m = new MenuFixture(dataSource, port);
        loc = m.location();
        buns = m.trackedInventory(loc, "Buns", 5);
        UUID burger = m.menuItem(loc, "Burger", 1500);
        m.itemConsumes(burger, buns, 1);
        order = m.order(loc, burger, 1);
        here = d.enrolledDevice(loc);
        elsewhere = d.enrolledDevice(m.location());
    }

    private int menu(@Nullable String token) {
        return d.raw("GET", "/api/v1/locations/" + loc + "/menu", null, token).statusCode();
    }

    private int eightySix(@Nullable String token) {
        return d.raw("PUT", "/api/v1/locations/" + loc + "/inventory/" + buns + "/eighty-six", "{\"eightySixed\":true}", token)
                .statusCode();
    }

    @Test
    void withoutATokenEachIsUnauthorized() {
        assertThat(menu(null)).isEqualTo(401);
        assertThat(eightySix(null)).isEqualTo(401);
        assertThat(d.commit(null, order).statusCode()).isEqualTo(401);
        assertThat(m.status(order)).isEqualTo("OPEN");
    }

    @Test
    void anotherLocationsDeviceGetsNotFoundAndChangesNothing() {
        assertThat(menu(elsewhere.token())).isEqualTo(404);
        assertThat(eightySix(elsewhere.token())).isEqualTo(404);
        assertThat(d.commit(elsewhere.token(), order).statusCode()).isEqualTo(404);
        assertThat(m.status(order)).isEqualTo("OPEN");
        assertThat(m.onHand(buns)).isEqualTo(5);
        assertThat(d.count("select count(*) from inventory_item where id = ? and manually_86ed", buns)).isZero();
    }

    @Test
    void theLocationsOwnDeviceCanUseThem() {
        assertThat(menu(here.token())).isEqualTo(200);
        assertThat(d.commit(here.token(), order).statusCode()).isEqualTo(200);
        assertThat(eightySix(here.token())).isEqualTo(204);
    }
}
