package com.squaretoast.kds;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * One-time tickets for the KDS stream (plan S6-2). A ticket is 256 random bits, stored only as its SHA-256,
 * valid for 60 seconds and for one connection, and only while the device that asked for it is not revoked.
 */
@Component
class StreamTickets {

    private static final int TTL_SECONDS = 60;
    private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9_-]{20,128}");

    private final JdbcClient db;
    private final SecureRandom random = new SecureRandom();

    StreamTickets(JdbcClient db) {
        this.db = db;
    }

    String issue(UUID device, UUID location) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        db.sql("delete from kds_stream_ticket where expires_at < now() - interval '1 hour'").update();
        db.sql("""
                insert into kds_stream_ticket(ticket_hash, device_id, location_id, expires_at)
                values (?, ?, ?, now() + make_interval(secs => ?))""")
                .params(sha256(ticket), device, location, TTL_SECONDS).update();
        return ticket;
    }

    record Redeemed(UUID device, UUID location) {}

    /** The ticket's device and location, marking the ticket used; empty if malformed, unknown, used, expired or revoked. */
    Optional<Redeemed> redeem(String ticket) {
        if (!SHAPE.matcher(ticket).matches()) {
            return Optional.empty();
        }
        return db.sql("""
                update kds_stream_ticket t set used_at = now()
                from device d
                where t.ticket_hash = ? and t.used_at is null and t.expires_at > now()
                  and d.id = t.device_id and d.revoked_at is null and d.location_id = t.location_id
                returning t.device_id, t.location_id""")
                .params(sha256(ticket))
                .query((rs, n) -> new Redeemed(rs.getObject("device_id", UUID.class), rs.getObject("location_id", UUID.class)))
                .optional();
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
