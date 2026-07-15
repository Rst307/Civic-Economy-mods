package org.civiceconomy.territory;

import java.util.List;

public record TerritoryMaintenanceAssessmentBatch(
        TerritoryMaintenanceCycle cycle, List<TerritoryFiscalAssessment> assessments) {
    public TerritoryMaintenanceAssessmentBatch {
        if (cycle == null || assessments == null || assessments.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Assessment Batch cannot contain null values");
        }
        assessments = List.copyOf(assessments);
    }
}
