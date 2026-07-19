package org.civiceconomy.integration.lightmanscurrency;

import net.minecraft.server.level.ServerLevel;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.NationalTreasuryProvisioner;

public final class LightmansCurrencyNationalTreasuryProvisioner
        implements NationalTreasuryProvisioner {
    private final LightmansCurrencyFiscalAccounts accounts;

    public LightmansCurrencyNationalTreasuryProvisioner(
            LightmansCurrencyFiscalAccounts accounts) {
        if (accounts == null) {
            throw new IllegalArgumentException("LC fiscal accounts cannot be null");
        }
        this.accounts = accounts;
    }

    public static LightmansCurrencyNationalTreasuryProvisioner forLevel(ServerLevel level) {
        return new LightmansCurrencyNationalTreasuryProvisioner(
                LightmansCurrencyFiscalAccounts.forLevel(level));
    }

    @Override
    public void ensureExists(NationId nationId, AccountId treasuryAccountId) {
        AccountId expected = new AccountId("nation:" + nationId.value() + ":treasury");
        if (!expected.equals(treasuryAccountId)) {
            throw new IllegalArgumentException(
                    "National Treasury account does not match Nation " + nationId);
        }
        accounts.create(
                treasuryAccountId,
                FiscalAccountKind.NATIONAL_TREASURY,
                "Nation " + nationId.value() + " National Treasury");
    }
}
