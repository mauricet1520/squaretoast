package com.squaretoast.pos.order

/** One modifier group as the options sheet shows it. */
data class OptionGroup(val id: String, val name: String, val minSelect: Int, val maxSelect: Int, val options: List<OptionChoice>)

data class OptionChoice(val id: String, val name: String, val priceDeltaCents: Long, val available: Boolean)

/** What the guest picked, kept on the cart line for the screen and sent as IDs only. */
data class ChosenOption(val id: String, val name: String, val priceDeltaCents: Long)

/** Checks a selection against the item's groups. The server checks again at commit; this is for the staff member. */
object OptionRules {
    /** Plain-words problems, empty when the selection can be added. */
    fun problems(groups: List<OptionGroup>, chosenIds: List<String>): List<String> = TODO("Slice 9")
}
