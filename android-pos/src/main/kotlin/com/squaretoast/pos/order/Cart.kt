package com.squaretoast.pos.order

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
)

/**
 * An order being rung up. Immutable, so Compose can hold it as state. Its IDs are made once, with the cart, so a
 * double tap or a retry of Send can never place two orders. The subtotal is an estimate: the server prices the check.
 */
data class Cart(
    val lines: List<CartLine> = emptyList(),
    val mutationId: UUID = UUID.randomUUID(),
    val orderId: UUID = UUID.randomUUID(),
) {
    val subtotalCents: Long get() = TODO("Slice 8")

    fun canAdd(item: MenuChoice): Boolean = TODO("Slice 8")

    fun add(item: MenuChoice, seat: Int, course: Int): Cart = TODO("Slice 8")

    fun setQuantity(menuItemId: String, seat: Int, course: Int, quantity: Int): Cart = TODO("Slice 8")

    fun toPlaceOrder(tableLabel: String, locationId: UUID): PlaceOrder = TODO("Slice 8")
}
