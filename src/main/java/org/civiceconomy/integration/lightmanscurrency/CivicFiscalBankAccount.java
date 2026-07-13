package org.civiceconomy.integration.lightmanscurrency;

import io.github.lightman314.lightmanscurrency.api.money.bank.salary.SalaryData;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue;
import io.github.lightman314.lightmanscurrency.common.bank.BankAccount;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

final class CivicFiscalBankAccount extends BankAccount {
    CivicFiscalBankAccount(Runnable markDirty) {
        super(markDirty);
    }

    CivicFiscalBankAccount(Runnable markDirty, CompoundTag tag, HolderLookup.Provider registries) {
        super(markDirty, tag, registries);
    }

    @Override
    public SalaryData createNewSalary() {
        return null;
    }

    @Override
    public List<SalaryData> getSalaries() {
        return List.of();
    }

    @Override
    public void deleteSalary(SalaryData salary) {}

    @Override
    public void applyInterest(
            double interestMultiplier,
            List<MoneyValue> limits,
            List<String> blacklist,
            boolean forceInterest,
            boolean notifyPlayers) {}

    @Override
    public void tick() {}
}
