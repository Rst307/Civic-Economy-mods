package org.civiceconomy.integration.lightmanscurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.monetary.ExternalPermanentDestruction;
import org.junit.jupiter.api.Test;

class LightmansCurrencyPermanentDestructionsTest {
    @Test
    void replayingTheSameDestructionUuidPermanentlyWithdrawsOnce() {
        FakeBankAccounts accounts = new FakeBankAccounts();
        AccountId treasury = new AccountId("nation:aurora:treasury");
        accounts.balances.put(treasury, MoneyAmount.ofMinorUnits(1_000L));
        ExternalPermanentDestruction destruction = new ExternalPermanentDestruction(
                UUID.fromString("39d709dc-2968-4121-bff5-6a1dcf004a87"),
                treasury,
                MoneyAmount.ofMinorUnits(600L));
        LightmansCurrencyPermanentDestructions destructions =
                new LightmansCurrencyPermanentDestructions(accounts);

        destructions.apply(destruction);
        destructions.apply(destruction);

        assertEquals(MoneyAmount.ofMinorUnits(400L), accounts.balances.get(treasury));
        assertEquals(Set.of(destruction.destructionId()), accounts.appliedTransactions);
    }

    @Test
    void anInsufficientDestructionRestoresTheWithdrawalAndIsNotMarkedApplied() {
        FakeBankAccounts accounts = new FakeBankAccounts();
        AccountId treasury = new AccountId("nation:aurora:treasury");
        accounts.balances.put(treasury, MoneyAmount.ofMinorUnits(500L));
        ExternalPermanentDestruction destruction = new ExternalPermanentDestruction(
                UUID.fromString("cd2d24d9-3a8e-4b8c-a96f-764bcc79261d"),
                treasury,
                MoneyAmount.ofMinorUnits(600L));
        LightmansCurrencyPermanentDestructions destructions =
                new LightmansCurrencyPermanentDestructions(accounts);

        assertThrows(
                InsufficientLightmansCurrencyBalanceException.class,
                () -> destructions.apply(destruction));

        assertEquals(MoneyAmount.ofMinorUnits(500L), accounts.balances.get(treasury));
        assertEquals(Set.of(), accounts.appliedTransactions);
    }

    private static final class FakeBankAccounts
            implements LightmansCurrencyPermanentDestructionAccounts {
        private final Map<AccountId, MoneyAmount> balances = new HashMap<>();
        private final Set<UUID> appliedTransactions = new HashSet<>();

        @Override
        public Object transactionLock() {
            return this;
        }

        @Override
        public boolean wasApplied(UUID transactionId) {
            return appliedTransactions.contains(transactionId);
        }

        @Override
        public MoneyAmount withdraw(AccountId accountId, MoneyAmount requested) {
            MoneyAmount balance = balances.getOrDefault(accountId, MoneyAmount.ZERO);
            MoneyAmount withdrawn = balance.compareTo(requested) >= 0 ? requested : balance;
            balances.put(accountId, balance.minus(withdrawn));
            return withdrawn;
        }

        @Override
        public void deposit(AccountId accountId, MoneyAmount amount) {
            balances.merge(accountId, amount, MoneyAmount::plus);
        }

        @Override
        public void recordApplied(UUID transactionId) {
            appliedTransactions.add(transactionId);
        }
    }
}
