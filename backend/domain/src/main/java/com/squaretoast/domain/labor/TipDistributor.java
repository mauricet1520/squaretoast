package com.squaretoast.domain.labor;

import com.squaretoast.domain.money.Money;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Splits a tip pool by weight in whole cents. Each member gets floor(pool x weight / total); the leftover cents go
 * one each to the largest remainders, ties broken by lowest employee id, so the shares always sum to the pool.
 */
public final class TipDistributor {

    /**
     * Unsigned id order (same as Postgres and the canonical hex string). {@link UUID#compareTo} compares signed
     * longs, which would sort ids starting 8-f before 0-7.
     */
    private static final Comparator<UUID> UNSIGNED_ID = Comparator.comparing(UUID::toString);

    private TipDistributor() {}

    public static Map<UUID, Money> distribute(Money pool, List<TipShare> shares) {
        if (pool.isNegative()) {
            throw new IllegalArgumentException("tip pool cannot be negative: " + pool);
        }
        long totalWeight = 0;
        HashSet<UUID> seen = new HashSet<>();
        for (TipShare s : shares) {
            if (!seen.add(s.employeeId())) {
                throw new IllegalArgumentException("employee listed twice: " + s.employeeId());
            }
            totalWeight = Math.addExact(totalWeight, s.weight());
        }
        if (totalWeight == 0) {
            throw new IllegalArgumentException("tip pool has no hours or points to distribute by");
        }

        record Part(UUID employeeId, long base, long remainder) {}
        List<Part> parts = new ArrayList<>();
        long allocated = 0;
        for (TipShare s : shares) {
            long product = Math.multiplyExact(pool.cents(), s.weight());
            Part p = new Part(s.employeeId(), product / totalWeight, product % totalWeight);
            parts.add(p);
            allocated = Math.addExact(allocated, p.base());
        }
        long leftover = pool.cents() - allocated;

        List<Part> byRemainder = new ArrayList<>(parts);
        byRemainder.sort(Comparator.comparingLong(Part::remainder).reversed().thenComparing(Part::employeeId, UNSIGNED_ID));
        HashSet<UUID> extraCent = new HashSet<>();
        for (int i = 0; i < leftover; i++) {
            extraCent.add(byRemainder.get(i).employeeId());
        }

        parts.sort(Comparator.comparing(Part::employeeId, UNSIGNED_ID));
        Map<UUID, Money> out = new LinkedHashMap<>();
        for (Part p : parts) {
            out.put(p.employeeId(), Money.ofCents(p.base() + (extraCent.contains(p.employeeId()) ? 1 : 0), pool.currency()));
        }
        return Collections.unmodifiableMap(out);
    }
}
