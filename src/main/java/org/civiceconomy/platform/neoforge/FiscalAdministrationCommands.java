package org.civiceconomy.platform.neoforge;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.civiceconomy.backup.OnlineDatabaseBackupPolicy;
import org.civiceconomy.backup.OnlineDatabaseBackupPolicyRegistry;
import org.civiceconomy.backup.OnlineDatabaseBackupPolicyVersion;
import org.civiceconomy.backup.ScheduleOnlineDatabaseBackupPolicy;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.BudgetDisbursementApprovalStatus;
import org.civiceconomy.fiscal.BudgetDisbursementRecoveryStatus;
import org.civiceconomy.fiscal.ChangeFiscalServiceState;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalCapability;
import org.civiceconomy.fiscal.FiscalCapabilityGrant;
import org.civiceconomy.fiscal.FiscalServiceAuthorizationView;
import org.civiceconomy.fiscal.FiscalServiceState;
import org.civiceconomy.fiscal.GrantFiscalCapability;
import org.civiceconomy.fiscal.RegisterFiscalService;
import org.civiceconomy.fiscal.RevokeFiscalCapability;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.TreasuryWithdrawalApprovalStatus;
import org.civiceconomy.fiscal.TreasuryWithdrawalRecoveryStatus;
import org.civiceconomy.monetary.MonetaryStockCorrection;
import org.civiceconomy.nation.CitizenshipPolicy;
import org.civiceconomy.nation.CitizenshipPolicyRegistry;
import org.civiceconomy.nation.CitizenshipPolicyVersion;
import org.civiceconomy.nation.EffectiveCitizenPopulationPolicy;
import org.civiceconomy.nation.EffectiveCitizenPopulationPolicyRegistry;
import org.civiceconomy.nation.EffectiveCitizenPopulationPolicyVersion;
import org.civiceconomy.nation.CandidateOnlineEvidencePolicy;
import org.civiceconomy.nation.CandidateOnlineEvidencePolicyRegistry;
import org.civiceconomy.nation.CandidateOnlineEvidencePolicyVersion;
import org.civiceconomy.nation.OnlineTimeObservationPolicy;
import org.civiceconomy.nation.OnlineTimeObservationPolicyRegistry;
import org.civiceconomy.nation.OnlineTimeObservationPolicyVersion;
import org.civiceconomy.nation.NationApplicationExpiryPolicy;
import org.civiceconomy.nation.NationApplicationExpiryPolicyRegistry;
import org.civiceconomy.nation.NationApplicationExpiryPolicyVersion;
import org.civiceconomy.nation.NationApplicationLifetimePolicy;
import org.civiceconomy.nation.NationApplicationLifetimePolicyRegistry;
import org.civiceconomy.nation.NationApplicationLifetimePolicyVersion;
import org.civiceconomy.nation.NationFoundingCandidateThresholdPolicy;
import org.civiceconomy.nation.NationFoundingCandidateThresholdPolicyRegistry;
import org.civiceconomy.nation.NationFoundingCandidateThresholdPolicyVersion;
import org.civiceconomy.nation.ScheduleCitizenshipPolicy;
import org.civiceconomy.nation.ScheduleEffectiveCitizenPopulationPolicy;
import org.civiceconomy.nation.ScheduleCandidateOnlineEvidencePolicy;
import org.civiceconomy.nation.ScheduleOnlineTimeObservationPolicy;
import org.civiceconomy.nation.ScheduleNationApplicationExpiryPolicy;
import org.civiceconomy.nation.ScheduleNationApplicationLifetimePolicy;
import org.civiceconomy.nation.ScheduleNationFoundingCandidateThresholdPolicy;
import org.civiceconomy.persistence.StoredDatabaseBackupOperation;
import org.civiceconomy.persistence.StoredDatabaseRestoreOperation;
import org.civiceconomy.production.GlobalReferencePriceRegistry;
import org.civiceconomy.production.GlobalReferencePriceVersion;
import org.civiceconomy.production.ProductionIndustryAssignmentRegistry;
import org.civiceconomy.production.ProductionIndustryAssignmentVersion;
import org.civiceconomy.production.ProductionIndustryId;
import org.civiceconomy.production.ProductionMarginalReturnPolicy;
import org.civiceconomy.production.ProductionMarginalReturnPolicyRegistry;
import org.civiceconomy.production.ProductionMarginalReturnPolicyVersion;
import org.civiceconomy.production.ProductionStrengthPolicy;
import org.civiceconomy.production.ProductionStrengthPolicyRegistry;
import org.civiceconomy.production.ProductionStrengthPolicyVersion;
import org.civiceconomy.production.RegisteredFacilityScopePolicy;
import org.civiceconomy.production.RegisteredFacilityScopePolicyRegistry;
import org.civiceconomy.production.RegisteredFacilityScopePolicyVersion;
import org.civiceconomy.production.ScheduleGlobalReferencePrice;
import org.civiceconomy.production.ScheduleProductionIndustryAssignment;
import org.civiceconomy.production.ScheduleProductionMarginalReturnPolicy;
import org.civiceconomy.production.ScheduleProductionStrengthPolicy;
import org.civiceconomy.production.ScheduleRegisteredFacilityScopePolicy;
import org.civiceconomy.strength.EffectiveCitizenStrengthPolicy;
import org.civiceconomy.strength.AuditableEconomicActivityPolicy;
import org.civiceconomy.strength.AuditableEconomicActivityPolicyRegistry;
import org.civiceconomy.strength.AuditableEconomicActivityPolicyVersion;
import org.civiceconomy.strength.EffectiveCitizenStrengthPolicyRegistry;
import org.civiceconomy.strength.EffectiveCitizenStrengthPolicyVersion;
import org.civiceconomy.strength.EffectiveTerritoryStrengthPolicy;
import org.civiceconomy.strength.EffectiveTerritoryStrengthPolicyRegistry;
import org.civiceconomy.strength.EffectiveTerritoryStrengthPolicyVersion;
import org.civiceconomy.strength.MintCompliancePolicy;
import org.civiceconomy.strength.MintCompliancePolicyRegistry;
import org.civiceconomy.strength.MintCompliancePolicyVersion;
import org.civiceconomy.strength.ScheduleEffectiveCitizenStrengthPolicy;
import org.civiceconomy.strength.ScheduleAuditableEconomicActivityPolicy;
import org.civiceconomy.strength.ScheduleEffectiveTerritoryStrengthPolicy;
import org.civiceconomy.strength.ScheduleMintCompliancePolicy;
import org.civiceconomy.territory.ScheduleTerritoryFreeAllocationPolicy;
import org.civiceconomy.territory.ScheduleTerritoryExpansionPricingPolicy;
import org.civiceconomy.territory.ScheduleTerritoryMaintenancePolicy;
import org.civiceconomy.territory.TerritoryExpansionPricingPolicyRegistry;
import org.civiceconomy.territory.TerritoryExpansionPricingPolicyVersion;
import org.civiceconomy.territory.TerritoryFreeAllocationPolicyRegistry;
import org.civiceconomy.territory.TerritoryFreeAllocationPolicyVersion;
import org.civiceconomy.territory.TerritoryMaintenancePolicyRegistry;
import org.civiceconomy.territory.TerritoryMaintenancePolicyVersion;

public final class FiscalAdministrationCommands {
    private static final ServiceIdentity TERRITORY_POLICY_SERVICE =
            new ServiceIdentity("civiceconomy-territory-policy");
    private static final ServiceIdentity TERRITORY_PRICING_SERVICE =
            new ServiceIdentity("civiceconomy-territory-pricing");
    private static final ServiceIdentity TERRITORY_MAINTENANCE_POLICY_SERVICE =
            new ServiceIdentity("civiceconomy-territory-maintenance-policy");
    private static final ServiceIdentity GLOBAL_REFERENCE_PRICE_SERVICE =
            new ServiceIdentity("civiceconomy-reference-price");
    private static final ServiceIdentity PRODUCTION_POLICY_SERVICE =
            new ServiceIdentity("civiceconomy-production-policy");
    private static final ServiceIdentity PRODUCTION_STRENGTH_POLICY_SERVICE =
            new ServiceIdentity("civiceconomy-production-strength-policy");
    private static final ServiceIdentity PRODUCTION_INDUSTRY_SERVICE =
            new ServiceIdentity("civiceconomy-production-industry");
    private static final ServiceIdentity EFFECTIVE_CITIZEN_STRENGTH_POLICY_SERVICE =
            new ServiceIdentity("civiceconomy-effective-citizen-strength-policy");
    private static final ServiceIdentity EFFECTIVE_TERRITORY_STRENGTH_POLICY_SERVICE =
            new ServiceIdentity("civiceconomy-effective-territory-strength-policy");
    private static final ServiceIdentity MINT_COMPLIANCE_POLICY_SERVICE =
            new ServiceIdentity("civiceconomy-mint-compliance-policy");
    private static final ServiceIdentity AUDITABLE_ECONOMIC_ACTIVITY_POLICY_SERVICE =
            new ServiceIdentity("civiceconomy-auditable-economic-activity-policy");
    private static final ServiceIdentity REGISTERED_FACILITY_SCOPE_POLICY_SERVICE =
            new ServiceIdentity("civiceconomy-registered-facility-scope-policy");
    private static final ServiceIdentity CITIZENSHIP_POLICY_SERVICE =
            new ServiceIdentity("civiceconomy-citizenship-policy");
    private static final ServiceIdentity EFFECTIVE_CITIZEN_POPULATION_POLICY_SERVICE =
            new ServiceIdentity("civiceconomy-effective-citizen-population-policy");
    private static final ServiceIdentity ONLINE_TIME_OBSERVATION_POLICY_SERVICE =
            new ServiceIdentity("civiceconomy-online-time-observation-policy");
    private static final ServiceIdentity NATION_APPLICATION_EXPIRY_POLICY_SERVICE =
            new ServiceIdentity("civiceconomy-nation-application-expiry-policy");
    private static final ServiceIdentity CANDIDATE_ONLINE_EVIDENCE_POLICY_SERVICE =
            new ServiceIdentity("civiceconomy-candidate-online-evidence-policy");
    private static final ServiceIdentity NATION_APPLICATION_LIFETIME_POLICY_SERVICE =
            new ServiceIdentity("civiceconomy-nation-application-lifetime-policy");
    private static final ServiceIdentity NATION_FOUNDING_CANDIDATE_THRESHOLD_POLICY_SERVICE =
            new ServiceIdentity("civiceconomy-nation-founding-candidate-threshold-policy");
    private static final ServiceIdentity ONLINE_DATABASE_BACKUP_POLICY_SERVICE =
            new ServiceIdentity("civiceconomy-online-database-backup-policy");

    private FiscalAdministrationCommands() {}

    public static void register(RegisterCommandsEvent event) {
        var service = Commands.literal("service")
                .then(Commands.literal("list").executes(context -> list(context.getSource())))
                .then(showCommand())
                .then(registerCommand())
                .then(grantCommand())
                .then(revokeCommand())
                .then(stateCommand("disable", FiscalServiceState.DISABLED))
                .then(stateCommand("enable", FiscalServiceState.ENABLED));
        var mint = Commands.literal("mint")
                .then(Commands.literal("recovery")
                        .executes(context -> triggerMintRecovery(context.getSource())))
                .then(Commands.literal("status")
                        .executes(context -> mintRecoveryStatus(context.getSource()))
                        .then(Commands.argument("batchId", UuidArgument.uuid())
                                .executes(context -> mintBatchStatus(
                                        context.getSource(),
                                        UuidArgument.getUuid(context, "batchId")))))
                .then(monetaryStockCorrectionCommand());
        var withdrawal = Commands.literal("withdrawal")
                .then(Commands.literal("recovery")
                        .then(Commands.literal("status")
                                .executes(context -> withdrawalRecoveryStatus(
                                        context.getSource()))));
        var budgetDisbursement = Commands.literal("budget-disbursement")
                .then(Commands.literal("recovery")
                        .then(Commands.literal("status")
                                .executes(context -> budgetDisbursementRecoveryStatus(
                                        context.getSource()))));
        var admin = Commands.literal("admin")
                .requires(source -> source.hasPermission(Commands.LEVEL_ADMINS))
                .then(service)
                .then(mint)
                .then(withdrawal)
                .then(budgetDisbursement)
                .then(databaseBackupCommand())
                .then(territoryPolicyCommand())
                .then(globalReferencePriceCommand())
                .then(productionPolicyCommand())
                .then(facilityPolicyCommand())
                .then(citizenshipPolicyCommand())
                .then(onlineTimeObservationPolicyCommand())
                .then(nationApplicationExpiryPolicyCommand())
                .then(strengthPolicyCommand());
        var civic = Commands.literal("civic")
                .then(Commands.literal("economy")
                        .then(FiscalBillCommands.command())
                        .then(NationApplicationCommands.command())
                        .then(admin));
        boolean dedicatedStartupPermit =
                CivicDebugWorldCommands.dedicatedStartupPermit();
        boolean integratedServer = event.getCommandSelection()
                == Commands.CommandSelection.INTEGRATED;
        if (DebugWorldCommandRegistrationPolicy.shouldRegister(
                integratedServer, dedicatedStartupPermit)) {
            civic.then(CivicDebugWorldCommands.command(dedicatedStartupPermit));
        }
        event.getDispatcher().register(civic);
    }

