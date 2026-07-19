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
import org.civiceconomy.mint.PendingMintIssuanceStep;
import org.civiceconomy.mint.PendingMintMaterialConsumption;
import org.civiceconomy.mint.PendingMintTreasuryCredit;

final class MintProcessRestartDrill {
    static final String PROPERTY = "civiceconomy.mintRestartDrill";
    private static final String GAME_TEST_SERVER =
            "net.minecraft.gametest.framework.GameTestServer";
    private static final String FILE_NAME = "mint-process-restart-drill.txt";

    private MintProcessRestartDrill() {}

    static boolean verifying() {
        return "verify".equals(System.getProperty(PROPERTY));
    }

    static void crashAfterAppliedStep(
            MinecraftServer server, PendingMintIssuanceStep operation) {
        String mode = System.getProperty(PROPERTY);
        if (!GAME_TEST_SERVER.equals(server.getClass().getName())) {
            return;
        }
        if (operation instanceof PendingMintTreasuryCredit credit
                && ("prepare".equals(mode)
                        || "prepare-external".equals(mode)
                        || "prepare-materials".equals(mode))) {
            server.overworld().getDataStorage().save();
            writeMarker(server, new Marker(
                    credit.batchId(),
                    credit.operationId(),
                    credit.issuance().treasuryAccount().value(),
                    credit.issuance().amount().minorUnits()));
            if ("prepare".equals(mode) || "prepare-external".equals(mode)) {
                Runtime.getRuntime().halt(86);
            }
            return;
        }
        if (operation instanceof PendingMintMaterialConsumption consumption
                && "prepare-materials".equals(mode)) {
            server.overworld().getDataStorage().save();
            Marker marker = readMarker(server);
            if (!marker.batchId().equals(consumption.batchId())
                    || !marker.operationId().equals(consumption.operationId())) {
                throw new IllegalStateException(
                        "Mint material restart step does not match its external marker");
            }
            Runtime.getRuntime().halt(87);
        }
    }

    static Marker readMarker(MinecraftServer server) {
        Path marker = marker(server);
        try {
            List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
            if (lines.size() != 4) {
                throw new IllegalStateException("Mint restart marker has invalid field count");
            }
            return new Marker(
                    UUID.fromString(lines.get(0)),
                    UUID.fromString(lines.get(1)),
                    lines.get(2),
                    Long.parseLong(lines.get(3)));
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Unable to read Mint restart marker " + marker, failure);
        }
    }

    private static void writeMarker(MinecraftServer server, Marker marker) {
        Path target = marker(server);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(
                    temporary,
                    marker.batchId() + System.lineSeparator()
                            + marker.operationId() + System.lineSeparator()
                            + marker.treasuryAccount() + System.lineSeparator()
                            + marker.amountMinorUnits() + System.lineSeparator(),
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
            throw new IllegalStateException("Unable to write Mint restart marker " + target, failure);
        }
    }

    private static Path marker(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve(FILE_NAME);
    }

    record Marker(
            UUID batchId,
            UUID operationId,
            String treasuryAccount,
            long amountMinorUnits) {}
}
