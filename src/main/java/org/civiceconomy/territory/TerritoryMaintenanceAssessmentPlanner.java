package org.civiceconomy.territory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.nation.Capital;
import org.civiceconomy.nation.NationId;

public final class TerritoryMaintenanceAssessmentPlanner {
    private final TerritoryMaintenancePriorityClassifier classifier;

    public TerritoryMaintenanceAssessmentPlanner() {
        this(new TerritoryMaintenancePriorityClassifier());
    }

    TerritoryMaintenanceAssessmentPlanner(
            TerritoryMaintenancePriorityClassifier classifier) {
        if (classifier == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Assessment Planner requires a classifier");
        }
        this.classifier = classifier;
    }

    public List<TerritoryMaintenanceClaimSnapshot> plan(
            NationId nationId,
            UUID ftbTeamId,
            Capital capital,
            List<TerritoryMaintenanceObservedClaim> claims,
            TerritoryFreeAllocation freeAllocation,
            TerritoryMaintenancePolicyVersion policy) {
        return plan(
                nationId,
                ftbTeamId,
                capital,
                claims,
                freeAllocation,
                policy,
                policy.effectiveAt(),
                Map.of());
    }

    public List<TerritoryMaintenanceClaimSnapshot> plan(
            NationId nationId,
            UUID ftbTeamId,
            Capital capital,
            List<TerritoryMaintenanceObservedClaim> claims,
            TerritoryFreeAllocation freeAllocation,
            TerritoryMaintenancePolicyVersion policy,
            Instant cycleStartsAt,
            Map<TerritoryClaimPosition, TerritoryMaintenanceRestorationHistory>
                    restorationHistory) {
        if (nationId == null
                || ftbTeamId == null
                || capital == null
                || claims == null
                || freeAllocation == null
                || policy == null
                || cycleStartsAt == null
                || restorationHistory == null
                || restorationHistory.entrySet().stream()
                        .anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)
                || claims.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Assessment planning cannot contain null values");
        }
        if (!freeAllocation.nationId().equals(nationId)) {
            throw new IllegalArgumentException(
                    "Territory Free Allocation must belong to the exact Nation");
        }
        List<TerritoryClaimPosition> positions =
                claims.stream().map(TerritoryMaintenanceObservedClaim::position).toList();
        Map<TerritoryClaimPosition, TerritoryMaintenancePriority> priorities =
                classifier.classify(capital, positions);
        List<TerritoryMaintenanceObservedClaim> ordered = claims.stream()
                .sorted(Comparator
                        .comparing((TerritoryMaintenanceObservedClaim claim) ->
                                priorities.get(claim.position()))
                        .thenComparing(claim -> claim.position().dimensionId())
                        .thenComparingInt(claim -> claim.position().chunkX())
                        .thenComparingInt(claim -> claim.position().chunkZ()))
                .toList();
        int freeClaims = Math.min(freeAllocation.totalFreeChunks(), ordered.size());
        return java.util.stream.IntStream.range(0, ordered.size())
                .mapToObj(index -> snapshot(
                        nationId,
                        ftbTeamId,
                        ordered.get(index),
                        priorities.get(ordered.get(index).position()),
                        index < freeClaims,
                        policy,
                        cycleStartsAt,
                        restorationHistory.get(ordered.get(index).position())))
                .toList();
    }

    private static TerritoryMaintenanceClaimSnapshot snapshot(
            NationId nationId,
            UUID ftbTeamId,
            TerritoryMaintenanceObservedClaim claim,
            TerritoryMaintenancePriority priority,
            boolean free,
            TerritoryMaintenancePolicyVersion policy,
            Instant cycleStartsAt,
            TerritoryMaintenanceRestorationHistory restorationHistory) {
        long due = free ? 0L : policy.baseMaintenancePerChargeableClaimMinorUnits();
        if (!free && priority == TerritoryMaintenancePriority.ENCLAVE_OR_CROSS_DIMENSION) {
            due = multiplyBasisPoints(
                    due, policy.enclaveAndCrossDimensionMultiplierBasisPoints());
        }
        if (claim.forceLoadRequested()) {
            due = Math.addExact(due, policy.forceLoadSurchargeMinorUnits());
        }
        TerritoryClaimPosition position = claim.position();
        long restorationFee = 0L;
        TerritoryMaintenanceRestorationEligibility restorationEligibility =
                TerritoryMaintenanceRestorationEligibility.NOT_REQUIRED;
        java.util.Optional<Instant> restorationCooldownEndsAt = java.util.Optional.empty();
        if (restorationHistory != null && restorationHistory.previouslySuspended()) {
            java.util.Optional<Instant> activeCooldown = restorationHistory.cooldownEndsAt()
                    .filter(eligibleAt -> cycleStartsAt.isBefore(eligibleAt));
            if (activeCooldown.isPresent()) {
                restorationEligibility =
                        TerritoryMaintenanceRestorationEligibility.COOLDOWN_BLOCKED;
                restorationCooldownEndsAt = activeCooldown;
            } else {
                restorationEligibility = TerritoryMaintenanceRestorationEligibility.ELIGIBLE;
                restorationFee = policy.restorationFeeMinorUnits();
                restorationCooldownEndsAt =
                        java.util.Optional.of(cycleStartsAt.plus(policy.restorationCooldown()));
            }
        }
        return new TerritoryMaintenanceClaimSnapshot(
                nationId,
                ftbTeamId,
                position.dimensionId(),
                position.chunkX(),
                position.chunkZ(),
                due,
                restorationFee,
                restorationEligibility,
                restorationCooldownEndsAt,
                priority);
    }

    private static long multiplyBasisPoints(long amount, int basisPoints) {
        long product = Math.multiplyExact(amount, basisPoints);
        return Math.addExact(product, 9_999L) / 10_000L;
    }
}
