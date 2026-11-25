package com.squaretoast.domain.labor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squaretoast.domain.labor.TipPoolIneligibleException.Reason;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** CLAUDE.md 5 / FLSA: no managers, supervisors or 20%+ active owners; no BOH when the location takes a tip credit. */
class TipPoolPolicyTest {

    private static TipPoolCandidate server() {
        return new TipPoolCandidate(UUID.randomUUID(), false, false, 0, false, false);
    }

    @Test
    void frontOfHouseStaffAreEligibleWithOrWithoutTipCredit() {
        assertThatCode(() -> TipPoolPolicy.check(server(), true)).doesNotThrowAnyException();
        assertThatCode(() -> TipPoolPolicy.check(server(), false)).doesNotThrowAnyException();
    }

    @Test
    void managersAndSupervisorsAreAlwaysRejected() {
        assertRejected(new TipPoolCandidate(UUID.randomUUID(), true, false, 0, false, false), false, Reason.MANAGER);
        assertRejected(new TipPoolCandidate(UUID.randomUUID(), false, true, 0, false, false), false, Reason.SUPERVISOR);
    }

    @Test
    void ownersAtTwentyPercentWhoActivelyManageAreRejected() {
        assertRejected(new TipPoolCandidate(UUID.randomUUID(), false, false, 2000, true, false), false, Reason.ACTIVE_OWNER);
        assertRejected(new TipPoolCandidate(UUID.randomUUID(), false, false, 10_000, true, false), false, Reason.ACTIVE_OWNER);
        assertThatCode(() -> TipPoolPolicy.check(new TipPoolCandidate(UUID.randomUUID(), false, false, 1999, true, false), false))
                .doesNotThrowAnyException();
        assertThatCode(() -> TipPoolPolicy.check(new TipPoolCandidate(UUID.randomUUID(), false, false, 5000, false, false), false))
                .doesNotThrowAnyException();
    }

    @Test
    void backOfHouseIsRejectedIfAndOnlyIfTheLocationTakesATipCredit() {
        TipPoolCandidate cook = new TipPoolCandidate(UUID.randomUUID(), false, false, 0, false, true);
        assertRejected(cook, true, Reason.BOH_AT_TIP_CREDIT_LOCATION);
        assertThatCode(() -> TipPoolPolicy.check(cook, false)).doesNotThrowAnyException();
    }

    @Test
    void candidateRejectsImpossibleEquity() {
        assertThatThrownBy(() -> new TipPoolCandidate(UUID.randomUUID(), false, false, 10_001, false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TipPoolCandidate(UUID.randomUUID(), false, false, -1, false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertRejected(TipPoolCandidate c, boolean tipCredit, Reason reason) {
        assertThatThrownBy(() -> TipPoolPolicy.check(c, tipCredit))
                .isInstanceOfSatisfying(TipPoolIneligibleException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.reason()).isEqualTo(reason));
    }
}
