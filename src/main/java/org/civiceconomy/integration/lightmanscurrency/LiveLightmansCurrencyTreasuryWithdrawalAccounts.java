package org.civiceconomy.integration.lightmanscurrency;

import com.mojang.datafixers.util.Pair;
import io.github.lightman314.lightmanscurrency.api.money.bank.BankAPI;
import io.github.lightman314.lightmanscurrency.api.money.coins.CoinAPI;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue;
import io.github.lightman314.lightmanscurrency.api.money.value.IItemBasedValue;
import io.github.lightman314.lightmanscurrency.api.money.value.builtin.CoinValue;
import io.github.lightman314.lightmanscurrency.util.InventoryUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;

final class LiveLightmansCurrencyTreasuryWithdrawalAccounts
        implements LightmansCurrencyTreasuryWithdrawalAccounts {
    private static final String CIVIC_PLAYER_DATA = "civiceconomy";
    private static final String CASH_DELIVERIES = "TreasuryWithdrawalCashDeliveries";

    private final LightmansCurrencyFiscalAccounts fiscalAccounts;
    private final MinecraftServer server;
    private final Function<UUID, ServerPlayer> players;

    LiveLightmansCurrencyTreasuryWithdrawalAccounts(
            LightmansCurrencyFiscalAccounts fiscalAccounts, MinecraftServer server) {
        this(fiscalAccounts, server, server.getPlayerList()::getPlayer);
    }

    LiveLightmansCurrencyTreasuryWithdrawalAccounts(
            LightmansCurrencyFiscalAccounts fiscalAccounts,
            MinecraftServer server,
            Function<UUID, ServerPlayer> players) {
        if (fiscalAccounts == null || server == null || players == null) {
            throw new IllegalArgumentException(
                    "Live Treasury Withdrawal dependencies cannot be null");
        }
        this.fiscalAccounts = fiscalAccounts;
        this.server = server;
        this.players = players;
    }

    @Override
    public Object transactionLock() {
        return fiscalAccounts.transactionLock();
    }

    @Override
    public boolean wasTreasuryDebitApplied(UUID withdrawalId) {
        requireServerThread();
        return fiscalAccounts.wasTreasuryWithdrawalDebitApplied(withdrawalId);
    }

    @Override
    public boolean wasCashDelivered(UUID withdrawalId, UUID playerId) {
        requireServerThread();
        return deliveredCashIds(requirePlayer(playerId)).contains(withdrawalId);
    }

    @Override
    public void requireCashCapacity(UUID playerId, MoneyAmount amount) {
        requireServerThread();
        ServerPlayer player = requirePlayer(playerId);
        Container simulated = InventoryUtil.copyInventory(player.getInventory());
        for (ItemStack stack : separatedCoinStacks(amount)) {
            if (!InventoryUtil.TryPutItemStack(simulated, stack.copy()).isEmpty()) {
                throw new InsufficientTreasuryWithdrawalInventoryCapacityException(
                        playerId, amount);
            }
        }
    }

    @Override
    public void debitTreasury(
            UUID withdrawalId, AccountId sourceAccount, MoneyAmount amount) {
        requireServerThread();
        if (fiscalAccounts.wasTreasuryWithdrawalDebitApplied(withdrawalId)) {
            return;
        }
        Pair<Boolean, MoneyValue> result = BankAPI.getApi()
                .BankWithdrawFromServer(fiscalAccounts.requireAccount(sourceAccount), value(amount));
        MoneyAmount withdrawn = result.getFirst()
                ? MoneyAmount.ofMinorUnits(result.getSecond().getCoreValue())
                : MoneyAmount.ZERO;
        if (!withdrawn.equals(amount)) {
            if (!withdrawn.equals(MoneyAmount.ZERO)) {
                deposit(sourceAccount, withdrawn);
            }
            throw new InsufficientLightmansCurrencyBalanceException(
                    sourceAccount, amount, withdrawn);
        }
        try {
            fiscalAccounts.recordTreasuryWithdrawalDebitApplied(withdrawalId);
        } catch (RuntimeException failure) {
            deposit(sourceAccount, withdrawn);
            throw failure;
        }
    }

    @Override
    public void deliverCash(UUID withdrawalId, UUID playerId, MoneyAmount amount) {
        requireServerThread();
        ServerPlayer player = requirePlayer(playerId);
        if (deliveredCashIds(player).contains(withdrawalId)) {
            return;
        }
        Inventory inventory = player.getInventory();
        List<ItemStack> snapshot = snapshot(inventory);
        for (ItemStack stack : separatedCoinStacks(amount)) {
            if (!InventoryUtil.TryPutItemStack(inventory, stack.copy()).isEmpty()) {
                restore(inventory, snapshot);
                throw new InsufficientTreasuryWithdrawalInventoryCapacityException(
                        playerId, amount);
            }
        }
        try {
            recordCashDelivered(player, withdrawalId);
        } catch (RuntimeException failure) {
            restore(inventory, snapshot);
            throw failure;
        }
    }

    private void deposit(AccountId accountId, MoneyAmount amount) {
        if (!BankAPI.getApi().BankDepositFromServer(
                fiscalAccounts.requireAccount(accountId), value(amount))) {
            throw new IllegalStateException(
                    "LC rejected Treasury Withdrawal rollback for " + accountId.value());
        }
    }

    private ServerPlayer requirePlayer(UUID playerId) {
        ServerPlayer player = players.apply(playerId);
        if (player == null) {
            throw new TreasuryWithdrawalPlayerOfflineException(playerId);
        }
        return player;
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                    "Treasury Withdrawal LC and player state must run on the server thread");
        }
    }

    private static List<UUID> deliveredCashIds(ServerPlayer player) {
        List<UUID> ids = new ArrayList<>();
        CompoundTag civic = player.getPersistentData().getCompound(CIVIC_PLAYER_DATA);
        ListTag saved = civic.getList(CASH_DELIVERIES, Tag.TAG_STRING);
        for (int index = 0; index < saved.size(); index++) {
            ids.add(UUID.fromString(saved.getString(index)));
        }
        return ids;
    }

    private static void recordCashDelivered(ServerPlayer player, UUID withdrawalId) {
        CompoundTag persistent = player.getPersistentData();
        CompoundTag civic = persistent.getCompound(CIVIC_PLAYER_DATA);
        ListTag saved = civic.getList(CASH_DELIVERIES, Tag.TAG_STRING);
        for (int index = 0; index < saved.size(); index++) {
            if (withdrawalId.toString().equals(saved.getString(index))) {
                return;
            }
        }
        saved.add(net.minecraft.nbt.StringTag.valueOf(withdrawalId.toString()));
        civic.put(CASH_DELIVERIES, saved);
        persistent.put(CIVIC_PLAYER_DATA, civic);
    }

    private static List<ItemStack> snapshot(Inventory inventory) {
        List<ItemStack> snapshot = new ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            snapshot.add(inventory.getItem(slot).copy());
        }
        return snapshot;
    }

    private static void restore(Inventory inventory, List<ItemStack> snapshot) {
        for (int slot = 0; slot < snapshot.size(); slot++) {
            inventory.setItem(slot, snapshot.get(slot));
        }
        inventory.setChanged();
    }

    private static MoneyValue value(MoneyAmount amount) {
        MoneyValue value = CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, amount.minorUnits());
        if (value.isEmpty()) {
            throw new IllegalStateException(
                    "LC main coin chain cannot represent "
                            + amount.minorUnits()
                            + " minor units");
        }
        return value;
    }

    private static List<ItemStack> separatedCoinStacks(MoneyAmount amount) {
        MoneyValue value = value(amount);
        if (!(value instanceof IItemBasedValue itemBasedValue)) {
            throw new IllegalStateException(
                    "LC main coin chain is not item-backed for Treasury Withdrawal");
        }
        return itemBasedValue.getAsSeperatedItemList();
    }
}
