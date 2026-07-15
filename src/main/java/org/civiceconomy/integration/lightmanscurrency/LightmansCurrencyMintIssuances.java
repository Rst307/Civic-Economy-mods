package org.civiceconomy.integration.lightmanscurrency;

import net.minecraft.server.level.ServerLevel;
import org.civiceconomy.mint.ExternalMintIssuance;
import org.civiceconomy.mint.ExternalMintIssuances;

public final class LightmansCurrencyMintIssuances implements ExternalMintIssuances {
    private final LightmansCurrencyMintIssuanceAccounts accounts;

    LightmansCurrencyMintIssuances(LightmansCurrencyMintIssuanceAccounts accounts) {
        this.accounts = accounts;
    }

    public static LightmansCurrencyMintIssuances live(ServerLevel level) {
        return new LightmansCurrencyMintIssuances(
                new LiveLightmansCurrencyMintIssuanceAccounts(
                        LightmansCurrencyFiscalAccounts.forLevel(level)));
    }

    @Override
    public void apply(ExternalMintIssuance issuance) {
        synchronized (accounts.transactionLock()) {
            if (accounts.wasApplied(issuance.issuanceId())) {
                return;
            }
            accounts.deposit(issuance.treasuryAccount(), issuance.amount());
            try {
                accounts.recordApplied(issuance.issuanceId());
            } catch (RuntimeException failure) {
                var withdrawn = accounts.withdraw(
                        issuance.treasuryAccount(), issuance.amount());
                if (!withdrawn.equals(issuance.amount())) {
                    var compensationFailure = new IllegalStateException(
                            "LC Mint issuance marker failed and its deposit could not be reversed");
                    compensationFailure.addSuppressed(failure);
                    throw compensationFailure;
                }
                throw failure;
            }
        }
    }
}
