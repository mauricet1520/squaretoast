package com.squaretoast.order;

import com.squaretoast.device.DevicePrincipal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/** Order commit by a device of the order's location: 200 COMMITTED, 409 conflict (OVERSOLD / ITEM_86ED / ORDER_NOT_OPEN), 422 invalid order, 404. */
@RestController
class OrderController {

    private static final int UNPROCESSABLE = 422;

    private final OrderCommitService commits;

    OrderController(OrderCommitService commits) {
        this.commits = commits;
    }

    @PostMapping(path = "/orders/{orderId}/commit", version = "1")
    Committed commit(@RequestAttribute(DevicePrincipal.ATTRIBUTE) DevicePrincipal device, @PathVariable UUID orderId) {
        commits.commitAt(orderId, device.locationId()); // also how staff accept a guest QR order
        return new Committed(orderId, "COMMITTED");
    }

    @ExceptionHandler
    ResponseEntity<Void> notFound(CommitException.NotFound e) {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler
    ResponseEntity<Conflict> conflict(CommitException.Conflict e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Conflict(e.conflict, e.inventoryItemId));
    }

    @ExceptionHandler
    ResponseEntity<Rejected> rejected(CommitException.Rejected e) {
        return ResponseEntity.status(UNPROCESSABLE).body(new Rejected(e.error, e.reason));
    }

    record Committed(UUID orderId, String status) {}

    record Conflict(String conflict, @Nullable UUID inventoryItemId) {}

    record Rejected(String error, @Nullable String reason) {}
}
