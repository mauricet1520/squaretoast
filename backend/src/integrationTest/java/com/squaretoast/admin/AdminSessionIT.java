package com.squaretoast.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.squaretoast.admin.AdminFixture.Session;
import com.squaretoast.admin.AdminFixture.User;
import com.squaretoast.testsupport.DeviceFixture;
import java.net.http.HttpResponse;
import java.util.Locale;
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

/** Slice B1: back-office users log in with a password and get a cookie session that device routes never accept. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AdminSessionIT {

    @Container @ServiceConnection
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired DataSource dataSource;
    @LocalServerPort int port;

    AdminFixture a;
    UUID merchant;
    UUID loc;
    User pat;

    @BeforeEach
    void seed() {
        a = new AdminFixture(dataSource, port);
        merchant = a.merchant();
        loc = a.location(merchant);
        pat = a.user(merchant);
    }

    @Test
    void loginSetsHttpOnlySecureSameSiteStrictCookie() {
        HttpResponse<String> r = a.login(pat.email().toLowerCase(Locale.ROOT), pat.password()); // email is case-insensitive

        assertThat(r.statusCode()).isEqualTo(200);
        String set = r.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(set).startsWith("st_admin=").contains("HttpOnly").contains("Secure").contains("SameSite=Strict")
                .contains("Path=/api");
        String token = AdminFixture.cookieToken(r);
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(a.count("select count(*) from admin_session where token_hash = ?", (Object) AdminFixture.sha256(token)))
                .as("only the hash is stored").isEqualTo(1);
        JsonNode body = AdminFixture.JSON.readTree(r.body());
        assertThat(body.get("userId").asString()).isEqualTo(pat.id().toString());
        assertThat(body.get("merchantId").asString()).isEqualTo(merchant.toString());
        assertThat(body.get("csrfToken").asString()).hasSizeGreaterThanOrEqualTo(43);
        assertThat(r.body()).doesNotContain("argon2").doesNotContain(pat.password());

        HttpResponse<String> me = a.call("GET", "/admin/session", null, new Session(token, ""));
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains(pat.id().toString());
    }

    @Test
    void passwordsAreStoredAsArgon2id() {
        String hash = a.db.sql("select password_hash from app_user where id = ?").params(pat.id()).query(String.class).single();
        assertThat(hash).startsWith("$argon2id$").doesNotContain(pat.password());
    }

    @Test
    void wrongPasswordIsGeneric401AndRateLimited() {
        HttpResponse<String> wrong = a.login(pat.email(), "nope");
        HttpResponse<String> unknown = a.login("nobody@example.com", "nope");
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(wrong.body()).as("no hint which part was wrong").isEqualTo(unknown.body());

        for (int i = 0; i < 4; i++) {
            assertThat(a.login(pat.email(), "nope " + i).statusCode()).isEqualTo(401);
        }
        HttpResponse<String> limited = a.login(pat.email(), pat.password());
        assertThat(limited.statusCode()).as("locked even with the right password").isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        assertThat(limited.headers().firstValue("Set-Cookie")).isEmpty();

        User other = a.user(merchant);
        assertThat(a.login(other.email(), other.password()).statusCode()).as("other accounts unaffected").isEqualTo(200);
    }

    @Test
    void aLoginForAnEmailAlreadyBeingCheckedIsTurnedAwayAtOnce() throws java.sql.SQLException {
        try (java.sql.Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (java.sql.PreparedStatement st = c.prepareStatement("select pg_advisory_xact_lock(hashtext(?))")) {
                st.setString(1, pat.email().toLowerCase(Locale.ROOT));
                st.execute();
            }
            long start = System.nanoTime();
            HttpResponse<String> r = a.login(pat.email(), pat.password());
            long millis = (System.nanoTime() - start) / 1_000_000;

            assertThat(r.statusCode()).as("no waiting on a pooled connection").isEqualTo(429);
            assertThat(millis).isLessThan(2_000);
            c.rollback();
        }
        assertThat(a.login(pat.email(), pat.password()).statusCode()).isEqualTo(200);
    }

    @Test
    void disabledUserCannotLogin() {
        Session before = a.session(pat);
        a.disable(pat);

        HttpResponse<String> r = a.login(pat.email(), pat.password());
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.body()).isEqualTo(a.login("nobody@example.com", "x").body());
        assertThat(a.call("GET", "/admin/session", null, before).statusCode()).as("live sessions end too").isEqualTo(401);
    }

    @Test
    void adminPathsRejectDeviceTokens() {
        DeviceFixture d = new DeviceFixture(dataSource, port);
        String device = d.enrolledDevice(loc).token();

        assertThat(a.send("GET", "/admin/session", null, null, null, device).statusCode()).isEqualTo(401);
        assertThat(a.send("GET", "/admin/locations", null, null, null, device).statusCode()).isEqualTo(401);
        assertThat(a.send("GET", "/admin/anything-new", null, null, null, null).statusCode())
                .as("deny by default").isEqualTo(401);
    }

    @Test
    void devicePathsRejectAdminCookies() {
        Session s = a.session(pat);
        assertThat(a.send("GET", "/locations/" + loc + "/menu", null, s.token(), s.csrf(), null).statusCode()).isEqualTo(401);
    }

    @Test
    void changesNeedTheCsrfTokenAndLogoutEndsTheSession() {
        Session s = a.session(pat);

        assertThat(a.send("DELETE", "/admin/session", null, s.token(), null, null).statusCode()).isEqualTo(403);
        assertThat(a.send("DELETE", "/admin/session", null, s.token(), "wrong", null).statusCode()).isEqualTo(403);
        assertThat(a.call("GET", "/admin/session", null, s).statusCode()).as("still logged in").isEqualTo(200);

        HttpResponse<String> out = a.call("DELETE", "/admin/session", null, s);
        assertThat(out.statusCode()).isEqualTo(204);
        assertThat(out.headers().firstValue("Set-Cookie").orElseThrow()).contains("Max-Age=0");
        assertThat(a.call("GET", "/admin/session", null, s).statusCode()).isEqualTo(401);
    }

    @Test
    void idleAndOldSessionsExpire() {
        Session idle = a.session(pat);
        a.db.sql("update admin_session set last_seen_at = now() - interval '31 minutes' where token_hash = ?")
                .params((Object) AdminFixture.sha256(idle.token())).update();
        assertThat(a.call("GET", "/admin/session", null, idle).statusCode()).isEqualTo(401);

        Session old = a.session(pat);
        a.db.sql("update admin_session set created_at = now() - interval '13 hours' where token_hash = ?")
                .params((Object) AdminFixture.sha256(old.token())).update();
        assertThat(a.call("GET", "/admin/session", null, old).statusCode()).isEqualTo(401);

        assertThat(a.call("GET", "/admin/session", null, new Session("x".repeat(43), "")).statusCode()).isEqualTo(401);
    }
}
