package com.squaretoast.pos.domain

import java.math.BigInteger

/**
 * Splits integer cents by weights: floor each exact share, then hand out the leftover cents one each by largest
 * fractional remainder (ties to the lower index). Shares always sum to the amount. BigInteger avoids overflow
 * in amount × weight.
 */
object SplitAllocator {

    fun evenly(amountCents: Long, ways: Int): List<Long> {
        require(ways > 0) { "ways must be positive" }
        return allocate(amountCents, List(ways) { 1L })
    }

    fun allocate(amountCents: Long, weights: List<Long>): List<Long> {
        require(amountCents >= 0) { "amount must not be negative" }
        require(weights.isNotEmpty()) { "at least one weight" }
        require(weights.all { it >= 0 }) { "weights must not be negative" }
        val total = weights.fold(BigInteger.ZERO) { acc, w -> acc + BigInteger.valueOf(w) }
        require(total.signum() > 0) { "weights must not all be zero" }

        val amount = BigInteger.valueOf(amountCents)
        val parts = weights.map { (amount * BigInteger.valueOf(it)).divideAndRemainder(total) }
        val shares = parts.map { it[0].toLong() }.toMutableList()
        var leftover = amountCents - shares.sum()
        val byRemainder = parts.indices.sortedWith(compareByDescending<Int> { parts[it][1] }.thenBy { it })
        for (i in byRemainder) {
            if (leftover == 0L) break
            shares[i] += 1
            leftover--
        }
        return shares
    }
}
