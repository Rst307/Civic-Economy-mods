package org.civiceconomy.platform.neoforge;

import com.mojang.logging.LogUtils;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.integration.ftb.FtbChunksAdapter;
import org.civiceconomy.integration.ftb.FtbNationTeamDirectory;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyNationalTreasuryProvisioner;
import org.civiceconomy.nation.ActivateNationApplication;
import org.civiceconomy.nation.ActivatedNation;
import org.civiceconomy.nation.Capital;
import org.civiceconomy.nation.CancelNationApplication;
import org.civiceconomy.nation.CreateNationApplication;
import org.civiceconomy.nation.NationApplication;
import org.civiceconomy.nation.NationApplicationRegistry;
import org.civiceconomy.nation.NationActivationCoordinator;
import org.civiceconomy.nation.NationFoundingPolicy;
import org.civiceconomy.nation.NationalTreasuryProvisioner;
import org.civiceconomy.nation.NationTeam;
import org.civiceconomy.nation.NationTeamDirectory;
import org.slf4j.Logger;

final class NationApplicationCommands {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Duration APPLICATION_LIFETIME = Duration.ofDays(7);
    private static final Duration EVIDENCE_WINDOW = Duration.ofDays(60);
    private static final Duration CITIZENSHIP_TRANSFER_COOLDOWN = Duration.ofDays(7);
    private static final ServiceIdentity FOUNDING_SERVICE =
            new ServiceIdentity("civiceconomy-founding");

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
                .then(Commands.literal("activate")
                        .executes(context -> activate(context.getSource())));
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
}
