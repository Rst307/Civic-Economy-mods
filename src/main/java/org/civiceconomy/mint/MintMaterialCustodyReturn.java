package org.civiceconomy.mint;

import java.util.List;
import java.util.UUID;

public record MintMaterialCustodyReturn(
        UUID operationId,
        UUID batchId,
        UUID mintId,
        UUID actorPlayerId,
        List<MintMaterialStack> materials) {
    public MintMaterialCustodyReturn {
        if (operationId == null
                || batchId == null
                || mintId == null
                || actorPlayerId == null
                || materials == null
                || materials.isEmpty()) {
            throw new IllegalArgumentException("Mint material custody return values are invalid");
        }
        materials = List.copyOf(materials);
    }
}
