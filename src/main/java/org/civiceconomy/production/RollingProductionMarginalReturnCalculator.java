package org.civiceconomy.production;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.civiceconomy.nation.NationId;

public final class RollingProductionMarginalReturnCalculator {
    private static final int FULL_WEIGHT_BASIS_POINTS = 10_000;
    private static final BigInteger BASIS_POINTS = BigInteger.valueOf(10_000L);

    private final long windowMillis;
    private final long fullWeightMillis;

    public RollingProductionMarginalReturnCalculator(
            Duration window, Duration fullWeightWindow) {
        if (window == null || fullWeightWindow == null
                || window.isNegative() || window.isZero()
                || fullWeightWindow.isNegative() || fullWeightWindow.isZero()
                || fullWeightWindow.compareTo(window) > 0) {
            throw new IllegalArgumentException(
                    "Rolling Production Marginal Return window is invalid");
        }
        this.windowMillis = window.toMillis();
        this.fullWeightMillis = fullWeightWindow.toMillis();
        if (windowMillis <= 0L || fullWeightMillis <= 0L
                || fullWeightMillis > windowMillis) {
            throw new IllegalArgumentException(
                    "Rolling Production Marginal Return durations are invalid");
        }
    }

    public RollingProductionMarginalReturnAssessment assess(
            NationId nationId,
            List<BoundProductionMarginalReturnContribution> bindings,
            Instant asOf) {
        if (nationId == null || bindings == null
                || bindings.stream().anyMatch(java.util.Objects::isNull)
                || asOf == null) {
            throw new IllegalArgumentException(
                    "Rolling Production Marginal Return inputs are required");
        }
        long end = asOf.toEpochMilli();
        if (end <= 0L) {
            throw new IllegalArgumentException(
                    "Rolling Production Marginal Return end must be positive");
        }
        long start = Math.max(0L, subtractWindow(end));
        Map<UUID, BoundProductionMarginalReturnContribution> unique = new LinkedHashMap<>();
        for (BoundProductionMarginalReturnContribution binding : bindings) {
            UUID observationId = binding.contribution().observationId();
            BoundProductionMarginalReturnContribution previous =
                    unique.putIfAbsent(observationId, binding);
            if (previous != null && !previous.equals(binding)) {
                throw new IllegalStateException(
                        "Rolling production contribution replay changed observation "
                                + observationId);
            }
        }
        List<BoundProductionMarginalReturnContribution> ordered = unique.values().stream()
                .filter(binding -> {
                    long evidenceAt = binding.evidenceAt().toEpochMilli();
                    return evidenceAt >= start && evidenceAt < end;
                })
                .sorted(Comparator
                        .comparing(BoundProductionMarginalReturnContribution::evidenceAt)
                        .thenComparing(binding ->
                                binding.contribution().observationId()))
                .toList();

        Map<UUID, Long> facilityUsage = new java.util.HashMap<>();
        Map<ProductionIndustryId, Long> industryUsage = new java.util.HashMap<>();
        long raw = 0L;
        long weighted = 0L;
        long afterFacility = 0L;
        long finalValue = 0L;
        int accepted = 0;
        List<RollingProductionContributionAssessment> details = new ArrayList<>();
        for (BoundProductionMarginalReturnContribution binding : ordered) {
            ProductionMarginalReturnContribution contribution = binding.contribution();
            int recencyWeight = weightBasisPoints(
                    end - binding.evidenceAt().toEpochMilli());
            long weightedValue = weightedValue(
                    contribution.valueMinorUnits(), recencyWeight);
            ProductionMarginalReturnPolicy policy = binding.marginalReturnPolicy().policy();
            long facilityBefore = facilityUsage.getOrDefault(
                    contribution.facilityId(), 0L);
            long facilityAfter = addExact(facilityBefore, weightedValue);
            long facilityContribution = marginalValue(
                    facilityBefore,
                    weightedValue,
                    policy.facilitySoftCapMinorUnits(),
                    policy.facilityExcessWeightBasisPoints());
            facilityUsage.put(contribution.facilityId(), facilityAfter);

            long industryBefore = industryUsage.getOrDefault(
                    contribution.industryId(), 0L);
            long industryAfter = addExact(industryBefore, facilityContribution);
            long finalContribution = marginalValue(
                    industryBefore,
                    facilityContribution,
                    policy.industrySoftCapMinorUnits(),
                    policy.industryExcessWeightBasisPoints());
            industryUsage.put(contribution.industryId(), industryAfter);

            raw = addExact(raw, contribution.valueMinorUnits());
            weighted = addExact(weighted, weightedValue);
            afterFacility = addExact(afterFacility, facilityContribution);
            finalValue = addExact(finalValue, finalContribution);
            details.add(new RollingProductionContributionAssessment(
                    contribution.observationId(),
                    binding.anchorExportId(),
                    contribution.facilityId(),
                    contribution.industryId(),
                    binding.industryAssignment().assignmentId(),
                    binding.marginalReturnPolicy().policyId(),
                    binding.evidenceAt(),
                    recencyWeight,
                    contribution.valueMinorUnits(),
                    weightedValue,
                    facilityBefore,
                    facilityContribution,
                    industryBefore,
                    finalContribution));
            accepted++;
        }
        return new RollingProductionMarginalReturnAssessment(
                nationId,
                start,
                end,
                raw,
                weighted,
                afterFacility,
                finalValue,
                weighted - afterFacility,
                afterFacility - finalValue,
                accepted,
                0,
                details);
    }

    public Instant windowStart(Instant asOf) {
        if (asOf == null || asOf.toEpochMilli() <= 0L) {
            throw new IllegalArgumentException(
                    "Rolling Production Marginal Return end must be positive");
        }
        return Instant.ofEpochMilli(Math.max(0L, subtractWindow(asOf.toEpochMilli())));
    }

    private long subtractWindow(long end) {
        try {
            return Math.subtractExact(end, windowMillis);
        } catch (ArithmeticException overflow) {
            return Long.MIN_VALUE;
        }
    }

    private long weightedValue(long value, int weight) {
        return BigInteger.valueOf(value)
                .multiply(BigInteger.valueOf(weight))
                .divide(BASIS_POINTS)
                .longValueExact();
    }

    private int weightBasisPoints(long ageMillis) {
        if (ageMillis <= fullWeightMillis) {
            return FULL_WEIGHT_BASIS_POINTS;
        }
        long decayingMillis = windowMillis - fullWeightMillis;
        long remainingMillis = Math.max(0L, windowMillis - ageMillis);
        return (int) Math.min(
                FULL_WEIGHT_BASIS_POINTS,
                Math.multiplyExact(remainingMillis, FULL_WEIGHT_BASIS_POINTS)
                        / decayingMillis);
    }

    private static long marginalValue(
            long cumulativeBefore, long value, long cap, int excessWeightBasisPoints) {
        long cumulativeAfter = addExact(cumulativeBefore, value);
        return Math.subtractExact(
                adjustedTotal(cumulativeAfter, cap, excessWeightBasisPoints),
                adjustedTotal(cumulativeBefore, cap, excessWeightBasisPoints));
    }

    private static long adjustedTotal(long value, long cap, int excessWeightBasisPoints) {
        if (value <= cap) {
            return value;
        }
        return BigInteger.valueOf(cap)
                .add(BigInteger.valueOf(value - cap)
                        .multiply(BigInteger.valueOf(excessWeightBasisPoints))
                        .divide(BASIS_POINTS))
                .longValueExact();
    }

    private static long addExact(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException(
                    "Rolling Production Marginal Return value overflow", overflow);
        }
    }
}
