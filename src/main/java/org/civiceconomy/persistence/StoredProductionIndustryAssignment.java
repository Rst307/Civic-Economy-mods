package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredProductionIndustryAssignment(
        UUID assignmentId,
        String serviceIdentity,
        String requestId,
        String actorIdentity,
        String createVersion,
        String recipeId,
        String industryId,
        long effectiveAtEpochMillis,
        String reason,
        long recordedAtEpochMillis) {}