    private static int triggerMintRecovery(CommandSourceStack source) {
        try {
            CivicServerRuntime.current().triggerMintRecovery();
            source.sendSuccess(() -> Component.literal(
                    "Mint recovery scan queued; pending operations remain server-authoritative"), true);
        } catch (RuntimeException failure) {
            source.sendFailure(Component.literal("Mint recovery rejected: " + failure.getMessage()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int withdrawalRecoveryStatus(CommandSourceStack source) {
        CivicServerRuntime.current().withdrawalRecoveryStatus()
                .whenComplete((status, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        sendFailure(source, failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(formatWithdrawalRecovery(status)),
                                false);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Treasury Withdrawal recovery status queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatWithdrawalRecovery(
            TreasuryWithdrawalRecoveryStatus status) {
        String approved = status.approvedWithoutOperation().isEmpty()
                ? "none"
                : status.approvedWithoutOperation().stream()
                        .map(approval -> NationApplicationCommands
                                .formatWithdrawalApprovalStatus(
                                        new TreasuryWithdrawalApprovalStatus(approval, false)))
                        .collect(Collectors.joining("; "));
        String prepared = status.preparedOperations().isEmpty()
                ? "none"
                : status.preparedOperations().stream()
                        .map(operation -> operation.withdrawalId()
                                + "=" + operation.state()
                                + ":approval=" + operation.approvalRequestId()
                                + ":nation=" + operation.nationId().value()
                                + ":actor=" + operation.actorPlayerId()
                                + ":amount=" + operation.amount().minorUnits()
                                + ":preparedAt=" + operation.preparedAt())
                        .collect(Collectors.joining("; "));
        return "Treasury Withdrawal recovery approvedWithoutOperation=[" + approved
                + "] preparedOperations=[" + prepared
                + "]; inspection is read-only and automatic recovery remains authoritative";
    }

    private static int budgetDisbursementRecoveryStatus(
            CommandSourceStack source) {
        CivicServerRuntime.current().budgetDisbursementRecoveryStatus()
                .whenComplete((status, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        sendFailure(source, failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(
                                        formatBudgetDisbursementRecovery(status)),
                                false);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal(
                        "Budget Disbursement recovery status queued"),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatBudgetDisbursementRecovery(
            BudgetDisbursementRecoveryStatus status) {
        String approved = status.approvedWithoutPayment().isEmpty()
                ? "none"
                : status.approvedWithoutPayment().stream()
                        .map(approval -> NationApplicationCommands
                                .formatDisbursementApprovalStatus(
                                        new BudgetDisbursementApprovalStatus(
                                                approval, false)))
                        .collect(Collectors.joining("; "));
        String incomplete = status.incompletePayments().isEmpty()
                ? "none"
                : status.incompletePayments().stream()
                        .map(payment -> payment.transactionId()
                                + "=" + payment.state()
                                + ":request=" + payment.requestId()
                                + ":source=" + payment.sourceAccount().value()
                                + ":recipient=" + payment.recipientAccount().value()
                                + ":amount=" + payment.amount().minorUnits())
                        .collect(Collectors.joining("; "));
        return "Budget Disbursement recovery approvedWithoutPayment=[" + approved
                + "] incompletePayments=[" + incomplete
                + "]; inspection is read-only and automatic recovery remains authoritative";
    }

    private static int mintRecoveryStatus(CommandSourceStack source) {
        CivicServerRuntime.current().pendingMintRecoveryCount()
                .whenComplete((count, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        source.sendFailure(Component.literal(
                                "Mint recovery status failed: " + failure.getMessage()));
                    } else {
                        source.sendSuccess(() -> Component.literal(
                                "Pending Mint recovery operations: " + count), false);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Mint recovery status queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int mintBatchStatus(CommandSourceStack source, UUID batchId) {
        CivicServerRuntime.current().mintBatchStatus(batchId)
                .whenComplete((status, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        source.sendFailure(Component.literal(
                                "Mint Batch status failed: " + failure.getMessage()));
                    } else {
                        source.sendSuccess(() -> Component.literal(
                                NationApplicationCommands.formatMintStatus(status)), false);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Mint Batch status queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            monetaryStockCorrectionCommand() {
        return Commands.literal("correction")
                .then(Commands.literal("apply")
                        .then(Commands.argument("incidentId", UuidArgument.uuid())
                                .then(Commands.argument("requestId", StringArgumentType.word())
                                        .then(Commands.argument(
                                                        "evidenceReference",
                                                        StringArgumentType.string())
                                                .then(Commands.argument(
                                                                "reason",
                                                                StringArgumentType.greedyString())
                                                        .executes(context -> correctMonetaryStock(
                                                                context.getSource(),
                                                                UuidArgument.getUuid(
                                                                        context, "incidentId"),
                                                                StringArgumentType.getString(
                                                                        context, "requestId"),
                                                                StringArgumentType.getString(
                                                                        context, "evidenceReference"),
                                                                StringArgumentType.getString(
                                                                        context, "reason"))))))))
                .then(Commands.literal("status")
                        .then(Commands.argument("incidentId", UuidArgument.uuid())
                                .executes(context -> monetaryStockCorrectionStatus(
                                        context.getSource(),
                                        UuidArgument.getUuid(context, "incidentId")))));
    }

    private static int correctMonetaryStock(
            CommandSourceStack source,
            UUID incidentId,
            String requestId,
            String evidenceReference,
            String reason) {
        CivicServerRuntime.current()
                .correctMonetaryStock(
                        administrator(source).value(),
                        requestId,
                        incidentId,
                        evidenceReference,
                        reason)
                .whenComplete((correction, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        Throwable cause = failure instanceof CompletionException
                                && failure.getCause() != null
                                ? failure.getCause()
                                : failure;
                        source.sendFailure(Component.literal(
                                "Monetary Stock Correction rejected: " + cause.getMessage()));
                    } else {
                        source.sendSuccess(() -> Component.literal(
                                formatMonetaryStockCorrection(correction)), true);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Monetary Stock Correction queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int monetaryStockCorrectionStatus(
            CommandSourceStack source, UUID incidentId) {
        CivicServerRuntime.current()
                .monetaryStockCorrection(incidentId)
                .whenComplete((correction, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        Throwable cause = failure instanceof CompletionException
                                && failure.getCause() != null
                                ? failure.getCause()
                                : failure;
                        source.sendFailure(Component.literal(
                                "Monetary Stock Correction status failed: "
                                        + cause.getMessage()));
                    } else {
                        source.sendSuccess(() -> Component.literal(
                                formatMonetaryStockCorrection(correction)), false);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Monetary Stock Correction status queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    static String formatMonetaryStockCorrection(MonetaryStockCorrection correction) {
        return "Monetary Stock Correction " + correction.correctionId()
                + " incident=" + correction.incidentId()
                + " operation=" + correction.operationId()
                + " batch=" + correction.batchId()
                + " amountMinorUnits=" + correction.amount().minorUnits()
                + " event=" + correction.event().eventId()
                + " administrator=" + correction.administratorIdentity()
                + " evidence=" + correction.evidenceReference()
                + " correctedAt=" + correction.correctedAt()
                + "; Mint Batch, materials, and quota remain quarantined";
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            databaseBackupCommand() {
        return Commands.literal("backup")
                .then(Commands.literal("status")
                        .executes(context -> databaseBackupStatus(context.getSource())))
                .then(Commands.literal("trigger")
                        .then(Commands.argument("requestId", StringArgumentType.word())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> triggerDatabaseBackup(
                                                context.getSource(),
                                                StringArgumentType.getString(
                                                        context, "requestId"),
                                                StringArgumentType.getString(
                                                        context, "reason"))))))
                .then(databaseBackupPolicyCommand())
                .then(databaseRestoreCommand());
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            databaseBackupPolicyCommand() {
        return Commands.literal("policy")
                .then(Commands.literal("show")
                        .executes(context -> showOnlineDatabaseBackupPolicy(
                                context.getSource())))
                .then(Commands.literal("schedule")
                        .then(Commands.argument("intervalMillis", LongArgumentType.longArg(1L))
                                .then(Commands.argument("retention", IntegerArgumentType.integer(1))
                                        .then(Commands.argument(
                                                        "effectiveAtEpochMillis",
                                                        LongArgumentType.longArg(0L))
                                                .then(Commands.argument(
                                                                "requestId",
                                                                StringArgumentType.word())
                                                        .then(Commands.argument(
                                                                        "reason",
                                                                        StringArgumentType.greedyString())
                                                                .executes(context ->
                                                                        scheduleOnlineDatabaseBackupPolicy(
                                                                                context.getSource(),
                                                                                LongArgumentType.getLong(
                                                                                        context,
                                                                                        "intervalMillis"),
                                                                                IntegerArgumentType.getInteger(
                                                                                        context,
                                                                                        "retention"),
                                                                                LongArgumentType.getLong(
                                                                                        context,
                                                                                        "effectiveAtEpochMillis"),
                                                                                StringArgumentType.getString(
                                                                                        context,
                                                                                        "requestId"),
                                                                                StringArgumentType.getString(
                                                                                        context,
                                                                                        "reason")))))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            databaseRestoreCommand() {
        return Commands.literal("restore")
                .then(Commands.literal("status")
                        .executes(context -> databaseRestoreStatus(context.getSource())))
                .then(Commands.literal("stage")
                        .then(Commands.argument("backupOperationId", UuidArgument.uuid())
                                .then(Commands.argument("requestId", StringArgumentType.word())
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(context -> stageDatabaseRestore(
                                                        context.getSource(),
                                                        UuidArgument.getUuid(
                                                                context, "backupOperationId"),
                                                        StringArgumentType.getString(
                                                                context, "requestId"),
                                                        StringArgumentType.getString(
                                                                context, "reason")))))))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("restoreOperationId", UuidArgument.uuid())
                                .then(Commands.argument("requestId", StringArgumentType.word())
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(context -> cancelDatabaseRestore(
                                                        context.getSource(),
                                                        UuidArgument.getUuid(
                                                                context, "restoreOperationId"),
                                                        StringArgumentType.getString(
                                                                context, "requestId"),
                                                        StringArgumentType.getString(
                                                                context, "reason")))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            territoryPolicyCommand() {
        return Commands.literal("territory")
                .then(territoryFreeAllocationPolicyCommand())
                .then(territoryPricingPolicyCommand())
                .then(territoryMaintenancePolicyCommand());
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            territoryFreeAllocationPolicyCommand() {
        return Commands.literal("policy")
                .then(Commands.literal("show")
                        .executes(context -> showTerritoryPolicy(context.getSource())))
                .then(Commands.literal("schedule")
                        .then(Commands.argument("baseChunks", IntegerArgumentType.integer(0))
                                .then(Commands.argument(
                                                "chunksPerEffectiveCitizen",
                                                IntegerArgumentType.integer(0))
                                        .then(Commands.argument(
                                                        "effectiveAtEpochMillis",
                                                        LongArgumentType.longArg(0L))
                                                .then(Commands.argument(
                                                                "requestId",
                                                                StringArgumentType.word())
                                                        .then(Commands.argument(
                                                                        "reason",
                                                                        StringArgumentType.greedyString())
                                                                .executes(context ->
                                                                        scheduleTerritoryPolicy(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(context, "baseChunks"),
                                                                                IntegerArgumentType.getInteger(context, "chunksPerEffectiveCitizen"),
                                                                                LongArgumentType.getLong(context, "effectiveAtEpochMillis"),
                                                                                StringArgumentType.getString(context, "requestId"),
                                                                                StringArgumentType.getString(context, "reason")))))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            territoryPricingPolicyCommand() {
        return Commands.literal("pricing")
                .then(Commands.literal("show")
                        .executes(context -> showTerritoryPricing(context.getSource())))
                .then(Commands.literal("schedule")
                        .then(Commands.argument(
                                        "firstOverageChunkCost", LongArgumentType.longArg(0L))
                                .then(Commands.argument(
                                                "additionalMarginalCost",
                                                LongArgumentType.longArg(0L))
                                        .then(Commands.argument(
                                                        "effectiveAtEpochMillis",
                                                        LongArgumentType.longArg(0L))
                                                .then(Commands.argument(
                                                                "requestId",
                                                                StringArgumentType.word())
                                                        .then(Commands.argument(
                                                                        "reason",
                                                                        StringArgumentType.greedyString())
                                                                .executes(context ->
                                                                        scheduleTerritoryPricing(
                                                                                context.getSource(),
                                                                                LongArgumentType.getLong(context, "firstOverageChunkCost"),
                                                                                LongArgumentType.getLong(context, "additionalMarginalCost"),
                                                                                LongArgumentType.getLong(context, "effectiveAtEpochMillis"),
                                                                                StringArgumentType.getString(context, "requestId"),
                                                                                StringArgumentType.getString(context, "reason")))))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            territoryMaintenancePolicyCommand() {
        return Commands.literal("maintenance")
                .then(Commands.literal("show")
                        .executes(context -> showTerritoryMaintenancePolicy(context.getSource())))
                .then(Commands.literal("schedule")
                        .then(Commands.argument(
                                        "cycleDurationMillis", LongArgumentType.longArg(1L))
                                .then(Commands.argument(
                                                "baseMaintenancePerClaim",
                                                LongArgumentType.longArg(0L))
                                        .then(Commands.argument(
                                                        "enclaveMultiplierBasisPoints",
                                                        IntegerArgumentType.integer(10_000))
                                                .then(Commands.argument(
                                                                "forceLoadSurcharge",
                                                                LongArgumentType.longArg(0L))
                                                        .then(Commands.argument(
                                                                        "restorationFee",
                                                                        LongArgumentType.longArg(0L))
                                                                .then(Commands.argument(
                                                                                "restorationCooldownMillis",
                                                                                LongArgumentType.longArg(1L))
                                                                        .then(Commands.argument(
                                                                                        "destructionBasisPoints",
                                                                                        IntegerArgumentType.integer(3_000, 8_000))
                                                                                .then(Commands.argument(
                                                                                                "effectiveAtEpochMillis",
                                                                                                LongArgumentType.longArg(0L))
                                                                                        .then(Commands.argument(
                                                                                                        "requestId",
                                                                                                        StringArgumentType.word())
                                                                                                .then(Commands.argument(
                                                                                                                "reason",
                                                                                                                StringArgumentType.greedyString())
                                                                                                        .executes(context ->
                                                                                                                scheduleTerritoryMaintenancePolicy(
                                                                                                                        context.getSource(),
                                                                                                                        LongArgumentType.getLong(context, "cycleDurationMillis"),
                                                                                                                        LongArgumentType.getLong(context, "baseMaintenancePerClaim"),
                                                                                                                        IntegerArgumentType.getInteger(context, "enclaveMultiplierBasisPoints"),
                                                                                                                        LongArgumentType.getLong(context, "forceLoadSurcharge"),
                                                                                                                        LongArgumentType.getLong(context, "restorationFee"),
                                                                                                                        LongArgumentType.getLong(context, "restorationCooldownMillis"),
                                                                                                                        IntegerArgumentType.getInteger(context, "destructionBasisPoints"),
                                                                                                                        LongArgumentType.getLong(context, "effectiveAtEpochMillis"),
                                                                                                                        StringArgumentType.getString(context, "requestId"),
                                                                                                                        StringArgumentType.getString(context, "reason"))))))))))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            globalReferencePriceCommand() {
        return Commands.literal("reference-price")
                .then(Commands.literal("show")
                        .then(Commands.argument("itemId", ResourceLocationArgument.id())
                                .then(Commands.argument(
                                                "componentFingerprint",
                                                StringArgumentType.string())
                                        .executes(context -> showGlobalReferencePrice(
                                                context.getSource(),
                                                ResourceLocationArgument.getId(
                                                        context,
                                                        "itemId").toString(),
                                                StringArgumentType.getString(
                                                        context,
                                                        "componentFingerprint"))))))
                .then(Commands.literal("schedule")
                        .then(Commands.argument("itemId", ResourceLocationArgument.id())
                                .then(Commands.argument(
                                                "componentFingerprint",
                                                StringArgumentType.string())
                                        .then(Commands.argument(
                                                        "unitPriceMinorUnits",
                                                        LongArgumentType.longArg(1L))
                                                .then(Commands.argument(
                                                                "effectiveAtEpochMillis",
                                                                LongArgumentType.longArg(0L))
                                                        .then(Commands.argument(
                                                                        "requestId",
                                                                        StringArgumentType.word())
                                                                .then(Commands.argument(
                                                                                "reason",
                                                                                StringArgumentType.greedyString())
                                                                        .executes(context ->
                                                                                scheduleGlobalReferencePrice(
                                                                                        context.getSource(),
                                                                                        ResourceLocationArgument.getId(context, "itemId").toString(),
                                                                                        StringArgumentType.getString(context, "componentFingerprint"),
                                                                                        LongArgumentType.getLong(context, "unitPriceMinorUnits"),
                                                                                        LongArgumentType.getLong(context, "effectiveAtEpochMillis"),
                                                                                        StringArgumentType.getString(context, "requestId"),
                                                                                        StringArgumentType.getString(context, "reason"))))))))));
    }

    private static int showGlobalReferencePrice(
            CommandSourceStack source,
            String itemId,
            String componentFingerprint) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new GlobalReferencePriceRegistry(database, clock)
                        .current(itemId, componentFingerprint, Instant.now(clock)))
                .whenComplete((price, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportDatabaseFailure(source, "Global Reference Price query", failure);
                    } else if (price.isEmpty()) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Global Reference Price is not configured for "
                                                + itemId + " components="
                                                + componentFingerprint
                                                + "; contribution remains zero"),
                                false);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(formatGlobalReferencePrice(
                                        price.orElseThrow())),
                                false);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Global Reference Price query queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int scheduleGlobalReferencePrice(
            CommandSourceStack source,
            String itemId,
            String componentFingerprint,
            long unitPriceMinorUnits,
            long effectiveAtEpochMillis,
            String requestId,
            String reason) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new GlobalReferencePriceRegistry(database, clock)
                        .schedule(new ScheduleGlobalReferencePrice(
                                GLOBAL_REFERENCE_PRICE_SERVICE,
                                requestId,
                                administrator(source).value(),
                                itemId,
                                componentFingerprint,
                                unitPriceMinorUnits,
                                Instant.ofEpochMilli(effectiveAtEpochMillis),
                                reason)))
                .whenComplete((price, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Scheduled " + formatGlobalReferencePrice(price)),
                                true);
                    } else {
                        reportDatabaseFailure(source, "Global Reference Price schedule", failure);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Global Reference Price schedule queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatGlobalReferencePrice(GlobalReferencePriceVersion price) {
        return "Global Reference Price " + price.priceId()
                + " item=" + price.itemId()
                + " components=" + price.componentFingerprint()
                + " unitPriceMinorUnits=" + price.unitPriceMinorUnits()
                + " effectiveAt=" + price.effectiveAt()
                + " actor=" + price.actorIdentity();
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            productionPolicyCommand() {
        var reason = Commands.argument("reason", StringArgumentType.greedyString())
                .executes(context -> scheduleProductionMarginalReturnPolicy(
                        context.getSource(),
                        LongArgumentType.getLong(context, "facilitySoftCapMinorUnits"),
                        IntegerArgumentType.getInteger(
                                context, "facilityExcessWeightBasisPoints"),
                        LongArgumentType.getLong(context, "industrySoftCapMinorUnits"),
                        IntegerArgumentType.getInteger(
                                context, "industryExcessWeightBasisPoints"),
                        LongArgumentType.getLong(context, "effectiveAtEpochMillis"),
                        StringArgumentType.getString(context, "requestId"),
                        StringArgumentType.getString(context, "reason")));
        var requestId = Commands.argument("requestId", StringArgumentType.word())
                .then(reason);
        var effectiveAt = Commands.argument(
                        "effectiveAtEpochMillis", LongArgumentType.longArg(0L))
                .then(requestId);
        var industryWeight = Commands.argument(
                        "industryExcessWeightBasisPoints",
                        IntegerArgumentType.integer(0, 10_000))
                .then(effectiveAt);
        var industryCap = Commands.argument(
                        "industrySoftCapMinorUnits", LongArgumentType.longArg(1L))
                .then(industryWeight);
        var facilityWeight = Commands.argument(
                        "facilityExcessWeightBasisPoints",
                        IntegerArgumentType.integer(0, 10_000))
                .then(industryCap);
        var facilityCap = Commands.argument(
                        "facilitySoftCapMinorUnits", LongArgumentType.longArg(1L))
                .then(facilityWeight);
        return Commands.literal("production")
                .then(Commands.literal("marginal-return")
                        .then(Commands.literal("show")
                                .executes(context -> showProductionMarginalReturnPolicy(
                                        context.getSource())))
                        .then(Commands.literal("schedule")
                                .then(facilityCap)))
                .then(productionStrengthPolicyCommand())
                .then(productionIndustryCommand());
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            facilityPolicyCommand() {
        return Commands.literal("facility")
                .then(Commands.literal("scope-policy")
                        .then(Commands.literal("show")
                                .executes(context -> showRegisteredFacilityScopePolicy(
                                        context.getSource())))
                        .then(Commands.literal("schedule")
                                .then(Commands.argument(
                                                "maxScopeChunks",
                                                IntegerArgumentType.integer(1))
                                        .then(Commands.argument(
                                                        "effectiveAtEpochMillis",
                                                        LongArgumentType.longArg(0L))
                                                .then(Commands.argument(
                                                                "requestId",
                                                                StringArgumentType.word())
                                                        .then(Commands.argument(
                                                                        "reason",
                                                                        StringArgumentType.greedyString())
                                                                .executes(context ->
                                                                        scheduleRegisteredFacilityScopePolicy(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(
                                                                                        context,
                                                                                        "maxScopeChunks"),
                                                                                LongArgumentType.getLong(
                                                                                        context,
                                                                                        "effectiveAtEpochMillis"),
                                                                                StringArgumentType.getString(
                                                                                        context,
                                                                                        "requestId"),
                                                                                StringArgumentType.getString(
                                                                                        context,
                                                                                        "reason")))))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            citizenshipPolicyCommand() {
        return Commands.literal("citizenship")
                .then(Commands.literal("policy")
                        .then(Commands.literal("show")
                                .executes(context -> showCitizenshipPolicy(
                                        context.getSource())))
                        .then(Commands.literal("schedule")
                                .then(Commands.argument(
                                                "correctionGraceMillis",
                                                LongArgumentType.longArg(1L))
                                                .then(Commands.argument(
                                                        "transferCooldownMillis",
                                                        LongArgumentType.longArg(0L))
                                                .then(Commands.argument(
                                                                "reconciliationIntervalMillis",
                                                                LongArgumentType.longArg(1L))
                                                        .then(Commands.argument(
                                                                "effectiveAtEpochMillis",
                                                                LongArgumentType.longArg(0L))
                                                        .then(Commands.argument(
                                                                        "requestId",
                                                                        StringArgumentType.word())
                                                                .then(Commands.argument(
                                                                                "reason",
                                                                                StringArgumentType.greedyString())
                                                                        .executes(context ->
                                                                                scheduleCitizenshipPolicy(
                                                                                        context.getSource(),
                                                                                        LongArgumentType.getLong(
                                                                                                context,
                                                                                                "correctionGraceMillis"),
                                                                                        LongArgumentType.getLong(
                                                                                                context,
                                                                                                "transferCooldownMillis"),
                                                                                        LongArgumentType.getLong(
                                                                                                context,
                                                                                                "reconciliationIntervalMillis"),
                                                                                        LongArgumentType.getLong(
                                                                                                context,
                                                                                                "effectiveAtEpochMillis"),
                                                                                        StringArgumentType.getString(
                                                                                                context,
                                                                                                "requestId"),
                                                                                        StringArgumentType.getString(
                                                                                                context,
                                                                                                "reason")))))))))))
                .then(Commands.literal("population-policy")
                        .then(Commands.literal("show")
                                .executes(context -> showEffectiveCitizenPopulationPolicy(
                                        context.getSource())))
                        .then(Commands.literal("schedule")
                                .then(Commands.argument(
                                                "observationWindowMillis",
                                                LongArgumentType.longArg(1L))
                                        .then(Commands.argument(
                                                        "fullContributionTimeMillis",
                                                        LongArgumentType.longArg(1L))
                                                .then(Commands.argument(
                                                                "effectiveAtEpochMillis",
                                                                LongArgumentType.longArg(0L))
                                                        .then(Commands.argument(
                                                                        "requestId",
                                                                        StringArgumentType.word())
                                                                .then(Commands.argument(
                                                                                "reason",
                                                                                StringArgumentType.greedyString())
                                                                        .executes(context ->
                                                                                scheduleEffectiveCitizenPopulationPolicy(
                                                                                        context.getSource(),
                                                                                        LongArgumentType.getLong(context, "observationWindowMillis"),
                                                                                        LongArgumentType.getLong(context, "fullContributionTimeMillis"),
                                                                                        LongArgumentType.getLong(context, "effectiveAtEpochMillis"),
                                                                                        StringArgumentType.getString(context, "requestId"),
                                                                                        StringArgumentType.getString(context, "reason"))))))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            onlineTimeObservationPolicyCommand() {
        return Commands.literal("online-time")
                .then(Commands.literal("policy")
                        .then(Commands.literal("show")
                                .executes(context -> showOnlineTimeObservationPolicy(
                                        context.getSource())))
                        .then(Commands.literal("schedule")
                                .then(Commands.argument(
                                                "checkpointIntervalMillis",
                                                LongArgumentType.longArg(1L))
                                        .then(Commands.argument(
                                                        "effectiveAtEpochMillis",
                                                        LongArgumentType.longArg(0L))
                                                .then(Commands.argument(
                                                                "requestId",
                                                                StringArgumentType.word())
                                                        .then(Commands.argument(
                                                                        "reason",
                                                                        StringArgumentType.greedyString())
                                                                .executes(context ->
                                                                        scheduleOnlineTimeObservationPolicy(
                                                                                context.getSource(),
                                                                                LongArgumentType.getLong(
                                                                                        context,
                                                                                        "checkpointIntervalMillis"),
                                                                                LongArgumentType.getLong(
                                                                                        context,
                                                                                        "effectiveAtEpochMillis"),
                                                                                StringArgumentType.getString(
                                                                                        context,
                                                                                        "requestId"),
                                                                                StringArgumentType.getString(
                                                                                        context,
                                                                                        "reason")))))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            strengthPolicyCommand() {
        return Commands.literal("strength")
                .then(Commands.literal("effective-citizen")
                        .then(Commands.literal("show")
                                .executes(context -> showEffectiveCitizenStrengthPolicy(
                                        context.getSource())))
                        .then(Commands.literal("schedule")
                                .then(Commands.argument(
                                                "fullStrengthScaleCitizenEquivalents",
                                                IntegerArgumentType.integer(1))
                                        .then(Commands.argument(
                                                        "effectiveAtEpochMillis",
                                                        LongArgumentType.longArg(0L))
                                                .then(Commands.argument(
                                                                "requestId",
                                                                StringArgumentType.word())
                                                        .then(Commands.argument(
                                                                        "reason",
                                                                        StringArgumentType.greedyString())
                                                                .executes(context ->
                                                                        scheduleEffectiveCitizenStrengthPolicy(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(
                                                                                        context,
                                                                                        "fullStrengthScaleCitizenEquivalents"),
                                                                                LongArgumentType.getLong(
                                                                                        context,
                                                                                        "effectiveAtEpochMillis"),
                                                                                StringArgumentType.getString(
                                                                                        context,
                                                                                        "requestId"),
                                                                                StringArgumentType.getString(
                                                                                        context,
                                                                                        "reason")))))))))
                .then(Commands.literal("effective-territory")
                        .then(Commands.literal("show")
                                .executes(context -> showEffectiveTerritoryStrengthPolicy(
                                        context.getSource())))
                        .then(Commands.literal("schedule")
                                .then(Commands.argument(
                                                "fullStrengthScaleEffectiveClaims",
                                                IntegerArgumentType.integer(1))
                                        .then(Commands.argument(
                                                        "effectiveAtEpochMillis",
                                                        LongArgumentType.longArg(0L))
                                                .then(Commands.argument(
                                                                "requestId",
                                                                StringArgumentType.word())
                                                        .then(Commands.argument(
                                                                        "reason",
                                                                        StringArgumentType.greedyString())
                                                                .executes(context ->
                                                                        scheduleEffectiveTerritoryStrengthPolicy(
                                                                                context.getSource(),
                                                                                IntegerArgumentType.getInteger(
                                                                                        context,
                                                                                        "fullStrengthScaleEffectiveClaims"),
                                                                                LongArgumentType.getLong(
                                                                                        context,
                                                                                        "effectiveAtEpochMillis"),
                                                                                StringArgumentType.getString(
                                                                                        context,
                                                                                        "requestId"),
                                                                                StringArgumentType.getString(
                                                                                        context,
                                                                                        "reason")))))))))
                .then(Commands.literal("mint-compliance")
                        .then(Commands.literal("show")
                                .executes(context -> showMintCompliancePolicy(
                                        context.getSource())))
                        .then(Commands.literal("schedule")
                                .then(Commands.argument(
                                                "observationWindowMillis",
                                                LongArgumentType.longArg(1L))
                                        .then(Commands.argument(
                                                        "recoveredCommitBasisPoints",
                                                        IntegerArgumentType.integer(0, 10_000))
                                                .then(Commands.argument(
                                                                "effectiveAtEpochMillis",
                                                                LongArgumentType.longArg(0L))
                                                        .then(Commands.argument(
                                                                        "requestId",
                                                                        StringArgumentType.word())
                                                                .then(Commands.argument(
                                                                                "reason",
                                                                                StringArgumentType.greedyString())
                                                                        .executes(context ->
                                                                                scheduleMintCompliancePolicy(
                                                                                        context.getSource(),
                                                                                        LongArgumentType.getLong(
                                                                                                context,
                                                                                                "observationWindowMillis"),
                                                                                        IntegerArgumentType.getInteger(
                                                                                                context,
                                                                                                "recoveredCommitBasisPoints"),
                                                                                        LongArgumentType.getLong(
                                                                                                context,
                                                                                                "effectiveAtEpochMillis"),
                                                                                        StringArgumentType.getString(
                                                                                                context,
                                                                                                "requestId"),
                                                                                        StringArgumentType.getString(
                                                                                                context,
                                                                                                "reason"))))))))))
                .then(Commands.literal("auditable-activity")
                        .then(Commands.literal("show")
                                .executes(context -> showAuditableEconomicActivityPolicy(
                                        context.getSource())))
                        .then(Commands.literal("schedule")
                                .then(Commands.argument(
                                                "observationWindowMillis",
                                                LongArgumentType.longArg(1L))
                                        .then(Commands.argument(
                                                        "fullStrengthScaleMinorUnits",
                                                        LongArgumentType.longArg(1L))
                                                .then(Commands.argument(
                                                                "effectiveAtEpochMillis",
                                                                LongArgumentType.longArg(0L))
                                                        .then(Commands.argument(
                                                                        "requestId",
                                                                        StringArgumentType.word())
                                                                .then(Commands.argument(
                                                                                "reason",
                                                                                StringArgumentType.greedyString())
                                                                        .executes(context ->
                                                                                scheduleAuditableEconomicActivityPolicy(
                                                                                        context.getSource(),
                                                                                        LongArgumentType.getLong(
                                                                                                context,
                                                                                                "observationWindowMillis"),
                                                                                        LongArgumentType.getLong(
                                                                                                context,
                                                                                                "fullStrengthScaleMinorUnits"),
                                                                                        LongArgumentType.getLong(
                                                                                                context,
                                                                                                "effectiveAtEpochMillis"),
                                                                                        StringArgumentType.getString(
                                                                                                context,
                                                                                                "requestId"),
                                                                                        StringArgumentType.getString(
                                                                                                context,
                                                                                                "reason"))))))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            nationApplicationExpiryPolicyCommand() {
        var expirySchedule = Commands.literal("schedule")
                .then(Commands.argument("scanIntervalMillis", LongArgumentType.longArg(1L))
                        .then(Commands.argument(
                                        "effectiveAtEpochMillis", LongArgumentType.longArg(0L))
                                .then(Commands.argument("requestId", StringArgumentType.word())
                                        .then(Commands.argument(
                                                        "reason", StringArgumentType.greedyString())
                                                .executes(context ->
                                                        scheduleNationApplicationExpiryPolicy(
                                                                context.getSource(),
                                                                LongArgumentType.getLong(
                                                                        context,
                                                                        "scanIntervalMillis"),
                                                                LongArgumentType.getLong(
                                                                        context,
                                                                        "effectiveAtEpochMillis"),
                                                                StringArgumentType.getString(
                                                                        context, "requestId"),
                                                                StringArgumentType.getString(
                                                                        context, "reason")))))));
        var candidateSchedule = Commands.literal("schedule")
                .then(Commands.argument(
                                "observationWindowMillis", LongArgumentType.longArg(1L))
                        .then(Commands.argument(
                                        "effectiveAtEpochMillis", LongArgumentType.longArg(0L))
                                .then(Commands.argument("requestId", StringArgumentType.word())
                                        .then(Commands.argument(
                                                        "reason", StringArgumentType.greedyString())
                                                .executes(context ->
                                                        scheduleCandidateOnlineEvidencePolicy(
                                                                context.getSource(),
                                                                LongArgumentType.getLong(
                                                                        context,
                                                                        "observationWindowMillis"),
                                                                LongArgumentType.getLong(
                                                                        context,
                                                                        "effectiveAtEpochMillis"),
                                                                StringArgumentType.getString(
                                                                        context, "requestId"),
                                                                StringArgumentType.getString(
                                                                        context, "reason")))))));
        var lifetimeSchedule = Commands.literal("schedule")
                .then(Commands.argument("lifetimeMillis", LongArgumentType.longArg(1L))
                        .then(Commands.argument(
                                        "effectiveAtEpochMillis", LongArgumentType.longArg(0L))
                                .then(Commands.argument("requestId", StringArgumentType.word())
                                        .then(Commands.argument(
                                                        "reason", StringArgumentType.greedyString())
                                                .executes(context ->
                                                        scheduleNationApplicationLifetimePolicy(
                                                                context.getSource(),
                                                                LongArgumentType.getLong(
                                                                        context, "lifetimeMillis"),
                                                                LongArgumentType.getLong(
                                                                        context, "effectiveAtEpochMillis"),
                                                                StringArgumentType.getString(
                                                                        context, "requestId"),
                                                                StringArgumentType.getString(
                                                                        context, "reason")))))));
        var thresholdSchedule = Commands.literal("schedule")
                .then(Commands.argument(
                                "minimumEffectiveCandidates", IntegerArgumentType.integer(1))
                        .then(Commands.argument(
                                        "effectiveAtEpochMillis", LongArgumentType.longArg(0L))
                                .then(Commands.argument("requestId", StringArgumentType.word())
                                        .then(Commands.argument(
                                                        "reason", StringArgumentType.greedyString())
                                                .executes(context ->
                                                        scheduleNationFoundingCandidateThresholdPolicy(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(
                                                                        context, "minimumEffectiveCandidates"),
                                                                LongArgumentType.getLong(
                                                                        context, "effectiveAtEpochMillis"),
                                                                StringArgumentType.getString(
                                                                        context, "requestId"),
                                                                StringArgumentType.getString(
                                                                        context, "reason")))))));
        return Commands.literal("nation-application")
                .then(Commands.literal("expiry-policy")
                        .then(Commands.literal("show")
                                .executes(context -> showNationApplicationExpiryPolicy(
                                        context.getSource())))
                        .then(expirySchedule))
                .then(Commands.literal("candidate-evidence-policy")
                        .then(Commands.literal("show")
                                .executes(context -> showCandidateOnlineEvidencePolicy(
                                        context.getSource())))
                        .then(candidateSchedule))
                .then(Commands.literal("lifetime-policy")
                        .then(Commands.literal("show")
                                .executes(context -> showNationApplicationLifetimePolicy(
                                        context.getSource())))
                        .then(lifetimeSchedule))
                .then(Commands.literal("candidate-threshold-policy")
                        .then(Commands.literal("show")
                                .executes(context -> showNationFoundingCandidateThresholdPolicy(
                                        context.getSource())))
                        .then(thresholdSchedule));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            productionStrengthPolicyCommand() {
        return Commands.literal("strength-policy")
                .then(Commands.literal("show")
                        .executes(context -> showProductionStrengthPolicy(
                                context.getSource())))
                .then(Commands.literal("schedule")
                        .then(Commands.argument(
                                        "observationWindowMillis",
                                        LongArgumentType.longArg(1L))
                                .then(Commands.argument(
                                                "fullWeightWindowMillis",
                                                LongArgumentType.longArg(1L))
                                        .then(Commands.argument(
                                                        "fullStrengthScaleMinorUnits",
                                                        LongArgumentType.longArg(1L))
                                                .then(Commands.argument(
                                                                "effectiveAtEpochMillis",
                                                                LongArgumentType.longArg(0L))
                                                        .then(Commands.argument(
                                                                        "requestId",
                                                                        StringArgumentType.word())
                                                                .then(Commands.argument(
                                                                                "reason",
                                                                                StringArgumentType.greedyString())
                                                                        .executes(context ->
                                                                                scheduleProductionStrengthPolicy(
                                                                                        context.getSource(),
                                                                                        LongArgumentType.getLong(context, "observationWindowMillis"),
                                                                                        LongArgumentType.getLong(context, "fullWeightWindowMillis"),
                                                                                        LongArgumentType.getLong(context, "fullStrengthScaleMinorUnits"),
                                                                                        LongArgumentType.getLong(context, "effectiveAtEpochMillis"),
                                                                                        StringArgumentType.getString(context, "requestId"),
                                                                                        StringArgumentType.getString(context, "reason"))))))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            productionIndustryCommand() {
        return Commands.literal("industry")
                .then(Commands.literal("show")
                        .then(Commands.argument("createVersion", StringArgumentType.word())
                                .then(Commands.argument("recipeId", ResourceLocationArgument.id())
                                        .executes(context -> showProductionIndustryAssignment(
                                                context.getSource(),
                                                StringArgumentType.getString(
                                                        context, "createVersion"),
                                                ResourceLocationArgument.getId(
                                                        context, "recipeId").toString())))))
                .then(Commands.literal("schedule")
                        .then(Commands.argument("createVersion", StringArgumentType.word())
                                .then(Commands.argument("recipeId", ResourceLocationArgument.id())
                                        .then(Commands.argument(
                                                        "industryId",
                                                        StringArgumentType.word())
                                                .then(Commands.argument(
                                                                "effectiveAtEpochMillis",
                                                                LongArgumentType.longArg(0L))
                                                        .then(Commands.argument(
                                                                        "requestId",
                                                                        StringArgumentType.word())
                                                                .then(Commands.argument(
                                                                                "reason",
                                                                                StringArgumentType.greedyString())
                                                                        .executes(context ->
                                                                                scheduleProductionIndustryAssignment(
                                                                                        context.getSource(),
                                                                                        StringArgumentType.getString(context, "createVersion"),
                                                                                        ResourceLocationArgument.getId(context, "recipeId").toString(),
                                                                                        StringArgumentType.getString(context, "industryId"),
                                                                                        LongArgumentType.getLong(context, "effectiveAtEpochMillis"),
                                                                                        StringArgumentType.getString(context, "requestId"),
                                                                                        StringArgumentType.getString(context, "reason"))))))))));
    }

    private static int showProductionIndustryAssignment(
            CommandSourceStack source, String createVersion, String recipeId) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new ProductionIndustryAssignmentRegistry(
                                database, clock)
                        .assignment(createVersion, recipeId, Instant.now(clock)))
                .whenComplete((assignment, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportDatabaseFailure(
                                source, "Production Industry assignment query", failure);
                    } else if (assignment.isEmpty()) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Production Industry is not assigned for createVersion="
                                                + createVersion + " recipe=" + recipeId
                                                + "; contribution remains paused"),
                                false);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(formatProductionIndustryAssignment(
                                        assignment.orElseThrow())),
                                false);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Production Industry assignment query queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int scheduleProductionIndustryAssignment(
            CommandSourceStack source,
            String createVersion,
            String recipeId,
            String industryId,
            long effectiveAtEpochMillis,
            String requestId,
            String reason) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new ProductionIndustryAssignmentRegistry(
                                database, clock)
                        .schedule(new ScheduleProductionIndustryAssignment(
                                PRODUCTION_INDUSTRY_SERVICE,
                                requestId,
                                administrator(source).value(),
                                createVersion,
                                recipeId,
                                new ProductionIndustryId(industryId),
                                Instant.ofEpochMilli(effectiveAtEpochMillis),
                                reason)))
                .whenComplete((assignment, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Scheduled "
                                                + formatProductionIndustryAssignment(assignment)),
                                true);
                    } else {
                        reportDatabaseFailure(
                                source, "Production Industry assignment schedule", failure);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Production Industry assignment schedule queued"),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatProductionIndustryAssignment(
            ProductionIndustryAssignmentVersion assignment) {
        return "Production Industry assignment " + assignment.assignmentId()
                + " createVersion=" + assignment.createVersion()
                + " recipe=" + assignment.recipeId()
                + " industry=" + assignment.industryId().value()
                + " effectiveAt=" + assignment.effectiveAt()
                + " actor=" + assignment.actorIdentity();
    }

    private static int showProductionMarginalReturnPolicy(CommandSourceStack source) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new ProductionMarginalReturnPolicyRegistry(
                                database, clock)
                        .current(Instant.now(clock)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportDatabaseFailure(
                                source, "Production Marginal Return policy query", failure);
                    } else if (policy.isEmpty()) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Production Marginal Return policy is not configured; "
                                                + "production scoring remains paused"),
                                false);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(
                                        formatProductionMarginalReturnPolicy(
                                                policy.orElseThrow())),
                                false);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Production Marginal Return policy query queued"),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static int scheduleProductionMarginalReturnPolicy(
            CommandSourceStack source,
            long facilitySoftCapMinorUnits,
            int facilityExcessWeightBasisPoints,
            long industrySoftCapMinorUnits,
            int industryExcessWeightBasisPoints,
            long effectiveAtEpochMillis,
            String requestId,
            String reason) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new ProductionMarginalReturnPolicyRegistry(
                                database, clock)
                        .schedule(new ScheduleProductionMarginalReturnPolicy(
                                PRODUCTION_POLICY_SERVICE,
                                requestId,
                                administrator(source).value(),
                                new ProductionMarginalReturnPolicy(
                                        facilitySoftCapMinorUnits,
                                        facilityExcessWeightBasisPoints,
                                        industrySoftCapMinorUnits,
                                        industryExcessWeightBasisPoints),
                                Instant.ofEpochMilli(effectiveAtEpochMillis),
                                reason)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Scheduled "
                                                + formatProductionMarginalReturnPolicy(policy)),
                                true);
                    } else {
                        reportDatabaseFailure(
                                source, "Production Marginal Return policy schedule", failure);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Production Marginal Return policy schedule queued"),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatProductionMarginalReturnPolicy(
            ProductionMarginalReturnPolicyVersion version) {
        ProductionMarginalReturnPolicy policy = version.policy();
        return "Production Marginal Return policy " + version.policyId()
                + " facilitySoftCapMinorUnits=" + policy.facilitySoftCapMinorUnits()
                + " facilityExcessWeightBasisPoints="
                + policy.facilityExcessWeightBasisPoints()
                + " industrySoftCapMinorUnits=" + policy.industrySoftCapMinorUnits()
                + " industryExcessWeightBasisPoints="
                + policy.industryExcessWeightBasisPoints()
                + " effectiveAt=" + version.effectiveAt()
                + " actor=" + version.actorIdentity();
    }

    private static int showProductionStrengthPolicy(CommandSourceStack source) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new ProductionStrengthPolicyRegistry(database, clock)
                        .current(Instant.now(clock)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportDatabaseFailure(source, "Production Strength policy query", failure);
                    } else if (policy.isEmpty()) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Production Strength policy is not configured; "
                                                + "production scoring remains paused"),
                                false);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(formatProductionStrengthPolicy(
                                        policy.orElseThrow())),
                                false);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Production Strength policy query queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int scheduleProductionStrengthPolicy(
            CommandSourceStack source,
            long observationWindowMillis,
            long fullWeightWindowMillis,
            long fullStrengthScaleMinorUnits,
            long effectiveAtEpochMillis,
            String requestId,
            String reason) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new ProductionStrengthPolicyRegistry(database, clock)
                        .schedule(new ScheduleProductionStrengthPolicy(
                                PRODUCTION_STRENGTH_POLICY_SERVICE,
                                requestId,
                                administrator(source).value(),
                                new ProductionStrengthPolicy(
                                        Duration.ofMillis(observationWindowMillis),
                                        Duration.ofMillis(fullWeightWindowMillis),
                                        fullStrengthScaleMinorUnits),
                                Instant.ofEpochMilli(effectiveAtEpochMillis),
                                reason)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Scheduled " + formatProductionStrengthPolicy(policy)),
                                true);
                    } else {
                        reportDatabaseFailure(
                                source, "Production Strength policy schedule", failure);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Production Strength policy schedule queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatProductionStrengthPolicy(
            ProductionStrengthPolicyVersion version) {
        ProductionStrengthPolicy policy = version.policy();
        return "Production Strength policy " + version.policyId()
                + " observationWindowMillis=" + policy.observationWindowMillis()
                + " fullWeightWindowMillis=" + policy.fullWeightWindowMillis()
                + " fullStrengthScaleMinorUnits="
                + policy.fullStrengthScaleMinorUnits()
                + " effectiveAt=" + version.effectiveAt()
                + " actor=" + version.actorIdentity();
    }

    private static int showRegisteredFacilityScopePolicy(CommandSourceStack source) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new RegisteredFacilityScopePolicyRegistry(
                                database, clock)
                        .current(Instant.now(clock)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportDatabaseFailure(
                                source, "Registered Facility Scope policy query", failure);
                    } else if (policy.isEmpty()) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Registered Facility Scope policy is not configured; Facility writes remain disabled"),
                                false);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(formatRegisteredFacilityScopePolicy(
                                        policy.orElseThrow())),
                                false);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Registered Facility Scope policy query queued"),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static int scheduleRegisteredFacilityScopePolicy(
            CommandSourceStack source,
            int maxScopeChunks,
            long effectiveAtEpochMillis,
            String requestId,
            String reason) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new RegisteredFacilityScopePolicyRegistry(
                                database, clock)
                        .schedule(new ScheduleRegisteredFacilityScopePolicy(
                                REGISTERED_FACILITY_SCOPE_POLICY_SERVICE,
                                requestId,
                                administrator(source).value(),
                                new RegisteredFacilityScopePolicy(maxScopeChunks),
                                Instant.ofEpochMilli(effectiveAtEpochMillis),
                                reason)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(
                                () -> Component.literal("Scheduled "
                                        + formatRegisteredFacilityScopePolicy(policy)),
                                true);
                    } else {
                        reportDatabaseFailure(
                                source, "Registered Facility Scope policy schedule", failure);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Registered Facility Scope policy schedule queued"),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatRegisteredFacilityScopePolicy(
            RegisteredFacilityScopePolicyVersion version) {
        return "Registered Facility Scope policy " + version.policyId()
                + " maxScopeChunks=" + version.policy().maxScopeChunks()
                + " effectiveAt=" + version.effectiveAt()
                + " actor=" + version.actorIdentity();
    }

    private static int showCitizenshipPolicy(CommandSourceStack source) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new CitizenshipPolicyRegistry(database, clock)
                        .current(Instant.now(clock)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportDatabaseFailure(source, "Citizenship policy query", failure);
                    } else if (policy.isEmpty()) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Citizenship policy is not configured; Citizenship writes and reconciliation remain disabled"),
                                false);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(formatCitizenshipPolicy(
                                        policy.orElseThrow())),
                                false);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Citizenship policy query queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int showEffectiveCitizenPopulationPolicy(CommandSourceStack source) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new EffectiveCitizenPopulationPolicyRegistry(database, clock)
                        .current(Instant.now(clock)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportDatabaseFailure(source, "Effective Citizen population policy query", failure);
                    } else if (policy.isEmpty()) {
                        source.sendSuccess(() -> Component.literal(
                                "Effective Citizen population policy is not configured; dependent population, territory and strength calculations remain disabled"), false);
                    } else {
                        source.sendSuccess(() -> Component.literal(
                                formatEffectiveCitizenPopulationPolicy(policy.orElseThrow())), false);
                    }
                }));
        source.sendSuccess(() -> Component.literal(
                "Effective Citizen population policy query queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int scheduleEffectiveCitizenPopulationPolicy(
            CommandSourceStack source, long observationWindowMillis,
            long fullContributionTimeMillis, long effectiveAtEpochMillis,
            String requestId, String reason) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new EffectiveCitizenPopulationPolicyRegistry(database, clock)
                        .schedule(new ScheduleEffectiveCitizenPopulationPolicy(
                                EFFECTIVE_CITIZEN_POPULATION_POLICY_SERVICE,
                                requestId,
                                administrator(source).value(),
                                new EffectiveCitizenPopulationPolicy(
                                        Duration.ofMillis(observationWindowMillis),
                                        Duration.ofMillis(fullContributionTimeMillis)),
                                Instant.ofEpochMilli(effectiveAtEpochMillis), reason)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(() -> Component.literal(
                                "Scheduled " + formatEffectiveCitizenPopulationPolicy(policy)), true);
                    } else {
                        reportDatabaseFailure(source,
                                "Effective Citizen population policy schedule", failure);
                    }
                }));
        source.sendSuccess(() -> Component.literal(
                "Effective Citizen population policy schedule queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatEffectiveCitizenPopulationPolicy(
            EffectiveCitizenPopulationPolicyVersion version) {
        return "Effective Citizen population policy " + version.policyId()
                + " observationWindowMillis=" + version.policy().observationWindow().toMillis()
                + " fullContributionTimeMillis="
                + version.policy().fullContributionTime().toMillis()
                + " effectiveAt=" + version.effectiveAt()
                + " actor=" + version.actorIdentity();
    }

    private static int scheduleCitizenshipPolicy(
            CommandSourceStack source,
            long correctionGraceMillis,
            long transferCooldownMillis,
            long reconciliationIntervalMillis,
            long effectiveAtEpochMillis,
            String requestId,
            String reason) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new CitizenshipPolicyRegistry(database, clock)
                        .schedule(new ScheduleCitizenshipPolicy(
                                CITIZENSHIP_POLICY_SERVICE,
                                requestId,
                                administrator(source).value(),
                                new CitizenshipPolicy(
                                        java.time.Duration.ofMillis(correctionGraceMillis),
                                        java.time.Duration.ofMillis(transferCooldownMillis),
                                        java.time.Duration.ofMillis(
                                                reconciliationIntervalMillis)),
                                Instant.ofEpochMilli(effectiveAtEpochMillis),
                                reason)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Scheduled " + formatCitizenshipPolicy(policy)),
                                true);
                    } else {
                        reportDatabaseFailure(source, "Citizenship policy schedule", failure);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Citizenship policy schedule queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatCitizenshipPolicy(CitizenshipPolicyVersion version) {
        return "Citizenship policy " + version.policyId()
                + " correctionGraceMillis=" + version.policy().correctionGrace().toMillis()
                + " transferCooldownMillis=" + version.policy().transferCooldown().toMillis()
                + " reconciliationIntervalMillis="
                + version.policy().reconciliationInterval().toMillis()
                + " effectiveAt=" + version.effectiveAt()
                + " actor=" + version.actorIdentity();
    }

    private static int showOnlineTimeObservationPolicy(CommandSourceStack source) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new OnlineTimeObservationPolicyRegistry(database, clock)
                        .current(Instant.now(clock)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportDatabaseFailure(source, "Online Time Observation policy query", failure);
                    } else if (policy.isEmpty()) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Online Time Observation policy is not configured; periodic online-time checkpoints remain disabled"),
                                false);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(formatOnlineTimeObservationPolicy(
                                        policy.orElseThrow())),
                                false);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Online Time Observation policy query queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int showNationApplicationExpiryPolicy(CommandSourceStack source) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new NationApplicationExpiryPolicyRegistry(database, clock)
                        .current(Instant.now(clock)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportDatabaseFailure(source, "Nation Application expiry policy query", failure);
                    } else if (policy.isEmpty()) {
                        source.sendSuccess(() -> Component.literal(
                                "Nation Application expiry policy is not configured; automatic expiry scans remain disabled"), false);
                    } else {
                        source.sendSuccess(() -> Component.literal(
                                formatNationApplicationExpiryPolicy(policy.orElseThrow())), false);
                    }
                }));
        source.sendSuccess(() -> Component.literal(
                "Nation Application expiry policy query queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int scheduleNationApplicationExpiryPolicy(
            CommandSourceStack source,
            long scanIntervalMillis,
            long effectiveAtEpochMillis,
            String requestId,
            String reason) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new NationApplicationExpiryPolicyRegistry(database, clock)
                        .schedule(new ScheduleNationApplicationExpiryPolicy(
                                NATION_APPLICATION_EXPIRY_POLICY_SERVICE,
                                requestId,
                                administrator(source).value(),
                                new NationApplicationExpiryPolicy(
                                        java.time.Duration.ofMillis(scanIntervalMillis)),
                                Instant.ofEpochMilli(effectiveAtEpochMillis),
                                reason)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(() -> Component.literal(
                                "Scheduled " + formatNationApplicationExpiryPolicy(policy)), true);
                    } else {
                        reportDatabaseFailure(source, "Nation Application expiry policy schedule", failure);
                    }
                }));
        source.sendSuccess(() -> Component.literal(
                "Nation Application expiry policy schedule queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatNationApplicationExpiryPolicy(
            NationApplicationExpiryPolicyVersion version) {
        return "Nation Application expiry policy " + version.policyId()
                + " scanIntervalMillis=" + version.policy().scanInterval().toMillis()
                + " effectiveAt=" + version.effectiveAt()
                + " actor=" + version.actorIdentity();
    }

    private static int showCandidateOnlineEvidencePolicy(CommandSourceStack source) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new CandidateOnlineEvidencePolicyRegistry(database, clock)
                        .current(Instant.now(clock)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportDatabaseFailure(
                                source, "Candidate Online Evidence policy query", failure);
                    } else if (policy.isEmpty()) {
                        source.sendSuccess(() -> Component.literal(
                                "Candidate Online Evidence policy is not configured; founding evidence operations remain disabled"), false);
                    } else {
                        source.sendSuccess(() -> Component.literal(
                                formatCandidateOnlineEvidencePolicy(policy.orElseThrow())), false);
                    }
                }));
        source.sendSuccess(() -> Component.literal(
                "Candidate Online Evidence policy query queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int scheduleCandidateOnlineEvidencePolicy(
            CommandSourceStack source,
            long observationWindowMillis,
            long effectiveAtEpochMillis,
            String requestId,
            String reason) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new CandidateOnlineEvidencePolicyRegistry(database, clock)
                        .schedule(new ScheduleCandidateOnlineEvidencePolicy(
                                CANDIDATE_ONLINE_EVIDENCE_POLICY_SERVICE,
                                requestId,
                                administrator(source).value(),
                                new CandidateOnlineEvidencePolicy(
                                        Duration.ofMillis(observationWindowMillis)),
                                Instant.ofEpochMilli(effectiveAtEpochMillis),
                                reason)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(() -> Component.literal(
                                "Scheduled " + formatCandidateOnlineEvidencePolicy(policy)), true);
                    } else {
                        reportDatabaseFailure(
                                source, "Candidate Online Evidence policy schedule", failure);
                    }
                }));
        source.sendSuccess(() -> Component.literal(
                "Candidate Online Evidence policy schedule queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatCandidateOnlineEvidencePolicy(
            CandidateOnlineEvidencePolicyVersion version) {
        return "Candidate Online Evidence policy " + version.policyId()
                + " observationWindowMillis="
                + version.policy().observationWindow().toMillis()
                + " effectiveAt=" + version.effectiveAt()
                + " actor=" + version.actorIdentity();
    }

    private static int showNationApplicationLifetimePolicy(CommandSourceStack source) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new NationApplicationLifetimePolicyRegistry(database, clock)
                        .current(Instant.now(clock)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportDatabaseFailure(source, "Nation Application lifetime policy query", failure);
                    } else if (policy.isEmpty()) {
                        source.sendSuccess(() -> Component.literal(
                                "Nation Application lifetime policy is not configured; new applications remain disabled"), false);
                    } else {
                        source.sendSuccess(() -> Component.literal(
                                formatNationApplicationLifetimePolicy(policy.orElseThrow())), false);
                    }
                }));
        source.sendSuccess(() -> Component.literal(
                "Nation Application lifetime policy query queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int scheduleNationApplicationLifetimePolicy(
            CommandSourceStack source, long lifetimeMillis, long effectiveAtEpochMillis,
            String requestId, String reason) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new NationApplicationLifetimePolicyRegistry(database, clock)
                        .schedule(new ScheduleNationApplicationLifetimePolicy(
                                NATION_APPLICATION_LIFETIME_POLICY_SERVICE,
                                requestId,
                                administrator(source).value(),
                                new NationApplicationLifetimePolicy(Duration.ofMillis(lifetimeMillis)),
                                Instant.ofEpochMilli(effectiveAtEpochMillis),
                                reason)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(() -> Component.literal(
                                "Scheduled " + formatNationApplicationLifetimePolicy(policy)), true);
                    } else {
                        reportDatabaseFailure(source, "Nation Application lifetime policy schedule", failure);
                    }
                }));
        source.sendSuccess(() -> Component.literal(
                "Nation Application lifetime policy schedule queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatNationApplicationLifetimePolicy(
            NationApplicationLifetimePolicyVersion version) {
        return "Nation Application lifetime policy " + version.policyId()
                + " lifetimeMillis=" + version.policy().lifetime().toMillis()
                + " effectiveAt=" + version.effectiveAt()
                + " actor=" + version.actorIdentity();
    }

    private static int showNationFoundingCandidateThresholdPolicy(CommandSourceStack source) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new NationFoundingCandidateThresholdPolicyRegistry(
                                database, clock)
                        .current(Instant.now(clock)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportDatabaseFailure(source, "Formal Nation founding candidate threshold query", failure);
                    } else if (policy.isEmpty()) {
                        source.sendSuccess(() -> Component.literal(
                                "Formal Nation founding candidate threshold is not configured; formal activation remains disabled"), false);
                    } else {
                        source.sendSuccess(() -> Component.literal(
                                formatNationFoundingCandidateThresholdPolicy(policy.orElseThrow())), false);
                    }
                }));
        source.sendSuccess(() -> Component.literal(
                "Formal Nation founding candidate threshold query queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int scheduleNationFoundingCandidateThresholdPolicy(
            CommandSourceStack source, int minimumEffectiveCandidates,
            long effectiveAtEpochMillis, String requestId, String reason) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new NationFoundingCandidateThresholdPolicyRegistry(
                                database, clock)
                        .schedule(new ScheduleNationFoundingCandidateThresholdPolicy(
                                NATION_FOUNDING_CANDIDATE_THRESHOLD_POLICY_SERVICE,
                                requestId,
                                administrator(source).value(),
                                new NationFoundingCandidateThresholdPolicy(minimumEffectiveCandidates),
                                Instant.ofEpochMilli(effectiveAtEpochMillis),
                                reason)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(() -> Component.literal(
                                "Scheduled " + formatNationFoundingCandidateThresholdPolicy(policy)), true);
                    } else {
                        reportDatabaseFailure(source,
                                "Formal Nation founding candidate threshold schedule", failure);
                    }
                }));
        source.sendSuccess(() -> Component.literal(
                "Formal Nation founding candidate threshold schedule queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatNationFoundingCandidateThresholdPolicy(
            NationFoundingCandidateThresholdPolicyVersion version) {
        return "Formal Nation founding candidate threshold policy " + version.policyId()
                + " minimumEffectiveCandidates="
                + version.policy().minimumEffectiveCandidates()
                + " effectiveAt=" + version.effectiveAt()
                + " actor=" + version.actorIdentity();
    }

    private static int scheduleOnlineTimeObservationPolicy(
            CommandSourceStack source,
            long checkpointIntervalMillis,
            long effectiveAtEpochMillis,
            String requestId,
            String reason) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new OnlineTimeObservationPolicyRegistry(database, clock)
                        .schedule(new ScheduleOnlineTimeObservationPolicy(
                                ONLINE_TIME_OBSERVATION_POLICY_SERVICE,
                                requestId,
                                administrator(source).value(),
                                new OnlineTimeObservationPolicy(
                                        java.time.Duration.ofMillis(checkpointIntervalMillis)),
                                Instant.ofEpochMilli(effectiveAtEpochMillis),
                                reason)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Scheduled " + formatOnlineTimeObservationPolicy(policy)),
                                true);
                    } else {
                        reportDatabaseFailure(
                                source, "Online Time Observation policy schedule", failure);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Online Time Observation policy schedule queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatOnlineTimeObservationPolicy(
            OnlineTimeObservationPolicyVersion version) {
        return "Online Time Observation policy " + version.policyId()
                + " checkpointIntervalMillis="
                + version.policy().checkpointInterval().toMillis()
                + " effectiveAt=" + version.effectiveAt()
                + " actor=" + version.actorIdentity();
    }

    private static int showEffectiveCitizenStrengthPolicy(CommandSourceStack source) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new EffectiveCitizenStrengthPolicyRegistry(
                                database, clock)
                        .current(Instant.now(clock)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportDatabaseFailure(
                                source, "Effective Citizen Strength policy query", failure);
                    } else if (policy.isEmpty()) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Effective Citizen Strength policy is not configured; "
                                                + "the population component remains paused"),
                                false);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(formatEffectiveCitizenStrengthPolicy(
                                        policy.orElseThrow())),
                                false);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Effective Citizen Strength policy query queued"),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static int scheduleEffectiveCitizenStrengthPolicy(
            CommandSourceStack source,
            int fullStrengthScaleCitizenEquivalents,
            long effectiveAtEpochMillis,
            String requestId,
            String reason) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new EffectiveCitizenStrengthPolicyRegistry(
                                database, clock)
                        .schedule(new ScheduleEffectiveCitizenStrengthPolicy(
                                EFFECTIVE_CITIZEN_STRENGTH_POLICY_SERVICE,
                                requestId,
                                administrator(source).value(),
                                new EffectiveCitizenStrengthPolicy(
                                        fullStrengthScaleCitizenEquivalents),
                                Instant.ofEpochMilli(effectiveAtEpochMillis),
                                reason)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(
                                () -> Component.literal("Scheduled "
                                        + formatEffectiveCitizenStrengthPolicy(policy)),
                                true);
                    } else {
                        reportDatabaseFailure(
                                source, "Effective Citizen Strength policy schedule", failure);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Effective Citizen Strength policy schedule queued"),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatEffectiveCitizenStrengthPolicy(
            EffectiveCitizenStrengthPolicyVersion version) {
        return "Effective Citizen Strength policy " + version.policyId()
                + " fullStrengthScaleCitizenEquivalents="
                + version.policy().fullStrengthScaleCitizenEquivalents()
                + " effectiveAt=" + version.effectiveAt()
                + " actor=" + version.actorIdentity();
    }

    private static int showEffectiveTerritoryStrengthPolicy(CommandSourceStack source) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new EffectiveTerritoryStrengthPolicyRegistry(
                                database, clock)
                        .current(Instant.now(clock)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportDatabaseFailure(
                                source, "Effective Territory Strength policy query", failure);
                    } else if (policy.isEmpty()) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Effective Territory Strength policy is not configured; "
                                                + "the territory component remains paused"),
                                false);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(formatEffectiveTerritoryStrengthPolicy(
                                        policy.orElseThrow())),
                                false);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Effective Territory Strength policy query queued"),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static int scheduleEffectiveTerritoryStrengthPolicy(
            CommandSourceStack source,
            int fullStrengthScaleEffectiveClaims,
            long effectiveAtEpochMillis,
            String requestId,
            String reason) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new EffectiveTerritoryStrengthPolicyRegistry(
                                database, clock)
                        .schedule(new ScheduleEffectiveTerritoryStrengthPolicy(
                                EFFECTIVE_TERRITORY_STRENGTH_POLICY_SERVICE,
                                requestId,
                                administrator(source).value(),
                                new EffectiveTerritoryStrengthPolicy(
                                        fullStrengthScaleEffectiveClaims),
                                Instant.ofEpochMilli(effectiveAtEpochMillis),
                                reason)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(
                                () -> Component.literal("Scheduled "
                                        + formatEffectiveTerritoryStrengthPolicy(policy)),
                                true);
                    } else {
                        reportDatabaseFailure(
                                source, "Effective Territory Strength policy schedule", failure);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Effective Territory Strength policy schedule queued"),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatEffectiveTerritoryStrengthPolicy(
            EffectiveTerritoryStrengthPolicyVersion version) {
        return "Effective Territory Strength policy " + version.policyId()
                + " fullStrengthScaleEffectiveClaims="
                + version.policy().fullStrengthScaleEffectiveClaims()
                + " effectiveAt=" + version.effectiveAt()
                + " actor=" + version.actorIdentity();
    }

    private static int showMintCompliancePolicy(CommandSourceStack source) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new MintCompliancePolicyRegistry(database, clock)
                        .current(Instant.now(clock)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportDatabaseFailure(source, "Mint Compliance policy query", failure);
                    } else if (policy.isEmpty()) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Mint Compliance policy is not configured; the compliance component remains paused"),
                                false);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(formatMintCompliancePolicy(
                                        policy.orElseThrow())),
                                false);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Mint Compliance policy query queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int scheduleMintCompliancePolicy(
            CommandSourceStack source,
            long observationWindowMillis,
            int recoveredCommitBasisPoints,
            long effectiveAtEpochMillis,
            String requestId,
            String reason) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new MintCompliancePolicyRegistry(database, clock)
                        .schedule(new ScheduleMintCompliancePolicy(
                                MINT_COMPLIANCE_POLICY_SERVICE,
                                requestId,
                                administrator(source).value(),
                                new MintCompliancePolicy(
                                        Duration.ofMillis(observationWindowMillis),
                                        recoveredCommitBasisPoints),
                                Instant.ofEpochMilli(effectiveAtEpochMillis),
                                reason)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Scheduled " + formatMintCompliancePolicy(policy)),
                                true);
                    } else {
                        reportDatabaseFailure(source, "Mint Compliance policy schedule", failure);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Mint Compliance policy schedule queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatMintCompliancePolicy(MintCompliancePolicyVersion version) {
        return "Mint Compliance policy " + version.policyId()
                + " observationWindow=" + version.policy().observationWindow()
                + " recoveredCommitBasisPoints="
                + version.policy().recoveredCommitBasisPoints()
                + " effectiveAt=" + version.effectiveAt()
                + " actor=" + version.actorIdentity();
    }

    private static int showAuditableEconomicActivityPolicy(CommandSourceStack source) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new AuditableEconomicActivityPolicyRegistry(
                                database, clock)
                        .current(Instant.now(clock)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportDatabaseFailure(
                                source, "Auditable Economic Activity policy query", failure);
                    } else if (policy.isEmpty()) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Auditable Economic Activity policy is not configured; the activity component remains paused"),
                                false);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(formatAuditableEconomicActivityPolicy(
                                        policy.orElseThrow())),
                                false);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal(
                        "Auditable Economic Activity policy query queued"),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static int scheduleAuditableEconomicActivityPolicy(
            CommandSourceStack source,
            long observationWindowMillis,
            long fullStrengthScaleMinorUnits,
            long effectiveAtEpochMillis,
            String requestId,
            String reason) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new AuditableEconomicActivityPolicyRegistry(
                                database, clock)
                        .schedule(new ScheduleAuditableEconomicActivityPolicy(
                                AUDITABLE_ECONOMIC_ACTIVITY_POLICY_SERVICE,
                                requestId,
                                administrator(source).value(),
                                new AuditableEconomicActivityPolicy(
                                        Duration.ofMillis(observationWindowMillis),
                                        fullStrengthScaleMinorUnits),
                                Instant.ofEpochMilli(effectiveAtEpochMillis),
                                reason)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(
                                () -> Component.literal("Scheduled "
                                        + formatAuditableEconomicActivityPolicy(policy)),
                                true);
                    } else {
                        reportDatabaseFailure(
                                source, "Auditable Economic Activity policy schedule", failure);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal(
                        "Auditable Economic Activity policy schedule queued"),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatAuditableEconomicActivityPolicy(
            AuditableEconomicActivityPolicyVersion version) {
        return "Auditable Economic Activity policy " + version.policyId()
                + " observationWindow=" + version.policy().observationWindow()
                + " fullStrengthScaleMinorUnits="
                + version.policy().fullStrengthScaleMinorUnits()
                + " effectiveAt=" + version.effectiveAt()
                + " actor=" + version.actorIdentity();
    }

    private static int showTerritoryMaintenancePolicy(CommandSourceStack source) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new TerritoryMaintenancePolicyRegistry(database, clock)
                        .current(Instant.now(clock)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportDatabaseFailure(source, "Territory maintenance policy query", failure);
                    } else if (policy.isEmpty()) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Territory Maintenance policy is not configured; automatic maintenance is disabled"),
                                false);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(formatTerritoryMaintenancePolicy(
                                        policy.orElseThrow())),
                                false);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Territory maintenance policy query queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int scheduleTerritoryMaintenancePolicy(
            CommandSourceStack source,
            long cycleDurationMillis,
            long baseMaintenancePerClaim,
            int enclaveMultiplierBasisPoints,
            long forceLoadSurcharge,
            long restorationFee,
            long restorationCooldownMillis,
            int destructionBasisPoints,
            long effectiveAtEpochMillis,
            String requestId,
            String reason) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new TerritoryMaintenancePolicyRegistry(database, clock)
                        .schedule(new ScheduleTerritoryMaintenancePolicy(
                                TERRITORY_MAINTENANCE_POLICY_SERVICE,
                                requestId,
                                administrator(source).value(),
                                Duration.ofMillis(cycleDurationMillis),
                                baseMaintenancePerClaim,
                                enclaveMultiplierBasisPoints,
                                forceLoadSurcharge,
                                restorationFee,
                                Duration.ofMillis(restorationCooldownMillis),
                                destructionBasisPoints,
                                Instant.ofEpochMilli(effectiveAtEpochMillis),
                                reason)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Scheduled " + formatTerritoryMaintenancePolicy(policy)),
                                true);
                    } else {
                        reportDatabaseFailure(
                                source, "Territory maintenance policy schedule", failure);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Territory maintenance policy schedule queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatTerritoryMaintenancePolicy(
            TerritoryMaintenancePolicyVersion policy) {
        return "Territory Maintenance policy " + policy.policyId()
                + " cycleDurationMillis=" + policy.cycleDuration().toMillis()
                + " baseMaintenancePerClaim="
                + policy.baseMaintenancePerChargeableClaimMinorUnits()
                + " enclaveMultiplierBasisPoints="
                + policy.enclaveAndCrossDimensionMultiplierBasisPoints()
                + " forceLoadSurcharge=" + policy.forceLoadSurchargeMinorUnits()
                + " restorationFee=" + policy.restorationFeeMinorUnits()
                + " restorationCooldownMillis=" + policy.restorationCooldown().toMillis()
                + " destructionBasisPoints=" + policy.destructionBasisPoints()
                + " effectiveAt=" + policy.effectiveAt()
                + " actor=" + policy.actorIdentity();
    }

    private static int showTerritoryPricing(CommandSourceStack source) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new TerritoryExpansionPricingPolicyRegistry(
                                database,
                                clock,
                                TerritoryExpansionPricingPolicyVersion.defaultPolicy(0L, 0L))
                        .current(Instant.now(clock)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(
                                () -> Component.literal(formatTerritoryPricing(policy)), false);
                    } else {
                        reportDatabaseFailure(source, "Territory pricing query", failure);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Territory pricing query queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int scheduleTerritoryPricing(
            CommandSourceStack source,
            long firstOverageChunkCost,
            long additionalMarginalCost,
            long effectiveAtEpochMillis,
            String requestId,
            String reason) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new TerritoryExpansionPricingPolicyRegistry(
                                database,
                                clock,
                                TerritoryExpansionPricingPolicyVersion.defaultPolicy(0L, 0L))
                        .schedule(new ScheduleTerritoryExpansionPricingPolicy(
                                TERRITORY_PRICING_SERVICE,
                                requestId,
                                administrator(source).value(),
                                firstOverageChunkCost,
                                additionalMarginalCost,
                                Instant.ofEpochMilli(effectiveAtEpochMillis),
                                reason)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Scheduled " + formatTerritoryPricing(policy)),
                                true);
                    } else {
                        reportDatabaseFailure(source, "Territory pricing schedule", failure);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Territory pricing schedule queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatTerritoryPricing(TerritoryExpansionPricingPolicyVersion policy) {
        return "Territory Expansion pricing " + policy.policyId()
                + " firstOverageChunkCost=" + policy.firstOverageChunkCost()
                + " additionalMarginalCost=" + policy.additionalMarginalCost()
                + " effectiveAt=" + policy.effectiveAt()
                + " actor=" + policy.actorIdentity()
                + (policy.defaultPolicy() ? " [DEFAULT]" : "");
    }

    private static int showTerritoryPolicy(CommandSourceStack source) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new TerritoryFreeAllocationPolicyRegistry(
                                database,
                                clock,
                                TerritoryFreeAllocationPolicyVersion.defaultPolicy(0, 0))
                        .current(Instant.now(clock)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(
                                () -> Component.literal(formatTerritoryPolicy(policy)), false);
                    } else {
                        reportDatabaseFailure(source, "Territory policy query", failure);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Territory policy query queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int scheduleTerritoryPolicy(
            CommandSourceStack source,
            int baseChunks,
            int chunksPerEffectiveCitizen,
            long effectiveAtEpochMillis,
            String requestId,
            String reason) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new TerritoryFreeAllocationPolicyRegistry(
                                database,
                                clock,
                                TerritoryFreeAllocationPolicyVersion.defaultPolicy(0, 0))
                        .schedule(new ScheduleTerritoryFreeAllocationPolicy(
                                TERRITORY_POLICY_SERVICE,
                                requestId,
                                administrator(source).value(),
                                baseChunks,
                                chunksPerEffectiveCitizen,
                                Instant.ofEpochMilli(effectiveAtEpochMillis),
                                reason)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Scheduled " + formatTerritoryPolicy(policy)),
                                true);
                    } else {
                        reportDatabaseFailure(source, "Territory policy schedule", failure);
                    }
                }));
        source.sendSuccess(() -> Component.literal("Territory policy schedule queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatTerritoryPolicy(TerritoryFreeAllocationPolicyVersion policy) {
        return "Territory Free Allocation policy " + policy.policyId()
                + " baseChunks=" + policy.baseChunks()
                + " chunksPerEffectiveCitizen=" + policy.chunksPerEffectiveCitizen()
                + " effectiveAt=" + policy.effectiveAt()
                + " actor=" + policy.actorIdentity()
                + (policy.defaultPolicy() ? " [DEFAULT]" : "");
    }

    private static void reportDatabaseFailure(
            CommandSourceStack source, String operation, Throwable failure) {
        Throwable cause = failure instanceof CompletionException
                && failure.getCause() != null
                ? failure.getCause()
                : failure;
        source.sendFailure(Component.literal(operation + " failed: " + cause.getMessage()));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            showCommand() {
        return Commands.literal("show")
                .then(Commands.argument("service", StringArgumentType.word())
                        .executes(context -> show(
                                context.getSource(),
                                StringArgumentType.getString(context, "service"))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            registerCommand() {
        return Commands.literal("register")
                .then(Commands.argument("service", StringArgumentType.word())
                        .then(Commands.argument("ownerModId", StringArgumentType.word())
                                .then(Commands.argument("requestId", StringArgumentType.word())
                                        .then(Commands.argument(
                                                        "displayName", StringArgumentType.string())
                                                .then(Commands.argument(
                                                                "reason",
                                                                StringArgumentType.greedyString())
                                                        .executes(context -> registerService(
                                                                context.getSource(),
                                                                StringArgumentType.getString(
                                                                        context, "service"),
                                                                StringArgumentType.getString(
                                                                        context, "ownerModId"),
                                                                StringArgumentType.getString(
                                                                        context, "requestId"),
                                                                StringArgumentType.getString(
                                                                        context, "displayName"),
                                                                StringArgumentType.getString(
                                                                        context, "reason"))))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            grantCommand() {
        return Commands.literal("grant")
                .then(Commands.argument("service", StringArgumentType.word())
                        .then(Commands.argument("capability", StringArgumentType.word())
                                .then(Commands.argument("account", StringArgumentType.word())
                                        .then(Commands.argument(
                                                        "requestId", StringArgumentType.word())
                                                .then(Commands.argument(
                                                                "reason",
                                                                StringArgumentType.greedyString())
                                                        .executes(context -> grant(
                                                                context.getSource(),
                                                                StringArgumentType.getString(
                                                                        context, "service"),
                                                                StringArgumentType.getString(
                                                                        context, "capability"),
                                                                StringArgumentType.getString(
                                                                        context, "account"),
                                                                StringArgumentType.getString(
                                                                        context, "requestId"),
                                                                StringArgumentType.getString(
                                                                        context, "reason"))))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            revokeCommand() {
        return Commands.literal("revoke")
                .then(Commands.argument("grantId", UuidArgument.uuid())
                        .then(Commands.argument("requestId", StringArgumentType.word())
                                .then(Commands.argument(
                                                "reason", StringArgumentType.greedyString())
                                        .executes(context -> revoke(
                                                context.getSource(),
                                                UuidArgument.getUuid(context, "grantId"),
                                                StringArgumentType.getString(context, "requestId"),
                                                StringArgumentType.getString(context, "reason"))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
            stateCommand(String literal, FiscalServiceState state) {
        return Commands.literal(literal)
                .then(Commands.argument("service", StringArgumentType.word())
                        .then(Commands.argument("requestId", StringArgumentType.word())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> changeState(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "service"),
                                                StringArgumentType.getString(context, "requestId"),
                                                StringArgumentType.getString(context, "reason"),
                                                state)))));
    }

    private static int registerService(
            CommandSourceStack source,
            String service,
            String ownerModId,
            String requestId,
            String displayName,
            String reason) {
        if (!ModList.get().isLoaded(ownerModId)) {
            source.sendFailure(Component.literal("Owner Mod is not loaded: " + ownerModId));
            return 0;
        }
        return enqueue(
                source,
                authorization -> authorization.register(new RegisterFiscalService(
                        new ServiceIdentity(service),
                        ownerModId,
                        displayName,
                        administrator(source),
                        requestId,
                        reason)),
                registered -> "Registered fiscal service "
                        + registered.serviceIdentity().value() + " for " + registered.ownerModId());
    }

    private static int grant(
            CommandSourceStack source,
            String service,
            String capability,
            String account,
            String requestId,
            String reason) {
        final FiscalCapability parsed;
        try {
            parsed = FiscalCapability.valueOf(capability.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            source.sendFailure(Component.literal("Unknown fiscal capability: " + capability));
            return 0;
        }
        ServiceIdentity administrator = administrator(source);
        return enqueue(
                source,
                authorization -> authorization.grant(new GrantFiscalCapability(
                        administrator,
                        requestId,
                        new ServiceIdentity(service),
                        parsed,
                        new AccountId(account),
                        reason)),
                granted -> "Granted " + granted.capability() + " on "
                        + granted.accountId().value() + " as " + granted.grantId());
    }

    private static int revoke(
            CommandSourceStack source, UUID grantId, String requestId, String reason) {
        ServiceIdentity administrator = administrator(source);
        return enqueue(
                source,
                authorization -> authorization.revoke(new RevokeFiscalCapability(
                        administrator, requestId, grantId, reason)),
                revoked -> "Revoked fiscal grant " + revoked.grantId());
    }

    private static int changeState(
            CommandSourceStack source,
            String service,
            String requestId,
            String reason,
            FiscalServiceState state) {
        ServiceIdentity administrator = administrator(source);
        return enqueue(
                source,
                authorization -> authorization.changeState(new ChangeFiscalServiceState(
                        administrator,
                        requestId,
                        new ServiceIdentity(service),
                        state,
                        reason)),
                changed -> "Fiscal service " + changed.serviceIdentity().value()
                        + " is now " + changed.state());
    }

    private static int show(CommandSourceStack source, String service) {
        return enqueue(
                source,
                authorization -> authorization.describe(new ServiceIdentity(service)),
                FiscalAdministrationCommands::format);
    }

    private static int list(CommandSourceStack source) {
        return enqueue(
                source,
                FiscalAuthorization::registeredServices,
                services -> services.isEmpty()
                        ? "No fiscal services are registered"
                        : services.stream()
                                .map(service -> service.serviceIdentity().value()
                                        + "=" + service.ownerModId())
                                .collect(Collectors.joining(", ")));
    }

    private static int triggerDatabaseBackup(
            CommandSourceStack source, String requestId, String reason) {
        CivicServerRuntime.current()
                .createDatabaseBackup(administrator(source).value(), requestId, reason)
                .whenComplete((backup, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(
                                () -> Component.literal("Database backup "
                                        + backup.state() + " " + backup.fileName()
                                        + " size=" + backup.sizeBytes()
                                        + " sha256=" + backup.sha256()),
                                false);
                    } else {
                        sendFailure(source, failure);
                    }
                }));
        return Command.SINGLE_SUCCESS;
    }

    private static int showOnlineDatabaseBackupPolicy(CommandSourceStack source) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new OnlineDatabaseBackupPolicyRegistry(database, clock)
                        .current(clock.instant()))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportDatabaseFailure(
                                source, "Online Database Backup policy query", failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(policy
                                        .map(FiscalAdministrationCommands::formatOnlineDatabaseBackupPolicy)
                                        .orElse("Online Database Backup policy is not configured; scheduled backups and rotation are disabled")),
                                false);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Online Database Backup policy query queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int scheduleOnlineDatabaseBackupPolicy(
            CommandSourceStack source,
            long intervalMillis,
            int retention,
            long effectiveAtEpochMillis,
            String requestId,
            String reason) {
        Clock clock = Clock.systemUTC();
        CivicServerRuntime.current()
                .submitDatabase(database -> new OnlineDatabaseBackupPolicyRegistry(database, clock)
                        .schedule(new ScheduleOnlineDatabaseBackupPolicy(
                                ONLINE_DATABASE_BACKUP_POLICY_SERVICE,
                                requestId,
                                administrator(source).value(),
                                new OnlineDatabaseBackupPolicy(
                                        Duration.ofMillis(intervalMillis), retention),
                                Instant.ofEpochMilli(effectiveAtEpochMillis),
                                reason)))
                .whenComplete((policy, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        reportDatabaseFailure(
                                source, "Online Database Backup policy schedule", failure);
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "Scheduled "
                                                + formatOnlineDatabaseBackupPolicy(policy)),
                                true);
                    }
                }));
        source.sendSuccess(
                () -> Component.literal("Online Database Backup policy schedule queued"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static String formatOnlineDatabaseBackupPolicy(
            OnlineDatabaseBackupPolicyVersion policy) {
        return "Online Database Backup policy " + policy.policyId()
                + " intervalMillis=" + policy.policy().interval().toMillis()
                + " retention=" + policy.policy().retention()
                + " effectiveAt=" + policy.effectiveAt()
                + " actor=" + policy.actorIdentity();
    }

    private static int databaseBackupStatus(CommandSourceStack source) {
        CivicServerRuntime.current()
                .databaseBackups()
                .whenComplete((backups, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        sendFailure(source, failure);
                        return;
                    }
                    String status = backups.isEmpty()
                            ? "No database backups have been requested"
                            : backups.stream()
                                    .limit(8)
                                    .map(FiscalAdministrationCommands::formatBackup)
                                    .collect(Collectors.joining(", "));
                    source.sendSuccess(() -> Component.literal(status), false);
                }));
        return Command.SINGLE_SUCCESS;
    }

    private static String formatBackup(StoredDatabaseBackupOperation backup) {
        return backup.operationId() + "=" + backup.state() + ":" + backup.fileName()
                + ":size=" + backup.sizeBytes();
    }

    private static int stageDatabaseRestore(
            CommandSourceStack source,
            UUID backupOperationId,
            String requestId,
            String reason) {
        CivicServerRuntime.current()
                .stageDatabaseRestore(
                        administrator(source).value(), requestId, backupOperationId, reason)
                .whenComplete((restore, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(
                                () -> Component.literal("Database restore "
                                        + restore.state() + " " + restore.operationId()
                                        + "; it will activate only during the next server startup"),
                                false);
                    } else {
                        sendFailure(source, failure);
                    }
                }));
        return Command.SINGLE_SUCCESS;
    }

    private static int cancelDatabaseRestore(
            CommandSourceStack source,
            UUID restoreOperationId,
            String requestId,
            String reason) {
        CivicServerRuntime.current()
                .cancelDatabaseRestore(
                        administrator(source).value(), requestId, restoreOperationId, reason)
                .whenComplete((restore, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(
                                () -> Component.literal("Database restore "
                                        + restore.operationId() + " is " + restore.state()),
                                false);
                    } else {
                        sendFailure(source, failure);
                    }
                }));
        return Command.SINGLE_SUCCESS;
    }

    private static int databaseRestoreStatus(CommandSourceStack source) {
        CivicServerRuntime.current()
                .databaseRestores()
                .whenComplete((restores, failure) -> source.getServer().execute(() -> {
                    if (failure != null) {
                        sendFailure(source, failure);
                        return;
                    }
                    String status = restores.isEmpty()
                            ? "No database restores have been requested"
                            : restores.stream()
                                    .limit(8)
                                    .map(FiscalAdministrationCommands::formatRestore)
                                    .collect(Collectors.joining(", "));
                    source.sendSuccess(() -> Component.literal(status), false);
                }));
        return Command.SINGLE_SUCCESS;
    }

    private static String formatRestore(StoredDatabaseRestoreOperation restore) {
        return restore.operationId() + "=" + restore.state()
                + ":source=" + restore.sourceBackupOperationId()
                + (restore.rollbackFileName() == null
                        ? ""
                        : ":rollback=" + restore.rollbackFileName());
    }

    private static void sendFailure(CommandSourceStack source, Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        source.sendFailure(Component.literal("Civic database operation failed: "
                + cause.getMessage()));
    }

    private static String format(FiscalServiceAuthorizationView view) {
        String grants = view.grants().isEmpty()
                ? "none"
                : view.grants().stream()
                        .map(status -> {
                            FiscalCapabilityGrant grant = status.grant();
                            return grant.grantId() + ":" + grant.capability() + "@"
                                    + grant.accountId().value() + "["
                                    + (status.active() ? "ACTIVE" : "REVOKED") + "]";
                        })
                        .collect(Collectors.joining(", "));
        return view.service().serviceIdentity().value() + " owner="
                + view.service().ownerModId() + " state=" + view.state() + " grants=" + grants;
    }

    private static ServiceIdentity administrator(CommandSourceStack source) {
        if (source.getEntity() != null) {
            return new ServiceIdentity("civic-admin-player:" + source.getEntity().getUUID());
        }
        return new ServiceIdentity("civic-admin-console:" + source.getTextName());
    }

    private static <T> int enqueue(
            CommandSourceStack source,
            Function<FiscalAuthorization, T> operation,
            Function<T, String> successMessage) {
        CivicServerRuntime.current()
                .submitAdministration(operation)
                .whenComplete((result, failure) -> source.getServer().execute(() -> {
                    if (failure == null) {
                        source.sendSuccess(
                                () -> Component.literal(successMessage.apply(result)), false);
                    } else {
                        Throwable cause = failure instanceof CompletionException
                                && failure.getCause() != null
                                ? failure.getCause()
                                : failure;
                        source.sendFailure(Component.literal(
                                "Civic administration failed: " + cause.getMessage()));
                    }
                }));
        source.sendSuccess(() -> Component.literal("Civic administration queued"), false);
        return Command.SINGLE_SUCCESS;
    }
}
