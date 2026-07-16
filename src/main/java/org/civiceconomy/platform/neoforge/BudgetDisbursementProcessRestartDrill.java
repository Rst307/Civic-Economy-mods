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
import org.civiceconomy.fiscal.PreparedBudgetDisbursementPayment;

final class BudgetDisbursementProcessRestartDrill {
    static final String PROPERTY =
            "civiceconomy.budgetDisbursementRestartDrill";
    static final String REQUEST_PREFIX = "budget-restart-drill-";
    private static final String PREPARE_EXTERNAL = "prepare-external";
    private static final String PREPARE_EXTERNAL_APPLIED =
            "prepare-external-applied";
    private static final String GAME_TEST_SERVER =
            "net.minecraft.gametest.framework.GameTestServer";
    private static final String FILE_NAME =
            "budget-disbursement-process-restart-drill.txt";

    private BudgetDisbursementProcessRestartDrill() {}

    static boolean preparing() {
        String mode = System.getProperty(PROPERTY);
        return PREPARE_EXTERNAL.equals(mode)
                || PREPARE_EXTERNAL_APPLIED.equals(mode);
    }

    static boolean verifying() {
        return "verify".equals(System.getProperty(PROPERTY));
    }

    static void crashAfterExternalApplied(
            MinecraftServer server,
            PreparedBudgetDisbursementPayment prepared) {
        String mode = System.getProperty(PROPERTY);
        if (!isControlledPreparation(server, prepared, mode)) {
            return;
        }
        if (!server.saveEverything(false, true, false)) {
            throw new IllegalStateException(
                    "Budget Disbursement restart drill could not flush the matched world");
        }
        writeMarker(server, marker(mode, prepared));
        if (PREPARE_EXTERNAL.equals(mode)) {
            Runtime.getRuntime().halt(88);
        }
    }

    static void crashAfterExternalRecorded(
            MinecraftServer server,
            PreparedBudgetDisbursementPayment prepared) {
        String mode = System.getProperty(PROPERTY);
        if (!PREPARE_EXTERNAL_APPLIED.equals(mode)
                || !isControlledPreparation(server, prepared, mode)) {
            return;
        }
        Marker marker = readMarker(server);
        if (!marker.transactionId()
                        .equals(prepared.transaction().transactionId())
                || !marker.requestId().equals(prepared.transaction().requestId())) {
            throw new IllegalStateException(
                    "Budget Disbursement restart marker does not match recorded Payment");
        }
        Runtime.getRuntime().halt(89);
    }

    static Marker readMarker(MinecraftServer server) {
        Path marker = marker(server);
        try {
            List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
            if (lines.size() != 7) {
                throw new IllegalStateException(
                        "Budget Disbursement restart marker has invalid field count");
            }
            return new Marker(
                    lines.get(0),
                    UUID.fromString(lines.get(1)),
                    UUID.fromString(lines.get(2)),
                    lines.get(3),
                    lines.get(4),
                    UUID.fromString(lines.get(5)),
                    Long.parseLong(lines.get(6)));
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException(
                    "Unable to read Budget Disbursement restart marker " + marker,
                    failure);
        }
    }

    private static boolean isControlledPreparation(
            MinecraftServer server,
            PreparedBudgetDisbursementPayment prepared,
            String mode) {
        return GAME_TEST_SERVER.equals(server.getClass().getName())
                && (PREPARE_EXTERNAL.equals(mode)
                        || PREPARE_EXTERNAL_APPLIED.equals(mode))
                && prepared.transaction().requestId().startsWith(REQUEST_PREFIX);
    }

    private static Marker marker(
            String mode, PreparedBudgetDisbursementPayment prepared) {
        String recipient = prepared.transaction().recipientAccount().value();
        if (!recipient.startsWith("player:")) {
            throw new IllegalStateException(
                    "Budget Disbursement restart recipient is not a player account");
        }
        return new Marker(
                mode,
                prepared.approvalRequestId(),
                prepared.transaction().transactionId(),
                prepared.transaction().requestId(),
                prepared.transaction().sourceAccount().value(),
                UUID.fromString(recipient.substring("player:".length())),
                prepared.transaction().amount().minorUnits());
    }

    private static void writeMarker(MinecraftServer server, Marker marker) {
        Path target = marker(server);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(
                    temporary,
                    marker.mode() + System.lineSeparator()
                            + marker.approvalRequestId() + System.lineSeparator()
                            + marker.transactionId() + System.lineSeparator()
                            + marker.requestId() + System.lineSeparator()
                            + marker.treasuryAccount() + System.lineSeparator()
                            + marker.recipientPlayerId() + System.lineSeparator()
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
                    "Unable to write Budget Disbursement restart marker " + target,
                    failure);
        }
    }

    private static Path marker(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve(FILE_NAME);
    }

    record Marker(
            String mode,
            UUID approvalRequestId,
            UUID transactionId,
            String requestId,
            String treasuryAccount,
            UUID recipientPlayerId,
            long amountMinorUnits) {}
}
