package com.squaretoast.pos.order

import com.squaretoast.pos.data.NewLine
import com.squaretoast.pos.data.PlaceOrder
import java.util.UUID

/** A menu item as the order screen sees it. */
data class MenuChoice(
    val menuItemId: String,
    val name: String,
    val priceCents: Long,
    val available: Boolean,
    val needsOptions: Boolean,
)

data class CartLine(
    val menuItemId: String,
    val name: String,
    val seat: Int,
    val course: Int,
    val quantity: Int,
    val unitPriceCents: Long,
    /** Sorted by ID, so the same choices always make the same line. */
    val options: List<ChosenOption> = emptyList(),
) {
    val totalCents: Long get() = unitPriceCents * quantity
}

/**
 * An order being rung up. Immutable, so Compose can hold it as state. Its IDs are made once, with the cart, so a
 * double tap or a retry of Send can never place two orders. The subtotal is an estimate: the server prices the check.
 */
data class Cart(
    val lines: List<CartLine> = emptyList(),
    val mutationId: UUID = UUID.randomUUID(),
    val orderId: UUID = UUID.randomUUID(),
) {
    val subtotalCents: Long get() = lines.sumOf { it.totalCents }

    fun canAdd(item: MenuChoice): Boolean = item.available && !item.needsOptions

    fun add(item: MenuChoice, seat: Int, course: Int): Cart {
        require(canAdd(item)) { "${item.name} can't be added" }
        require(seat > 0 && course > 0) { "seats and courses start at 1" }
        val existing = lines.find { it.menuItemId == item.menuItemId && it.seat == seat && it.course == course }
        return if (existing == null) {
            copy(lines = lines + CartLine(item.menuItemId, item.name, seat, course, 1, item.priceCents))
        } else {
            setQuantity(item.menuItemId, seat, course, existing.quantity + 1)
        }
    }

    /** Adds an item with options; the unit price includes the option deltas. Refused if [OptionRules] finds problems. */
    fun addWithOptions(item: MenuChoice, seat: Int, course: Int, groups: List<OptionGroup>, chosenIds: List<String>): Cart =
        TODO("Slice 9")

    fun setQuantity(line: CartLine, quantity: Int): Cart = TODO("Slice 9")

    fun setQuantity(menuItemId: String, seat: Int, course: Int, quantity: Int): Cart {
        require(quantity >= 0) { "quantity can't be negative" }
        val match = { l: CartLine -> l.menuItemId == menuItemId && l.seat == seat && l.course == course }
        return copy(lines = if (quantity == 0) lines.filterNot(match) else lines.map { if (match(it)) it.copy(quantity = quantity) else it })
    }

    fun toPlaceOrder(tableLabel: String, locationId: UUID): PlaceOrder {
        val table = tableLabel.trim()
        require(lines.isNotEmpty()) { "the cart is empty" }
        require(table.isNotEmpty()) { "pick a table" }
        return PlaceOrder(
            mutationId = mutationId,
            orderId = orderId,
            locationId = locationId,
            tableLabel = table,
            lines = lines.map { NewLine(it.seat, it.course, it.menuItemId, it.name, it.quantity, it.unitPriceCents) },
        )
    }
}
