package org.civiceconomy.integration.lightmanscurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.ExternalPayment;
import org.civiceconomy.fiscal.MoneyAmount;
import org.junit.jupiter.api.Test;

class LightmansCurrencyPaymentsTest {
    @Test
    void replayingTheSameTransactionUuidMovesPlayerBankMoneyOnce() {
        UUID source = UUID.fromString("ecf250e1-9c67-4c75-a59f-ecf9d00d3902");
        UUID recipient = UUID.fromString("87dd46d0-c72b-4b4a-9204-d340f68c971e");
        FakeBankAccounts accounts = new FakeBankAccounts();
        AccountId sourceAccount = new AccountId("player:" + source);
        AccountId recipientAccount = new AccountId("player:" + recipient);
        accounts.balances.put(sourceAccount, MoneyAmount.ofMinorUnits(1_000));
        accounts.balances.put(recipientAccount, MoneyAmount.ofMinorUnits(25));
        LightmansCurrencyPayments payments = new LightmansCurrencyPayments(accounts);
        ExternalPayment payment = new ExternalPayment(
                UUID.fromString("cb778504-ac08-42f4-b532-8ba8c8d1c542"),
                sourceAccount,
                recipientAccount,
                MoneyAmount.ofMinorUnits(300));

        payments.apply(payment);
        payments.apply(payment);

        assertEquals(MoneyAmount.ofMinorUnits(700), accounts.balances.get(sourceAccount));
        assertEquals(MoneyAmount.ofMinorUnits(325), accounts.balances.get(recipientAccount));
        assertEquals(Set.of(payment.transactionId()), accounts.appliedTransactions);
    }

    @Test
    void anInsufficientWithdrawalIsRestoredAndNeverMarkedApplied() {
        UUID source = UUID.fromString("ecf250e1-9c67-4c75-a59f-ecf9d00d3902");
        UUID recipient = UUID.fromString("87dd46d0-c72b-4b4a-9204-d340f68c971e");
        FakeBankAccounts accounts = new FakeBankAccounts();
        AccountId sourceAccount = new AccountId("player:" + source);
        AccountId recipientAccount = new AccountId("player:" + recipient);
        accounts.balances.put(sourceAccount, MoneyAmount.ofMinorUnits(200));
        accounts.balances.put(recipientAccount, MoneyAmount.ofMinorUnits(25));
        LightmansCurrencyPayments payments = new LightmansCurrencyPayments(accounts);
        ExternalPayment payment = new ExternalPayment(
                UUID.fromString("cb778504-ac08-42f4-b532-8ba8c8d1c542"),
                sourceAccount,
                recipientAccount,
                MoneyAmount.ofMinorUnits(300));

        assertThrows(InsufficientLightmansCurrencyBalanceException.class, () -> payments.apply(payment));

        assertEquals(MoneyAmount.ofMinorUnits(200), accounts.balances.get(sourceAccount));
        assertEquals(MoneyAmount.ofMinorUnits(25), accounts.balances.get(recipientAccount));
        assertEquals(Set.of(), accounts.appliedTransactions);
    }

    private static final class FakeBankAccounts implements LightmansCurrencyBankAccounts {
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
