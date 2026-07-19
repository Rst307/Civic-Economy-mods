package org.civiceconomy.production;

import java.time.Instant;
import java.util.List;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredRollingProductionContributionAssessment;
import org.civiceconomy.persistence.StoredRollingProductionMarginalReturnAssessment;

public final class RollingProductionMarginalReturnRegistry {
    private final CivicDatabase database;
    private final ProductionMarginalReturnContributionRegistry contributions;
    private final RollingProductionMarginalReturnCalculator calculator;

    public RollingProductionMarginalReturnRegistry(
            CivicDatabase database,
            ProductionMarginalReturnContributionRegistry contributions,
            RollingProductionMarginalReturnCalculator calculator) {
        if (database == null || contributions == null || calculator == null) {
            throw new IllegalArgumentException(
                    "Rolling Production Marginal Return dependencies are required");
        }
        this.database = database;
        this.contributions = contributions;
        this.calculator = calculator;
    }

    public RollingProductionMarginalReturnAssessment assess(NationId nationId, Instant asOf) {
        List<BoundProductionMarginalReturnContribution> bindings = contributions.bindings(
                nationId, calculator.windowStart(asOf), asOf);
        RollingProductionMarginalReturnAssessment assessment =
                calculator.assess(nationId, bindings, asOf);
        int unbound = contributions.unboundExportedObservationCount(
                nationId, calculator.windowStart(asOf), asOf);
        assessment = new RollingProductionMarginalReturnAssessment(
                assessment.nationId(),
                assessment.windowStartEpochMillis(),
                assessment.windowEndEpochMillis(),
                assessment.rawValueMinorUnits(),
                assessment.recencyWeightedValueMinorUnits(),
                assessment.afterFacilityReturnsMinorUnits(),
                assessment.finalValueMinorUnits(),
                assessment.facilityMarginalReductionMinorUnits(),
                assessment.industryMarginalReductionMinorUnits(),
                assessment.acceptedContributionCount(),
                unbound,
                assessment.contributions());
        database.replaceRollingProductionMarginalReturnAssessment(
                toStored(assessment),
                assessment.contributions().stream()
                        .map(detail -> toStored(nationId, detail))
                        .toList());
        return current(nationId);
    }

    public RollingProductionMarginalReturnAssessment current(NationId nationId) {
        if (nationId == null) {
            throw new IllegalArgumentException("Rolling Production assessment Nation is required");
        }
        StoredRollingProductionMarginalReturnAssessment stored =
                database.rollingProductionMarginalReturnAssessment(nationId.value());
        if (stored == null) {
            return null;
        }
        List<RollingProductionContributionAssessment> details =
                database.rollingProductionContributionAssessments(nationId.value()).stream()
                        .map(detail -> new RollingProductionContributionAssessment(
                                detail.observationId(),
                                detail.anchorExportId(),
                                detail.facilityId(),
                                new ProductionIndustryId(detail.industryId()),
                                detail.industryAssignmentId(),
                                detail.marginalReturnPolicyId(),
                                Instant.ofEpochMilli(detail.evidenceAtEpochMillis()),
                                detail.recencyWeightBasisPoints(),
                                detail.rawValueMinorUnits(),
                                detail.recencyWeightedValueMinorUnits(),
                                detail.facilityUsageBeforeMinorUnits(),
                                detail.afterFacilityReturnsMinorUnits(),
                                detail.industryUsageBeforeMinorUnits(),
                                detail.finalValueMinorUnits()))
                        .toList();
        return new RollingProductionMarginalReturnAssessment(
                nationId,
                stored.windowStartEpochMillis(),
                stored.windowEndEpochMillis(),
                stored.rawValueMinorUnits(),
                stored.recencyWeightedValueMinorUnits(),
                stored.afterFacilityReturnsMinorUnits(),
                stored.finalValueMinorUnits(),
                stored.facilityMarginalReductionMinorUnits(),
                stored.industryMarginalReductionMinorUnits(),
                stored.acceptedContributionCount(),
                stored.unboundExportedObservationCount(),
                details);
    }

    private static StoredRollingProductionMarginalReturnAssessment toStored(
            RollingProductionMarginalReturnAssessment assessment) {
        return new StoredRollingProductionMarginalReturnAssessment(
                assessment.nationId().value(),
                assessment.windowStartEpochMillis(),
                assessment.windowEndEpochMillis(),
                assessment.rawValueMinorUnits(),
                assessment.recencyWeightedValueMinorUnits(),
                assessment.afterFacilityReturnsMinorUnits(),
                assessment.finalValueMinorUnits(),
                assessment.facilityMarginalReductionMinorUnits(),
                assessment.industryMarginalReductionMinorUnits(),
                assessment.acceptedContributionCount(),
                assessment.unboundExportedObservationCount());
    }

    private static StoredRollingProductionContributionAssessment toStored(
            NationId nationId, RollingProductionContributionAssessment detail) {
        return new StoredRollingProductionContributionAssessment(
                nationId.value(),
                detail.observationId(),
                detail.anchorExportId(),
                detail.facilityId(),
                detail.industryId().value(),
                detail.industryAssignmentId(),
                detail.marginalReturnPolicyId(),
                detail.evidenceAt().toEpochMilli(),
                detail.recencyWeightBasisPoints(),
                detail.rawValueMinorUnits(),
                detail.recencyWeightedValueMinorUnits(),
                detail.facilityUsageBeforeMinorUnits(),
                detail.afterFacilityReturnsMinorUnits(),
                detail.industryUsageBeforeMinorUnits(),
                detail.finalValueMinorUnits());
    }
}
