package org.civiceconomy.mint;

import java.util.List;
import java.util.UUID;

public record MintMaterialCustodyTransfer(
        UUID transferId,
        UUID batchId,
        UUID mintId,
        UUID actorPlayerId,
        List<MintMaterialStack> materials) {
    public MintMaterialCustodyTransfer {
        if (transferId == null || batchId == null || mintId == null
                || actorPlayerId == null || materials == null || materials.isEmpty()) {
            throw new IllegalArgumentException("Mint material custody transfer is invalid");
        }
        materials = List.copyOf(materials);
    }
}