package com.squaretoast.pos.domain

import java.time.LocalDate

/*
 * Slice 14 (S14-1): a port of the backend's domain/tax/TaxEngine, so the POS can total a check while offline.
 * Both run shared-test-vectors/tax-vectors.json; change one only together with the other and the vectors.
 */

/** How the order leaves the restaurant; mirrors `orders.fulfillment_channel`. */
enum class FulfillmentChannel { DINE_IN, TAKEOUT, DELIVERY }

/** Serving temperature of an item; mirrors `menu_item.temp`. Drives the CA cold-food-to-go rule. */
enum class FoodTemp { HOT, COLD, NA }

object TaxCategory {
    const val FOOD = "FOOD"
    const val SERVICE_CHARGE = "SERVICE_CHARGE"
}

data class TaxContext(
    val stateCode: String,
    val date: LocalDate,
    val channel: FulfillmentChannel,
    val ca8080Applies: Boolean,
    val caSeparateAccountingElected: Boolean,
    val mandatoryServiceChargeTaxable: Boolean,
)

/** One jurisdiction's rate for a category. A null channel means any channel. Effective on [effectiveFrom, effectiveTo). */
data class TaxRule(
    val stateCode: String,
    val jurisdictionCode: String,
    val taxCategory: String,
    val channel: FulfillmentChannel?,
    val ratePpm: Int,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
) {
    init {
        require(ratePpm in 0..PPM) { "rate must be 0..1 000 000 ppm" }
        require(effectiveTo == null || effectiveTo.isAfter(effectiveFrom)) { "effectiveTo must be after effectiveFrom" }
    }

    fun effectiveOn(date: LocalDate) = !date.isBefore(effectiveFrom) && (effectiveTo == null || date.isBefore(effectiveTo))

    companion object { const val PPM = 1_000_000L }
}

/** A line's share of one check, in cents (price incl. options x qty). */
data class TaxableLine(val taxCategory: String, val temp: FoodTemp, val cents: Long) {
    init { require(cents >= 0) { "line amount cannot be negative" } }
}

/** A charge that is not a voluntary tip. Voluntary tips are never taxed and never passed here. */
data class ServiceCharge(val kind: Kind, val cents: Long, val deliveryFeeTaxable: Boolean = false) {
    enum class Kind { MANDATORY, AUTO_GRATUITY, DELIVERY_FEE }

    init {
        require(cents >= 0) { "service charge cannot be negative" }
        require(!deliveryFeeTaxable || kind == Kind.DELIVERY_FEE) { "only a delivery fee sets its own taxability" }
    }
}

data class TaxResult(val taxableCents: Long, val exemptCents: Long, val taxCents: Long)

class MissingTaxRuleException(state: String, category: String, channel: FulfillmentChannel) :
    IllegalStateException("no effective $category tax rule in $state for $channel")

object TaxEngine {

    private const val CALIFORNIA = "CA"

    /** Rate = sum over jurisdictions; a channel rule replaces that jurisdiction's any-channel rule; half-up once per check. */
    fun compute(ctx: TaxContext, rules: List<TaxRule>, lines: List<TaxableLine>, charges: List<ServiceCharge> = emptyList()): TaxResult {
        var taxable = 0L
        var exempt = 0L
        var taxMicroCents = 0L // cents x ppm
        for (line in lines) {
            if (isCaliforniaColdFoodToGoExempt(ctx, line)) {
                exempt = Math.addExact(exempt, line.cents)
            } else {
                taxable = Math.addExact(taxable, line.cents)
                taxMicroCents = Math.addExact(taxMicroCents, Math.multiplyExact(line.cents, rate(ctx, rules, line.taxCategory).toLong()))
            }
        }
        for (charge in charges) {
            val isTaxable = when (charge.kind) {
                ServiceCharge.Kind.MANDATORY, ServiceCharge.Kind.AUTO_GRATUITY -> ctx.mandatoryServiceChargeTaxable
                ServiceCharge.Kind.DELIVERY_FEE -> charge.deliveryFeeTaxable
            }
            if (isTaxable) {
                taxable = Math.addExact(taxable, charge.cents)
                taxMicroCents = Math.addExact(taxMicroCents,
                    Math.multiplyExact(charge.cents, rate(ctx, rules, TaxCategory.SERVICE_CHARGE).toLong()))
            } else {
                exempt = Math.addExact(exempt, charge.cents)
            }
        }
        val tax = Math.addExact(taxMicroCents, TaxRule.PPM / 2) / TaxRule.PPM // half-up; amounts are non-negative
        return TaxResult(taxable, exempt, tax)
    }

    private fun isCaliforniaColdFoodToGoExempt(ctx: TaxContext, line: TaxableLine) =
        ctx.stateCode == CALIFORNIA && line.temp == FoodTemp.COLD && line.taxCategory == TaxCategory.FOOD &&
            ctx.channel != FulfillmentChannel.DINE_IN && !(ctx.ca8080Applies && !ctx.caSeparateAccountingElected)

    private fun rate(ctx: TaxContext, rules: List<TaxRule>, category: String): Int {
        val specific = HashMap<String, TaxRule>()
        val anyChannel = HashMap<String, TaxRule>()
        for (r in rules) {
            val ch = r.channel
            if (r.stateCode != ctx.stateCode || r.taxCategory != category || !r.effectiveOn(ctx.date) ||
                (ch != null && ch != ctx.channel)) continue
            val slot = if (ch == null) anyChannel else specific
            check(slot.putIfAbsent(r.jurisdictionCode, r) == null) {
                "two effective $category rules for jurisdiction ${r.jurisdictionCode}"
            }
        }
        if (specific.isEmpty() && anyChannel.isEmpty()) throw MissingTaxRuleException(ctx.stateCode, category, ctx.channel)
        val combined = HashMap(anyChannel).apply { putAll(specific) }
        return combined.values.fold(0) { ppm, r -> Math.addExact(ppm, r.ratePpm) }
    }
}
