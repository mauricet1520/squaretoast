package com.squaretoast.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Slice B3: writes one append-only {@code audit_event} row per change, in the caller's transaction, so a change
 * and its record commit or roll back together. Secret-looking keys are redacted from before/after first.
 */
@Component
public class AuditLog {

    /** Who acted: at most one of a back-office user, a POS employee or a device. */
    public record Actor(@Nullable UUID userId, @Nullable UUID employeeId, @Nullable UUID deviceId) {
        public static Actor user(UUID id) {
            return new Actor(id, null, null);
        }

        public static Actor employee(UUID id) {
            return new Actor(null, id, null);
        }

        public static Actor device(UUID id) {
            return new Actor(null, null, id);
        }
    }

    public record Entry(UUID merchantId, @Nullable UUID locationId, @Nullable Actor actor, @Nullable UUID approverUserId,
                        String action, String entityKind, @Nullable UUID entityId, @Nullable Object before,
                        @Nullable Object after) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String REQUEST_ID = "squaretoast.requestId";
    /** Key words that mark a secret; matched as whole words of a camelCase or snake_case key. */
    private static final Set<String> SECRET_WORDS = Set.of("password", "passwd", "passcode", "pin", "pan", "card", "cvv", "cvc",
            "token", "secret", "otp", "mfa");
    private static final String REDACTED = "[redacted]";

    private final JdbcClient db;

    AuditLog(JdbcClient db) {
        this.db = db;
    }

    public void record(Entry e) {
        Actor actor = e.actor() == null ? new Actor(null, null, null) : e.actor();
        db.sql("""
                insert into audit_event(id, merchant_id, location_id, actor_user_id, actor_employee_id, actor_device_id,
                    approver_user_id, action, entity_kind, entity_id, before, after, request_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?)""")
                .params(java.util.Arrays.asList(UUID.randomUUID(), e.merchantId(), e.locationId(), actor.userId(),
                        actor.employeeId(), actor.deviceId(),
                        e.approverUserId(), e.action(), e.entityKind(), e.entityId(), json(e.before()), json(e.after()),
                        requestId()))
                .update();
    }

    private static @Nullable String json(@Nullable Object value) {
        return value == null ? null : redact(JSON.valueToTree(value)).toString();
    }

    /** One id per HTTP request, shared by every event the request writes; a fresh one outside a request. */
    private static UUID requestId() {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra == null) {
            return UUID.randomUUID();
        }
        Object id = ra.getAttribute(REQUEST_ID, RequestAttributes.SCOPE_REQUEST);
        if (id instanceof UUID u) {
            return u;
        }
        UUID u = UUID.randomUUID();
        ra.setAttribute(REQUEST_ID, u, RequestAttributes.SCOPE_REQUEST);
        return u;
    }

    /** A copy of {@code node} with every secret-looking key's value replaced, at any depth. */
    static JsonNode redact(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            ObjectNode out = JSON.createObjectNode();
            for (Map.Entry<String, JsonNode> f : obj.properties()) {
                if (isSecret(f.getKey())) {
                    out.put(f.getKey(), REDACTED);
                } else {
                    out.set(f.getKey(), redact(f.getValue()));
                }
            }
            return out;
        }
        if (node instanceof ArrayNode arr) {
            ArrayNode out = JSON.createArrayNode();
            for (JsonNode item : arr) {
                out.add(redact(item));
            }
            return out;
        }
        return node.deepCopy();
    }

    static boolean isSecret(String key) {
        for (String word : words(key)) {
            if (SECRET_WORDS.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** "posPin" -> [pos, pin]; "Card_Number" -> [card, number]; "spanish" -> [spanish]. */
    private static List<String> words(String key) {
        List<String> out = new ArrayList<>();
        StringBuilder w = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean boundary = !Character.isLetterOrDigit(c)
                    || (Character.isUpperCase(c) && i > 0 && Character.isLowerCase(key.charAt(i - 1)));
            if (boundary && w.length() > 0) {
                out.add(w.toString().toLowerCase(Locale.ROOT));
                w.setLength(0);
            }
            if (Character.isLetterOrDigit(c)) {
                w.append(c);
            }
        }
        if (w.length() > 0) {
            out.add(w.toString().toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
