package org.civiceconomy.integration.lightmanscurrency;

import io.github.lightman314.lightmanscurrency.api.money.bank.IBankAccount;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;
import org.civiceconomy.fiscal.AccountId;

final class CivicFiscalAccountData extends SavedData {
    static final String DATA_NAME = "civiceconomy_fiscal_accounts";
    static final Factory<CivicFiscalAccountData> FACTORY =
            new Factory<>(CivicFiscalAccountData::new, CivicFiscalAccountData::load);

    private final Map<AccountId, StoredAccount> accounts = new LinkedHashMap<>();
    private final Set<UUID> appliedPermanentDestructions = new HashSet<>();
    private final Set<UUID> appliedMintIssuances = new HashSet<>();

    private CivicFiscalAccountData() {}

    static CivicFiscalAccountData load(CompoundTag root, HolderLookup.Provider registries) {
        CivicFiscalAccountData data = new CivicFiscalAccountData();
        ListTag savedAccounts = root.getList("Accounts", Tag.TAG_COMPOUND);
        for (int index = 0; index < savedAccounts.size(); index++) {
            CompoundTag saved = savedAccounts.getCompound(index);
            AccountId accountId = new AccountId(saved.getString("AccountId"));
            FiscalAccountKind kind = FiscalAccountKind.valueOf(saved.getString("Kind"));
            kind.requireMatches(accountId);
            CivicFiscalBankAccount account =
                    new CivicFiscalBankAccount(data::setDirty, saved.getCompound("BankAccount"), registries);
            if (data.accounts.putIfAbsent(accountId, new StoredAccount(kind, account)) != null) {
                throw new IllegalStateException("Duplicate Civic fiscal account " + accountId.value());
            }
        }
        ListTag savedDestructions = root.getList("AppliedPermanentDestructions", Tag.TAG_STRING);
        for (int index = 0; index < savedDestructions.size(); index++) {
            data.appliedPermanentDestructions.add(
                    UUID.fromString(savedDestructions.getString(index)));
        }
        ListTag savedIssuances = root.getList("AppliedMintIssuances", Tag.TAG_STRING);
        for (int index = 0; index < savedIssuances.size(); index++) {
            data.appliedMintIssuances.add(UUID.fromString(savedIssuances.getString(index)));
        }
        return data;
    }

    void create(AccountId accountId, FiscalAccountKind kind, String displayName) {
        kind.requireMatches(accountId);
        StoredAccount existing = accounts.get(accountId);
        if (existing != null) {
            if (existing.kind() != kind || !existing.account().getOwnersName().equals(displayName)) {
                throw new IllegalStateException("Conflicting Civic fiscal account definition " + accountId.value());
            }
            return;
        }
        CivicFiscalBankAccount account = new CivicFiscalBankAccount(this::setDirty);
        account.updateOwnersName(displayName);
        accounts.put(accountId, new StoredAccount(kind, account));
        setDirty();
    }

    IBankAccount account(AccountId accountId) {
        StoredAccount stored = accounts.get(accountId);
        return stored == null ? null : stored.account();
    }

    List<AccountId> accountIds() {
        return List.copyOf(accounts.keySet());
    }

    boolean wasPermanentDestructionApplied(UUID destructionId) {
        return appliedPermanentDestructions.contains(destructionId);
    }

    void recordPermanentDestructionApplied(UUID destructionId) {
        if (appliedPermanentDestructions.add(destructionId)) {
            setDirty();
        }
    }

    boolean wasMintIssuanceApplied(UUID issuanceId) {
        return appliedMintIssuances.contains(issuanceId);
    }

    void recordMintIssuanceApplied(UUID issuanceId) {
        if (appliedMintIssuances.add(issuanceId)) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        ListTag savedAccounts = new ListTag();
        accounts.forEach((accountId, stored) -> {
            CompoundTag saved = new CompoundTag();
            saved.putString("AccountId", accountId.value());
            saved.putString("Kind", stored.kind().name());
            saved.put("BankAccount", stored.account().save(registries));
            savedAccounts.add(saved);
        });
        root.put("Accounts", savedAccounts);
        ListTag savedDestructions = new ListTag();
        appliedPermanentDestructions.stream()
                .map(UUID::toString)
                .sorted()
                .map(net.minecraft.nbt.StringTag::valueOf)
                .forEach(savedDestructions::add);
        root.put("AppliedPermanentDestructions", savedDestructions);
        ListTag savedIssuances = new ListTag();
        appliedMintIssuances.stream()
                .map(UUID::toString)
                .sorted()
                .map(net.minecraft.nbt.StringTag::valueOf)
                .forEach(savedIssuances::add);
        root.put("AppliedMintIssuances", savedIssuances);
        return root;
    }

    private record StoredAccount(FiscalAccountKind kind, CivicFiscalBankAccount account) {}
}
