package com.squaretoast.domain.labor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** CLAUDE.md 5: overtime is 1.5x the FULL minimum wage, less the tip credit, never 1.5x the cash wage. */
class OvertimeCalculatorTest {

    private static final TippedWage FEDERAL = new TippedWage(725, 213);
    private static final TippedWage CALIFORNIA = new TippedWage(1690, 1690);

    @Test
    void tippedOvertimeCashRateIsOnePointFiveTimesFullMinimumWageLessTipCredit() {
        // 1.5 x 7.25 = 10.875; tip credit 5.12 -> 5.755/h, rounded up in the employee's favour.
        assertThat(OvertimeCalculator.overtimeCashRateCents(FEDERAL)).isEqualTo(576L);
        assertThat(OvertimeCalculator.overtimeCashRateCents(FEDERAL)).isNotEqualTo(320L); // 1.5 x 2.13, the illegal shortcut
    }

    @Test
    void withoutATipCreditOvertimeIsOnePointFiveTimesMinimumWage() {
        assertThat(OvertimeCalculator.overtimeCashRateCents(CALIFORNIA)).isEqualTo(2535L);
    }

    @Test
    void weeklyCashWagesPayHoursOverFortyAtTheOvertimeRate() {
        assertThat(OvertimeCalculator.weeklyCashWagesCents(FEDERAL, 0)).isZero();
        assertThat(OvertimeCalculator.weeklyCashWagesCents(FEDERAL, 4000)).isEqualTo(8520L);
        // 40h x 2.13 + 5h x 5.755 = 85.20 + 28.775 = 113.975 -> 113.98
        assertThat(OvertimeCalculator.weeklyCashWagesCents(FEDERAL, 4500)).isEqualTo(11_398L);
        // 40.25h: the quarter hour of overtime is 1.43875 -> total 86.63875 -> 86.64
        assertThat(OvertimeCalculator.weeklyCashWagesCents(FEDERAL, 4025)).isEqualTo(8664L);
    }

    @Test
    void wageInputsAreValidated() {
        assertThatThrownBy(() -> new TippedWage(725, 726)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TippedWage(725, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OvertimeCalculator.weeklyCashWagesCents(FEDERAL, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
