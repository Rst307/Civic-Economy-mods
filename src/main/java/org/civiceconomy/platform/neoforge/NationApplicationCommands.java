package org.civiceconomy.platform.neoforge;

import com.mojang.logging.LogUtils;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.FiscalBillKind;
import org.civiceconomy.fiscal.TreasuryWithdrawalApprovalStatus;
import org.civiceconomy.fiscal.WithdrawalApprovalPolicyVersion;
import org.civiceconomy.integration.ftb.FtbChunksAdapter;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import org.civiceconomy.integration.ftb.FtbNationTeamDirectory;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyNationalTreasuryProvisioner;
import org.civiceconomy.nation.ActivateNationApplication;
import org.civiceconomy.nation.ActivatedNation;
import org.civiceconomy.nation.Capital;
import org.civiceconomy.nation.CancelNationApplication;
import org.civiceconomy.nation.CitizenshipCorrectionGraceRegistry;
import org.civiceconomy.nation.CitizenshipRegistry;
import org.civiceconomy.nation.CreateNationApplication;
import org.civiceconomy.nation.NationApplication;
import org.civiceconomy.nation.NationApplicationRegistry;
import org.civiceconomy.nation.NationActivationCoordinator;
import org.civiceconomy.nation.NationFoundingPolicy;
import org.civiceconomy.nation.NationEffectiveCitizenPopulation;
import org.civiceconomy.nation.GrantNationFiscalPermission;
import org.civiceconomy.nation.FtbTeamsNationProvider;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationFiscalPermissionGrant;
import org.civiceconomy.nation.NationPopulationCalculator;
import org.civiceconomy.nation.NationRegistry;
import org.civiceconomy.nation.RevokeNationFiscalPermission;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.territory.TerritoryFreeAllocation;
import org.civiceconomy.territory.TerritoryFreeAllocationPolicyRegistry;
import org.civiceconomy.territory.TerritoryFreeAllocationPolicyVersion;
import org.civiceconomy.nation.NationalTreasuryProvisioner;
import org.civiceconomy.nation.OnlineTimeLedger;
import org.civiceconomy.nation.NationTeam;
import org.civiceconomy.nation.NationTeamDirectory;
import org.slf4j.Logger;

final class NationApplicationCommands {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Duration APPLICATION_LIFETIME = Duration.ofDays(7);
    private static final Duration EVIDENCE_WINDOW = Duration.ofDays(60);
    private static final Duration FULL_EFFECTIVE_CITIZEN_TIME = Duration.ofHours(8);
    private static final Duration CITIZENSHIP_TRANSFER_COOLDOWN = Duration.ofDays(7);
    private static final ServiceIdentity FOUNDING_SERVICE =
            new ServiceIdentity("civiceconomy-founding");
    private static final ServiceIdentity GOVERNANCE_SERVICE =
            new ServiceIdentity("civiceconomy-governance");

