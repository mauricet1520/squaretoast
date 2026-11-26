package com.squaretoast.pos.order

/** One modifier group as the options sheet shows it. */
data class OptionGroup(val id: String, val name: String, val minSelect: Int, val maxSelect: Int, val options: List<OptionChoice>)

data class OptionChoice(val id: String, val name: String, val priceDeltaCents: Long, val available: Boolean)

/** What the guest picked, kept on the cart line for the screen and sent as IDs only. */
data class ChosenOption(val id: String, val name: String, val priceDeltaCents: Long)

/** Checks a selection against the item's groups. The server checks again at commit; this is for the staff member. */
object OptionRules {
    /** Plain-words problems, empty when the selection can be added. */
    fun problems(groups: List<OptionGroup>, chosenIds: List<String>): List<String> {
        val byId = groups.flatMap { g -> g.options.map { it.id to (g to it) } }.toMap()
        val problems = mutableListOf<String>()
        if (chosenIds.any { it !in byId }) problems += "That option isn't on this item"
        if (chosenIds.distinct().size != chosenIds.size) problems += "An option was picked twice"
        chosenIds.mapNotNull { byId[it]?.second }.distinct().filterNot { it.available }.forEach { problems += "${it.name} is sold out" }
        for (g in groups) {
            val picked = chosenIds.distinct().count { byId[it]?.first?.id == g.id }
            val name = g.name.lowercase()
            when {
                picked < g.minSelect -> problems += if (g.minSelect == 1) "Pick a $name" else "Pick at least ${g.minSelect} $name"
                picked > g.maxSelect -> problems += if (g.maxSelect == 1) "Pick only 1 $name" else "Pick up to ${g.maxSelect} $name"
            }
        }
        return problems
    }
}
