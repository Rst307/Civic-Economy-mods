package org.civiceconomy.production;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredCreateRecipeCompletion;
import org.civiceconomy.persistence.StoredFacilityAccountingReceipt;
import org.civiceconomy.persistence.StoredFacilityProductionDecision;
import org.civiceconomy.persistence.StoredProductionIndustryAssignment;
import org.civiceconomy.persistence.StoredProductionInventoryChange;
import org.civiceconomy.persistence.StoredProductionMarginalReturnContribution;
import org.civiceconomy.persistence.StoredProductionMarginalReturnPolicy;

public final class ProductionMarginalReturnContributionRegistry {
    private final CivicDatabase database;
    private final ProductionValueAddedCalculator valueAddedCalculator;
    private final ProductionIndustryAssignmentRegistry industries;
    private final ProductionMarginalReturnPolicyRegistry policies;
    private final Clock clock;

    public ProductionMarginalReturnContributionRegistry(
            CivicDatabase database,
            ProductionValueAddedCalculator valueAddedCalculator,
            ProductionIndustryAssignmentRegistry industries,
            ProductionMarginalReturnPolicyRegistry policies,
            Clock clock) {
        if (database == null || valueAddedCalculator == null || industries == null
                || policies == null || clock == null) {
            throw new IllegalArgumentException(
                    "Production Marginal Return contribution dependencies cannot be null");
        }
        this.database = database;
        this.valueAddedCalculator = valueAddedCalculator;
        this.industries = industries;
        this.policies = policies;
        this.clock = clock;
    }

    public List<ProductionContributionBindingAssessment> bindExport(UUID exportId) {
        if (exportId == null) {
            throw new IllegalArgumentException("Production Export Event ID is required");
        }
        ProductionInventoryExportLineage lineage =
                new ProductionInventoryExportLineageRegistry(database).lineage(exportId);
        if (lineage == null) {
            throw new IllegalStateException(
                    "Production Export Event has no durable Receipt lineage " + exportId);
        }
        return lineage.sourceReceiptIds().stream()
                .map(database::facilityProductionDecisionForReceipt)
                .filter(java.util.Objects::nonNull)
                .map(decision -> bindObservation(decision.observationId(), exportId))
                .toList();
    }

    private ProductionContributionBindingAssessment bindObservation(
            UUID observationId, UUID anchorExportId) {
        if (observationId == null) {
            throw new IllegalArgumentException("Production observation ID is required");
        }
        FacilityProductionObservation observation = observation(observationId);
        if (observation == null) {
            throw new IllegalStateException(
                    "Production observation is not durably recorded " + observationId);
        }
        Instant evidenceAt = Instant.ofEpochMilli(
                observation.completion().observedAtEpochMillis());
        ProductionValueAddedAssessment valueAdded = valueAddedCalculator.calculate(observation);
        if (valueAdded.decision() != ProductionValueAddedDecision.INCLUDED) {
            return new ProductionContributionBindingAssessment(
                    ProductionContributionBindingDecision.EXCLUDED_VALUE_ADDED,
                    valueAdded,
                    Optional.empty(),
                    "Production Value Added was excluded: " + valueAdded.decision());
        }
        Optional<ProductionIndustryAssignmentVersion> industry = industries.assignment(
                        observation.completion().createVersion(),
                        observation.completion().recipeId(),
                        evidenceAt);
        if (industry.isEmpty()) {
            return new ProductionContributionBindingAssessment(
                    ProductionContributionBindingDecision
                            .EXCLUDED_MISSING_INDUSTRY_ASSIGNMENT,
                    valueAdded,
                    Optional.empty(),
                    "No trusted Production Industry assignment was effective at evidence time");
        }
        Optional<ProductionMarginalReturnPolicyVersion> policy = policies.current(evidenceAt);
        if (policy.isEmpty()) {
            return new ProductionContributionBindingAssessment(
                    ProductionContributionBindingDecision
                            .EXCLUDED_MISSING_MARGINAL_RETURN_POLICY,
                    valueAdded,
                    Optional.empty(),
                    "No trusted Production Marginal Return Policy was effective at evidence time");
        }
        StoredProductionMarginalReturnContribution stored =
                database.recordProductionMarginalReturnContribution(
                        observation.completion().observationId(),
                        anchorExportId,
                        observation.decision().facilityId(),
                        industry.orElseThrow().assignmentId(),
                        policy.orElseThrow().policyId(),
                        valueAdded.valueAddedMinorUnits(),
                        evidenceAt.toEpochMilli(),
                        clock.millis());
        BoundProductionMarginalReturnContribution binding = toBinding(stored);
        return new ProductionContributionBindingAssessment(
                ProductionContributionBindingDecision.BOUND,
                valueAdded,
                Optional.of(binding),
                "Bound production contribution to trusted evidence-time policy versions");
    }

