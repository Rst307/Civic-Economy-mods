package org.civiceconomy.integration.lightmanscurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;
import org.junit.jupiter.api.Test;

class LightmansCurrencyAccountBalancesTest {
    @Test
    void playerAccountBalanceUsesExactLcMinorUnits() {
        UUID playerId = UUID.fromString("1d97ff65-7fba-401d-ab76-8b5d70d19558");
        LightmansCurrencyAccountBalances balances = new LightmansCurrencyAccountBalances(
                ignored -> MoneyAmount.ZERO,
                requestedPlayerId -> {
                    assertEquals(playerId, requestedPlayerId);
                    return Optional.of(MoneyAmount.ofMinorUnits(987));
                });

        assertEquals(
                MoneyAmount.ofMinorUnits(987),
                balances.balance(new AccountId("player:" + playerId)));
    }

    @Test
    void unknownPlayerAccountFailsClosedInsteadOfReadingAsZero() {
        UUID playerId = UUID.fromString("cc55d3f8-4587-4dcc-a948-8170d7b6c1a2");
        LightmansCurrencyAccountBalances balances = new LightmansCurrencyAccountBalances(
                ignored -> MoneyAmount.ZERO, ignored -> Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> balances.balance(new AccountId("player:" + playerId)));
    }

    @Test
    void malformedPlayerAndUnknownNonPlayerAccountsFailClosed() {
        LightmansCurrencyAccountBalances balances = new LightmansCurrencyAccountBalances(
                accountId -> {
                    throw new IllegalArgumentException("Unknown Civic fiscal account " + accountId.value());
                },
                ignored -> {
                    throw new AssertionError("Malformed account must fail before the LC lookup");
                });

        assertThrows(
                IllegalArgumentException.class,
                () -> balances.balance(new AccountId("player:not-a-uuid")));
        assertThrows(
                IllegalArgumentException.class,
                () -> balances.balance(new AccountId("external:unregistered")));
    }
}
