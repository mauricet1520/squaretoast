package com.squaretoast.device;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DeviceController {

    record EnrollRequest(@Nullable String code, @Nullable String publicKeySpki, @Nullable String label) {}

    private final DeviceService devices;

    DeviceController(DeviceService devices) {
        this.devices = devices;
    }

    /** Public: the one-time code is the credential. */
    @PostMapping(path = "/devices/enroll", version = "1")
    DeviceService.Enrolled enroll(@RequestBody EnrollRequest req) {
        String code = req.code();
        String key = req.publicKeySpki();
        if (code == null || key == null || code.length() > 128 || key.length() > 512) {
            throw new DeviceService.EnrollmentRejected();
        }
        String label = req.label();
        return devices.enroll(code, key, label == null ? "" : label);
    }

    @GetMapping(path = "/devices/me", version = "1")
    DeviceService.Me me(@RequestAttribute(DevicePrincipal.ATTRIBUTE) DevicePrincipal device) {
        return devices.me(device);
    }

    @ExceptionHandler
    ResponseEntity<Void> rejected(DeviceService.EnrollmentRejected e) {
        return ResponseEntity.badRequest().build();
    }
}
