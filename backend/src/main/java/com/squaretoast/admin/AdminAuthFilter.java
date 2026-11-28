package com.squaretoast.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards every {@code /api/{v}/admin/**} path, deny by default; only {@code POST .../admin/session} (login) is open.
 * Accepts only the {@code st_admin} session cookie: device bearer tokens are never looked at here, and the device
 * filter skips these paths, so the two kinds of caller can never stand in for each other. Every call other than
 * GET/HEAD/OPTIONS must also carry the session's CSRF token in {@code X-CSRF-Token}.
 */
final class AdminAuthFilter extends OncePerRequestFilter {

    static final String COOKIE = "st_admin";
    static final Pattern ADMIN = Pattern.compile("^/api/[^/]+/admin(/.*)?$");
    private static final Pattern LOGIN = Pattern.compile("^/api/[^/]+/admin/session$");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    private record Found(UUID userId, UUID merchantId, String csrfHex) {}

    private final JdbcClient db;

    AdminAuthFilter(JdbcClient db) {
        this.db = db;
    }

    private static String path(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return request.getServletPath() + (pathInfo == null ? "" : pathInfo);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = path(request);
        if (!ADMIN.matcher(path).matches()) {
            return true;
        }
        return request.getMethod().equals("POST") && LOGIN.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = cookie(request);
        Optional<Found> found = token == null ? Optional.empty() : lookup(token);
        if (token == null || found.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (!SAFE.contains(request.getMethod())) {
            String csrf = request.getHeader("X-CSRF-Token");
            if (csrf == null || !Secrets.matchesHash(csrf, HexFormat.of().parseHex(found.get().csrfHex()))) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        }
        request.setAttribute(AdminPrincipal.ATTRIBUTE, new AdminPrincipal(found.get().userId(), found.get().merchantId(),
                HexFormat.of().formatHex(Secrets.sha256(token))));
        chain.doFilter(request, response);
    }

    private static @Nullable String cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (c.getName().equals(COOKIE) && TOKEN.matcher(c.getValue()).matches()) {
                return c.getValue();
            }
        }
        return null;
    }

    /** A live session: not revoked, used in the last 30 minutes, under 12 hours old, its user not disabled. */
    private Optional<Found> lookup(String token) {
        return db.sql("""
                update admin_session s set last_seen_at = now()
                from app_user u
                where s.token_hash = ? and u.id = s.user_id and s.revoked_at is null and u.disabled_at is null
                  and s.last_seen_at > now() - interval '30 minutes' and s.created_at > now() - interval '12 hours'
                returning s.user_id, u.merchant_id, s.csrf_hash""")
                .params((Object) Secrets.sha256(token))
                .query((rs, n) -> new Found(rs.getObject("user_id", UUID.class), rs.getObject("merchant_id", UUID.class),
                        HexFormat.of().formatHex(rs.getBytes("csrf_hash"))))
                .optional();
    }
}
