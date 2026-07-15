package org.civiceconomy.mint;

import java.util.Objects;
import java.util.UUID;

public record PendingMintMaterialReturn(MintMaterialCustodyReturn operation)
        implements PendingMintMaterialOperation {
    public PendingMintMaterialReturn {
        Objects.requireNonNull(operation, "Mint material return cannot be null");
    }

    @Override
    public UUID batchId() {
        return operation.batchId();
    }

    @Override
    public void apply(ExternalMintMaterialCustody custody) {
        Objects.requireNonNull(custody, "Mint material custody cannot be null")
                .returnToSource(operation);
    }
}
