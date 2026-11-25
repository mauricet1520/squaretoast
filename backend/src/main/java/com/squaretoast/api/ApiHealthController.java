package com.squaretoast.api;

import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Versioned API liveness: reports actuator health plus the API version that served the request. */
@RestController
class ApiHealthController {

    private final HealthEndpoint health;

    ApiHealthController(HealthEndpoint health) {
        this.health = health;
    }

    @GetMapping(path = "/health", version = "1")
    ApiInfo health(@PathVariable("version") String version) {
        HealthDescriptor descriptor = health.health();
        String status = descriptor == null ? "UNKNOWN" : descriptor.getStatus().getCode();
        return new ApiInfo(status, version.replaceFirst("^[vV]", ""));
    }

    record ApiInfo(String status, String apiVersion) {}
}
