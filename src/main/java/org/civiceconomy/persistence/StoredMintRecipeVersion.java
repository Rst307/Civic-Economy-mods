package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredMintRecipeVersion(
        UUID recipeVersionId,
        String serviceIdentity,
        String requestId,
        int versionNumber,
        long processingDurationMillis,
        String reason,
        long publishedAtEpochMillis) {}
