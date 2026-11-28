package com.squaretoast.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.squaretoast.admin.AdminFixture.Session;
import com.squaretoast.admin.AdminFixture.User;
import java.net.http.HttpResponse;
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

/** Slice B2: an owner of merchant A sees nothing of merchant B, by any id, on any admin route. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TenantIsolationIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired DataSource dataSource;
    @LocalServerPort int port;

    AdminFixture a;
    UUID merchantA;
    UUID locA;
    User ownerA;
    Session sa;
    UUID merchantB;
    UUID locB;
    User ownerB;
    UUID grantB;

    @BeforeEach
    void seed() {
        a = new AdminFixture(dataSource, port);
        merchantA = a.merchant();
        locA = a.location(merchantA);
        ownerA = a.user(merchantA);
        a.grant(ownerA, "OWNER", "MERCHANT", merchantA);
        sa = a.session(ownerA);
        merchantB = a.merchant();
        locB = a.location(merchantB);
        ownerB = a.user(merchantB);
        grantB = a.grant(ownerB, "OWNER", "MERCHANT", merchantB);
    }

    record Probe(String method, String path, @Nullable String body) {}

    @Test
    void userOfMerchantACannotReadMerchantBById() {
        UUID gm = a.roleId("GM");
        UUID groupB = a.group(merchantB, locB);
        List<Probe> probes = List.of(
                new Probe("GET", "/admin/locations/" + locB, null),
                new Probe("PATCH", "/admin/locations/" + locB, "{\"name\":\"Mine now\"}"),
                new Probe("DELETE", "/admin/role-grants/" + grantB, null),
                new Probe("POST", "/admin/role-grants", AdminFixture.grantBody(ownerB.id(), gm, "LOCATION", locB)),
                new Probe("POST", "/admin/role-grants", AdminFixture.grantBody(ownerA.id(), gm, "LOCATION", locB)),
                new Probe("POST", "/admin/role-grants", AdminFixture.grantBody(ownerA.id(), gm, "GROUP", groupB)),
                new Probe("POST", "/admin/role-grants", AdminFixture.grantBody(ownerA.id(), gm, "MERCHANT", merchantB)),
                new Probe("POST", "/admin/role-grants", AdminFixture.grantBody(ownerB.id(), gm, "LOCATION", locA)),
                new Probe("GET", "/admin/locations/" + UUID.randomUUID(), null));

        for (Probe p : probes) {
            HttpResponse<String> r = a.call(p.method(), p.path(), p.body(), sa);
            assertThat(r.statusCode()).as(p.method() + " " + p.path() + " " + p.body()).isEqualTo(404);
            assertThat(r.body()).as("no B data in the reply").doesNotContain(locB.toString()).doesNotContain(ownerB.email());
        }
        assertThat(a.count("select count(*) from location where id = ? and name = 'Main'", locB)).isEqualTo(1);
        assertThat(a.count("select count(*) from role_grant where id = ?", grantB)).isEqualTo(1);
        assertThat(a.count("select count(*) from role_grant where user_id in (?, ?)", ownerA.id(), ownerB.id())).isEqualTo(2);
    }

    @Test
    void listsShowOnlyYourOwnMerchant() {
        HttpResponse<String> locations = a.call("GET", "/admin/locations", null, sa);
        assertThat(locations.statusCode()).isEqualTo(200);
        assertThat(locations.body()).contains(locA.toString()).doesNotContain(locB.toString());

        HttpResponse<String> users = a.call("GET", "/admin/users", null, sa);
        assertThat(users.statusCode()).isEqualTo(200);
        assertThat(users.body()).contains(ownerA.email()).doesNotContain(ownerB.email());
    }
}
