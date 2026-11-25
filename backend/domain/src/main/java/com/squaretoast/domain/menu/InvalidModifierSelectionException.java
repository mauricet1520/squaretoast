package com.squaretoast.domain.menu;

/** The modifiers chosen for an order line break the item's group rules. */
public final class InvalidModifierSelectionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the selection was rejected. */
    public enum Reason { UNKNOWN_OPTION, DUPLICATE_OPTION, INACTIVE_OPTION, TOO_FEW, TOO_MANY }

    private final Reason reason;

    public InvalidModifierSelectionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
