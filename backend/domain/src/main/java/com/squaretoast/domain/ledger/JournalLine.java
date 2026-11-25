package com.squaretoast.domain.ledger;

import com.squaretoast.domain.money.Money;
import java.util.Objects;

/** One signed posting: positive is a debit, negative a credit. */
public record JournalLine(Account account, Money amount) {

    public JournalLine {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(amount, "amount");
    }
}
