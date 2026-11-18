package com.squaretoast.testsupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Enrolls POS devices and speaks the device API exactly as the Android CloudApi does. */
public final class DeviceFixture {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    public record Device(UUID id, UUID location, String token) {}

    private final JdbcClient db;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    public DeviceFixture(DataSource ds, int port) {
        this.db = JdbcClient.create(ds);
        this.baseUrl = "http://localhost:" + port + "/api/v1";
    }

    // ------------------------------------------------------------------ seeding

    /** A one-time enrollment code for {@code location}, stored hashed like the real issuer would. */
    public String enrollmentCode(UUID location, int expiresInMinutes) {
        String code = "ENR-" + UUID.randomUUID();
        db.sql("insert into device_enrollment(code_hash, location_id, expires_at) values (?, ?, now() + make_interval(mins => ?))")
                .params(sha256(code), location, expiresInMinutes).update();
        return code;
    }

    public static String p256PublicKey() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return Base64.getEncoder().encodeToString(g.generateKeyPair().getPublic().getEncoded());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String rsaPublicKey() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return Base64.getEncoder().encodeToString(g.generateKeyPair().getPublic().getEncoded());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public Device enrolledDevice(UUID location) {
        HttpResponse<String> r = enroll(enrollmentCode(location, 15), p256PublicKey());
        if (r.statusCode() != 200) {
            throw new IllegalStateException("enroll failed: " + r.statusCode() + " " + r.body());
        }
        JsonNode b = body(r);
        return new Device(UUID.fromString(b.get("deviceId").asString()), UUID.fromString(b.get("locationId").asString()),
                b.get("deviceToken").asString());
    }

    public void revoke(UUID device) {
        db.sql("update device set revoked_at = now() where id = ?").params(device).update();
    }

    public void terminalLocation(UUID location, @Nullable String ref) {
        db.sql("update location set stripe_terminal_location_id = ? where id = ?").params(ref, location).update();
    }

    /** Idempotent: tests share one database, and two identical rules would be ambiguous to the tax engine. */
    public void taxRule(String state, String category, int ratePpm) {
        db.sql("""
                insert into tax_rule(id, state_code, jurisdiction_code, tax_category, rate_ppm, effective_from)
                select ?, ?, ?, ?, ?, date '2020-01-01'
                where not exists (select 1 from tax_rule where state_code = ? and jurisdiction_code = ? and tax_category = ?)""")
                .params(UUID.randomUUID(), state, state + "-STATE", category, ratePpm, state, state + "-STATE", category)
                .update();
    }

    // ------------------------------------------------------------------ queries

    public Map<String, Object> row(String sql, Object... params) {
        return db.sql(sql).params(params).query().singleRow();
    }

    public long count(String sql, Object... params) {
        return db.sql(sql).params(params).query(Long.class).single();
    }

    public UUID checkOf(UUID order) {
        return db.sql("select id from guest_check where order_id = ?").params(order).query(UUID.class).single();
    }

    // ------------------------------------------------------------------ device API

    public HttpResponse<String> enroll(String code, String publicKeySpki) {
        ObjectNode b = JSON.createObjectNode().put("code", code).put("publicKeySpki", publicKeySpki).put("label", "Bar iPad");
        return send("POST", "/devices/enroll", b.toString(), null);
    }

    public HttpResponse<String> me(@Nullable String token) {
        return send("GET", "/devices/me", null, token);
    }

    public HttpResponse<String> sync(@Nullable String token, List<ObjectNode> mutations) {
        ArrayNode arr = JSON.createArrayNode();
        mutations.forEach(arr::add);
        return send("POST", "/sync/mutations", JSON.createObjectNode().set("mutations", arr).toString(), token);
    }

    public HttpResponse<String> connectionToken(@Nullable String token) {
        return send("POST", "/terminal/connection_token", "{}", token);
    }

    public HttpResponse<String> pay(@Nullable String token, UUID check, String body) {
        return send("POST", "/checks/" + check + "/payments", body, token);
    }

    /** A request to an arbitrary path under the server root, e.g. one with encoded or decorated segments. */
    public HttpResponse<String> raw(String method, String path, @Nullable String body, @Nullable String token) {
        return send(method, path.substring("/api/v1".length()), body, token);
    }

