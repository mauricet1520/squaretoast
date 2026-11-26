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
        return addLine(item, seat, course, emptyList())
    }

    /** Adds an item with options; the unit price includes the option deltas. Refused if [OptionRules] finds problems. */
    fun addWithOptions(item: MenuChoice, seat: Int, course: Int, groups: List<OptionGroup>, chosenIds: List<String>): Cart {
        require(item.available) { "${item.name} is sold out" }
        val problems = OptionRules.problems(groups, chosenIds)
        require(problems.isEmpty()) { problems.joinToString("; ") }
        val byId = groups.flatMap { it.options }.associateBy { it.id }
        val chosen = chosenIds.sorted().map { id -> byId.getValue(id).let { ChosenOption(it.id, it.name, it.priceDeltaCents) } }
        return addLine(item, seat, course, chosen)
    }

    private fun addLine(item: MenuChoice, seat: Int, course: Int, options: List<ChosenOption>): Cart {
        require(seat > 0 && course > 0) { "seats and courses start at 1" }
        val unit = item.priceCents + options.sumOf { it.priceDeltaCents }
        val new = CartLine(item.menuItemId, item.name, seat, course, 1, unit, options)
        val existing = lines.find { it.sameAs(new) }
        return if (existing == null) copy(lines = lines + new) else setQuantity(existing, existing.quantity + 1)
    }

    /** Changes exactly this line: same item, seat, course and options. */
    fun setQuantity(line: CartLine, quantity: Int): Cart {
        require(quantity >= 0) { "quantity can't be negative" }
        return copy(lines = if (quantity == 0) lines.filterNot { it.sameAs(line) }
            else lines.map { if (it.sameAs(line)) it.copy(quantity = quantity) else it })
    }

    /** The plain line (no options) for this item, seat and course. */
    fun setQuantity(menuItemId: String, seat: Int, course: Int, quantity: Int): Cart =
        setQuantity(CartLine(menuItemId, "", seat, course, 0, 0), quantity)

    private fun CartLine.sameAs(o: CartLine) =
        menuItemId == o.menuItemId && seat == o.seat && course == o.course && options.map { it.id } == o.options.map { it.id }

    fun toPlaceOrder(tableLabel: String, locationId: UUID): PlaceOrder {
        val table = tableLabel.trim()
        require(lines.isNotEmpty()) { "the cart is empty" }
        require(table.isNotEmpty()) { "pick a table" }
        return PlaceOrder(
            mutationId = mutationId,
            orderId = orderId,
            locationId = locationId,
            tableLabel = table,
            lines = lines.map {
                NewLine(it.seat, it.course, it.menuItemId, it.name, it.quantity, it.unitPriceCents, it.options.map { o -> o.id })
            },
        )
    }
}
