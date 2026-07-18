package org.civiceconomy.production;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregates production evidence from durable export-to-Receipt lineage.
 * A source Receipt is counted once even when its inventory is split across exports.
 */
public final class ProductionChainContributionCalculator {
    private final ProductionValueAddedCalculator valueAddedCalculator;

    public ProductionChainContributionCalculator(
            ProductionValueAddedCalculator valueAddedCalculator) {
        if (valueAddedCalculator == null) {
            throw new IllegalArgumentException(
                    "Production chain contribution calculator requires a value-added calculator");
        }
        this.valueAddedCalculator = valueAddedCalculator;
    }

    public ProductionChainContributionAssessment assess(
            List<FacilityProductionObservation> observations,
            List<ProductionInventoryExportLineage> lineages,
            java.time.Instant asOf) {
        if (observations == null || observations.stream().anyMatch(java.util.Objects::isNull)
                || lineages == null || lineages.stream().anyMatch(java.util.Objects::isNull)
                || asOf == null || asOf.toEpochMilli() < 0L) {
            throw new IllegalArgumentException(
                    "Production observations, export lineage and assessment time are required");
        }

        Map<UUID, ProductionInventoryExportLineage> uniqueLineages = new LinkedHashMap<>();
        for (ProductionInventoryExportLineage lineage : lineages) {
            ProductionInventoryExportLineage previous =
                    uniqueLineages.putIfAbsent(lineage.exportId(), lineage);
            if (previous != null && !previous.equals(lineage)) {
                throw new IllegalStateException(
                        "Production export lineage replay changed immutable sources "
                                + lineage.exportId());
            }
        }

        Map<UUID, FacilityProductionObservation> observationsByReceipt = new LinkedHashMap<>();
        for (FacilityProductionObservation observation : observations) {
            UUID receiptId = observation.receipt().receiptId();
            FacilityProductionObservation previous = observationsByReceipt.putIfAbsent(
                    receiptId, observation);
            if (previous != null && !previous.equals(observation)) {
                throw new IllegalStateException(
                        "Production observation replay changed immutable Receipt source "
                                + receiptId);
            }
        }

        LinkedHashSet<UUID> sourceReceipts = new LinkedHashSet<>();
        uniqueLineages.values().forEach(lineage -> sourceReceipts.addAll(lineage.sourceReceiptIds()));
        long acceptedValue = 0L;
        int acceptedCount = 0;
        int excludedCount = 0;
        int unmatchedCount = 0;
        EnumMap<ProductionValueAddedDecision, Integer> excludedByDecision =
                new EnumMap<>(ProductionValueAddedDecision.class);
        for (UUID receiptId : sourceReceipts) {
            FacilityProductionObservation observation = observationsByReceipt.get(receiptId);
            if (observation == null) {
                unmatchedCount++;
                continue;
            }
            ProductionValueAddedAssessment assessment =
                    valueAddedCalculator.calculate(observation);
            if (assessment.decision() != ProductionValueAddedDecision.INCLUDED) {
                excludedCount++;
                excludedByDecision.merge(assessment.decision(), 1, Integer::sum);
                continue;
            }
            try {
                acceptedValue = Math.addExact(acceptedValue, assessment.valueAddedMinorUnits());
                acceptedCount++;
            } catch (ArithmeticException overflow) {
                excludedCount++;
                excludedByDecision.merge(
                        ProductionValueAddedDecision.EXCLUDED_WEIGHT_OVERFLOW, 1, Integer::sum);
            }
        }
        return new ProductionChainContributionAssessment(
                acceptedValue,
                acceptedCount,
                excludedCount,
                sourceReceipts.size(),
                unmatchedCount,
                excludedByDecision);
    }

    public ProductionChainContributionAssessment assess(
            List<FacilityProductionObservation> observations,
            List<UUID> exportIds,
            ProductionInventoryExportLineageSource lineageSource,
            java.time.Instant asOf) {
        if (exportIds == null || exportIds.stream().anyMatch(java.util.Objects::isNull)
                || lineageSource == null) {
            throw new IllegalArgumentException(
                    "Production export IDs and lineage source are required");
        }
        List<ProductionInventoryExportLineage> lineages = exportIds.stream()
                .map(exportId -> {
                    ProductionInventoryExportLineage lineage = lineageSource.lineage(exportId);
                    if (lineage == null) {
                        throw new IllegalStateException(
                                "Missing durable production export lineage " + exportId);
                    }
                    return lineage;
                })
                .toList();
        return assess(observations, lineages, asOf);
    }
}
