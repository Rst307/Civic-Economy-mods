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
        var admin = Commands.literal("admin")
                .requires(source -> source.hasPermission(Commands.LEVEL_ADMINS))
                .then(service)
                .then(territoryPolicyCommand());
        var civic = Commands.literal("civic")
                .then(Commands.literal("economy")
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
