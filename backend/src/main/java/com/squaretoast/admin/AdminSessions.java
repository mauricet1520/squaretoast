package com.squaretoast.admin;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Slice B1: password login, rate limits and session lifecycle. Every failure looks the same to the caller. */
@Service
class AdminSessions {

    static final int MAX_EMAIL_FAILURES = 5;
    static final int MAX_IP_FAILURES = 50;
    static final int WINDOW_SECONDS = 15 * 60;
    private static final int MAX_EMAIL = 254;
    private static final int MAX_PASSWORD = 1024; // Argon2 on megabyte inputs is a cheap way to burn our CPU

    sealed interface Result {}

    record Ok(UUID userId, UUID merchantId, String token, String csrf) implements Result {}

    record Invalid() implements Result {}

    record Limited(int retryAfterSeconds) implements Result {}

    private record UserRow(UUID id, UUID merchantId, String hash, boolean disabled) {}

    private final JdbcClient db;

    AdminSessions(JdbcClient db) {
        this.db = db;
    }

    @Transactional
    Result login(@Nullable String email, @Nullable String password, String ip) {
        if (email == null || password == null || email.length() > MAX_EMAIL || password.length() > MAX_PASSWORD) {
            return new Invalid();
        }
        String key = email.trim().toLowerCase(Locale.ROOT);
        // One login per email at a time, so parallel guesses cannot slip past the failure count.
        db.sql("select pg_advisory_xact_lock(hashtext(?))").params(key).query((rs, n) -> 1).list();
        long emailFailures = db.sql("""
                select count(*) from admin_login_attempt
                where email_lower = ? and not ok and at > greatest(now() - make_interval(secs => ?),
                      coalesce((select max(at) from admin_login_attempt where email_lower = ? and ok), '-infinity'))""")
                .params(key, WINDOW_SECONDS, key).query(Long.class).single();
        long ipFailures = db.sql("""
                select count(*) from admin_login_attempt where ip = ? and not ok and at > now() - make_interval(secs => ?)""")
                .params(ip, WINDOW_SECONDS).query(Long.class).single();
        if (emailFailures >= MAX_EMAIL_FAILURES || ipFailures >= MAX_IP_FAILURES) {
            return new Limited(WINDOW_SECONDS);
        }

        Optional<UserRow> user = db.sql("""
                select id, merchant_id, password_hash, disabled_at is not null as disabled from app_user where lower(email) = ?""")
                .params(key)
                .query((rs, n) -> new UserRow(rs.getObject("id", UUID.class), rs.getObject("merchant_id", UUID.class),
                        rs.getString("password_hash"), rs.getBoolean("disabled")))
                .optional();
        boolean ok;
        if (user.isEmpty()) {
            Passwords.dummyCheck(password);
            ok = false;
        } else {
            ok = Passwords.matches(password, user.get().hash()) && !user.get().disabled();
        }
        db.sql("insert into admin_login_attempt(email_lower, ip, ok) values (?, ?, ?)").params(key, ip, ok).update();
        if (!ok) {
            return new Invalid();
        }
        String token = Secrets.newToken();
        String csrf = Secrets.newToken();
        db.sql("insert into admin_session(token_hash, user_id, csrf_hash) values (?, ?, ?)")
                .params(Secrets.sha256(token), user.get().id(), Secrets.sha256(csrf)).update();
        return new Ok(user.get().id(), user.get().merchantId(), token, csrf);
    }

    void logout(AdminPrincipal p) {
        db.sql("update admin_session set revoked_at = now() where token_hash = decode(?, 'hex') and revoked_at is null")
                .params(p.sessionKey()).update();
    }

    record Me(UUID userId, UUID merchantId, String email, String displayName) {}

    Me me(AdminPrincipal p) {
        return db.sql("select email, display_name from app_user where id = ?")
                .params(p.userId())
                .query((rs, n) -> new Me(p.userId(), p.merchantId(), rs.getString("email"), rs.getString("display_name")))
                .single();
    }
}
