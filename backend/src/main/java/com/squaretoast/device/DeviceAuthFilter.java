package com.squaretoast.device;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires a live device bearer token on the POS endpoints. Tokens are looked up by their SHA-256, so a leaked
 * table holds nothing usable and no secret is compared byte by byte.
 */
final class DeviceAuthFilter extends OncePerRequestFilter {

    private static final Pattern GUARDED = Pattern.compile("^/api/[^/]+/(devices/me$|sync/|terminal/|checks/)");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{20,128}");

    private final JdbcClient db;

    DeviceAuthFilter(JdbcClient db) {
        this.db = db;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !GUARDED.matcher(request.getRequestURI().substring(request.getContextPath().length())).find();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<DevicePrincipal> device = authenticate(request.getHeader("Authorization"));
        if (device.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer");
            return;
        }
        request.setAttribute(DevicePrincipal.ATTRIBUTE, device.get());
        chain.doFilter(request, response);
    }

    private Optional<DevicePrincipal> authenticate(@Nullable String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = header.substring(7);
        if (!TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        return db.sql("select id, location_id from device where token_hash = ? and revoked_at is null")
                .params(Tokens.sha256(token))
                .query((rs, n) -> new DevicePrincipal(rs.getObject("id", UUID.class), rs.getObject("location_id", UUID.class)))
                .optional();
    }
}
