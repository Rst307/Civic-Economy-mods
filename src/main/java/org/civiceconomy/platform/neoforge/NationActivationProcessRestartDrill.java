package org.civiceconomy.platform.neoforge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyNationalTreasuryProvisioner;
import org.civiceconomy.nation.Capital;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.NationalTreasuryProvisioner;

final class NationActivationProcessRestartDrill {
    static final String PROPERTY = "civiceconomy.nationActivationRestartDrill";
    static final String REQUEST_PREFIX = "nation-activation-restart-drill-";
    static final String SERVICE_IDENTITY = "civiceconomy-founding";
    static final String REASON = "Matched Nation Activation process restart";
    private static final String PREPARE_TREASURY = "prepare-treasury";
    private static final String GAME_TEST_SERVER =
            "net.minecraft.gametest.framework.GameTestServer";
    private static final String FILE_NAME =
            "nation-activation-process-restart-drill.txt";
    private static volatile Expected expected;

    private NationActivationProcessRestartDrill() {}

    static boolean preparing() {
        return PREPARE_TREASURY.equals(System.getProperty(PROPERTY));
    }

    static boolean verifying() {
        return "verify".equals(System.getProperty(PROPERTY));
    }

    static void expect(
            UUID applicationId,
            String requestId,
            UUID ftbTeamId,
            UUID playerId,
            Capital capital) {
        expected = new Expected(
                applicationId,
                requestId,
                ftbTeamId,
                playerId,
                capital);
    }

    static NationalTreasuryProvisioner provisioner(MinecraftServer server) {
        return (nationId, treasuryAccountId) -> {
            CompletableFuture<Void> provisioned = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    LightmansCurrencyNationalTreasuryProvisioner.forLevel(server.overworld())
                            .ensureExists(nationId, treasuryAccountId);
                    if (isControlledPreparation(server)) {
                        flushMarkerAndHalt(server, nationId, treasuryAccountId);
                    }
                    provisioned.complete(null);
                } catch (Throwable failure) {
                    provisioned.completeExceptionally(failure);
                }
            });
            provisioned.join();
        };
    }

    static Marker readMarker(MinecraftServer server) {
        Path marker = marker(server);
        try {
            List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
            if (lines.size() != 10) {
                throw new IllegalStateException(
                        "Nation Activation restart marker has invalid field count");
            }
            return new Marker(
                    lines.get(0),
                    lines.get(1),
                    UUID.fromString(lines.get(2)),
                    UUID.fromString(lines.get(3)),
                    lines.get(4),
                    UUID.fromString(lines.get(5)),
                    UUID.fromString(lines.get(6)),
                    new Capital(
                            lines.get(7),
                            Integer.parseInt(lines.get(8)),
                            Integer.parseInt(lines.get(9))));
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException(
                    "Unable to read Nation Activation restart marker " + marker,
                    failure);
        }
    }

    private static boolean isControlledPreparation(MinecraftServer server) {
        Expected current = expected;
        return preparing()
                && GAME_TEST_SERVER.equals(server.getClass().getName())
                && current != null
                && current.requestId().startsWith(REQUEST_PREFIX);
    }

    private static void flushMarkerAndHalt(
            MinecraftServer server,
            NationId nationId,
            AccountId treasuryAccountId) {
        if (!server.saveEverything(false, true, false)) {
            throw new IllegalStateException(
                    "Nation Activation restart drill could not flush the matched world");
        }
        Expected current = expected;
        writeMarker(server, new Marker(
                PREPARE_TREASURY,
                current.requestId(),
                current.applicationId(),
                nationId.value(),
                treasuryAccountId.value(),
                current.ftbTeamId(),
                current.playerId(),
                current.capital()));
        Runtime.getRuntime().halt(92);
    }

    private static void writeMarker(MinecraftServer server, Marker marker) {
        Path target = marker(server);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(
                    temporary,
                    marker.mode() + System.lineSeparator()
                            + marker.requestId() + System.lineSeparator()
                            + marker.applicationId() + System.lineSeparator()
                            + marker.nationId() + System.lineSeparator()
                            + marker.treasuryAccount() + System.lineSeparator()
                            + marker.ftbTeamId() + System.lineSeparator()
                            + marker.playerId() + System.lineSeparator()
                            + marker.capital().dimensionId() + System.lineSeparator()
                            + marker.capital().chunkX() + System.lineSeparator()
                            + marker.capital().chunkZ() + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Unable to write Nation Activation restart marker " + target,
                    failure);
        }
    }

    private static Path marker(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve(FILE_NAME);
    }

    private record Expected(
            UUID applicationId,
            String requestId,
            UUID ftbTeamId,
            UUID playerId,
            Capital capital) {}

    record Marker(
            String mode,
            String requestId,
            UUID applicationId,
            UUID nationId,
            String treasuryAccount,
            UUID ftbTeamId,
            UUID playerId,
            Capital capital) {}
}
