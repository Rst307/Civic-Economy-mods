package org.civiceconomy.territory;

import org.civiceconomy.fiscal.AccountId;

@FunctionalInterface
public interface TerritoryClearingAccountProvisioner {
    void ensureExists(AccountId clearingAccountId);
}
