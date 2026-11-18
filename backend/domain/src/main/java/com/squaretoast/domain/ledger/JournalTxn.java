package com.squaretoast.domain.ledger;

import java.util.List;
import java.util.Objects;

/** A balanced journal transaction, checked here as well as by the database triggers. */
public record JournalTxn(String kind, String sourceRef, List<JournalLine> lines) {

    public JournalTxn {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sourceRef, "sourceRef");
        lines = List.copyOf(lines);
        if (lines.size() < 2) {
            throw new IllegalArgumentException("a journal txn needs at least two lines");
        }
        long sum = 0;
        for (JournalLine l : lines) {
            if (!l.amount().currency().equals(lines.get(0).amount().currency())) {
                throw new IllegalArgumentException("a journal txn cannot mix currencies");
            }
            sum = Math.addExact(sum, l.amount().cents());
        }
        if (sum != 0) {
            throw new IllegalArgumentException("journal txn unbalanced by " + sum + " cents");
        }
    }
}
