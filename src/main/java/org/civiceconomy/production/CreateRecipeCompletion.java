package org.civiceconomy.production;

import java.util.UUID;

public record CreateRecipeCompletion(
        UUID observationId,
        String createVersion,
        CreateMachineKind machineKind,
        String recipeId,
        String dimensionId,
        int blockX,
        int blockY,
        int blockZ,
        long observedAtEpochMillis,
        MachineInventoryDelta inventoryDelta) {
    public CreateRecipeCompletion {
        if (observationId == null || machineKind == null || inventoryDelta == null
                || createVersion == null || createVersion.isBlank()
                || recipeId == null || recipeId.isBlank()
                || dimensionId == null || dimensionId.isBlank()
                || observedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("Create Recipe Completion is invalid");
        }
    }
}
