package org.civiceconomy.mint;

import java.util.Objects;
import java.util.UUID;

public record PendingMintMaterialTake(MintMaterialCustodyTransfer transfer)
        implements PendingMintMaterialOperation {
    public PendingMintMaterialTake {
        Objects.requireNonNull(transfer, "Mint material transfer cannot be null");
    }

    @Override
    public UUID batchId() {
        return transfer.batchId();
    }

    @Override
    public void apply(ExternalMintMaterialCustody custody) {
        Objects.requireNonNull(custody, "Mint material custody cannot be null").take(transfer);
    }
}
