package org.civiceconomy.mint;

import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;

public record PrepareMintBatch(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID mintId,
        UUID periodId,
        UUID actorPlayerId,
        MoneyAmount amount,
        List<MintMaterialStack> materials,
        String reason) {
    public PrepareMintBatch {
        if (serviceIdentity == null || mintId == null || periodId == null
                || actorPlayerId == null || amount == null || materials == null) {
            throw new IllegalArgumentException("Mint Batch request cannot contain null values");
        }
        materials = List.copyOf(materials);
        if (requestId == null || requestId.isBlank() || amount.minorUnits() <= 0L
                || materials.isEmpty() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Mint Batch request values are invalid");
        }
    }
}