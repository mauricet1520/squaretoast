package com.squaretoast.admin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Seeds merchants and back-office users and speaks the admin API the way a browser would (cookie + CSRF header). */
final class AdminFixture {

    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern COOKIE = Pattern.compile("st_admin=([^;]*)");

    record User(UUID id, UUID merchant, String email, String password) {}

    record Session(String token, String csrf) {}

    final JdbcClient db;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    AdminFixture(DataSource ds, int port) {
        this.db = JdbcClient.create(ds);
        this.baseUrl = "http://localhost:" + port + "/api/v1";
    }

    UUID merchant() {
        UUID id = UUID.randomUUID();
        db.sql("insert into merchant(id, legal_name) values (?, 'Group Co')").params(id).update();
        return id;
    }

    UUID location(UUID merchant) {
        UUID id = UUID.randomUUID();
        db.sql("insert into location(id, merchant_id, name, state_code, timezone) values (?, ?, 'Main', 'US', 'America/New_York')")
                .params(id, merchant).update();
        return id;
    }

    User user(UUID merchant) {
        UUID id = UUID.randomUUID();
        String email = "u" + id.toString().substring(0, 8) + "@Example.com";
        String password = "correct horse " + id;
        db.sql("insert into app_user(id, merchant_id, email, display_name, password_hash) values (?, ?, ?, 'Pat', ?)")
                .params(id, merchant, email, Passwords.hash(password)).update();
        return new User(id, merchant, email, password);
    }

    /** Grants a system role (OWNER, GROUP_ADMIN, GM, SHIFT_LEAD, STAFF) at a scope, as an operator would. */
    UUID grant(User u, String systemRole, String scopeKind, UUID scopeId) {
        UUID id = UUID.randomUUID();
        db.sql("""
                insert into role_grant(id, user_id, role_id, scope_kind, scope_id)
                select ?, ?, r.id, ?, ? from role r where r.system_key = ? and r.merchant_id is null""")
                .params(id, u.id(), scopeKind, scopeId, systemRole).update();
        return id;
    }

    UUID roleId(String systemRole) {
        return db.sql("select id from role where system_key = ? and merchant_id is null").params(systemRole)
                .query(UUID.class).single();
    }

    UUID group(UUID merchant, UUID... locations) {
        UUID id = UUID.randomUUID();
        db.sql("insert into location_group(id, merchant_id, name) values (?, ?, 'Coast')").params(id, merchant).update();
        for (UUID l : locations) {
            db.sql("insert into location_group_member(group_id, location_id) values (?, ?)").params(id, l).update();
        }
        return id;
    }

    static String grantBody(UUID user, UUID role, String scopeKind, UUID scopeId) {
        return JSON.createObjectNode().put("userId", user.toString()).put("roleId", role.toString())
                .put("scopeKind", scopeKind).put("scopeId", scopeId.toString()).toString();
    }

    void disable(User u) {
        db.sql("update app_user set disabled_at = now() where id = ?").params(u.id()).update();
    }

    HttpResponse<String> login(String email, String password) {
        String body = JSON.createObjectNode().put("email", email).put("password", password).toString();
        return send("POST", "/admin/session", body, null, null, null);
    }

    Session session(User u) {
        HttpResponse<String> r = login(u.email(), u.password());
        if (r.statusCode() != 200) {
            throw new IllegalStateException("login failed: " + r.statusCode());
        }
        return new Session(cookieToken(r), JSON.readTree(r.body()).get("csrfToken").asString());
    }

    static String cookieToken(HttpResponse<String> r) {
        String set = r.headers().firstValue("Set-Cookie").orElseThrow();
        Matcher m = COOKIE.matcher(set);
        if (!m.find()) {
            throw new IllegalStateException("no st_admin cookie: " + set);
        }
        return m.group(1);
    }

    /** A browser call: the session cookie, plus the CSRF header on anything but GET. */
    HttpResponse<String> call(String method, String path, @Nullable String body, @Nullable Session s) {
        return send(method, path, body, s == null ? null : s.token(),
                s == null || method.equals("GET") ? null : s.csrf(), null);
    }

    HttpResponse<String> send(String method, String path, @Nullable String body, @Nullable String cookie,
                              @Nullable String csrf, @Nullable String bearer) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (cookie != null) {
            b.header("Cookie", "st_admin=" + cookie);
        }
        if (csrf != null) {
            b.header("X-CSRF-Token", csrf);
        }
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    long count(String sql, Object... params) {
        return db.sql(sql).params(params).query(Long.class).single();
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
