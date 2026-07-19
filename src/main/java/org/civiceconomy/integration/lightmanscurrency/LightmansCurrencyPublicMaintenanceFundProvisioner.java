package org.civiceconomy.integration.lightmanscurrency;

import net.minecraft.server.level.ServerLevel;
import org.civiceconomy.fiscal.AccountId;

public final class LightmansCurrencyPublicMaintenanceFundProvisioner {
    public static final AccountId ACCOUNT_ID =
            new AccountId("system:territory:public-maintenance-fund");

    private final LightmansCurrencyFiscalAccounts accounts;

    LightmansCurrencyPublicMaintenanceFundProvisioner(
            LightmansCurrencyFiscalAccounts accounts) {
        this.accounts = accounts;
    }

    public static LightmansCurrencyPublicMaintenanceFundProvisioner forLevel(
            ServerLevel level) {
        return new LightmansCurrencyPublicMaintenanceFundProvisioner(
                LightmansCurrencyFiscalAccounts.forLevel(level));
    }

    public void ensureExists() {
        accounts.create(
                ACCOUNT_ID,
                FiscalAccountKind.PUBLIC_MAINTENANCE_FUND,
                "Civic Public Maintenance Fund");
    }
}
