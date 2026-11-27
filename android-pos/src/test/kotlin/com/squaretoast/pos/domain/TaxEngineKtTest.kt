package com.squaretoast.pos.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Slice 14 (S14-2): the server's tax vectors, so the claim the POS signs offline equals the bill the server computes. */
class TaxEngineKtTest {

    private val cases = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResource("tax-vectors.json")).readText(),
    ).jsonObject["cases"]!!.jsonArray

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.textOrNull(key: String) = getValue(key).takeUnless { it is JsonNull }?.jsonPrimitive?.content

    @Test
    fun everyVectorMatchesTheServer() {
        assertTrue(cases.size >= 15)
        for (element in cases) {
            val c = element.jsonObject
            val name = c.text("name")
            val x = c.getValue("context").jsonObject
            val ctx = TaxContext(
                stateCode = x.text("state"),
                date = LocalDate.parse(x.text("date")),
                channel = FulfillmentChannel.valueOf(x.text("channel")),
                ca8080Applies = x.getValue("ca8080").jsonPrimitive.boolean,
                caSeparateAccountingElected = x.getValue("caSeparate").jsonPrimitive.boolean,
                mandatoryServiceChargeTaxable = x.getValue("mandatoryTaxable").jsonPrimitive.boolean,
            )
            val rules = c.getValue("rules").jsonArray.map { it.jsonObject }.map { r ->
                TaxRule(r.text("state"), r.text("jurisdiction"), r.text("category"),
                    r.textOrNull("channel")?.let(FulfillmentChannel::valueOf), r.getValue("ratePpm").jsonPrimitive.int,
                    LocalDate.parse(r.text("from")), r.textOrNull("to")?.let(LocalDate::parse))
            }
            val lines = c.getValue("lines").jsonArray.map { it.jsonObject }.map { l ->
                TaxableLine(l.text("category"), FoodTemp.valueOf(l.text("temp")), l.getValue("cents").jsonPrimitive.long)
            }
            val charges = c.getValue("charges").jsonArray.map { it.jsonObject }.map { s ->
                ServiceCharge(ServiceCharge.Kind.valueOf(s.text("kind")), s.getValue("cents").jsonPrimitive.long,
                    s.getValue("deliveryFeeTaxable").jsonPrimitive.boolean)
            }
            val expected = c.getValue("expected")
            if (expected is JsonNull) {
                assertThrows(name, MissingTaxRuleException::class.java) { TaxEngine.compute(ctx, rules, lines, charges) }
            } else {
                val e = expected.jsonObject
                val r = TaxEngine.compute(ctx, rules, lines, charges)
                assertEquals("$name taxable", e.getValue("taxable").jsonPrimitive.long, r.taxableCents)
                assertEquals("$name exempt", e.getValue("exempt").jsonPrimitive.long, r.exemptCents)
                assertEquals("$name tax", e.getValue("tax").jsonPrimitive.long, r.taxCents)
            }
        }
    }

    @Test
    fun negativeAmountsAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { TaxableLine("FOOD", FoodTemp.HOT, -1) }
        assertThrows(IllegalArgumentException::class.java) { ServiceCharge(ServiceCharge.Kind.MANDATORY, -1) }
        assertThrows(IllegalArgumentException::class.java) { ServiceCharge(ServiceCharge.Kind.MANDATORY, 100, deliveryFeeTaxable = true) }
    }

    @Test
    fun twoEffectiveRulesForOneJurisdictionAreAmbiguous() {
        val start = LocalDate.of(2026, 1, 1)
        val ctx = TaxContext("TX", LocalDate.of(2026, 11, 17), FulfillmentChannel.DINE_IN, false, false, false)
        val rules = listOf(TaxRule("TX", "TX-STATE", "FOOD", null, 62_500, start, null),
            TaxRule("TX", "TX-STATE", "FOOD", null, 50_000, start, null))
        assertThrows(IllegalStateException::class.java) {
            TaxEngine.compute(ctx, rules, listOf(TaxableLine("FOOD", FoodTemp.NA, 1000)))
        }
    }
}
