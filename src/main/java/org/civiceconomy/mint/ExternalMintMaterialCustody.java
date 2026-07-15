package org.civiceconomy.mint;

public interface ExternalMintMaterialCustody {
    void take(MintMaterialCustodyTransfer transfer);

    void returnToSource(MintMaterialCustodyReturn operation);
}
