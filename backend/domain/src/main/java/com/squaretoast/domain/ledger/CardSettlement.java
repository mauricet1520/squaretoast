package com.squaretoast.domain.ledger;

import com.squaretoast.domain.money.CurrencyMismatchException;
import com.squaretoast.domain.money.Money;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the gross of one settled card charge went. Debits: the merchant's net (amount - application fee), the
 * platform's margin (application fee - processor fee, may be negative) and the processor's fee. Credits: tips owed
 * to staff and the rest of the check (sales incl. tax; check close splits tax out later).
 */
public final class CardSettlement {

    public static final String KIND = "CARD_SETTLEMENT";

    private CardSettlement() {}

    public static JournalTxn txn(String intentRef, Money amount, Money tip, Money applicationFee, Money processorFee) {
        for (Money m : List.of(tip, applicationFee, processorFee)) {
            if (!m.currency().equals(amount.currency())) {
                throw new CurrencyMismatchException(amount.currency(), m.currency());
            }
        }
        if (amount.cents() <= 0) {
            throw new IllegalArgumentException("settled amount must be positive: " + amount);
        }
        if (tip.isNegative() || tip.cents() > amount.cents()) {
            throw new IllegalArgumentException("tip must be 0..amount: " + tip);
        }
        if (applicationFee.isNegative() || applicationFee.cents() > amount.cents()) {
            throw new IllegalArgumentException("application fee must be 0..amount: " + applicationFee);
        }
        if (processorFee.isNegative()) {
            throw new IllegalArgumentException("processor fee cannot be negative: " + processorFee);
        }
        List<JournalLine> lines = new ArrayList<>();
        add(lines, Account.MERCHANT_CASH, amount.minus(applicationFee));
        add(lines, Account.PLATFORM_FEE, applicationFee.minus(processorFee));
        add(lines, Account.PROCESSOR_FEE, processorFee);
        add(lines, Account.TIPS_PAYABLE, Money.ofCents(-tip.cents(), tip.currency()));
        add(lines, Account.SALES, Money.ofCents(-(amount.cents() - tip.cents()), amount.currency()));
        return new JournalTxn(KIND, intentRef, lines);
    }

    /** The platform's margin on a settlement: its PLATFORM_FEE posting. */
    public static Money margin(JournalTxn settlement) {
        Money margin = Money.zero(settlement.lines().get(0).amount().currency());
        for (JournalLine l : settlement.lines()) {
            if (l.account() == Account.PLATFORM_FEE) {
                margin = margin.plus(l.amount());
            }
        }
        return margin;
    }

    private static void add(List<JournalLine> lines, Account account, Money amount) {
        if (amount.cents() != 0) {
            lines.add(new JournalLine(account, amount));
        }
    }
}
