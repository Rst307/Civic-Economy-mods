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
import org.civiceconomy.integration.lightmanscurrency.PermanentDestructionProgressObserver;
import org.civiceconomy.monetary.ExternalPermanentDestruction;

final class PermanentDestructionProcessRestartDrill {
    static final String PROPERTY =
            "civiceconomy.permanentDestructionRestartDrill";
    static final String REQUEST_PREFIX = "destruction-restart-drill-";
    private static final String PREPARE_EXTERNAL = "prepare-external";
    private static final String PREPARE_EXTERNAL_RECORDED =
            "prepare-external-recorded";
    private static final String GAME_TEST_SERVER =
            "net.minecraft.gametest.framework.GameTestServer";
    private static final String FILE_NAME =
            "permanent-destruction-process-restart-drill.txt";
    private static volatile Expected expected;

    private PermanentDestructionProcessRestartDrill() {}

    static boolean preparing() {
        String mode = System.getProperty(PROPERTY);
        return PREPARE_EXTERNAL.equals(mode)
                || PREPARE_EXTERNAL_RECORDED.equals(mode);
    }

    static boolean verifying() {
        return "verify".equals(System.getProperty(PROPERTY));
    }

    static void expect(
            String requestId,
            String treasuryAccount,
            long amountMinorUnits,
            long issuanceBeforeMinorUnits) {
        expected = new Expected(
                requestId,
                treasuryAccount,
                amountMinorUnits,
                issuanceBeforeMinorUnits);
    }

    static PermanentDestructionProgressObserver observer(MinecraftServer server) {
        return new PermanentDestructionProgressObserver() {
            @Override
            public void afterExternalApplied(ExternalPermanentDestruction destruction) {
                if (!server.isSameThread()) {
                    throw new IllegalStateException(
                            "Permanent Destruction LC effect did not run on the server thread");
                }
                String mode = System.getProperty(PROPERTY);
                if (!isControlledPreparation(server, destruction, mode)) {
                    return;
                }
                if (!server.saveEverything(false, true, false)) {
                    throw new IllegalStateException(
                            "Permanent Destruction restart drill could not flush the matched world");
                }
                writeMarker(server, marker(mode, destruction));
                if (PREPARE_EXTERNAL.equals(mode)) {
                    Runtime.getRuntime().halt(93);
                }
            }

            @Override
            public void afterExternalRecorded(ExternalPermanentDestruction destruction) {
                String mode = System.getProperty(PROPERTY);
                if (!PREPARE_EXTERNAL_RECORDED.equals(mode)
                        || !isControlledPreparation(server, destruction, mode)) {
                    return;
                }
                if (server.isSameThread()) {
                    throw new IllegalStateException(
                            "Permanent Destruction SQLite record ran on the server thread");
                }
                Marker marker = readMarker(server);
                if (!marker.operationId().equals(destruction.destructionId())) {
                    throw new IllegalStateException(
                            "Permanent Destruction restart marker does not match recorded operation");
                }
                Runtime.getRuntime().halt(94);
            }
        };
    }

    static Marker readMarker(MinecraftServer server) {
        Path marker = marker(server);
        try {
            List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
            if (lines.size() != 6) {
                throw new IllegalStateException(
                        "Permanent Destruction restart marker has invalid field count");
            }
            return new Marker(
                    lines.get(0),
                    lines.get(1),
                    UUID.fromString(lines.get(2)),
                    lines.get(3),
                    Long.parseLong(lines.get(4)),
                    Long.parseLong(lines.get(5)));
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException(
                    "Unable to read Permanent Destruction restart marker " + marker,
                    failure);
        }
    }

    private static boolean isControlledPreparation(
            MinecraftServer server,
            ExternalPermanentDestruction destruction,
            String mode) {
        Expected current = expected;
        return GAME_TEST_SERVER.equals(server.getClass().getName())
                && (PREPARE_EXTERNAL.equals(mode)
                        || PREPARE_EXTERNAL_RECORDED.equals(mode))
                && current != null
                && current.requestId().startsWith(REQUEST_PREFIX)
                && current.treasuryAccount().equals(
                        destruction.sourceAccount().value())
                && current.amountMinorUnits()
                        == destruction.amount().minorUnits();
    }

    private static Marker marker(
            String mode, ExternalPermanentDestruction destruction) {
        Expected current = expected;
        return new Marker(
                mode,
                current.requestId(),
                destruction.destructionId(),
                destruction.sourceAccount().value(),
                destruction.amount().minorUnits(),
                current.issuanceBeforeMinorUnits());
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
                            + marker.operationId() + System.lineSeparator()
                            + marker.treasuryAccount() + System.lineSeparator()
                            + marker.amountMinorUnits() + System.lineSeparator()
                            + marker.issuanceBeforeMinorUnits() + System.lineSeparator(),
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
                    "Unable to write Permanent Destruction restart marker " + target,
                    failure);
        }
    }

    private static Path marker(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve(FILE_NAME);
    }

    private record Expected(
            String requestId,
            String treasuryAccount,
            long amountMinorUnits,
            long issuanceBeforeMinorUnits) {}

    record Marker(
            String mode,
            String requestId,
            UUID operationId,
            String treasuryAccount,
            long amountMinorUnits,
            long issuanceBeforeMinorUnits) {
        boolean haltedAfterExternalRecord() {
            return PREPARE_EXTERNAL_RECORDED.equals(mode);
        }
    }
}
