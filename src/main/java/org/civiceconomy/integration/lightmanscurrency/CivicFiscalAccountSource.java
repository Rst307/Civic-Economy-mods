package org.civiceconomy.integration.lightmanscurrency;

import io.github.lightman314.lightmanscurrency.api.money.bank.reference.BankReference;
import io.github.lightman314.lightmanscurrency.api.money.bank.source.BankAccountSource;
import java.util.List;

final class CivicFiscalAccountSource extends BankAccountSource {
    static final CivicFiscalAccountSource INSTANCE = new CivicFiscalAccountSource();

    private CivicFiscalAccountSource() {}

    @Override
    public List<BankReference> CollectAllReferences(boolean isClient) {
        if (isClient) {
            return List.of();
        }
        LightmansCurrencyFiscalAccounts accounts = LightmansCurrencyFiscalAccounts.liveOrNull();
        if (accounts == null) {
            return List.of();
        }
        return accounts.accountIds().stream()
                .map(CivicFiscalAccountReference::new)
                .map(reference -> reference.flagAsClient(false))
                .map(BankReference.class::cast)
                .toList();
    }
}
