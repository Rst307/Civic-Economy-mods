package org.civiceconomy.platform.neoforge;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.civiceconomy.CivicEconomy;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.nation.OnlineSessionAccumulator;
import org.civiceconomy.nation.RecordOnlineTime;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.slf4j.Logger;

public final class CivicServerRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHECKPOINT_INTERVAL_TICKS = 20 * 60;
    private static volatile CivicServerRuntime current;

    private final Clock clock;
    private RuntimeState state;

    public CivicServerRuntime() {
        this(Clock.systemUTC());
    }

    CivicServerRuntime(Clock clock) {
        this.clock = clock;
        current = this;
    }

    static CivicServerRuntime current() {
        CivicServerRuntime runtime = current;
        if (runtime == null) {
            throw new IllegalStateException("Civic server runtime has not been constructed");
        }
        return runtime;
    }

    public void onServerStarted(ServerStartedEvent event) {
        if (state != null) {
            throw new IllegalStateException("Civic server runtime is already active");
        }
        MinecraftServer server = event.getServer();
        Path databaseDirectory = server.getWorldPath(LevelResource.ROOT).resolve("civiceconomy");
        try {
            Files.createDirectories(databaseDirectory);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to create Civic database directory " + databaseDirectory, failure);
        }
        NeoForgeModCatalog mods = new NeoForgeModCatalog();
        DatabaseIdentity identity = new DatabaseIdentity(
                CivicWorldIdentityData.getOrCreate(server),
                requireVersion(mods, CivicEconomy.MOD_ID),
                requireVersion(mods, "lightmanscurrency"),
                requireVersion(mods, "ftbteams"),
                requireVersion(mods, "ftbchunks"));
        CivicDatabase database = CivicDatabase.open(databaseDirectory.resolve("civic.sqlite3"), identity);
        AsyncOnlineTimeWriter writer = new AsyncOnlineTimeWriter(database);
        OnlineSessionAccumulator sessions =
                new OnlineSessionAccumulator(new ServiceIdentity("civiceconomy-server"));
        long now = clock.millis();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sessions.login(player.getUUID(), now);
        }
        state = new RuntimeState(server, sessions, writer);
        LOGGER.info("Civic server runtime opened world-bound SQLite and enabled buffered online-time observation");
        if (CivicDebugWorldData.get(server).enabled()) {
            LOGGER.warn(
                    "DEBUG WORLD is active for {}; Civic debug data must not be represented as formal economy data",
                    server.getWorldData().getLevelName());
        }
    }

    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            observeLogin(player);
        }
    }

    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            observeLogout(player);
        }
    }

    void observeLogin(ServerPlayer player) {
        RuntimeState current = state;
        if (current != null) {
            current.sessions.login(player.getUUID(), clock.millis());
        }
    }

    void observeLogout(ServerPlayer player) {
        RuntimeState current = state;
        if (current != null) {
            current.writer.submit(current.sessions.logout(player.getUUID(), clock.millis()));
        }
    }

    public void onServerTick(ServerTickEvent.Post event) {
        RuntimeState current = state;
        if (current == null || current.server != event.getServer()) {
            return;
        }
        current.ticksSinceCheckpoint++;
        if (current.ticksSinceCheckpoint >= CHECKPOINT_INTERVAL_TICKS) {
            current.ticksSinceCheckpoint = 0;
            current.writer.submit(current.sessions.checkpoint(clock.millis()));
        }
        Throwable failure = current.writer.failure();
        if (failure != null && !current.failureLogged) {
            current.failureLogged = true;
            LOGGER.error("Civic online-time persistence failed closed; new intervals are paused", failure);
        }
    }

    public void onServerStopping(ServerStoppingEvent event) {
        RuntimeState current = state;
        if (current != null && current.server == event.getServer()) {
            List<RecordOnlineTime> finalIntervals = current.sessions.checkpoint(clock.millis());
            current.writer.submit(finalIntervals);
        }
    }

    public void onServerStopped(ServerStoppedEvent event) {
        RuntimeState current = state;
        if (current == null || current.server != event.getServer()) {
            return;
        }
        try {
            current.writer.close();
        } finally {
            state = null;
        }
        LOGGER.info("Civic server runtime closed SQLite after draining buffered online-time intervals");
    }

    <T> CompletableFuture<T> submitAdministration(
            Function<FiscalAuthorization, T> operation) {
        return submitDatabase(database -> operation.apply(new FiscalAuthorization(database)));
    }

    <T> CompletableFuture<T> submitDatabase(Function<CivicDatabase, T> operation) {
        RuntimeState current = state;
        if (current == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Civic server runtime is not active"));
        }
        return current.writer.submitDatabase(operation);
    }

    private static String requireVersion(NeoForgeModCatalog mods, String modId) {
        return mods.version(modId)
                .orElseThrow(() -> new IllegalStateException("Loaded mod version is unavailable: " + modId));
    }

    private static final class RuntimeState {
        private final MinecraftServer server;
        private final OnlineSessionAccumulator sessions;
        private final AsyncOnlineTimeWriter writer;
        private int ticksSinceCheckpoint;
        private boolean failureLogged;

        private RuntimeState(
                MinecraftServer server, OnlineSessionAccumulator sessions, AsyncOnlineTimeWriter writer) {
            this.server = server;
            this.sessions = sessions;
            this.writer = writer;
        }
    }
}
