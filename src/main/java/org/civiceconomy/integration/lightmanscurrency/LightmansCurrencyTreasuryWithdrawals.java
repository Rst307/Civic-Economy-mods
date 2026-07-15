package org.civiceconomy.integration.lightmanscurrency;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;
import java.util.function.Function;
import org.civiceconomy.fiscal.ExternalTreasuryWithdrawal;

final class LightmansCurrencyTreasuryWithdrawals implements ExternalTreasuryWithdrawals {
    private final LightmansCurrencyTreasuryWithdrawalAccounts accounts;

    LightmansCurrencyTreasuryWithdrawals(
            LightmansCurrencyTreasuryWithdrawalAccounts accounts) {
        if (accounts == null) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal accounts cannot be null");
        }
        this.accounts = accounts;
    }

    static LightmansCurrencyTreasuryWithdrawals live(ServerLevel level) {
        MinecraftServer server = level.getServer();
        return new LightmansCurrencyTreasuryWithdrawals(
                new LiveLightmansCurrencyTreasuryWithdrawalAccounts(
                        LightmansCurrencyFiscalAccounts.forLevel(level), server));
    }

    static LightmansCurrencyTreasuryWithdrawals live(
            ServerLevel level, Function<UUID, ServerPlayer> players) {
        MinecraftServer server = level.getServer();
        return new LightmansCurrencyTreasuryWithdrawals(
                new LiveLightmansCurrencyTreasuryWithdrawalAccounts(
                        LightmansCurrencyFiscalAccounts.forLevel(level), server, players));
    }

    @Override
    public void apply(ExternalTreasuryWithdrawal withdrawal) {
        synchronized (accounts.transactionLock()) {
            boolean cashDelivered = accounts.wasCashDelivered(
                    withdrawal.withdrawalId(), withdrawal.actorPlayerId());
            if (!cashDelivered) {
                accounts.requireCashCapacity(
                        withdrawal.actorPlayerId(), withdrawal.amount());
            }
            if (!accounts.wasTreasuryDebitApplied(withdrawal.withdrawalId())) {
                accounts.debitTreasury(
                        withdrawal.withdrawalId(),
                        withdrawal.sourceAccount(),
                        withdrawal.amount());
            }
            if (!cashDelivered) {
                accounts.deliverCash(
                        withdrawal.withdrawalId(),
                        withdrawal.actorPlayerId(),
                        withdrawal.amount());
            }
        }
    }
}
