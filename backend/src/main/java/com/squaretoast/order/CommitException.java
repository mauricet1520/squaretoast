package com.squaretoast.order;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Why an order commit was refused. Thrown inside the commit transaction, so every decrement rolls back. */
sealed class CommitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    CommitException(String message) {
        super(message);
    }

    /** 404: no such order. */
    static final class NotFound extends CommitException {
        private static final long serialVersionUID = 1L;

        NotFound(UUID order) {
            super("no order " + order);
        }
    }

    /** 409: OVERSOLD, ITEM_86ED or ORDER_NOT_OPEN. */
    static final class Conflict extends CommitException {
        private static final long serialVersionUID = 1L;
        final String conflict;
        final @Nullable UUID inventoryItemId;

        Conflict(String conflict, @Nullable UUID inventoryItemId) {
            super(conflict);
            this.conflict = conflict;
            this.inventoryItemId = inventoryItemId;
        }
    }

    /** 422: the order itself is invalid (INVALID_MODIFIERS with a reason, ITEM_NOT_AT_LOCATION). */
    static final class Rejected extends CommitException {
        private static final long serialVersionUID = 1L;
        final String error;
        final @Nullable String reason;

        Rejected(String error, @Nullable String reason) {
            super(error);
            this.error = error;
            this.reason = reason;
        }
    }
}
