package org.civiceconomy.mint;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;

public record MintBatch(
        UUID batchId,
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID mintId,
        UUID periodId,
        NationId nationId,
        UUID recipeVersionId,
        MoneyAmount amount,
        UUID actorPlayerId,
        List<MintMaterialStack> materials,
        String state,
        String custodyState,
        String reason,
        Instant preparedAt) {
    public MintBatch { materials = List.copyOf(materials); }
}