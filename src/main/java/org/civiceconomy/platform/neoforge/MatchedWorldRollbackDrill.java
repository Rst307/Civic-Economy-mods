package org.civiceconomy.platform.neoforge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

final class MatchedWorldRollbackDrill {
    static final String PROPERTY = "civiceconomy.matchedWorldRollbackDrill";
    static final String REQUEST_PREFIX = "matched-world-rollback-destruction-";
    private static final Path SNAPSHOT = Path.of(
            "matched-world-rollback", "snapshot-world");
    private static final Path DATABASE_BACKUP = Path.of(
            "matched-world-rollback", "civic-matched.sqlite3");
    private static final Path MARKER = Path.of(
            "matched-world-rollback", "marker.txt");

    private MatchedWorldRollbackDrill() {}

    static boolean preparing() {
        return "prepare".equals(System.getProperty(PROPERTY));
    }

    static boolean verifying() {
        return "verify".equals(System.getProperty(PROPERTY));
    }

    static Path databaseBackup(MinecraftServer server) {
        Path backup = runDirectory(server).resolve(DATABASE_BACKUP);
        try {
            Files.createDirectories(backup.getParent());
            Files.deleteIfExists(backup);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Unable to prepare matched rollback database backup " + backup,
                    failure);
        }
        return backup;
    }

    static void capture(MinecraftServer server, Path databaseBackup) {
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                    "Matched rollback world capture must run on the server thread");
        }
        if (!server.saveEverything(false, true, false)) {
            throw new IllegalStateException(
                    "Matched rollback drill could not flush the world");
        }
        Path run = runDirectory(server);
        MatchedWorldRollbackSnapshot.capture(
                run,
                server.getWorldPath(LevelResource.ROOT),
                run.resolve(SNAPSHOT),
                databaseBackup);
    }

    static void haltAfterLaterState(MinecraftServer server, Marker marker) {
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                    "Matched rollback halt must run on the server thread");
        }
        if (!server.saveEverything(false, true, false)) {
            throw new IllegalStateException(
                    "Matched rollback drill could not flush the later world state");
        }
        writeMarker(server, marker);
        Runtime.getRuntime().halt(95);
    }

    static Marker readMarker(MinecraftServer server) {
        Path marker = runDirectory(server).resolve(MARKER);
        try {
            List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
            if (lines.size() != 5) {
                throw new IllegalStateException(
                        "Matched rollback marker has invalid field count");
            }
            return new Marker(
                    lines.get(0),
                    UUID.fromString(lines.get(1)),
                    lines.get(2),
                    Long.parseLong(lines.get(3)),
                    Long.parseLong(lines.get(4)));
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException(
                    "Unable to read matched rollback marker " + marker,
                    failure);
        }
    }

    private static void writeMarker(MinecraftServer server, Marker marker) {
        Path target = runDirectory(server).resolve(MARKER);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(
                    temporary,
                    marker.requestId() + System.lineSeparator()
                            + marker.nationId() + System.lineSeparator()
                            + marker.treasuryAccount() + System.lineSeparator()
                            + marker.issuanceMinorUnits() + System.lineSeparator()
                            + marker.destroyedMinorUnits() + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Unable to write matched rollback marker " + target,
                    failure);
        }
    }

    private static Path runDirectory(MinecraftServer server) {
        Path world = server.getWorldPath(LevelResource.ROOT)
                .toAbsolutePath()
                .normalize();
        Path run = world.getParent();
        if (run == null) {
            throw new IllegalStateException("Matched rollback world has no run directory");
        }
        return run;
    }

    record Marker(
            String requestId,
            UUID nationId,
            String treasuryAccount,
            long issuanceMinorUnits,
            long destroyedMinorUnits) {}
}
