package com.squaretoast.domain.menu;

import static com.squaretoast.domain.menu.MenuFixtures.group;
import static com.squaretoast.domain.menu.MenuFixtures.item;
import static com.squaretoast.domain.menu.MenuFixtures.option;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squaretoast.domain.menu.InvalidModifierSelectionException.Reason;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Linked modifier groups: min/max limits are enforced per item at order time. */
class ModifierSelectionTest {

    private final ModifierOption cheddar = option("Cheddar", 100);
    private final ModifierOption swiss = option("Swiss", 100);
    private final ModifierOption bacon = option("Bacon", 200);
    private final ModifierGroup cheese = group("Cheese", 1, 1, cheddar, swiss);
    private final ModifierGroup addOns = group("Add-ons", 0, 1, bacon);
    private final MenuItem burger = item("Burger", 1200, List.of(cheese, addOns));
    private final MenuItem melt = item("Melt", 1100, List.of(cheese));

    @Test
    void selectionWithinEveryGroupsLimitsIsValid() {
        assertThatCode(() -> ModifierSelection.validate(burger, List.of(cheddar.id()))).doesNotThrowAnyException();
        assertThatCode(() -> ModifierSelection.validate(burger, List.of(swiss.id(), bacon.id()))).doesNotThrowAnyException();
    }

    @Test
    void linkedGroupIsValidatedIndependentlyForEachItem() {
        assertThatCode(() -> ModifierSelection.validate(melt, List.of(swiss.id()))).doesNotThrowAnyException();
        assertRejected(melt, List.of(), Reason.TOO_FEW);
    }

    @Test
    void missingRequiredChoiceIsRejected() {
        assertRejected(burger, List.of(bacon.id()), Reason.TOO_FEW);
    }

    @Test
    void exceedingAGroupsMaximumIsRejected() {
        assertRejected(burger, List.of(cheddar.id(), swiss.id()), Reason.TOO_MANY);
    }

    @Test
    void optionFromAGroupNotLinkedToTheItemIsRejected() {
        assertRejected(melt, List.of(swiss.id(), bacon.id()), Reason.UNKNOWN_OPTION);
        assertRejected(burger, List.of(cheddar.id(), UUID.randomUUID()), Reason.UNKNOWN_OPTION);
    }

    @Test
    void choosingTheSameOptionTwiceIsRejected() {
        MenuItem pizza = item("Pizza", 1400, List.of(group("Toppings", 0, 3, bacon)));
        assertRejected(pizza, List.of(bacon.id(), bacon.id()), Reason.DUPLICATE_OPTION);
    }

    @Test
    void inactiveOptionIsRejected() {
        ModifierOption retired = new ModifierOption(UUID.randomUUID(), "Retired", 0, false, List.of());
        MenuItem toast = item("Toast", 500, List.of(group("Spread", 0, 1, retired)));
        assertRejected(toast, List.of(retired.id()), Reason.INACTIVE_OPTION);
    }

    private static void assertRejected(MenuItem item, List<UUID> selected, Reason reason) {
        assertThatThrownBy(() -> ModifierSelection.validate(item, selected))
                .isInstanceOfSatisfying(InvalidModifierSelectionException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.reason()).isEqualTo(reason));
    }
}
