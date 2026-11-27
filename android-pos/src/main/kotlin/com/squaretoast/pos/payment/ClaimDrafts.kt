package com.squaretoast.pos.payment

import com.squaretoast.pos.data.OrderEntity
import com.squaretoast.pos.data.OrderLineEntity
import com.squaretoast.pos.domain.FoodTemp
import com.squaretoast.pos.domain.FulfillmentChannel
import com.squaretoast.pos.domain.TaxContext
import com.squaretoast.pos.domain.TaxEngine
import com.squaretoast.pos.domain.TaxRule
import com.squaretoast.pos.domain.TaxableLine
import com.squaretoast.pos.sync.TaxProfile
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Base64

/**
 * Slice 14: the claim the POS signs for an offline QR pay code. The total is computed here exactly as the server
 * will bill the synced check (S14-1, S14-4), so the paid claim reconciles. Anything it cannot price returns null:
 * no pay code is better than a wrong amount.
 */
object ClaimDrafts {

    private val CURRENCY = Regex("[A-Z]{3}")

    fun draft(order: OrderEntity, lines: List<OrderLineEntity>, profile: TaxProfile, deviceId: String, today: LocalDate): ClaimDraft? {
        val checkId = order.checkId ?: return null
        if (lines.isEmpty() || profile.locationId != order.locationId || !CURRENCY.matches(profile.currency)) return null
        return try {
            val items = profile.items.associateBy { it.menuItemId }
            val taxable = lines.map { l ->
                val item = items[l.menuItemId] ?: return null
                TaxableLine(item.taxCategory, FoodTemp.valueOf(item.temp), Math.multiplyExact(l.unitPriceCents, l.quantity.toLong()))
            }
            val rules = profile.rules.map { r ->
                TaxRule(profile.stateCode, r.jurisdictionCode, r.taxCategory, r.channel?.let(FulfillmentChannel::valueOf),
                    r.ratePpm, LocalDate.parse(r.effectiveFrom), r.effectiveTo?.let(LocalDate::parse))
            }
            // POS orders are dine-in with no service charges, as the server stores them (S14-4).
            val ctx = TaxContext(profile.stateCode, today, FulfillmentChannel.DINE_IN, profile.ca8080Applies,
                profile.caSeparateAccountingElected, profile.mandatoryServiceChargeTaxable)
            val subtotal = taxable.fold(0L) { s, l -> Math.addExact(s, l.cents) }
            val tax = TaxEngine.compute(ctx, rules, taxable).taxCents
            ClaimDraft(checkId, order.locationId, deviceId, linesDigest(lines), subtotal, tax, 0, Math.addExact(subtotal, tax),
                profile.currency)
        } catch (_: IllegalArgumentException) { // unknown enum, bad date or rule, negative amount
            null
        } catch (_: IllegalStateException) { // no effective rule, or two for one jurisdiction
            null
        } catch (_: ArithmeticException) {
            null
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** Plan 19.6: the location-local date the order was rung up, the date the server taxes it by. Null for a bad zone. */
    fun saleDate(createdAtMillis: Long, timezone: String): LocalDate? = try {
        java.time.Instant.ofEpochMilli(createdAtMillis).atZone(java.time.ZoneId.of(timezone)).toLocalDate()
    } catch (_: java.time.DateTimeException) {
        null
    }

    /**
     * The server taxes an order by its sale time only within the last 72 h (plan 19.6); outside that it would use
     * another date than the phone. One hour of margin for sync time; a sale "in the future" means the clock moved.
     */
    fun withinServerWindow(createdAtMillis: Long, nowMillis: Long): Boolean =
        createdAtMillis <= nowMillis && nowMillis - createdAtMillis < 71 * 3_600_000L

    /** base64url(SHA-256) of "id|qty|unitCents" per line, sorted by line id, joined by newlines (S14-5). */
    fun linesDigest(lines: List<OrderLineEntity>): String {
        val text = lines.sortedBy { it.id }.joinToString("\n") { "${it.id}|${it.quantity}|${it.unitPriceCents}" }
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
    }
}

/** S14-6: a pay code only when Tap to Pay cannot run (offline), the device can sign, and the tax rules cover today. */
object PayCodeGate {
    fun allowed(online: Boolean, enrolled: Boolean, profile: TaxProfile?, today: LocalDate): Boolean {
        if (online || !enrolled || profile == null) return false
        return try {
            !today.isBefore(LocalDate.parse(profile.validFrom)) && today.isBefore(LocalDate.parse(profile.validUntil))
        } catch (_: DateTimeParseException) {
            false
        }
    }
}