    public static String payBody(UUID idempotencyKey, String method, long tipCents) {
        return JSON.createObjectNode().put("idempotencyKey", idempotencyKey.toString()).put("method", method)
                .put("tipCents", tipCents).toString();
    }

    // ------------------------------------------------------------------ mutations (Android payload shapes)

    public record Line(UUID menuItem, int seat, int course, int qty, long devicePriceCents) {}

    private long seq;

    public ObjectNode orderPlaced(UUID mutationId, UUID order, UUID location, Map<Integer, UUID> courseIds, List<Line> lines) {
        ArrayNode ls = JSON.createArrayNode();
        for (Line l : lines) {
            ls.add(JSON.createObjectNode()
                    .put("lineId", UUID.randomUUID().toString())
                    .put("courseId", courseIds.get(l.course()).toString())
                    .put("course", l.course())
                    .put("seat", l.seat())
                    .put("menuItemId", l.menuItem().toString())
                    .put("quantity", l.qty())
                    .put("unitPriceCents", l.devicePriceCents()));
        }
        ObjectNode payload = JSON.createObjectNode().put("orderId", order.toString()).put("locationId", location.toString())
                .put("tableLabel", "T4");
        payload.set("lines", ls);
        return mutation(mutationId, order, "ORDER_PLACED", payload.toString());
    }

    public ObjectNode courseTransitioned(UUID mutationId, UUID order, UUID course, String action, String from, String to) {
        String payload = JSON.createObjectNode().put("courseId", course.toString()).put("action", action)
                .put("from", from).put("to", to).toString();
        return mutation(mutationId, order, "COURSE_TRANSITIONED", payload);
    }

    public ObjectNode mutation(UUID mutationId, UUID aggregate, String type, String payloadJson) {
        return JSON.createObjectNode().put("mutationId", mutationId.toString()).put("aggregateId", aggregate.toString())
                .put("type", type).put("deviceSeq", ++seq).put("payloadJson", payloadJson);
    }

    /** mutationId -> [status, reason] from a sync response. */
    public static Map<String, List<String>> results(HttpResponse<String> r) {
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        for (JsonNode n : body(r).get("results")) {
            List<String> v = new ArrayList<>();
            v.add(n.get("status").asString());
            JsonNode reason = n.get("reason");
            v.add(reason == null || reason.isNull() ? "" : reason.asString());
            out.put(n.get("mutationId").asString(), v);
        }
        return out;
    }

    public static JsonNode body(HttpResponse<String> r) {
        return JSON.readTree(r.body());
    }

    // ------------------------------------------------------------------ Slice 6: signing devices, KDS, guest QR, claims

    /** An enrolled device that also holds the private half of its claim key, as Android Keystore would. */
    public record Signer(Device device, KeyPair keys) {}

