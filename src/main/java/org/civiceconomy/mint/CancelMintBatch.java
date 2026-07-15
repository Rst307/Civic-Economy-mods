package org.civiceconomy.mint;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record CancelMintBatch(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID batchId,
        UUID actorPlayerId,
        String reason) {
    public CancelMintBatch {
        if (serviceIdentity == null
                || requestId == null
                || requestId.isBlank()
                || batchId == null
                || actorPlayerId == null
                || reason == null
                || reason.isBlank()) {
            throw new IllegalArgumentException("Mint Batch cancellation values are invalid");
        }
    }
}
