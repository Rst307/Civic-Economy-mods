package org.civiceconomy.territory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.civiceconomy.fiscal.MoneyAmount;

public final class TerritoryMaintenancePriorityPolicy {
    private static final Comparator<TerritoryMaintenanceCandidate> ORDER = Comparator
            .comparing(TerritoryMaintenanceCandidate::priority)
            .thenComparing(TerritoryMaintenanceCandidate::dimensionId)
            .thenComparingInt(TerritoryMaintenanceCandidate::chunkX)
            .thenComparingInt(TerritoryMaintenanceCandidate::chunkZ)
            .thenComparing(TerritoryMaintenanceCandidate::assessmentId);

    public TerritoryMaintenancePriorityDecision select(
            List<TerritoryMaintenanceCandidate> candidates, MoneyAmount available) {
        if (candidates == null || available == null || candidates.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Territory Maintenance priority input cannot contain null values");
        }
        Set<java.util.UUID> assessmentIds = new HashSet<>();
        if (candidates.stream().anyMatch(candidate -> !assessmentIds.add(candidate.assessmentId()))) {
            throw new IllegalArgumentException(
                    "Territory Maintenance priority input contains duplicate assessments");
        }
        List<TerritoryMaintenanceCandidate> ordered = candidates.stream().sorted(ORDER).toList();
        List<TerritoryMaintenanceCandidate> funded = new ArrayList<>();
        List<TerritoryMaintenanceCandidate> suspended = new ArrayList<>();
        MoneyAmount remaining = available;
        boolean budgetExhausted = false;
        for (TerritoryMaintenanceCandidate candidate : ordered) {
            if ((budgetExhausted && !candidate.maintenanceDue().equals(MoneyAmount.ZERO))
                    || candidate.maintenanceDue().minorUnits() > remaining.minorUnits()) {
                budgetExhausted = true;
                suspended.add(candidate);
            } else {
                funded.add(candidate);
                remaining = remaining.minus(candidate.maintenanceDue());
            }
        }
        return new TerritoryMaintenancePriorityDecision(
                funded,
                suspended,
                available.minus(remaining),
                remaining);
    }
}
