package com.squaretoast.pos.order

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Slice 9: item options ("medium rare", "no onion") on the POS. */
class OptionsTest {

    private val cook = OptionGroup("g-cook", "Cook temp", minSelect = 1, maxSelect = 1, options = listOf(
        OptionChoice("o-rare", "Rare", 0, available = false),
        OptionChoice("o-med", "Medium", 0, available = true),
        OptionChoice("o-well", "Well done", 0, available = true),
    ))
    private val extras = OptionGroup("g-extras", "Extras", minSelect = 0, maxSelect = 2, options = listOf(
        OptionChoice("o-bacon", "Bacon", 250, available = true),
        OptionChoice("o-egg", "Egg", 150, available = true),
        OptionChoice("o-noonion", "No onion", 0, available = true),
    ))
    private val groups = listOf(cook, extras)
    private val steak = MenuChoice("steak", "Steak", 3000, available = true, needsOptions = true)
    private val location = UUID.fromString("00000000-0000-0000-0000-0000000000b1")

    // ---- OptionRules

    @Test
    fun aValidSelectionHasNoProblems() {
        assertEquals(emptyList<String>(), OptionRules.problems(groups, listOf("o-med")))
        assertEquals(emptyList<String>(), OptionRules.problems(groups, listOf("o-med", "o-bacon", "o-noonion")))
    }

    @Test
    fun aMissingRequiredChoiceIsNamedInPlainWords() {
        assertEquals(listOf("Pick a cook temp"), OptionRules.problems(groups, listOf("o-bacon")))
    }

    @Test
    fun tooManyChoicesInAGroupIsAProblem() {
        assertEquals(listOf("Pick up to 2 extras"), OptionRules.problems(groups, listOf("o-med", "o-bacon", "o-egg", "o-noonion")))
        assertEquals(listOf("Pick only 1 cook temp"), OptionRules.problems(groups, listOf("o-med", "o-well")))
    }

    @Test
    fun aSoldOutOptionIsAProblem() {
        assertEquals(listOf("Rare is sold out"), OptionRules.problems(groups, listOf("o-rare")))
    }

    @Test
    fun anOptionNotOnThisItemOrPickedTwiceIsAProblem() {
        assertTrue(OptionRules.problems(groups, listOf("o-med", "o-sprinkles")).isNotEmpty())
        assertTrue(OptionRules.problems(groups, listOf("o-med", "o-bacon", "o-bacon")).isNotEmpty())
    }

    // ---- Cart with options

    @Test
    fun theUnitPriceIncludesTheOptionsInWholeCents() {
        val cart = Cart().addWithOptions(steak, 1, 1, groups, listOf("o-med", "o-bacon", "o-egg"))
        val line = cart.lines.single()
        assertEquals(3000L + 250L + 150L, line.unitPriceCents)
        assertEquals(listOf("o-bacon", "o-egg", "o-med"), line.options.map { it.id }) // sorted, so the key is stable
        assertEquals(listOf("Bacon", "Egg", "Medium"), line.options.map { it.name })
        assertEquals(3400L, cart.subtotalCents)
    }

    @Test
    fun sameOptionsMergeAndDifferentOptionsAreSeparateLines() {
        val cart = Cart()
            .addWithOptions(steak, 1, 1, groups, listOf("o-med", "o-bacon"))
            .addWithOptions(steak, 1, 1, groups, listOf("o-bacon", "o-med")) // same choices, other order
            .addWithOptions(steak, 1, 1, groups, listOf("o-well"))
        assertEquals(2, cart.lines.size)
        assertEquals(2, cart.lines.single { it.options.any { o -> o.id == "o-bacon" } }.quantity)
    }

    @Test
    fun anInvalidSelectionIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { Cart().addWithOptions(steak, 1, 1, groups, listOf("o-bacon")) }
        assertThrows(IllegalArgumentException::class.java) { Cart().addWithOptions(steak, 1, 1, groups, listOf("o-rare")) }
        val soldOut = steak.copy(available = false)
        assertThrows(IllegalArgumentException::class.java) { Cart().addWithOptions(soldOut, 1, 1, groups, listOf("o-med")) }
    }

    @Test
    fun quantityChangesTouchOnlyThatExactLine() {
        val cart = Cart()
            .addWithOptions(steak, 1, 1, groups, listOf("o-med"))
            .addWithOptions(steak, 1, 1, groups, listOf("o-well"))
        val medium = cart.lines.single { it.options.single().id == "o-med" }

        val changed = cart.setQuantity(medium, 3).setQuantity(cart.lines.single { it.options.single().id == "o-well" }, 0)

        assertEquals(1, changed.lines.size)
        assertEquals(3, changed.lines.single().quantity)
        assertEquals("o-med", changed.lines.single().options.single().id)
    }

    @Test
    fun theOrderSendsOnlyOptionIds() {
        val cmd = Cart().addWithOptions(steak, 2, 1, groups, listOf("o-noonion", "o-med")).toPlaceOrder("T1", location)
        val line = cmd.lines.single()
        assertEquals(listOf("o-med", "o-noonion"), line.optionIds)
        assertEquals(3000L, line.unitPriceCents) // + 0 deltas; an estimate, the server reprices
        assertEquals(2, line.seat)
    }
}
