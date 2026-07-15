package org.civiceconomy.mint;

import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;

public record ExternalMintIssuance(
        UUID issuanceId, AccountId treasuryAccount, MoneyAmount amount) {
    public ExternalMintIssuance {
        if (issuanceId == null || treasuryAccount == null || amount == null) {
            throw new IllegalArgumentException("Mint issuance cannot contain null values");
        }
        if (amount.equals(MoneyAmount.ZERO)) {
            throw new IllegalArgumentException("Mint issuance amount must be positive");
        }
    }
}
