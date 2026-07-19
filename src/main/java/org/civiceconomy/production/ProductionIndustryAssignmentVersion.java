package org.civiceconomy.production;

import java.time.Instant;
import java.util.UUID;

public record ProductionIndustryAssignmentVersion(
        UUID assignmentId,
        String createVersion,
        String recipeId,
        ProductionIndustryId industryId,
        Instant effectiveAt,
        String actorIdentity,
        String reason,
        Instant recordedAt) {
    public ProductionIndustryAssignmentVersion {
        if (assignmentId == null || industryId == null || effectiveAt == null
                || recordedAt == null
                || createVersion == null || createVersion.isBlank()
                || recipeId == null || !recipeId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Production Industry assignment version is invalid");
        }
    }
}
