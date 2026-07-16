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
import org.civiceconomy.fiscal.ExternalTreasuryWithdrawal;
import org.civiceconomy.integration.lightmanscurrency.TreasuryWithdrawalProgressObserver;

final class TreasuryWithdrawalProcessRestartDrill {
    static final String PROPERTY =
            "civiceconomy.treasuryWithdrawalRestartDrill";
    static final String REQUEST_PREFIX = "withdrawal-restart-drill-";
    private static final String PREPARE_DEBIT = "prepare-debit";
    private static final String PREPARE_DELIVERY = "prepare-delivery";
    private static final String GAME_TEST_SERVER =
            "net.minecraft.gametest.framework.GameTestServer";
    private static final String FILE_NAME =
            "treasury-withdrawal-process-restart-drill.txt";
    private static volatile Expected expected;

    private TreasuryWithdrawalProcessRestartDrill() {}

    static boolean preparing() {
        String mode = System.getProperty(PROPERTY);
        return PREPARE_DEBIT.equals(mode) || PREPARE_DELIVERY.equals(mode);
    }

    static boolean verifying() {
        return "verify".equals(System.getProperty(PROPERTY));
    }

    static void expect(String requestId, UUID playerId) {
        expected = new Expected(requestId, playerId);
    }

    static TreasuryWithdrawalProgressObserver observer(MinecraftServer server) {
        return new TreasuryWithdrawalProgressObserver() {
            @Override
            public void afterTreasuryDebit(ExternalTreasuryWithdrawal withdrawal) {
                String mode = System.getProperty(PROPERTY);
                if (PREPARE_DEBIT.equals(mode)
                        && isControlledPreparation(server, withdrawal, mode)) {
                    flushMarkerAndHalt(server, mode, withdrawal, 90);
                }
            }

            @Override
            public void afterCashDelivery(ExternalTreasuryWithdrawal withdrawal) {
                String mode = System.getProperty(PROPERTY);
                if (PREPARE_DELIVERY.equals(mode)
                        && isControlledPreparation(server, withdrawal, mode)) {
                    flushMarkerAndHalt(server, mode, withdrawal, 91);
                }
            }
        };
    }

    static Marker readMarker(MinecraftServer server) {
        Path marker = marker(server);
        try {
            List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
            if (lines.size() != 6) {
                throw new IllegalStateException(
                        "Treasury Withdrawal restart marker has invalid field count");
            }
            return new Marker(
                    lines.get(0),
                    lines.get(1),
                    UUID.fromString(lines.get(2)),
                    lines.get(3),
                    UUID.fromString(lines.get(4)),
                    Long.parseLong(lines.get(5)));
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException(
                    "Unable to read Treasury Withdrawal restart marker " + marker,
                    failure);
        }
    }

    private static boolean isControlledPreparation(
            MinecraftServer server,
            ExternalTreasuryWithdrawal withdrawal,
            String mode) {
        Expected current = expected;
        return GAME_TEST_SERVER.equals(server.getClass().getName())
                && (PREPARE_DEBIT.equals(mode) || PREPARE_DELIVERY.equals(mode))
                && current != null
                && current.requestId().startsWith(REQUEST_PREFIX)
                && current.playerId().equals(withdrawal.actorPlayerId());
    }

    private static void flushMarkerAndHalt(
            MinecraftServer server,
            String mode,
            ExternalTreasuryWithdrawal withdrawal,
            int exitCode) {
        if (!server.saveEverything(false, true, false)) {
            throw new IllegalStateException(
                    "Treasury Withdrawal restart drill could not flush the matched world");
        }
        Expected current = expected;
        writeMarker(server, new Marker(
                mode,
                current.requestId(),
                withdrawal.withdrawalId(),
                withdrawal.sourceAccount().value(),
                withdrawal.actorPlayerId(),
                withdrawal.amount().minorUnits()));
        Runtime.getRuntime().halt(exitCode);
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
                            + marker.withdrawalId() + System.lineSeparator()
                            + marker.treasuryAccount() + System.lineSeparator()
                            + marker.playerId() + System.lineSeparator()
                            + marker.amountMinorUnits() + System.lineSeparator(),
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
                    "Unable to write Treasury Withdrawal restart marker " + target,
                    failure);
        }
    }

    private static Path marker(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve(FILE_NAME);
    }

    private record Expected(String requestId, UUID playerId) {}

    record Marker(
            String mode,
            String requestId,
            UUID withdrawalId,
            String treasuryAccount,
            UUID playerId,
            long amountMinorUnits) {
        boolean haltedAfterDelivery() {
            return PREPARE_DELIVERY.equals(mode);
        }
    }
}
