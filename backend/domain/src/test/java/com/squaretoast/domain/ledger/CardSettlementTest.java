package com.squaretoast.domain.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squaretoast.domain.money.CurrencyCode;
import com.squaretoast.domain.money.Money;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Where the gross of one settled card charge went: debits are who received it (merchant net, platform margin,
 * processor fee), credits are what it pays for (sales and tips owed to staff).
 */
class CardSettlementTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");

    private static Money usd(long cents) {
        return Money.ofCents(cents, USD);
    }

    private static Map<Account, Long> byAccount(JournalTxn t) {
        return t.lines().stream().collect(Collectors.toMap(JournalLine::account, l -> l.amount().cents()));
    }

    @Test
    void settlementBalancesAndMarginIsApplicationFeeMinusProcessorFee() {
        // $50.00 charge incl. $8.00 tip, app fee 30c, Stripe fee $1.75
        JournalTxn t = CardSettlement.txn("pi_1", usd(5000), usd(800), usd(30), usd(175));
        assertThat(t.kind()).isEqualTo(CardSettlement.KIND);
        assertThat(t.sourceRef()).isEqualTo("pi_1");
        assertThat(byAccount(t)).containsOnly(
                Map.entry(Account.MERCHANT_CASH, 4970L),
                Map.entry(Account.PLATFORM_FEE, -145L),
                Map.entry(Account.PROCESSOR_FEE, 175L),
                Map.entry(Account.TIPS_PAYABLE, -800L),
                Map.entry(Account.SALES, -4200L));
        assertThat(t.lines().stream().mapToLong(l -> l.amount().cents()).sum()).isZero();
    }

    @Test
    void positiveMarginWhenTheFeeExceedsTheProcessorCost() {
        JournalTxn t = CardSettlement.txn("pi_2", usd(10_000), usd(0), usd(300), usd(270));
        assertThat(byAccount(t)).containsEntry(Account.PLATFORM_FEE, 30L).containsEntry(Account.PROCESSOR_FEE, 270L);
        assertThat(CardSettlement.margin(t)).isEqualTo(usd(30));
    }

    @Test
    void zeroAmountsGetNoLine() {
        JournalTxn t = CardSettlement.txn("pi_3", usd(1000), usd(0), usd(0), usd(0));
        assertThat(byAccount(t)).containsOnly(Map.entry(Account.MERCHANT_CASH, 1000L), Map.entry(Account.SALES, -1000L));
        assertThat(CardSettlement.margin(t)).isEqualTo(usd(0));
    }

    @Test
    void inconsistentInputsAreRejected() {
        assertThatThrownBy(() -> CardSettlement.txn("pi_4", usd(100), usd(101), usd(0), usd(0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CardSettlement.txn("pi_4", usd(100), usd(0), usd(101), usd(0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CardSettlement.txn("pi_4", usd(100), usd(0), usd(0), usd(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CardSettlement.txn("pi_4", usd(0), usd(0), usd(0), usd(0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
