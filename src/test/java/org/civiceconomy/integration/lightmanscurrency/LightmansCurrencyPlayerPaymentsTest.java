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

class LightmansCurrencyPlayerPaymentsTest {
    @Test
    void replayingTheSameTransactionUuidMovesPlayerBankMoneyOnce() {
        UUID source = UUID.fromString("ecf250e1-9c67-4c75-a59f-ecf9d00d3902");
        UUID recipient = UUID.fromString("87dd46d0-c72b-4b4a-9204-d340f68c971e");
        FakePlayerBankAccounts accounts = new FakePlayerBankAccounts();
        accounts.balances.put(source, MoneyAmount.ofMinorUnits(1_000));
        accounts.balances.put(recipient, MoneyAmount.ofMinorUnits(25));
        LightmansCurrencyPlayerPayments payments = new LightmansCurrencyPlayerPayments(accounts);
        ExternalPayment payment = new ExternalPayment(
                UUID.fromString("cb778504-ac08-42f4-b532-8ba8c8d1c542"),
                new AccountId("player:" + source),
                new AccountId("player:" + recipient),
                MoneyAmount.ofMinorUnits(300));

        payments.apply(payment);
        payments.apply(payment);

        assertEquals(MoneyAmount.ofMinorUnits(700), accounts.balances.get(source));
        assertEquals(MoneyAmount.ofMinorUnits(325), accounts.balances.get(recipient));
        assertEquals(Set.of(payment.transactionId()), accounts.appliedTransactions);
    }

    @Test
    void anInsufficientWithdrawalIsRestoredAndNeverMarkedApplied() {
        UUID source = UUID.fromString("ecf250e1-9c67-4c75-a59f-ecf9d00d3902");
        UUID recipient = UUID.fromString("87dd46d0-c72b-4b4a-9204-d340f68c971e");
        FakePlayerBankAccounts accounts = new FakePlayerBankAccounts();
        accounts.balances.put(source, MoneyAmount.ofMinorUnits(200));
        accounts.balances.put(recipient, MoneyAmount.ofMinorUnits(25));
        LightmansCurrencyPlayerPayments payments = new LightmansCurrencyPlayerPayments(accounts);
        ExternalPayment payment = new ExternalPayment(
                UUID.fromString("cb778504-ac08-42f4-b532-8ba8c8d1c542"),
                new AccountId("player:" + source),
                new AccountId("player:" + recipient),
                MoneyAmount.ofMinorUnits(300));

        assertThrows(InsufficientLightmansCurrencyBalanceException.class, () -> payments.apply(payment));

        assertEquals(MoneyAmount.ofMinorUnits(200), accounts.balances.get(source));
        assertEquals(MoneyAmount.ofMinorUnits(25), accounts.balances.get(recipient));
        assertEquals(Set.of(), accounts.appliedTransactions);
    }

    private static final class FakePlayerBankAccounts implements PlayerBankAccounts {
        private final Map<UUID, MoneyAmount> balances = new HashMap<>();
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
        public MoneyAmount withdraw(UUID playerId, MoneyAmount requested) {
            MoneyAmount balance = balances.getOrDefault(playerId, MoneyAmount.ZERO);
            MoneyAmount withdrawn = balance.compareTo(requested) >= 0 ? requested : balance;
            balances.put(playerId, balance.minus(withdrawn));
            return withdrawn;
        }

        @Override
        public void deposit(UUID playerId, MoneyAmount amount) {
            balances.merge(playerId, amount, MoneyAmount::plus);
        }

        @Override
        public void recordApplied(UUID transactionId) {
            appliedTransactions.add(transactionId);
        }
    }
}
