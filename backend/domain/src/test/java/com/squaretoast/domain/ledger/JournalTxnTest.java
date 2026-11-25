package com.squaretoast.domain.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squaretoast.domain.money.CurrencyCode;
import com.squaretoast.domain.money.Money;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Mirrors the DB triggers: at least two lines, sum zero, one currency. */
class JournalTxnTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");

    private static JournalLine line(Account a, long cents) {
        return new JournalLine(a, Money.ofCents(cents, USD));
    }

    @Test
    void balancedTxnIsAccepted() {
        JournalTxn t = new JournalTxn("TEST", "ref", List.of(line(Account.MERCHANT_CASH, 100), line(Account.SALES, -100)));
        assertThat(t.lines()).hasSize(2);
    }

    @Test
    void unbalancedTxnIsRejected() {
        assertThatThrownBy(() -> new JournalTxn("TEST", "ref", List.of(line(Account.MERCHANT_CASH, 100), line(Account.SALES, -99))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void singleLineTxnIsRejected() {
        assertThatThrownBy(() -> new JournalTxn("TEST", "ref", List.of(line(Account.MERCHANT_CASH, 0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mixedCurrenciesAreRejected() {
        JournalLine cad = new JournalLine(Account.SALES, Money.ofCents(-100, CurrencyCode.of("CAD")));
        assertThatThrownBy(() -> new JournalTxn("TEST", "ref", List.of(line(Account.MERCHANT_CASH, 100), cad)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountsMatchTheDatabaseCheckConstraint() {
        assertThat(Account.values()).extracting(Enum::name).containsExactlyInAnyOrder(
                "SALES", "TAX_PAYABLE", "TIPS_PAYABLE", "SERVICE_CHARGE", "PLATFORM_FEE", "PROCESSOR_FEE",
                "MERCHANT_CASH", "TIP_FEE_DEDUCTION", "REFUNDS");
    }
}
