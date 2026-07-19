package org.civiceconomy.production;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Applies exact, explainable facility and industry soft caps to verified production value. */
public final class ProductionMarginalReturnCalculator {
    private static final BigInteger BASIS_POINTS = BigInteger.valueOf(10_000L);

    private final ProductionMarginalReturnPolicy policy;

    public ProductionMarginalReturnCalculator(ProductionMarginalReturnPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("Production marginal-return policy is required");
        }
        this.policy = policy;
    }

    public ProductionMarginalReturnAssessment assess(
            List<ProductionMarginalReturnContribution> contributions) {
        if (contributions == null || contributions.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Production marginal-return contributions are required");
        }
        Map<UUID, ProductionMarginalReturnContribution> unique = new LinkedHashMap<>();
        for (ProductionMarginalReturnContribution contribution : contributions) {
            ProductionMarginalReturnContribution previous =
                    unique.putIfAbsent(contribution.observationId(), contribution);
            if (previous != null && !previous.equals(contribution)) {
                throw new IllegalStateException(
                        "Production marginal-return replay changed observation "
                                + contribution.observationId());
            }
        }

        Map<BucketKey, Long> rawByBucket = aggregate(
                unique.values(), contribution -> new BucketKey(
                        contribution.facilityId(), contribution.industryId()));
        Map<UUID, Long> rawByFacility = aggregateBuckets(
                rawByBucket, BucketKey::facilityId);
        Map<UUID, ProductionMarginalReturnBandAssessment> facilities = new LinkedHashMap<>();
        Map<BucketKey, Long> afterFacilityByBucket = new LinkedHashMap<>();
        for (Map.Entry<UUID, Long> entry : rawByFacility.entrySet()) {
            long adjusted = softCap(
                    entry.getValue(),
                    policy.facilitySoftCapMinorUnits(),
                    policy.facilityExcessWeightBasisPoints());
            facilities.put(entry.getKey(),
                    new ProductionMarginalReturnBandAssessment(entry.getValue(), adjusted));
            Map<BucketKey, Long> facilityBuckets = filter(
                    rawByBucket, key -> key.facilityId().equals(entry.getKey()));
            afterFacilityByBucket.putAll(allocate(
                    facilityBuckets,
                    adjusted,
                    Comparator.comparing(key -> key.industryId().value())));
        }

        Map<ProductionIndustryId, Long> afterFacilityByIndustry = aggregateBuckets(
                afterFacilityByBucket, BucketKey::industryId);
        Map<ProductionIndustryId, ProductionMarginalReturnBandAssessment> industries =
                new LinkedHashMap<>();
        Map<BucketKey, Long> finalByBucket = new LinkedHashMap<>();
        for (Map.Entry<ProductionIndustryId, Long> entry : afterFacilityByIndustry.entrySet()) {
            long adjusted = softCap(
                    entry.getValue(),
                    policy.industrySoftCapMinorUnits(),
                    policy.industryExcessWeightBasisPoints());
            industries.put(entry.getKey(),
                    new ProductionMarginalReturnBandAssessment(entry.getValue(), adjusted));
            Map<BucketKey, Long> industryBuckets = filter(
                    afterFacilityByBucket, key -> key.industryId().equals(entry.getKey()));
            finalByBucket.putAll(allocate(
                    industryBuckets,
                    adjusted,
                    Comparator.comparing(key -> key.facilityId().toString())));
        }

        long raw = sum(rawByBucket.values());
        long afterFacility = sum(afterFacilityByBucket.values());
        long finalValue = sum(finalByBucket.values());
        return new ProductionMarginalReturnAssessment(
                raw,
                afterFacility,
                finalValue,
                raw - afterFacility,
                afterFacility - finalValue,
                facilities,
                industries);
    }

    private static <K> Map<K, Long> aggregate(
            Iterable<ProductionMarginalReturnContribution> contributions,
            Function<ProductionMarginalReturnContribution, K> keySource) {
        Map<K, Long> totals = new LinkedHashMap<>();
        for (ProductionMarginalReturnContribution contribution : contributions) {
            totals.merge(
                    keySource.apply(contribution),
                    contribution.valueMinorUnits(),
                    ProductionMarginalReturnCalculator::addExact);
        }
        return totals;
    }

    private static <K> Map<K, Long> aggregateBuckets(
            Map<BucketKey, Long> buckets,
            Function<BucketKey, K> keySource) {
        Map<K, Long> totals = new LinkedHashMap<>();
        buckets.forEach((key, value) -> totals.merge(
                keySource.apply(key), value, ProductionMarginalReturnCalculator::addExact));
        return totals;
    }

    private static Map<BucketKey, Long> filter(
            Map<BucketKey, Long> source,
            java.util.function.Predicate<BucketKey> predicate) {
        Map<BucketKey, Long> filtered = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (predicate.test(key)) {
                filtered.put(key, value);
            }
        });
        return filtered;
    }

    private static <K> Map<K, Long> allocate(
            Map<K, Long> weights, long target, Comparator<K> tieBreaker) {
        long total = sum(weights.values());
        if (target < 0L || target > total || (total == 0L && target != 0L)) {
            throw new IllegalStateException("Production marginal-return allocation is invalid");
        }
        Map<K, Long> allocations = new LinkedHashMap<>();
        List<Remainder<K>> remainders = new ArrayList<>();
        long allocated = 0L;
        for (Map.Entry<K, Long> entry : weights.entrySet()) {
            BigInteger numerator = BigInteger.valueOf(entry.getValue())
                    .multiply(BigInteger.valueOf(target));
            BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(
                    BigInteger.valueOf(total));
            long quotient = quotientAndRemainder[0].longValueExact();
            allocations.put(entry.getKey(), quotient);
            allocated = addExact(allocated, quotient);
            remainders.add(new Remainder<>(entry.getKey(), quotientAndRemainder[1]));
        }
        long remaining = target - allocated;
        remainders.sort(Comparator
                .<Remainder<K>, BigInteger>comparing(Remainder::remainder)
                .reversed()
                .thenComparing(Remainder::key, tieBreaker));
        for (int index = 0; index < remaining; index++) {
            K key = remainders.get(index).key();
            allocations.put(key, allocations.get(key) + 1L);
        }
        return allocations;
    }

    private static long softCap(long value, long cap, int excessWeightBasisPoints) {
        if (value <= cap) {
            return value;
        }
        BigInteger weightedExcess = BigInteger.valueOf(value - cap)
                .multiply(BigInteger.valueOf(excessWeightBasisPoints))
                .divide(BASIS_POINTS);
        return BigInteger.valueOf(cap).add(weightedExcess).longValueExact();
    }

    private static long sum(Iterable<Long> values) {
        long total = 0L;
        for (Long value : values) {
            total = addExact(total, value);
        }
        return total;
    }

    private static long addExact(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("Production marginal-return value overflow", overflow);
        }
    }

    private record BucketKey(UUID facilityId, ProductionIndustryId industryId) {}

    private record Remainder<K>(K key, BigInteger remainder) {}
}