    public BoundProductionMarginalReturnContribution binding(UUID observationId) {
        if (observationId == null) {
            throw new IllegalArgumentException("Production observation ID is required");
        }
        StoredProductionMarginalReturnContribution stored =
                database.productionMarginalReturnContribution(observationId);
        return stored == null ? null : toBinding(stored);
    }

    private FacilityProductionObservation observation(UUID observationId) {
        StoredCreateRecipeCompletion completion = database.createRecipeCompletion(observationId);
        if (completion == null) {
            return null;
        }
        StoredFacilityProductionDecision decision =
                database.facilityProductionDecision(observationId);
        if (decision == null) {
            throw new IllegalStateException(
                    "Production Completion has no immutable decision " + observationId);
        }
        StoredFacilityAccountingReceipt receipt =
                database.facilityAccountingReceipt(decision.receiptId());
        if (receipt == null) {
            throw new IllegalStateException(
                    "Production decision has no immutable Receipt " + observationId);
        }
        return new FacilityProductionObservation(
                new CreateRecipeCompletion(
                        completion.observationId(),
                        completion.createVersion(),
                        CreateMachineKind.valueOf(completion.machineKind()),
                        completion.recipeId(),
                        completion.dimensionId(),
                        completion.blockX(),
                        completion.blockY(),
                        completion.blockZ(),
                        completion.observedAtEpochMillis(),
                        new MachineInventoryDelta(
                                toChanges(database.createRecipeCompletionChanges(
                                        observationId, "INPUT")),
                                toChanges(database.createRecipeCompletionChanges(
                                        observationId, "OUTPUT")))),
                new FacilityAccountingReceipt(
                        receipt.receiptId(),
                        receipt.interfaceId(),
                        new FacilityAccountingInterfacePosition(
                                receipt.dimensionId(),
                                receipt.blockX(),
                                receipt.blockY(),
                                receipt.blockZ()),
                        receipt.observedAtEpochMillis(),
                        toChanges(database.facilityAccountingReceiptChanges(
                                receipt.receiptId()))),
                new FacilityProductionDecision(
                        decision.observationId(),
                        decision.facilityId(),
                        decision.interfaceId(),
                        decision.receiptId(),
                        FacilityProductionDecisionKind.valueOf(decision.decision()),
                        decision.reason()),
                Instant.ofEpochMilli(decision.decidedAtEpochMillis()));
    }

    private static List<MachineInventoryChange> toChanges(
            List<StoredProductionInventoryChange> changes) {
        return changes.stream()
                .map(change -> new MachineInventoryChange(
                        change.slot(),
                        new ProductionStack(
                                change.itemId(),
                                change.componentFingerprint(),
                                change.count())))
                .toList();
    }

    private BoundProductionMarginalReturnContribution toBinding(
            StoredProductionMarginalReturnContribution stored) {
        StoredProductionIndustryAssignment industry =
                database.productionIndustryAssignment(stored.industryAssignmentId());
        StoredProductionMarginalReturnPolicy policy =
                database.productionMarginalReturnPolicy(stored.marginalReturnPolicyId());
        if (industry == null || policy == null) {
            throw new IllegalStateException(
                    "Production contribution references missing immutable policy history");
        }
        ProductionIndustryAssignmentVersion industryVersion =
                new ProductionIndustryAssignmentVersion(
                        industry.assignmentId(),
                        industry.createVersion(),
                        industry.recipeId(),
                        new ProductionIndustryId(industry.industryId()),
                        Instant.ofEpochMilli(industry.effectiveAtEpochMillis()),
                        industry.actorIdentity(),
                        industry.reason(),
                        Instant.ofEpochMilli(industry.recordedAtEpochMillis()));
        ProductionMarginalReturnPolicyVersion policyVersion =
                new ProductionMarginalReturnPolicyVersion(
                        policy.policyId(),
                        new ProductionMarginalReturnPolicy(
                                policy.facilitySoftCapMinorUnits(),
                                policy.facilityExcessWeightBasisPoints(),
                                policy.industrySoftCapMinorUnits(),
                                policy.industryExcessWeightBasisPoints()),
                        Instant.ofEpochMilli(policy.effectiveAtEpochMillis()),
                        policy.actorIdentity(),
                        policy.reason(),
                        Instant.ofEpochMilli(policy.recordedAtEpochMillis()));
        return new BoundProductionMarginalReturnContribution(
                stored.anchorExportId(),
                new ProductionMarginalReturnContribution(
                        stored.observationId(),
                        stored.facilityId(),
                        industryVersion.industryId(),
                        stored.valueAddedMinorUnits()),
                industryVersion,
                policyVersion,
                Instant.ofEpochMilli(stored.evidenceAtEpochMillis()),
                Instant.ofEpochMilli(stored.recordedAtEpochMillis()));
    }
}
