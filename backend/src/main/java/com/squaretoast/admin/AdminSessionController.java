package com.squaretoast.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Slice B1: {@code /admin/session} logs in, says who you are, and logs out. */
@RestController
class AdminSessionController {

    /** The one body every failed login gets: wrong password, unknown email and disabled user look the same. */
    private static final Map<String, String> INVALID = Map.of("error", "invalid_credentials");

    record LoginRequest(@Nullable String email, @Nullable String password) {}

    record LoginResponse(UUID userId, UUID merchantId, String csrfToken) {}

    private final AdminSessions sessions;

    AdminSessionController(AdminSessions sessions) {
        this.sessions = sessions;
    }

    @PostMapping(path = "/admin/session", version = "1")
    ResponseEntity<Object> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        AdminSessions.Result r = sessions.login(body.email(), body.password(), request.getRemoteAddr());
        return switch (r) {
            case AdminSessions.Ok ok -> ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, cookie(ok.token(), Duration.ofHours(12)).toString())
                    .body(new LoginResponse(ok.userId(), ok.merchantId(), ok.csrf()));
            case AdminSessions.Limited l -> ResponseEntity.status(429)
                    .header(HttpHeaders.RETRY_AFTER, Integer.toString(l.retryAfterSeconds())).body(INVALID);
            case AdminSessions.Invalid i -> ResponseEntity.status(401).body(INVALID);
        };
    }

    @GetMapping(path = "/admin/session", version = "1")
    AdminSessions.Me me(@RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return sessions.me(admin);
    }

    @DeleteMapping(path = "/admin/session", version = "1")
    ResponseEntity<Void> logout(@RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        sessions.logout(admin);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    private static ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(AdminAuthFilter.COOKIE, value)
                .httpOnly(true).secure(true).sameSite("Strict").path("/api").maxAge(maxAge).build();
    }
}
