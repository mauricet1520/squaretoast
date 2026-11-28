package com.squaretoast.pos.payment

import com.squaretoast.pos.data.OrderEntity
import com.squaretoast.pos.data.OrderLineEntity
import com.squaretoast.pos.sync.ItemTax
import com.squaretoast.pos.sync.TaxProfile
import com.squaretoast.pos.sync.TaxRuleDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Base64

/** Slice 14 (S14-1, S14-4, S14-5): what the POS signs for an offline QR pay code. */
class ClaimDraftsTest {

    private val today = LocalDate.of(2026, 11, 27)

    private val profile = TaxProfile(
        locationId = "loc-1", stateCode = "CA", currency = "USD", timezone = "America/Los_Angeles",
        ca8080Applies = false, caSeparateAccountingElected = false, mandatoryServiceChargeTaxable = true,
        validFrom = "2026-11-27", validUntil = "2026-12-04",
        rules = listOf(
            TaxRuleDto("CA-SF", "FOOD", null, 86_250, "2026-01-01", null),
            TaxRuleDto("CA-SF", "ALCOHOL", null, 86_250, "2026-01-01", null),
        ),
        items = listOf(ItemTax("burger", "FOOD", "HOT"), ItemTax("salad", "FOOD", "COLD"), ItemTax("beer", "ALCOHOL", "COLD")),
    )

    private val order = OrderEntity("ord-1", "loc-1", "T4", 0, checkId = "chk-1")

    private fun line(id: String, item: String, qty: Int, unit: Long) = OrderLineEntity(id, "ord-1", "c-1", 1, item, item, qty, unit)

    private val lines = listOf(line("l-2", "salad", 1, 1100), line("l-1", "burger", 2, 1500))

    @Test
    fun aDineInCheckIsTaxedOnThePhoneLikeOnTheServer() {
        val d = ClaimDrafts.draft(order, lines, profile, "dev-1", today)!!

        assertEquals("chk-1", d.checkId) // the check the POS will sync, so the paid claim reconciles
        assertEquals("loc-1", d.locationId)
        assertEquals("dev-1", d.deviceId)
        assertEquals(4100L, d.subtotalCents)
        assertEquals(354L, d.taxCents) // 4100 x 8.625% = 353.625, half-up once; dine-in cold food is taxed
        assertEquals(0L, d.serviceChargeCents)
        assertEquals(4454L, d.totalCents)
        assertEquals("USD", d.currency)
    }

    @Test
    fun theLinesDigestIsSha256OfTheSortedLines() {
        val d = ClaimDrafts.draft(order, lines, profile, "dev-1", today)!!
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest("l-1|2|1500\nl-2|1|1100".toByteArray()))
        assertEquals(expected, d.linesDigest)
        assertEquals(d.linesDigest, ClaimDrafts.draft(order, lines.reversed(), profile, "dev-1", today)!!.linesDigest)
        assertNotEquals(d.linesDigest,
            ClaimDrafts.draft(order, listOf(line("l-2", "salad", 1, 1100), line("l-1", "burger", 3, 1500)), profile, "dev-1", today)!!.linesDigest)
    }

    @Test
    fun anythingTheProfileCannotPriceFailsClosed() {
        val noRule = profile.copy(rules = profile.rules.filter { it.taxCategory == "FOOD" })
        assertNull("no rule for beer", ClaimDrafts.draft(order, lines + line("l-3", "beer", 1, 700), noRule, "dev-1", today))
        assertNull("item not in the profile", ClaimDrafts.draft(order, lines + line("l-3", "soda", 1, 300), profile, "dev-1", today))
        assertNull("order not synced with a check id", ClaimDrafts.draft(order.copy(checkId = null), lines, profile, "dev-1", today))
        assertNull("another location's profile", ClaimDrafts.draft(order, lines, profile.copy(locationId = "loc-2"), "dev-1", today))
        assertNull("an empty order", ClaimDrafts.draft(order, emptyList(), profile, "dev-1", today))
        assertNull("an unknown temp", ClaimDrafts.draft(order, lines,
            profile.copy(items = profile.items.map { it.copy(temp = "WARM") }), "dev-1", today))
        assertNull("another currency", ClaimDrafts.draft(order, lines, profile.copy(currency = "usd"), "dev-1", today))
    }

    @Test
    fun aRuleThatEndsBeforeTodayIsNotUsed() {
        val ended = profile.copy(rules = listOf(TaxRuleDto("CA-SF", "FOOD", null, 86_250, "2026-01-01", "2026-11-27")))
        assertNull(ClaimDrafts.draft(order, lines.take(1), ended, "dev-1", today))
        val changed = ended.copy(rules = ended.rules + TaxRuleDto("CA-SF", "FOOD", null, 90_000, "2026-11-27", null))
        assertEquals(99L, ClaimDrafts.draft(order, lines.take(1), changed, "dev-1", today)!!.taxCents) // 1100 x 9%
    }

    @Test
    fun thePayCodeIsOfferedOnlyOfflineEnrolledAndWithAProfileForToday() {
        assertTrue(PayCodeGate.allowed(online = false, enrolled = true, profile = profile, today = today))
        assertTrue(PayCodeGate.allowed(online = false, enrolled = true, profile = profile, today = today.plusDays(6)))
        assertFalse("online: take the card normally", PayCodeGate.allowed(online = true, enrolled = true, profile = profile, today = today))
        assertFalse("not enrolled", PayCodeGate.allowed(online = false, enrolled = false, profile = profile, today = today))
        assertFalse("no profile", PayCodeGate.allowed(online = false, enrolled = true, profile = null, today = today))
        assertFalse("profile too old", PayCodeGate.allowed(online = false, enrolled = true, profile = profile, today = today.plusDays(7)))
        assertFalse("clock before the profile", PayCodeGate.allowed(online = false, enrolled = true, profile = profile, today = today.minusDays(1)))
        assertFalse("bad dates", PayCodeGate.allowed(online = false, enrolled = true, profile = profile.copy(validUntil = "soon"), today = today))
    }

    @Test
    fun theSaleDateIsTheLocationsLocalDateWhenTheOrderWasRungUp() {
        // 23:59 in Los Angeles on Nov 26 is already Nov 27 in UTC: the claim must use Nov 26's rules (plan 19.6).
        val at = java.time.ZonedDateTime.of(2026, 11, 26, 23, 59, 0, 0, java.time.ZoneId.of("America/Los_Angeles"))
        assertEquals(LocalDate.of(2026, 11, 26), ClaimDrafts.saleDate(at.toInstant().toEpochMilli(), "America/Los_Angeles"))
        assertNull(ClaimDrafts.saleDate(0, "Not/AZone"))
    }

    @Test
    fun aPayCodeIsRefusedForAnOrderTheServerWouldTaxByAnotherDay() {
        val now = 1_000_000_000_000L
        val hour = 3_600_000L
        assertTrue(ClaimDrafts.withinServerWindow(now - 70 * hour, now))
        assertFalse("older than the server's 72 h clamp", ClaimDrafts.withinServerWindow(now - 72 * hour, now))
        assertFalse("rung up 'in the future': the clock moved", ClaimDrafts.withinServerWindow(now + 60_000, now))
    }
}
