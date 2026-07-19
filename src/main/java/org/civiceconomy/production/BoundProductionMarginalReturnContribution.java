package org.civiceconomy.production;

import java.time.Instant;

public record BoundProductionMarginalReturnContribution(
        java.util.UUID anchorExportId,
        ProductionMarginalReturnContribution contribution,
        ProductionIndustryAssignmentVersion industryAssignment,
        ProductionMarginalReturnPolicyVersion marginalReturnPolicy,
        Instant evidenceAt,
        Instant recordedAt) {
    public BoundProductionMarginalReturnContribution {
        if (anchorExportId == null || contribution == null || industryAssignment == null
                || marginalReturnPolicy == null || evidenceAt == null || recordedAt == null
                || !contribution.industryId().equals(industryAssignment.industryId())
                || evidenceAt.toEpochMilli() < 0L
                || recordedAt.isBefore(evidenceAt)) {
            throw new IllegalArgumentException(
                    "Bound Production Marginal Return contribution is invalid");
        }
    }
}
