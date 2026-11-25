package com.squaretoast.domain.labor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squaretoast.domain.money.CurrencyCode;
import com.squaretoast.domain.money.Money;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Integer cents, largest-remainder residual, deterministic tie-break by employee id. */
class TipDistributorTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private static Money usd(long cents) {
        return Money.ofCents(cents, USD);
    }

    @Test
    void equalWeightsGiveTheLeftoverCentToTheLowestIdOnATie() {
        Map<UUID, Money> out = TipDistributor.distribute(usd(1000), List.of(new TipShare(C, 1), new TipShare(A, 1), new TipShare(B, 1)));
        assertThat(out).containsEntry(A, usd(334)).containsEntry(B, usd(333)).containsEntry(C, usd(333));
    }

    @Test
    void tieBreakUsesUnsignedIdOrderNotJavaSignedUuidCompare() {
        UUID high = UUID.fromString("80000000-0000-0000-0000-000000000000");
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Map<UUID, Money> out = TipDistributor.distribute(usd(3), List.of(new TipShare(high, 1), new TipShare(low, 1)));
        assertThat(out).containsEntry(low, usd(2)).containsEntry(high, usd(1));
    }

    @Test
    void leftoverCentsGoToTheLargestRemainders() {
        // hours x100: 8h and 4h of $10.01 -> 667.33 / 333.67 -> the .67 gets the cent.
        Map<UUID, Money> out = TipDistributor.distribute(usd(1001), List.of(new TipShare(A, 800), new TipShare(B, 400)));
        assertThat(out).containsEntry(A, usd(667)).containsEntry(B, usd(334));
    }

    @Test
    void sharesAlwaysSumToThePool() {
        List<TipShare> shares = List.of(new TipShare(A, 733), new TipShare(B, 129), new TipShare(C, 1));
        for (long pool : new long[] {0, 1, 2, 99, 12_345, 9_999_999}) {
            long total = TipDistributor.distribute(usd(pool), shares).values().stream().mapToLong(Money::cents).sum();
            assertThat(total).as("pool %d", pool).isEqualTo(pool);
        }
    }

    @Test
    void zeroWeightMembersGetNothing() {
        Map<UUID, Money> out = TipDistributor.distribute(usd(500), List.of(new TipShare(A, 0), new TipShare(B, 3)));
        assertThat(out).containsEntry(A, usd(0)).containsEntry(B, usd(500));
    }

    @Test
    void invalidPoolsAreRejected() {
        assertThatThrownBy(() -> TipDistributor.distribute(usd(100), List.of(new TipShare(A, 0))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TipDistributor.distribute(usd(100), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TipDistributor.distribute(usd(-1), List.of(new TipShare(A, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TipDistributor.distribute(usd(100), List.of(new TipShare(A, 1), new TipShare(A, 2))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TipShare(A, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
