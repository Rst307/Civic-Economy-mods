package org.civiceconomy.platform.neoforge;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

final class CivicDebugWorldCommands {
    static final String DEDICATED_STARTUP_PERMISSION_PROPERTY =
            "civiceconomy.allowDedicatedDebugWorld";
    private static final Logger LOGGER = LogUtils.getLogger();

    private CivicDebugWorldCommands() {}

    static boolean dedicatedStartupPermit() {
        return Boolean.getBoolean(DEDICATED_STARTUP_PERMISSION_PROPERTY);
    }

    static LiteralArgumentBuilder<CommandSourceStack> command(
            boolean dedicatedStartupPermit) {
        return Commands.literal("debug")
                .requires(source -> authorizedOperator(source, dedicatedStartupPermit))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource())))
                .then(Commands.literal("enable")
                        .executes(context -> prompt(context.getSource()))
                        .then(Commands.literal("confirm")
                                .executes(context -> enable(context.getSource()))));
    }

    private static boolean authorizedOperator(
            CommandSourceStack source, boolean dedicatedStartupPermit) {
        if (!source.hasPermission(Commands.LEVEL_ADMINS)
                || !(source.getEntity() instanceof ServerPlayer player)) {
            return false;
        }
        if (source.getServer().isDedicatedServer()) {
            return dedicatedStartupPermit;
        }
        return source.getServer().isSingleplayerOwner(player.getGameProfile());
    }

    private static int status(CommandSourceStack source) {
        boolean enabled = CivicDebugWorldData.get(source.getServer()).enabled();
        source.sendSuccess(
                () -> Component.literal(enabled
                        ? "DEBUG WORLD is permanently enabled for this world"
                        : "DEBUG WORLD is disabled for this world"),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static int prompt(CommandSourceStack source) {
        source.sendFailure(Component.literal(
                "WARNING: enabling DEBUG WORLD is permanent for this Civic dataset. "
                        + "Debug identities, funds, and quotas cannot become formal economy data. "
                        + "Run /civic debug enable confirm to continue."));
        return Command.SINGLE_SUCCESS;
    }

    private static int enable(CommandSourceStack source) {
        CivicDebugWorldData data = CivicDebugWorldData.get(source.getServer());
        if (!data.enable()) {
            source.sendSuccess(
                    () -> Component.literal("DEBUG WORLD is already enabled"), false);
            return Command.SINGLE_SUCCESS;
        }
        Component warning = Component.literal(
                        "DEBUG WORLD ENABLED: Civic debug data is isolated from formal economy data")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        source.getServer().getPlayerList().broadcastSystemMessage(warning, false);
        LOGGER.warn(
                "DEBUG WORLD permanently enabled by {} in world {}",
                source.getTextName(),
                source.getServer().getWorldData().getLevelName());
        return Command.SINGLE_SUCCESS;
    }
}
