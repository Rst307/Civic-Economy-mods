package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredCreateRecipeCompletion(
        UUID observationId,
        String createVersion,
        String machineKind,
        String recipeId,
        String dimensionId,
        int blockX,
        int blockY,
        int blockZ,
        long observedAtEpochMillis) {}
