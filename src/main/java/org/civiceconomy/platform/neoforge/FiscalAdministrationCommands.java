package org.civiceconomy.platform.neoforge;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
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

public final class FiscalAdministrationCommands {
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
                .then(service);
        event.getDispatcher().register(Commands.literal("civic")
                .then(Commands.literal("economy").then(admin)));
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
