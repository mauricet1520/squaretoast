package com.squaretoast.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squaretoast.domain.money.CurrencyCode;
import com.squaretoast.domain.money.CurrencyMismatchException;
import com.squaretoast.domain.money.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChargeRequestTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final UUID KEY = UUID.randomUUID();
    private static final UUID CHECK = UUID.randomUUID();

    private static Money usd(long cents) {
        return Money.ofCents(cents, USD);
    }

    @Test
    void validRequestKeepsItsParts() {
        ChargeRequest r = new ChargeRequest(KEY, CHECK, usd(1200), usd(200), usd(30), "acct_1Abc");
        assertThat(r.amount()).isEqualTo(usd(1200));
        assertThat(r.connectedAccountRef()).isEqualTo("acct_1Abc");
    }

    @Test
    void amountMustBePositiveAndCoverTipAndFee() {
        assertThatThrownBy(() -> new ChargeRequest(KEY, CHECK, usd(0), usd(0), usd(0), "acct_1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChargeRequest(KEY, CHECK, usd(100), usd(101), usd(0), "acct_1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChargeRequest(KEY, CHECK, usd(100), usd(0), usd(101), "acct_1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChargeRequest(KEY, CHECK, usd(100), usd(-1), usd(0), "acct_1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChargeRequest(KEY, CHECK, usd(100), usd(0), usd(-1), "acct_1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void currenciesMustMatch() {
        Money cad = Money.ofCents(10, CurrencyCode.of("CAD"));
        assertThatThrownBy(() -> new ChargeRequest(KEY, CHECK, usd(100), cad, usd(0), "acct_1"))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> new ChargeRequest(KEY, CHECK, usd(100), usd(0), cad, "acct_1"))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void connectedAccountMustLookLikeAStripeAccountId() {
        for (String bad : new String[] {"", "acct_", "cus_123", "acct_12&amount=1", "acct_1 2"}) {
            assertThatThrownBy(() -> new ChargeRequest(KEY, CHECK, usd(100), usd(0), usd(0), bad))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
