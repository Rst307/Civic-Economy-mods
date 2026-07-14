package org.civiceconomy.nation;

import org.civiceconomy.fiscal.AccountId;

@FunctionalInterface
public interface NationalTreasuryProvisioner {
    void ensureExists(NationId nationId, AccountId treasuryAccountId);
}
