package org.civiceconomy.mixin.lightmanscurrency;

import io.github.lightman314.lightmanscurrency.common.data.types.BankDataCache;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.civiceconomy.integration.lightmanscurrency.CivicBankDataTransactions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BankDataCache.class, remap = false)
public abstract class BankDataCacheMixin implements CivicBankDataTransactions {
    @Unique
    private static final String CIVIC_APPLIED_TRANSFERS_KEY = "civiceconomy:applied_player_transfers";

    @Unique
    private final Set<UUID> civicEconomy$appliedTransfers = new HashSet<>();

    @Override
    public synchronized boolean civicEconomy$wasApplied(UUID transactionId) {
        return civicEconomy$appliedTransfers.contains(transactionId);
    }

    @Override
    public synchronized void civicEconomy$recordApplied(UUID transactionId) {
        if (civicEconomy$appliedTransfers.add(transactionId)) {
            ((BankDataCache) (Object) this).setChanged();
        }
    }

    @Inject(method = "save", at = @At("TAIL"), remap = false)
    private void civicEconomy$saveAppliedTransfers(
            CompoundTag tag, HolderLookup.Provider registries, CallbackInfo callback) {
        ListTag transfers = new ListTag();
        civicEconomy$appliedTransfers.stream()
                .map(UUID::toString)
                .sorted()
                .map(StringTag::valueOf)
                .forEach(transfers::add);
        tag.put(CIVIC_APPLIED_TRANSFERS_KEY, transfers);
    }

    @Inject(method = "load", at = @At("TAIL"), remap = false)
    private void civicEconomy$loadAppliedTransfers(
            CompoundTag tag, HolderLookup.Provider registries, CallbackInfo callback) {
        civicEconomy$appliedTransfers.clear();
        ListTag transfers = tag.getList(CIVIC_APPLIED_TRANSFERS_KEY, Tag.TAG_STRING);
        for (int index = 0; index < transfers.size(); index++) {
            civicEconomy$appliedTransfers.add(UUID.fromString(transfers.getString(index)));
        }
    }
}
