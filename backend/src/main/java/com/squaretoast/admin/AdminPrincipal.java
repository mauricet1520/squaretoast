package com.squaretoast.admin;

import java.util.UUID;

/**
 * A logged-in back-office user, set by {@link AdminAuthFilter}. The merchant comes from the session, never from
 * the request. {@code sessionKey} is the hex SHA-256 of the cookie token, used only to end this session.
 */
public record AdminPrincipal(UUID userId, UUID merchantId, String sessionKey) {

    public static final String ATTRIBUTE = "squaretoast.admin";
}
