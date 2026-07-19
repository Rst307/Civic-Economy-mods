package org.civiceconomy.production;

import java.time.Instant;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ScheduleProductionIndustryAssignment(
        ServiceIdentity serviceIdentity,
        String requestId,
        String actorIdentity,
        String createVersion,
        String recipeId,
        ProductionIndustryId industryId,
        Instant effectiveAt,
        String reason) {
    public ScheduleProductionIndustryAssignment {
        if (serviceIdentity == null || industryId == null || effectiveAt == null) {
            throw new IllegalArgumentException(
                    "Production Industry assignment cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()
                || actorIdentity == null || actorIdentity.isBlank()
                || createVersion == null || createVersion.isBlank()
                || recipeId == null || !recipeId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Production Industry assignment is invalid");
        }
    }
}
