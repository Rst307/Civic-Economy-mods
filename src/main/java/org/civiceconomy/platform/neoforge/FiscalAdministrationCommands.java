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
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.civiceconomy.fiscal.AccountId;
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
import org.civiceconomy.persistence.StoredDatabaseBackupOperation;
import org.civiceconomy.persistence.StoredDatabaseRestoreOperation;
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
        var admin = Commands.literal("admin")
                .requires(source -> source.hasPermission(Commands.LEVEL_ADMINS))
                .then(service)
                .then(mint)
                .then(withdrawal)
                .then(databaseBackupCommand())
                .then(territoryPolicyCommand());
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
                .then(databaseRestoreCommand());
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
