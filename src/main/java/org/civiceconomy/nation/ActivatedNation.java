package org.civiceconomy.nation;

import org.civiceconomy.fiscal.AccountId;

public record ActivatedNation(
        RegisteredNation nation, Capital capital, AccountId treasuryAccountId) {
    public ActivatedNation {
        if (nation == null || capital == null || treasuryAccountId == null) {
            throw new IllegalArgumentException("Activated Nation fields cannot be null");
        }
    }
}