    private NationApplicationCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("nation")
                .then(Commands.literal("apply")
                        .executes(context -> apply(context.getSource())))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource())))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(context -> cancel(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "reason")))))
                .then(Commands.literal("population")
                        .executes(context -> population(context.getSource())))
                .then(roleCommand())
                .then(billCommand())
                .then(mintCommand())
                .then(territoryCommand())
                .then(treasuryCommand())
                .then(Commands.literal("activate")
                        .executes(context -> activate(context.getSource())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> billCommand() {
        var issue = Commands.literal("issue")
                .then(Commands.argument("requestId", StringArgumentType.word())
                        .then(Commands.argument("payerUuid", UuidArgument.uuid())
                                .then(Commands.argument(
                                                "amountMinorUnits",
                                                LongArgumentType.longArg(1L))
                                        .then(Commands.argument(
                                                        "kind",
                                                        StringArgumentType.word())
                                                .then(Commands.argument(
                                                                "dueAtEpochMillis",
                                                                LongArgumentType.longArg(0L))
                                                        .then(Commands.argument(
                                                                        "purpose",
                                                                        StringArgumentType.greedyString())
                                                                .executes(context -> issueFiscalBill(
                                                                        context.getSource(),
                                                                        StringArgumentType.getString(context, "requestId"),
                                                                        UuidArgument.getUuid(context, "payerUuid"),
                                                                        LongArgumentType.getLong(context, "amountMinorUnits"),
                                                                        StringArgumentType.getString(context, "kind"),
                                                                        LongArgumentType.getLong(context, "dueAtEpochMillis"),
                                                                        StringArgumentType.getString(context, "purpose")))))))));
        return Commands.literal("bill")
                .then(issue)
                .then(Commands.literal("list")
                        .executes(context -> listNationFiscalBills(context.getSource())))
                .then(Commands.literal("status")
                        .then(Commands.argument("billId", UuidArgument.uuid())
                                .executes(context -> nationFiscalBillStatus(
                                        context.getSource(),
                                        UuidArgument.getUuid(context, "billId")))));
    }

    private static int issueFiscalBill(
            CommandSourceStack source,
            String requestId,
            UUID payerPlayerId,
            long amountMinorUnits,
            String kindName,
            long dueAtEpochMillis,
            String purpose)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        FiscalBillKind kind = FiscalBillKind.valueOf(kindName.toUpperCase(Locale.ROOT));
        CivicServerRuntime.current()
                .issueNationalFiscalBill(
                        player,
                        requestId,
                        payerPlayerId,
                        amountMinorUnits,
                        kind,
                        dueAtEpochMillis,
                        purpose)
                .whenComplete((bill, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Fiscal Bill issuance", failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Issued Fiscal Bill " + bill.billId()
                                                + " payer=" + bill.payerAccount().value()
                                                + " beneficiary="
                                                + bill.beneficiaryAccount().value()
                                                + " amountMinorUnits="
                                                + bill.amount().minorUnits()
                                                + " kind=" + bill.kind()
                                                + " due=" + bill.dueAt()),
                                false);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Fiscal Bill issuance queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int listNationFiscalBills(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CivicServerRuntime.current()
                .nationFiscalBills(player)
                .whenComplete((bills, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Nation Fiscal Bill list", failure);
                    } else {
                        String result = bills.isEmpty()
                                ? "No Fiscal Bills benefit your current National Treasury"
                                : bills.stream()
                                        .limit(20)
                                        .map(FiscalBillCommands::formatFiscalBill)
                                        .collect(Collectors.joining("; "));
                        source.sendSuccess(() -> Component.literal(result), false);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Nation Fiscal Bill list queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int nationFiscalBillStatus(CommandSourceStack source, UUID billId)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CivicServerRuntime.current()
                .nationFiscalBill(player, billId)
                .whenComplete((bill, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Nation Fiscal Bill status", failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(
                                        FiscalBillCommands.formatFiscalBill(bill)),
                                false);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Nation Fiscal Bill status queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> treasuryCommand() {
        return Commands.literal("treasury")
                .then(withdrawCommand())
                .then(Commands.literal("destroy")
                        .then(Commands.argument("requestId", StringArgumentType.word())
                                .then(Commands.argument(
                                                "amountMinorUnits",
                                                LongArgumentType.longArg(1L))
                                        .then(Commands.argument(
                                                        "reason",
                                                        StringArgumentType.greedyString())
                                                .executes(context -> destroyTreasury(
                                                        context.getSource(),
                                                        StringArgumentType.getString(
                                                                context, "requestId"),
                                                        LongArgumentType.getLong(
                                                                context, "amountMinorUnits"),
                                                        StringArgumentType.getString(
                                                                context, "reason")))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> withdrawCommand() {
        return Commands.literal("withdraw")
                .then(withdrawApprovalCommand())
                .then(withdrawApprovalInspectionCommand())
                .then(withdrawPolicyCommand())
                .then(Commands.argument("requestId", StringArgumentType.word())
                        .then(Commands.argument(
                                        "amountMinorUnits",
                                        LongArgumentType.longArg(1L))
                                .then(Commands.argument(
                                                "reason",
                                                StringArgumentType.greedyString())
                                        .executes(context -> withdrawTreasury(
                                                context.getSource(),
                                                StringArgumentType.getString(
                                                        context, "requestId"),
                                                LongArgumentType.getLong(
                                                        context, "amountMinorUnits"),
                                                StringArgumentType.getString(
                                                        context, "reason"))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> withdrawApprovalCommand() {
        return Commands.literal("approve")
                .then(Commands.argument("approvalRequestId", UuidArgument.uuid())
                        .then(Commands.argument("requestId", StringArgumentType.word())
                                .then(Commands.argument(
                                                "reason",
                                                StringArgumentType.greedyString())
                                        .executes(context -> approveTreasuryWithdrawal(
                                                context.getSource(),
                                                UuidArgument.getUuid(
                                                        context, "approvalRequestId"),
                                                StringArgumentType.getString(
                                                        context, "requestId"),
                                                StringArgumentType.getString(
                                                        context, "reason"))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack>
            withdrawApprovalInspectionCommand() {
        return Commands.literal("approval")
                .then(Commands.literal("list")
                        .executes(context -> listTreasuryWithdrawalApprovals(
                                context.getSource())))
                .then(Commands.literal("status")
                        .then(Commands.argument("approvalRequestId", UuidArgument.uuid())
                                .executes(context -> treasuryWithdrawalApprovalStatus(
                                        context.getSource(),
                                        UuidArgument.getUuid(
                                                context, "approvalRequestId")))))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("approvalRequestId", UuidArgument.uuid())
                                .then(Commands.argument("requestId", StringArgumentType.word())
                                        .then(Commands.argument(
                                                        "reason",
                                                        StringArgumentType.greedyString())
                                                .executes(context ->
                                                        cancelTreasuryWithdrawalApproval(
                                                                context.getSource(),
                                                                UuidArgument.getUuid(
                                                                        context,
                                                                        "approvalRequestId"),
                                                                StringArgumentType.getString(
                                                                        context,
                                                                        "requestId"),
                                                                StringArgumentType.getString(
                                                                        context,
                                                                        "reason")))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> withdrawPolicyCommand() {
        var reason = Commands.argument("reason", StringArgumentType.greedyString())
                .executes(context -> scheduleTreasuryWithdrawalPolicy(
                        context.getSource(),
                        StringArgumentType.getString(context, "requestId"),
                        LongArgumentType.getLong(context, "effectiveAtEpochMillis"),
                        LongArgumentType.getLong(context, "thresholdMinorUnits"),
                        IntegerArgumentType.getInteger(context, "requiredApprovals"),
                        StringArgumentType.getString(context, "reason")));
        var required = Commands.argument(
                        "requiredApprovals", IntegerArgumentType.integer(1, 16))
                .then(reason);
        var threshold = Commands.argument(
                        "thresholdMinorUnits", LongArgumentType.longArg(0L))
                .then(required);
        var effective = Commands.argument(
                        "effectiveAtEpochMillis", LongArgumentType.longArg(0L))
                .then(threshold);
        var request = Commands.argument("requestId", StringArgumentType.word())
                .then(effective);
        return Commands.literal("policy")
                .then(Commands.literal("status")
                        .executes(context -> treasuryWithdrawalPolicyStatus(
                                context.getSource())))
                .then(Commands.literal("schedule").then(request));
    }

    private static int treasuryWithdrawalPolicyStatus(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CivicServerRuntime.current()
                .withdrawalApprovalPolicy(player)
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Treasury Withdrawal approval policy status", failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(formatWithdrawalPolicy(policy)), false);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Treasury Withdrawal approval policy status queued"),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static int listTreasuryWithdrawalApprovals(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CivicServerRuntime.current()
                .withdrawalApprovalStatuses(player)
                .whenComplete((statuses, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Treasury Withdrawal approval list", failure);
                    } else {
                        String result = statuses.isEmpty()
                                ? "No Treasury Withdrawal approvals exist for your current Nation"
                                : statuses.stream()
                                        .limit(20)
                                        .map(NationApplicationCommands::formatWithdrawalApprovalStatus)
                                        .collect(Collectors.joining("; "));
                        source.sendSuccess(() -> Component.literal(result), false);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Treasury Withdrawal approval list queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int treasuryWithdrawalApprovalStatus(
            CommandSourceStack source, UUID approvalRequestId)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CivicServerRuntime.current()
                .withdrawalApprovalStatus(player, approvalRequestId)
                .whenComplete((status, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Treasury Withdrawal approval status", failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(
                                        formatWithdrawalApprovalStatus(status)),
                                false);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Treasury Withdrawal approval status queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int cancelTreasuryWithdrawalApproval(
            CommandSourceStack source,
            UUID approvalRequestId,
            String requestId,
            String reason)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CivicServerRuntime.current()
                .cancelWithdrawalApproval(player, approvalRequestId, requestId, reason)
                .whenComplete((approval, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Treasury Withdrawal approval cancellation", failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Cancelled Treasury Withdrawal approval "
                                                + approval.approvalRequestId()),
                                true);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal(
                        "Treasury Withdrawal approval cancellation queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    static String formatWithdrawalPolicy(WithdrawalApprovalPolicyVersion policy) {
        return "Withdrawal Approval Policy " + policy.policyId()
                + " nation=" + policy.nationId().value()
                + " effectiveAt=" + policy.effectiveAt()
                + " actor=" + policy.actorPlayerId()
                + " reason=\"" + policy.reason() + "\""
                + " tiers=" + policy.tiers()
                + (policy.defaultPolicy() ? " [CONSERVATIVE DEFAULT]" : "");
    }

    static String formatWithdrawalApprovalStatus(
            TreasuryWithdrawalApprovalStatus status) {
        var approval = status.approval();
        String votes = approval.votes().stream()
                .map(vote -> vote.approverPlayerId()
                        + "@" + vote.approvedAt()
                        + " reason=\"" + vote.reason() + "\"")
                .collect(Collectors.joining(", "));
        return "Withdrawal Approval " + approval.approvalRequestId()
                + " state=" + approval.state()
                + " canApprove=" + status.canApprove()
                + " nation=" + approval.nationId().value()
                + " amount=" + approval.amount().minorUnits()
                + " initiator=" + approval.actorPlayerId()
                + " reason=\"" + approval.reason() + "\""
                + " policy=" + approval.policyId()
                + " approvals=" + approval.approverPlayerIds().size()
                + "/" + approval.requiredApprovals()
                + " initiatedAt=" + approval.initiatedAt()
                + " expiresAt=" + approval.expiresAt()
                + " approvedAt=" + approval.approvedAt()
                + " executedAt=" + approval.executedAt()
                + " expiredAt=" + approval.expiredAt()
                + " cancelledBy=" + approval.cancelledByPlayerId()
                + " cancellationReason=\"" + approval.cancellationReason() + "\""
                + " cancelledAt=" + approval.cancelledAt()
                + " votes=[" + votes + "]";
    }

    private static int withdrawTreasury(
            CommandSourceStack source,
            String requestId,
            long amountMinorUnits,
            String reason)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CivicServerRuntime.current()
                .withdrawNationalTreasury(player, requestId, amountMinorUnits, reason)
                .whenComplete((withdrawal, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "National Treasury Withdrawal", failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Committed Treasury Withdrawal "
                                                + withdrawal.withdrawalId()
                                                + " amount="
                                                + withdrawal.amount().minorUnits()
                                                + " player="
                                                + withdrawal.actorPlayerId()),
                                true);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("National Treasury Withdrawal queued"),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static int approveTreasuryWithdrawal(
            CommandSourceStack source,
            UUID approvalRequestId,
            String requestId,
            String reason)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CivicServerRuntime.current()
                .approveNationalTreasuryWithdrawal(
                        player, approvalRequestId, requestId, reason)
                .whenComplete((outcome, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "National Treasury Withdrawal approval", failure);
                    } else if (outcome.executed()) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Approved and committed Treasury Withdrawal "
                                                + outcome.withdrawal().withdrawalId()
                                                + " approvals="
                                                + outcome.approval().approverPlayerIds().size()
                                                + "/"
                                                + outcome.approval().requiredApprovals()),
                                true);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Recorded Treasury Withdrawal approval "
                                                + outcome.approval().approvalRequestId()
                                                + " approvals="
                                                + outcome.approval().approverPlayerIds().size()
                                                + "/"
                                                + outcome.approval().requiredApprovals()),
                                true);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("National Treasury Withdrawal approval queued"),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static int scheduleTreasuryWithdrawalPolicy(
            CommandSourceStack source,
            String requestId,
            long effectiveAtEpochMillis,
            long thresholdMinorUnits,
            int requiredApprovals,
            String reason)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CivicServerRuntime.current()
                .scheduleWithdrawalApprovalPolicy(
                        player,
                        requestId,
                        thresholdMinorUnits,
                        requiredApprovals,
                        effectiveAtEpochMillis,
                        reason)
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Treasury Withdrawal approval policy", failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Scheduled Withdrawal Approval Policy "
                                                + policy.policyId()
                                                + " effectiveAt=" + policy.effectiveAt()
                                                + " tiers=" + policy.tiers()),
                                true);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Treasury Withdrawal approval policy queued"),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static int destroyTreasury(
            CommandSourceStack source,
            String requestId,
            long amountMinorUnits,
            String reason)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CivicServerRuntime.current()
                .destroyNationalTreasury(player, requestId, amountMinorUnits, reason)
                .whenComplete((event, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "National Treasury Permanent Destruction", failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Confirmed Permanent Destruction " + event.eventId()
                                                + " amount=" + event.amount().minorUnits()
                                                + " netIssuanceChange=-"
                                                + event.amount().minorUnits()),
                                true);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("National Treasury Permanent Destruction queued"),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static int mintStatus(CommandSourceStack source, UUID batchId)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CivicServerRuntime.current()
                .mintBatchStatusForActor(player.getUUID(), batchId)
                .whenComplete((status, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Mint Batch status", failure);
                        return;
                    }
                    source.sendSuccess(
                            () -> Component.literal(formatMintStatus(status)), false);
                }));
        source.sendSuccess(() -> Component.literal("Mint Batch status queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    static String formatMintStatus(MintBatchStatus status) {
        return MintBatchStatusFormatter.format(status);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mintCommand() {
        return Commands.literal("mint")
                .then(Commands.literal("start")
                        .then(Commands.argument("requestId", StringArgumentType.word())
                                .then(Commands.argument("mintId", UuidArgument.uuid())
                                        .then(Commands.argument("periodId", UuidArgument.uuid())
                                                .then(Commands.argument(
                                                                "amountMinorUnits",
                                                                LongArgumentType.longArg(1L))
                                                        .executes(context -> startMintBatch(
                                                                context.getSource(),
                                                                StringArgumentType.getString(
                                                                        context, "requestId"),
                                                                UuidArgument.getUuid(
                                                                        context, "mintId"),
                                                                UuidArgument.getUuid(
                                                                        context, "periodId"),
                                                                LongArgumentType.getLong(
                                                                        context,
                                                                        "amountMinorUnits"))))))))
                .then(Commands.literal("status")
                        .executes(context -> mintStatus(context.getSource(), null))
                        .then(Commands.argument("batchId", UuidArgument.uuid())
                                .executes(context -> mintStatus(
                                        context.getSource(),
                                        UuidArgument.getUuid(context, "batchId")))))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("batchId", UuidArgument.uuid())
                                .then(Commands.argument("requestId", StringArgumentType.word())
                                        .then(Commands.argument(
                                                        "reason",
                                                        StringArgumentType.greedyString())
                                                .executes(context -> cancelMintBatch(
                                                        context.getSource(),
                                                        UuidArgument.getUuid(
                                                                context, "batchId"),
                                                        StringArgumentType.getString(
                                                                context, "requestId"),
                                                        StringArgumentType.getString(
                                                                context, "reason")))))));
    }

    private static int startMintBatch(
            CommandSourceStack source,
            String requestId,
            UUID mintId,
            UUID periodId,
            long amountMinorUnits)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CivicServerRuntime.current()
                .startMintBatch(
                        player, requestId, mintId, periodId, amountMinorUnits)
                .whenComplete((batch, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Mint Batch start", failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Started Mint Batch " + batch.batchId()
                                                + " state=" + batch.state()
                                                + " amount=" + batch.amount().minorUnits()),
                                true);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Mint Batch start queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int cancelMintBatch(
            CommandSourceStack source, UUID batchId, String requestId, String reason)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CivicServerRuntime.current()
                .cancelMintBatch(player, batchId, requestId, reason)
                .whenComplete((batch, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Mint Batch cancellation", failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Cancelled Mint Batch " + batch.batchId()
                                                + " custody=" + batch.custodyState()),
                                true);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Mint Batch cancellation queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> territoryCommand() {
        return Commands.literal("territory")
                .then(Commands.literal("allowance")
                        .executes(context -> territoryAllowance(context.getSource())))
                .then(Commands.literal("prepare")
                        .then(Commands.argument("requestId", StringArgumentType.word())
                                .executes(context -> prepareTerritoryClaim(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "requestId")))))
                .then(Commands.literal("restore")
                        .then(Commands.argument("requestId", StringArgumentType.word())
                                .executes(context -> restoreTerritory(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "requestId")))))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("permitId", UuidArgument.uuid())
                                .then(Commands.argument("requestId", StringArgumentType.word())
                                        .then(Commands.argument(
                                                        "reason",
                                                        StringArgumentType.greedyString())
                                                .executes(context -> cancelTerritoryClaim(
                                                        context.getSource(),
                                                        UuidArgument.getUuid(
                                                                context, "permitId"),
                                                        StringArgumentType.getString(
                                                                context, "requestId"),
                                                        StringArgumentType.getString(
                                                                context, "reason")))))));
    }

    private static int restoreTerritory(CommandSourceStack source, String requestId)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        FtbNationTeamDirectory teams = FtbNationTeamDirectory.live();
        NationTeam team = teams.findEffectiveTeamForPlayer(player.getUUID())
                .or(() -> teams.findOwnedTeamForPlayer(player.getUUID()))
                .orElseThrow(() -> new SecurityException(
                        "You must belong to a formal Nation FTB Team"));
        ChunkPos chunk = new ChunkPos(player.blockPosition());
        CivicServerRuntime.current()
                .restoreTerritory(
                        team,
                        player.getUUID(),
                        requestId,
                        player.level().dimension().location().toString(),
                        chunk.x,
                        chunk.z)
                .whenComplete((payment, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Territory Restoration", failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Restored Territory "
                                                + payment.restoration().restorationId()
                                                + " total="
                                                + payment.restoration().totalDue().minorUnits()
                                                + " nextCyclePrepayment="
                                                + payment.restoration()
                                                        .nextCyclePrepayment()
                                                        .minorUnits()
                                                + " restorationFee="
                                                + payment.restoration()
                                                        .restorationFee()
                                                        .minorUnits()
                                                + "; request force-load manually if desired"),
                                true);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Territory Restoration queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int prepareTerritoryClaim(CommandSourceStack source, String requestId)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        FtbNationTeamDirectory teams = FtbNationTeamDirectory.live();
        NationTeam team = teams.findEffectiveTeamForPlayer(player.getUUID())
                .or(() -> teams.findOwnedTeamForPlayer(player.getUUID()))
                .orElseThrow(() -> new SecurityException(
                        "You must belong to a formal Nation FTB Team"));
        ChunkPos chunk = new ChunkPos(player.blockPosition());
        int currentClaimedChunks = FTBChunksAPI.api()
                .getManager()
                .getOrCreateData(FTBTeamsAPI.api().getManager().getTeamByID(team.teamId()).orElseThrow())
                .getClaimedChunks()
                .size();
        CivicServerRuntime.current()
                .prepareTerritoryClaim(
                        team,
                        player.getUUID(),
                        requestId,
                        player.level().dimension().location().toString(),
                        chunk.x,
                        chunk.z,
                        currentClaimedChunks)
                .whenComplete((prepared, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Territory Claim preparation", failure);
                    } else if (prepared.permit() != null) {
                        var permit = prepared.permit();
                        source.sendSuccess(
                                () -> Component.literal(
                                        "READY Territory Claim Permit " + permit.permitId()
                                                + " prepayment="
                                                + permit.prepayment().minorUnits()),
                                true);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "READY Free Claim Authorization "
                                                + prepared.freeClaim().authorizationId()),
                                true);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Territory Claim preparation queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int cancelTerritoryClaim(
            CommandSourceStack source, UUID permitId, String requestId, String reason)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CivicServerRuntime.current()
                .cancelTerritoryClaim(player.getUUID(), permitId, requestId, reason)
                .whenComplete((permit, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Territory Claim cancellation", failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Cancelled Territory Claim Permit " + permit.permitId()),
                                true);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Territory Claim cancellation queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int territoryAllowance(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID playerId = source.getPlayerOrException().getUUID();
        Instant asOf = Instant.now();
        Clock queryClock = Clock.fixed(asOf, ZoneOffset.UTC);
        CivicServerRuntime.current()
                .submitDatabase(database -> {
                    NationEffectiveCitizenPopulation population =
                            calculatePopulation(database, playerId, asOf, queryClock);
                    TerritoryFreeAllocationPolicyVersion policy =
                            new TerritoryFreeAllocationPolicyRegistry(
                                            database,
                                            queryClock,
                                            TerritoryFreeAllocationPolicyVersion.defaultPolicy(0, 0))
                                    .current(asOf);
                    TerritoryFreeAllocation allocation = policy.policy().calculate(population);
                    return new TerritoryAllowanceView(population, policy, allocation);
                })
                .whenComplete((allowance, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Territory Free Allocation", failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(formatTerritoryAllowance(allowance)),
                                false);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Territory allowance query queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatTerritoryAllowance(TerritoryAllowanceView view) {
        return "Nation " + view.population().nationId().value()
                + " Territory Free Allocation=" + view.allocation().totalFreeChunks()
                + " chunks (base=" + view.allocation().baseChunks()
                + ", effectiveCitizens=" + view.allocation().effectiveCitizenCount()
                + ", perCitizen=" + view.allocation().chunksPerEffectiveCitizen()
                + ") policy=" + view.policy().policyId()
                + " effectiveAt=" + view.policy().effectiveAt()
                + (view.policy().defaultPolicy() ? " [CONSERVATIVE DEFAULT]" : "");
    }

    private static LiteralArgumentBuilder<CommandSourceStack> roleCommand() {
        return Commands.literal("role")
                .then(Commands.literal("list")
                        .executes(context -> listRoles(context.getSource())))
                .then(Commands.literal("grant")
                        .then(Commands.argument("playerId", UuidArgument.uuid())
                                .then(Commands.argument("permission", StringArgumentType.word())
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(context -> grantRole(
                                                        context.getSource(),
                                                        UuidArgument.getUuid(context, "playerId"),
                                                        StringArgumentType.getString(
                                                                context, "permission"),
                                                        StringArgumentType.getString(
                                                                context, "reason")))))))
                .then(Commands.literal("revoke")
                        .then(Commands.argument("grantId", UuidArgument.uuid())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> revokeRole(
                                                context.getSource(),
                                                UuidArgument.getUuid(context, "grantId"),
                                                StringArgumentType.getString(context, "reason"))))));
    }

    private static int listRoles(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer actor = source.getPlayerOrException();
        NationTeam team = FtbNationTeamDirectory.live()
                .findEffectiveTeamForPlayer(actor.getUUID())
                .orElseThrow(() -> new SecurityException(
                        "You must be an effective member of a registered Nation"));
        Clock commandClock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        CivicServerRuntime.current()
                .submitDatabase(database -> {
                    NationFiscalCommandContext governance =
                            fiscalGovernance(database, team, commandClock);
                    if (governance.provider().findForCitizen(actor.getUUID()).isEmpty()) {
                        throw new SecurityException(
                                "Your effective Citizenship is unavailable or suspended");
                    }
                    return governance.authorities().activeGrants(governance.nationId());
                })
                .whenComplete((grants, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Nation fiscal role list", failure);
                    } else {
                        String details = grants.stream()
                                .map(NationApplicationCommands::formatFiscalGrant)
                                .collect(Collectors.joining(", "));
                        source.sendSuccess(
                                () -> Component.literal("Nation fiscal roles [" + details + "]"),
                                false);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Nation fiscal role query queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int grantRole(
            CommandSourceStack source,
            UUID targetPlayerId,
            String permissionName,
            String reason)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer actor = source.getPlayerOrException();
        NationTeam team = ownedTeam(actor);
        NationFiscalPermission permission = NationFiscalPermission.valueOf(
                permissionName.toUpperCase(Locale.ROOT));
        Clock commandClock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        CivicServerRuntime.current()
                .submitDatabase(database -> {
                    NationFiscalCommandContext governance =
                            fiscalGovernance(database, team, commandClock);
                    return governance.authorities().grant(new GrantNationFiscalPermission(
                                    GOVERNANCE_SERVICE,
                                    "player-role-grant:" + UUID.randomUUID(),
                                    governance.nationId(),
                                    actor.getUUID(),
                                    targetPlayerId,
                                    permission,
                                    reason));
                })
                .whenComplete((grant, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Nation fiscal role grant", failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal("Granted " + formatFiscalGrant(grant)),
                                true);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Nation fiscal role grant queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int revokeRole(CommandSourceStack source, UUID grantId, String reason)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer actor = source.getPlayerOrException();
        NationTeam team = ownedTeam(actor);
        Clock commandClock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        CivicServerRuntime.current()
                .submitDatabase(database -> {
                    NationFiscalCommandContext governance =
                            fiscalGovernance(database, team, commandClock);
                    return governance.authorities().revoke(new RevokeNationFiscalPermission(
                                    GOVERNANCE_SERVICE,
                                    "player-role-revoke:" + UUID.randomUUID(),
                                    governance.nationId(),
                                    actor.getUUID(),
                                    grantId,
                                    reason));
                })
                .whenComplete((revocation, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Nation fiscal role revocation", failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Revoked Nation fiscal grant " + revocation.grantId()),
                                true);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Nation fiscal role revocation queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatFiscalGrant(NationFiscalPermissionGrant grant) {
        return grant.grantId() + " player=" + grant.playerId()
                + " permission=" + grant.permission();
    }

    private static NationFiscalCommandContext fiscalGovernance(
            CivicDatabase database, NationTeam team, Clock clock) {
        NationTeamDirectory teamSnapshot = snapshot(team);
        NationRegistry nations = new NationRegistry(database, teamSnapshot);
        var nation = nations.findByFtbTeam(team.teamId())
                .orElseThrow(() -> new IllegalStateException(
                        "Your FTB Team is not bound to a Nation"));
        CitizenshipRegistry citizenships = new CitizenshipRegistry(
                database, CITIZENSHIP_TRANSFER_COOLDOWN, clock);
        FtbTeamsNationProvider provider = new FtbTeamsNationProvider(
                nations,
                citizenships,
                new CitizenshipCorrectionGraceRegistry(database, clock),
                teamSnapshot);
        return new NationFiscalCommandContext(
                nation.nationId(),
                provider,
                new NationFiscalAuthorityRegistry(database, provider, clock));
    }

    private static int apply(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UUID applicantPlayerId = player.getUUID();
        NationTeam team = FtbNationTeamDirectory.live()
                .findOwnedTeamForPlayer(applicantPlayerId)
                .orElseThrow(() -> new IllegalStateException(
                        "You must own one non-player FTB Team before applying to found a Nation"));
        if (!team.headId().equals(applicantPlayerId)) {
            throw new SecurityException("Only the FTB Team head can create a Nation Application");
        }
        Instant appliedAt = Instant.now();
        Clock commandClock = Clock.fixed(appliedAt, ZoneOffset.UTC);
        NationTeamDirectory snapshot = snapshot(team);
        CivicServerRuntime.current()
                .submitDatabase(database -> {
                    NationApplicationRegistry registry =
                            new NationApplicationRegistry(database, snapshot, commandClock);
                    Optional<NationApplication> pending =
                            registry.findPendingByFtbTeam(team.teamId());
                    if (pending.isPresent()) {
                        return pending.orElseThrow();
                    }
                    return registry.create(new CreateNationApplication(
                            FOUNDING_SERVICE,
                            "player-apply:" + UUID.randomUUID(),
                            team.teamId(),
                            applicantPlayerId,
                            appliedAt.plus(APPLICATION_LIFETIME)));
                })
                .whenComplete((application, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Nation Application " + application.applicationId().value()
                                                + " is PENDING until " + application.expiresAt()),
                                false);
                    } else {
                        Throwable cause = failure instanceof CompletionException
                                && failure.getCause() != null
                                ? failure.getCause()
                                : failure;
                        LOGGER.error("Nation Application command failed", cause);
                        source.sendFailure(Component.literal(
                                "Nation Application failed: " + cause.getMessage()));
                    }
                }));
        source.sendSuccess(() -> Component.literal("Nation Application queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int population(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        UUID playerId = source.getPlayerOrException().getUUID();
        Instant asOf = Instant.now();
        Clock queryClock = Clock.fixed(asOf, ZoneOffset.UTC);
        CivicServerRuntime.current()
                .submitDatabase(database -> {
                    return calculatePopulation(database, playerId, asOf, queryClock);
                })
                .whenComplete((population, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Nation population", failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(formatPopulation(population)), false);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Nation population query queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static NationEffectiveCitizenPopulation calculatePopulation(
            CivicDatabase database, UUID playerId, Instant asOf, Clock queryClock) {
        CitizenshipRegistry citizenships = new CitizenshipRegistry(
                database, CITIZENSHIP_TRANSFER_COOLDOWN, queryClock);
        var citizenship = citizenships.current(playerId)
                .orElseThrow(() -> new IllegalStateException(
                        "You do not have an active formal Citizenship"));
        return new NationPopulationCalculator(
                        citizenships,
                        new CitizenshipCorrectionGraceRegistry(database, queryClock),
                        new OnlineTimeLedger(database),
                        EVIDENCE_WINDOW,
                        FULL_EFFECTIVE_CITIZEN_TIME)
                .calculate(citizenship.nationId(), asOf);
    }

    private static String formatPopulation(NationEffectiveCitizenPopulation population) {
        String details = population.citizens().stream()
                .map(citizen -> citizen.playerId()
                        + ":" + citizen.attributedOnlineMillis() + "ms="
                        + String.format(Locale.ROOT, "%.3f", citizen.contribution()))
                .collect(Collectors.joining(", "));
        return "Nation " + population.nationId().value()
                + " effectiveCitizens=" + population.effectiveCitizenCount()
                + " populationEquivalent="
                + String.format(Locale.ROOT, "%.3f", population.populationEquivalent())
                + " asOf=" + population.asOf()
                + " details=[" + details + "]";
    }

    private static int status(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        NationTeam team = ownedTeam(player);
        Clock commandClock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        boolean debugWorld = CivicDebugWorldData.get(source.getServer()).enabled();
        int requiredEffectiveCandidates = debugWorld ? 1 : 2;
        CivicServerRuntime.current()
                .submitDatabase(database -> {
                    NationApplicationRegistry registry = new NationApplicationRegistry(
                            database, snapshot(team), commandClock);
                    Optional<NationApplication> application =
                            registry.findPendingByFtbTeam(team.teamId());
                    if (application.isEmpty()) {
                        return Optional.<NationApplicationStatus>empty();
                    }
                    NationApplication pending = application.orElseThrow();
                    int effectiveCandidates = (int) registry.claimCandidateEvidence(
                                    pending.applicationId(), EVIDENCE_WINDOW)
                            .stream()
                            .filter(evidence -> evidence.attributedMillis() > 0L)
                            .count();
                    return Optional.of(new NationApplicationStatus(
                            pending,
                            effectiveCandidates,
                            requiredEffectiveCandidates,
                            debugWorld));
                })
                .whenComplete((status, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Nation Application status", failure);
                    } else if (status.isEmpty()) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Your FTB Team has no PENDING Nation Application"),
                                false);
                    } else {
                        NationApplicationStatus current = status.orElseThrow();
                        NationApplication pending = current.application();
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Nation Application " + pending.applicationId().value()
                                                + " state=" + pending.state()
                                                + " effectiveCandidates="
                                                + current.effectiveCandidates() + "/"
                                                + current.requiredEffectiveCandidates()
                                                + " mode="
                                                + (current.debugWorld() ? "DEBUG WORLD" : "FORMAL")
                                                + " expires=" + pending.expiresAt()),
                                false);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Nation Application status queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int cancel(CommandSourceStack source, String reason)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        NationTeam team = ownedTeam(player);
        Clock commandClock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        CivicServerRuntime.current()
                .submitDatabase(database -> {
                    NationApplicationRegistry registry = new NationApplicationRegistry(
                            database, snapshot(team), commandClock);
                    NationApplication application = registry.findPendingByFtbTeam(team.teamId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Your FTB Team has no PENDING Nation Application"));
                    return registry.cancel(new CancelNationApplication(
                            FOUNDING_SERVICE,
                            "player-cancel:" + UUID.randomUUID(),
                            application.applicationId(),
                            player.getUUID(),
                            EVIDENCE_WINDOW,
                            reason));
                })
                .whenComplete((application, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportFailure(source, "Nation Application cancellation", failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Cancelled Nation Application "
                                                + application.applicationId().value()),
                                false);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Nation Application cancellation queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int activate(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UUID applicantPlayerId = player.getUUID();
        NationTeam team = ownedTeam(player);
        ChunkPos capitalChunk = new ChunkPos(player.blockPosition());
        var capitalClaim = FtbChunksAdapter.live()
                .find(player.level().dimension(), capitalChunk)
                .orElseThrow(() -> new IllegalStateException(
                        "The proposed Capital chunk must be claimed by your FTB Team"));
        if (!capitalClaim.teamId().equals(team.teamId())) {
            throw new SecurityException(
                    "The proposed Capital chunk is not claimed by your FTB Team");
        }
        Instant activatedAt = Instant.now();
        Clock commandClock = Clock.fixed(activatedAt, ZoneOffset.UTC);
        Capital capital = new Capital(
                player.level().dimension().location().toString(),
                capitalChunk.x,
                capitalChunk.z);
        NationFoundingPolicy policy = CivicDebugWorldData.get(source.getServer()).enabled()
                ? NationFoundingPolicy.debugWorld(
                        2, EVIDENCE_WINDOW, CITIZENSHIP_TRANSFER_COOLDOWN)
                : NationFoundingPolicy.formal(
                        2, EVIDENCE_WINDOW, CITIZENSHIP_TRANSFER_COOLDOWN);
        NationalTreasuryProvisioner provisioner = serverThreadTreasuryProvisioner(source);

        CivicServerRuntime.current()
                .submitDatabase(database -> {
                    NationApplicationRegistry registry = new NationApplicationRegistry(
                            database, snapshot(team), commandClock);
                    NationApplication application = registry.findPendingByFtbTeam(team.teamId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Your FTB Team has no PENDING Nation Application"));
                    return new NationActivationCoordinator(
                                    database, provisioner, policy, commandClock)
                            .activate(new ActivateNationApplication(
                                    FOUNDING_SERVICE,
                                    "player-activate:" + UUID.randomUUID(),
                                    application.applicationId(),
                                    capital,
                                    "FTB Team head activated the Nation at its claimed Capital"));
                })
                .whenComplete((activated, failure) -> source.getServer().execute(() ->
                        reportActivation(source, activated, failure)));
        source.sendSuccess(() -> Component.literal("Nation activation queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static NationalTreasuryProvisioner serverThreadTreasuryProvisioner(
            CommandSourceStack source) {
        return (nationId, treasuryAccountId) -> {
            CompletableFuture<Void> provisioned = new CompletableFuture<>();
            source.getServer().execute(() -> {
                try {
                    LightmansCurrencyNationalTreasuryProvisioner.forLevel(
                                    source.getServer().overworld())
                            .ensureExists(nationId, treasuryAccountId);
                    provisioned.complete(null);
                } catch (Throwable failure) {
                    provisioned.completeExceptionally(failure);
                }
            });
            provisioned.join();
        };
    }

    private static void reportActivation(
            CommandSourceStack source, ActivatedNation activated, Throwable failure) {
        if (failure == null) {
            source.sendSuccess(
                    () -> Component.literal(
                            "Activated Nation " + activated.nation().nationId().value()
                                    + " with Capital " + activated.capital().dimensionId()
                                    + " " + activated.capital().chunkX()
                                    + " " + activated.capital().chunkZ()),
                    false);
            return;
        }
        Throwable cause = failure instanceof CompletionException
                && failure.getCause() != null
                ? failure.getCause()
                : failure;
        LOGGER.error("Nation activation command failed", cause);
        source.sendFailure(Component.literal("Nation activation failed: " + cause.getMessage()));
    }

    private static NationTeam ownedTeam(ServerPlayer player) {
        return FtbNationTeamDirectory.live()
                .findOwnedTeamForPlayer(player.getUUID())
                .orElseThrow(() -> new IllegalStateException(
                        "You must own one non-player FTB Team for Nation Application operations"));
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

    private static NationTeamDirectory snapshot(NationTeam team) {
        return new NationTeamDirectory() {
            @Override
            public Optional<NationTeam> find(UUID teamId) {
                return team.teamId().equals(teamId) ? Optional.of(team) : Optional.empty();
            }

            @Override
            public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
                return team.citizens().contains(playerId) || team.headId().equals(playerId)
                        ? Optional.of(team)
                        : Optional.empty();
            }
        };
    }

    private record NationApplicationStatus(
            NationApplication application,
            int effectiveCandidates,
            int requiredEffectiveCandidates,
            boolean debugWorld) {}

    private record NationFiscalCommandContext(
            org.civiceconomy.nation.NationId nationId,
            FtbTeamsNationProvider provider,
            NationFiscalAuthorityRegistry authorities) {}

    private record TerritoryAllowanceView(
            NationEffectiveCitizenPopulation population,
            TerritoryFreeAllocationPolicyVersion policy,
            TerritoryFreeAllocation allocation) {}
}
