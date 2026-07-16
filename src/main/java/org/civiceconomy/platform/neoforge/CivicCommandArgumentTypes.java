package org.civiceconomy.platform.neoforge;

import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.civiceconomy.CivicEconomy;

public final class CivicCommandArgumentTypes {
    private static final DeferredRegister<ArgumentTypeInfo<?, ?>> TYPES =
            DeferredRegister.create(
                    Registries.COMMAND_ARGUMENT_TYPE,
                    CivicEconomy.MOD_ID);

    static {
        TYPES.register(
                "withdrawal_approval_tiers",
                () -> ArgumentTypeInfos.registerByClass(
                        WithdrawalApprovalTierArgumentType.class,
                        SingletonArgumentInfo.contextFree(
                                WithdrawalApprovalTierArgumentType::tiers)));
        TYPES.register(
                "budget_disbursement_approval_tiers",
                () -> ArgumentTypeInfos.registerByClass(
                        BudgetDisbursementApprovalTierArgumentType.class,
                        SingletonArgumentInfo.contextFree(
                                BudgetDisbursementApprovalTierArgumentType::tiers)));
    }

    private CivicCommandArgumentTypes() {}

    public static void register(IEventBus modEventBus) {
        TYPES.register(modEventBus);
    }
}
