package com.squaretoast.device;

import java.util.UUID;

/** The authenticated POS device for this request, put on the request by {@link DeviceAuthFilter}. */
public record DevicePrincipal(UUID deviceId, UUID locationId) {
    public static final String ATTRIBUTE = "squaretoast.device";
}