    public Signer enrolledSigner(UUID location) {
        KeyPair keys = p256KeyPair();
        HttpResponse<String> r = enroll(enrollmentCode(location, 15), Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        if (r.statusCode() != 200) {
            throw new IllegalStateException("enroll failed: " + r.statusCode() + " " + r.body());
        }
        JsonNode b = body(r);
        return new Signer(new Device(UUID.fromString(b.get("deviceId").asString()),
                UUID.fromString(b.get("locationId").asString()), b.get("deviceToken").asString()), keys);
    }

    public static KeyPair p256KeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A dining table with a QR token; returns the token. */
    public String table(UUID location, String label) {
        String qr = "qr" + UUID.randomUUID().toString().replace("-", "");
        db.sql("insert into dining_table(id, location_id, label, qr_token) values (?, ?, ?, ?)")
                .params(UUID.randomUUID(), location, label, qr).update();
        return qr;
    }

    public UUID station(UUID location, String name) {
        UUID id = UUID.randomUUID();
        db.sql("insert into kds_station(id, location_id, name) values (?, ?, ?)").params(id, location, name).update();
        return id;
    }

    public void assignStation(UUID menuItem, UUID station) {
        db.sql("update menu_item set kds_station_id = ? where id = ?").params(station, menuItem).update();
    }

    public UUID courseOf(UUID order, int courseNo) {
        return db.sql("select id from course where order_id = ? and course_no = ?").params(order, courseNo)
                .query(UUID.class).single();
    }

    public HttpResponse<String> board(@Nullable String token) {
        return send("GET", "/kds/board", null, token);
    }

    public HttpResponse<String> transition(@Nullable String token, UUID course, String action, String expectedFrom) {
        String b = JSON.createObjectNode().put("action", action).put("expectedFrom", expectedFrom).toString();
        return send("POST", "/courses/" + course + "/transitions", b, token);
    }

    public HttpResponse<String> streamTicketResponse(@Nullable String token) {
        return send("POST", "/kds/stream-tickets", "{}", token);
    }

    public String streamTicket(String token) {
        HttpResponse<String> r = streamTicketResponse(token);
        if (r.statusCode() != 200) {
            throw new IllegalStateException("stream ticket failed: " + r.statusCode());
        }
        return body(r).get("ticket").asString();
    }

    public String kdsStreamUrl(String ticket, @Nullable Long lastEventId) {
        return baseUrl + "/kds/stream?ticket=" + ticket + (lastEventId == null ? "" : "&lastEventId=" + lastEventId);
    }

    public HttpResponse<String> commit(@Nullable String token, UUID order) {
        return send("POST", "/orders/" + order + "/commit", null, token);
    }

    public HttpResponse<String> guestTable(String qr) {
        return send("GET", "/t/" + qr, null, null);
    }

    public HttpResponse<String> guestOrder(String qr, String body) {
        return send("POST", "/t/" + qr + "/orders", body, null);
    }

    public HttpResponse<String> guestChecks(String qr) {
        return send("GET", "/t/" + qr + "/checks", null, null);
    }

    public HttpResponse<String> guestCheck(String qr, UUID check) {
        return send("GET", "/t/" + qr + "/checks/" + check, null, null);
    }

    public HttpResponse<String> guestPay(String qr, UUID check, UUID idempotencyKey, long tipCents) {
        String b = JSON.createObjectNode().put("idempotencyKey", idempotencyKey.toString()).put("tipCents", tipCents).toString();
        return send("POST", "/t/" + qr + "/checks/" + check + "/payments", b, null);
    }

    /** A guest order line: item, quantity, modifier options, and a price the server must ignore. */
    public record GuestLine(UUID menuItem, int qty, List<UUID> options) {}

    public static String guestOrderBody(UUID orderId, List<GuestLine> lines) {
        ArrayNode ls = JSON.createArrayNode();
        for (GuestLine l : lines) {
            ArrayNode opts = JSON.createArrayNode();
            l.options().forEach(o -> opts.add(o.toString()));
            ObjectNode n = JSON.createObjectNode().put("menuItemId", l.menuItem().toString()).put("qty", l.qty())
                    .put("unitPriceCents", 1);
            n.set("optionIds", opts);
            ls.add(n);
        }
        ObjectNode b = JSON.createObjectNode().put("orderId", orderId.toString());
        b.set("lines", ls);
        return b.toString();
    }

    public HttpResponse<String> claimPay(String claim, UUID idempotencyKey, long tipCents) {
        String b = JSON.createObjectNode().put("claim", claim).put("idempotencyKey", idempotencyKey.toString())
                .put("tipCents", tipCents).toString();
        return send("POST", "/claims/payments", b, null);
    }

    /** The claim fields Android's OfflineClaimSigner signs, in its field order. */
    public static ObjectNode claimPayload(UUID check, UUID location, UUID device, long subtotal, long tax, long service,
                                          long total, String currency, long expiresAtEpochSecond, String nonce) {
        return JSON.createObjectNode().put("checkId", check.toString()).put("locationId", location.toString())
                .put("deviceId", device.toString()).put("linesDigest", "sha256-test").put("subtotalCents", subtotal)
                .put("taxCents", tax).put("serviceChargeCents", service).put("totalCents", total).put("currency", currency)
                .put("expiresAtEpochSecond", expiresAtEpochSecond).put("nonce", nonce);
    }

    /** base64url(payload).base64url(SHA256withECDSA signature), exactly as the POS puts it in the QR fragment. */
    public static String signClaim(KeyPair keys, String payloadJson) {
        try {
            byte[] payload = payloadJson.getBytes(StandardCharsets.UTF_8);
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(keys.getPrivate());
            s.update(payload);
            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            return b64.encodeToString(payload) + "." + b64.encodeToString(s.sign());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private HttpResponse<String> send(String method, String path, @Nullable String body, @Nullable String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
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

    public static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
