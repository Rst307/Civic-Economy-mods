package org.civiceconomy.integration.lightmanscurrency;

import net.minecraft.server.level.ServerLevel;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.territory.TerritoryClearingAccountProvisioner;
import org.civiceconomy.territory.TerritoryFiscalServiceProvisioner;

public final class LightmansCurrencyTerritoryClearingAccountProvisioner
        implements TerritoryClearingAccountProvisioner {
    public static final AccountId ACCOUNT_ID =
            TerritoryFiscalServiceProvisioner.CLEARING_ACCOUNT_ID;

    private final LightmansCurrencyFiscalAccounts accounts;

    public LightmansCurrencyTerritoryClearingAccountProvisioner(
            LightmansCurrencyFiscalAccounts accounts) {
        if (accounts == null) {
            throw new IllegalArgumentException("LC fiscal accounts cannot be null");
        }
        this.accounts = accounts;
    }

    public static LightmansCurrencyTerritoryClearingAccountProvisioner forLevel(
            ServerLevel level) {
        return new LightmansCurrencyTerritoryClearingAccountProvisioner(
                LightmansCurrencyFiscalAccounts.forLevel(level));
    }

    @Override
    public void ensureExists(AccountId clearingAccountId) {
        if (!ACCOUNT_ID.equals(clearingAccountId)) {
            throw new IllegalArgumentException(
                    "Territory clearing account must use the canonical Civic account ID");
        }
        accounts.create(
                clearingAccountId,
                FiscalAccountKind.TERRITORY_PREPAYMENT_CLEARING,
                "Civic Territory Prepayment Clearing");
    }
}
