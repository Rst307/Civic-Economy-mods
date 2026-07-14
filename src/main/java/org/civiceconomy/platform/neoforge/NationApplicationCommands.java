package org.civiceconomy.platform.neoforge;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.integration.ftb.FtbNationTeamDirectory;
import org.civiceconomy.nation.CreateNationApplication;
import org.civiceconomy.nation.NationApplication;
import org.civiceconomy.nation.NationApplicationRegistry;
import org.civiceconomy.nation.NationTeam;
import org.civiceconomy.nation.NationTeamDirectory;

final class NationApplicationCommands {
    private static final Duration APPLICATION_LIFETIME = Duration.ofDays(7);
    private static final ServiceIdentity FOUNDING_SERVICE =
            new ServiceIdentity("civiceconomy-founding");

    private NationApplicationCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("nation")
                .then(Commands.literal("apply")
                        .executes(context -> apply(context.getSource())));
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
                        source.sendFailure(Component.literal(
                                "Nation Application failed: " + cause.getMessage()));
                    }
                }));
        source.sendSuccess(() -> Component.literal("Nation Application queued"), false);
        return Command.SINGLE_SUCCESS;
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
}
