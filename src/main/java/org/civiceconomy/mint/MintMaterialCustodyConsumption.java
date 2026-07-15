package org.civiceconomy.mint;

import java.util.List;
import java.util.UUID;

public record MintMaterialCustodyConsumption(
        UUID operationId,
        UUID batchId,
        UUID mintId,
        UUID actorPlayerId,
        List<MintMaterialStack> materials) {
    public MintMaterialCustodyConsumption {
        if (operationId == null
                || batchId == null
                || mintId == null
                || actorPlayerId == null
                || materials == null
                || materials.isEmpty()) {
            throw new IllegalArgumentException(
                    "Mint material consumption cannot contain null or empty values");
        }
        materials = List.copyOf(materials);
    }
}
