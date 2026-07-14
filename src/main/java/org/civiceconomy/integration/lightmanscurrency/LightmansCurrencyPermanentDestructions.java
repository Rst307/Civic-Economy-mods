package org.civiceconomy.integration.lightmanscurrency;

import net.minecraft.server.level.ServerLevel;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.monetary.ExternalPermanentDestruction;
import org.civiceconomy.monetary.ExternalPermanentDestructions;

public final class LightmansCurrencyPermanentDestructions
        implements ExternalPermanentDestructions {
    private final LightmansCurrencyPermanentDestructionAccounts accounts;

    LightmansCurrencyPermanentDestructions(
            LightmansCurrencyPermanentDestructionAccounts accounts) {
        this.accounts = accounts;
    }

    public static LightmansCurrencyPermanentDestructions live(ServerLevel level) {
        return new LightmansCurrencyPermanentDestructions(
                new LiveLightmansCurrencyPermanentDestructionAccounts(
                        LightmansCurrencyFiscalAccounts.forLevel(level)));
    }

    @Override
    public void apply(ExternalPermanentDestruction destruction) {
        synchronized (accounts.transactionLock()) {
            if (accounts.wasApplied(destruction.destructionId())) {
                return;
            }
            MoneyAmount withdrawn = accounts.withdraw(
                    destruction.sourceAccount(), destruction.amount());
            if (!withdrawn.equals(destruction.amount())) {
                if (!withdrawn.equals(MoneyAmount.ZERO)) {
                    accounts.deposit(destruction.sourceAccount(), withdrawn);
                }
                throw new InsufficientLightmansCurrencyBalanceException(
                        destruction.sourceAccount(), destruction.amount(), withdrawn);
            }
            try {
                accounts.recordApplied(destruction.destructionId());
            } catch (RuntimeException failure) {
                accounts.deposit(destruction.sourceAccount(), withdrawn);
                throw failure;
            }
        }
    }
}
