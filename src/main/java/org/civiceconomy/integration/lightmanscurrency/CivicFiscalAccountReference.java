package org.civiceconomy.integration.lightmanscurrency;

import io.github.lightman314.lightmanscurrency.api.misc.icons.IconData;
import io.github.lightman314.lightmanscurrency.api.misc.player.PlayerReference;
import io.github.lightman314.lightmanscurrency.api.money.bank.IBankAccount;
import io.github.lightman314.lightmanscurrency.api.money.bank.reference.BankReference;
import io.github.lightman314.lightmanscurrency.api.money.bank.reference.BankReferenceType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.civiceconomy.CivicEconomy;
import org.civiceconomy.fiscal.AccountId;

final class CivicFiscalAccountReference extends BankReference {
    static final BankReferenceType TYPE = new Type();

    private final AccountId accountId;

    CivicFiscalAccountReference(AccountId accountId) {
        super(TYPE);
        this.accountId = accountId;
    }

    @Override
    public IBankAccount get() {
        LightmansCurrencyFiscalAccounts accounts = LightmansCurrencyFiscalAccounts.liveOrNull();
        return accounts == null ? null : accounts.accountOrNull(accountId);
    }

    @Override
    public boolean isSalaryTarget(PlayerReference player) {
        return false;
    }

    @Override
    public boolean allowedAccess(PlayerReference player) {
        return false;
    }

    @Override
    public boolean allowedAccess(Player player) {
        return false;
    }

    @Override
    public int salaryPermission(PlayerReference player) {
        return 0;
    }

    @Override
    public boolean canPersist(Player player) {
        return false;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        tag.putString("AccountId", accountId.value());
    }

    @Override
    protected void encodeAdditional(FriendlyByteBuf buffer) {
        buffer.writeUtf(accountId.value());
    }

    @Override
    public IconData getIcon() {
        return null;
    }

    private static final class Type extends BankReferenceType {
        private Type() {
            super(ResourceLocation.fromNamespaceAndPath(CivicEconomy.MOD_ID, "fiscal_account"));
        }

        @Override
        public BankReference load(CompoundTag tag) {
            return new CivicFiscalAccountReference(new AccountId(tag.getString("AccountId")));
        }

        @Override
        public BankReference decode(FriendlyByteBuf buffer) {
            return new CivicFiscalAccountReference(new AccountId(buffer.readUtf()));
        }
    }
}
