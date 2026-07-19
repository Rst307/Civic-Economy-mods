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
import org.civiceconomy.mint.ExternalMintIssuance;
import org.junit.jupiter.api.Test;

class LightmansCurrencyMintIssuancesTest {
    @Test
    void replayingTheSameIssuanceUuidDepositsIntoTheTreasuryOnce() {
        FakeIssuanceAccounts accounts = new FakeIssuanceAccounts();
        AccountId treasury = new AccountId("nation:aurora:treasury");
        ExternalMintIssuance issuance = new ExternalMintIssuance(
                UUID.fromString("6844d49f-e3f6-49a2-844a-d763fdb4036e"),
                treasury,
                MoneyAmount.ofMinorUnits(300L));
        LightmansCurrencyMintIssuances issuances =
                new LightmansCurrencyMintIssuances(accounts);

        issuances.apply(issuance);
        issuances.apply(issuance);

        assertEquals(MoneyAmount.ofMinorUnits(300L), accounts.balances.get(treasury));
        assertEquals(Set.of(issuance.issuanceId()), accounts.appliedIssuances);
    }

    @Test
    void markerFailureWithdrawsTheDepositAndLeavesTheIssuanceRetryable() {
        FakeIssuanceAccounts accounts = new FakeIssuanceAccounts();
        accounts.failNextMarker = true;
        AccountId treasury = new AccountId("nation:aurora:treasury");
        ExternalMintIssuance issuance = new ExternalMintIssuance(
                UUID.fromString("eb8253fc-b54a-4e62-838d-c9b18c19457e"),
                treasury,
                MoneyAmount.ofMinorUnits(300L));
        LightmansCurrencyMintIssuances issuances =
                new LightmansCurrencyMintIssuances(accounts);

        assertThrows(IllegalStateException.class, () -> issuances.apply(issuance));

        assertEquals(MoneyAmount.ZERO, accounts.balances.get(treasury));
        assertEquals(Set.of(), accounts.appliedIssuances);

        issuances.apply(issuance);
        assertEquals(MoneyAmount.ofMinorUnits(300L), accounts.balances.get(treasury));
    }

    private static final class FakeIssuanceAccounts
            implements LightmansCurrencyMintIssuanceAccounts {
        private final Map<AccountId, MoneyAmount> balances = new HashMap<>();
        private final Set<UUID> appliedIssuances = new HashSet<>();
        private boolean failNextMarker;

        @Override
        public Object transactionLock() {
            return this;
        }

        @Override
        public boolean wasApplied(UUID issuanceId) {
            return appliedIssuances.contains(issuanceId);
        }

        @Override
        public void deposit(AccountId accountId, MoneyAmount amount) {
            balances.merge(accountId, amount, MoneyAmount::plus);
        }

        public MoneyAmount withdraw(AccountId accountId, MoneyAmount amount) {
            balances.compute(accountId, (ignored, balance) -> balance.minus(amount));
            return amount;
        }

        @Override
        public void recordApplied(UUID issuanceId) {
            if (failNextMarker) {
                failNextMarker = false;
                throw new IllegalStateException("simulated marker failure");
            }
            appliedIssuances.add(issuanceId);
        }
    }
}
