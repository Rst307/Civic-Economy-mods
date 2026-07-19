package org.civiceconomy.integration.lightmanscurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.ExternalTreasuryWithdrawal;
import org.civiceconomy.fiscal.MoneyAmount;
import org.junit.jupiter.api.Test;

class LightmansCurrencyTreasuryWithdrawalsTest {
    private static final UUID WITHDRAWAL_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final AccountId TREASURY =
            new AccountId("nation:33333333-3333-3333-3333-333333333333:treasury");
    private static final ExternalTreasuryWithdrawal WITHDRAWAL =
            new ExternalTreasuryWithdrawal(
                    WITHDRAWAL_ID, TREASURY, PLAYER_ID, MoneyAmount.ofMinorUnits(600L));

    @Test
    void insufficientCashCapacityFailsBeforeTreasuryDebit() {
        FakeAccounts accounts = new FakeAccounts();
        accounts.hasCapacity = false;
        LightmansCurrencyTreasuryWithdrawals withdrawals =
                new LightmansCurrencyTreasuryWithdrawals(accounts);

        assertThrows(IllegalStateException.class, () -> withdrawals.apply(WITHDRAWAL));

        assertEquals(0, accounts.debitCalls);
        assertEquals(0, accounts.deliveryCalls);
    }

    @Test
    void retryAfterTreasuryDebitOnlyCompletesTheMissingCashDelivery() {
        FakeAccounts accounts = new FakeAccounts();
        accounts.failFirstDelivery = true;
        LightmansCurrencyTreasuryWithdrawals withdrawals =
                new LightmansCurrencyTreasuryWithdrawals(accounts);

        assertThrows(IllegalStateException.class, () -> withdrawals.apply(WITHDRAWAL));
        assertEquals(1, accounts.debitCalls);
        assertEquals(1, accounts.deliveryCalls);

        withdrawals.apply(WITHDRAWAL);
        withdrawals.apply(WITHDRAWAL);

        assertEquals(1, accounts.debitCalls);
        assertEquals(2, accounts.deliveryCalls);
    }

    private static final class FakeAccounts
            implements LightmansCurrencyTreasuryWithdrawalAccounts {
        private final Object lock = new Object();
        private boolean hasCapacity = true;
        private boolean debitApplied;
        private boolean cashDelivered;
        private boolean failFirstDelivery;
        private int debitCalls;
        private int deliveryCalls;

        @Override
        public Object transactionLock() {
            return lock;
        }

        @Override
        public boolean wasTreasuryDebitApplied(UUID withdrawalId) {
            return debitApplied;
        }

        @Override
        public boolean wasCashDelivered(UUID withdrawalId, UUID playerId) {
            return cashDelivered;
        }

        @Override
        public void requireCashCapacity(UUID playerId, MoneyAmount amount) {
            if (!hasCapacity) {
                throw new IllegalStateException("insufficient inventory capacity");
            }
        }

        @Override
        public void debitTreasury(
                UUID withdrawalId, AccountId sourceAccount, MoneyAmount amount) {
            debitCalls++;
            debitApplied = true;
        }

        @Override
        public void deliverCash(
                UUID withdrawalId, UUID playerId, MoneyAmount amount) {
            deliveryCalls++;
            if (failFirstDelivery) {
                failFirstDelivery = false;
                throw new IllegalStateException("simulated delivery failure");
            }
            cashDelivered = true;
        }
    }
}
