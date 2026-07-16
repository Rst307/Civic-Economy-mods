package org.civiceconomy.platform.neoforge;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.civiceconomy.fiscal.FiscalBill;
import org.slf4j.Logger;

final class FiscalBillCommands {
    private static final Logger LOGGER = LogUtils.getLogger();

    private FiscalBillCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("bill")
                .then(Commands.literal("list")
                        .executes(context -> listForPayer(context.getSource())))
                .then(Commands.literal("status")
                        .then(Commands.argument("billId", UuidArgument.uuid())
                                .executes(context -> statusForPayer(
                                        context.getSource(),
                                        UuidArgument.getUuid(context, "billId")))));
    }

    private static int listForPayer(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CivicServerRuntime.current()
                .payerFiscalBills(player)
                .whenComplete((bills, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Fiscal Bill payer list", failure);
                    } else {
                        String result = bills.isEmpty()
                                ? "No Fiscal Bills are addressed to your player account"
                                : bills.stream()
                                        .limit(20)
                                        .map(FiscalBillCommands::formatFiscalBill)
                                        .collect(Collectors.joining("; "));
                        source.sendSuccess(() -> Component.literal(result), false);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Fiscal Bill payer list queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int statusForPayer(CommandSourceStack source, UUID billId)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CivicServerRuntime.current()
                .payerFiscalBill(player, billId)
                .whenComplete((bill, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Fiscal Bill payer status", failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(formatFiscalBill(bill)), false);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Fiscal Bill payer status queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    static String formatFiscalBill(FiscalBill bill) {
        return "Bill " + bill.billId()
                + " state=" + bill.state()
                + " kind=" + bill.kind()
                + " payer=" + bill.payerAccount().value()
                + " beneficiary=" + bill.beneficiaryAccount().value()
                + " amountMinorUnits=" + bill.amount().minorUnits()
                + " settledMinorUnits=" + bill.settledAmount().minorUnits()
                + " remainingMinorUnits=" + bill.remainingAmount().minorUnits()
                + " due=" + bill.dueAt()
                + " escrow=" + bill.escrowId().map(UUID::toString).orElse("none")
                + " purpose=" + bill.purpose();
    }

    private static void reportFailure(
            CommandSourceStack source, String operation, Throwable failure) {
        Throwable cause = failure instanceof CompletionException
                && failure.getCause() != null
                ? failure.getCause()
                : failure;
        LOGGER.error("{} command failed", operation, cause);
        source.sendFailure(Component.literal(operation + " failed: " + cause.getMessage()));
    }
}
