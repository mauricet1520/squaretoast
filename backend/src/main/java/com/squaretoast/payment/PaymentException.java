package com.squaretoast.payment;

/** Why a card payment could not be started. */
public abstract sealed class PaymentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    PaymentException(String message) {
        super(message);
    }

    public static final class NotFound extends PaymentException {
        private static final long serialVersionUID = 1L;

        NotFound() {
            super("check not found");
        }
    }

    public static final class CheckNotOpen extends PaymentException {
        private static final long serialVersionUID = 1L;

        CheckNotOpen() {
            super("check is not open");
        }
    }

    /** The merchant has no Stripe account or no pricing plan in effect. */
    public static final class NotConfigured extends PaymentException {
        private static final long serialVersionUID = 1L;

        NotConfigured(String what) {
            super("merchant not set up for card payments: " + what);
        }
    }

    /** Everything owed is already paid or being paid under another key. */
    public static final class NothingDue extends PaymentException {
        private static final long serialVersionUID = 1L;

        NothingDue() {
            super("nothing due");
        }
    }

    /** The idempotency key already belongs to a different charge. */
    public static final class IdempotencyConflict extends PaymentException {
        private static final long serialVersionUID = 1L;

        IdempotencyConflict() {
            super("idempotency key reused for a different charge");
        }
    }
}
