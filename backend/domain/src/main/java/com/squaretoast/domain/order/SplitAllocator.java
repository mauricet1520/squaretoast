package com.squaretoast.domain.order;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Splits integer cents by weights: floor each exact share, then give the leftover cents one each by largest
 * fractional remainder (ties to the lower index). Shares always sum to the amount. Vectors: shared-test-vectors.
 */
public final class SplitAllocator {

    private SplitAllocator() {}

    public static List<Long> evenly(long amountCents, int ways) {
        if (ways <= 0) {
            throw new IllegalArgumentException("ways must be positive");
        }
        return allocate(amountCents, java.util.Collections.nCopies(ways, 1L));
    }

    public static List<Long> allocate(long amountCents, List<Long> weights) {
        if (amountCents < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (weights.isEmpty()) {
            throw new IllegalArgumentException("at least one weight");
        }
        BigInteger total = BigInteger.ZERO;
        for (long w : weights) {
            if (w < 0) {
                throw new IllegalArgumentException("weights must not be negative");
            }
            total = total.add(BigInteger.valueOf(w));
        }
        if (total.signum() == 0) {
            throw new IllegalArgumentException("weights must not all be zero");
        }
        BigInteger amount = BigInteger.valueOf(amountCents);
        List<Long> shares = new ArrayList<>(weights.size());
        List<BigInteger> remainders = new ArrayList<>(weights.size());
        long allocated = 0;
        for (long w : weights) {
            BigInteger[] qr = amount.multiply(BigInteger.valueOf(w)).divideAndRemainder(total);
            shares.add(qr[0].longValueExact());
            remainders.add(qr[1]);
            allocated += qr[0].longValueExact();
        }
        long leftover = amountCents - allocated;
        List<Integer> order = IntStream.range(0, weights.size()).boxed()
                .sorted(Comparator.<Integer, BigInteger>comparing(remainders::get).reversed().thenComparing(i -> i))
                .toList();
        for (int i = 0; i < leftover; i++) {
            int idx = order.get(i);
            shares.set(idx, shares.get(idx) + 1);
        }
        return List.copyOf(shares);
    }
}
