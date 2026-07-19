package org.civiceconomy.platform.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MatchedWorldRollbackSnapshotTest {
    @TempDir Path temporaryDirectory;

    @Test
    void restoresOneMatchedWorldAndCivicDatabasePairAfterLaterChanges()
            throws Exception {
        Path runDirectory = temporaryDirectory.resolve("run");
        Path worldDirectory = runDirectory.resolve("world");
        Path civicDirectory = worldDirectory.resolve("civiceconomy");
        Path lightmansCurrencyData = worldDirectory
                .resolve("data")
                .resolve("lightmanscurrency_bank_data.dat");
        Path liveDatabase = civicDirectory.resolve("civic.sqlite3");
        Path databaseBackup = runDirectory
                .resolve("matched-world-rollback")
                .resolve("civic-matched.sqlite3");
        Path snapshotDirectory = runDirectory
                .resolve("matched-world-rollback")
                .resolve("snapshot-world");
        Path unrelated = runDirectory.resolve("operator-owned.txt");

        Files.createDirectories(lightmansCurrencyData.getParent());
        Files.createDirectories(civicDirectory);
        Files.createDirectories(databaseBackup.getParent());
        Files.writeString(worldDirectory.resolve("level.dat"), "matched-world");
        Files.writeString(lightmansCurrencyData, "matched-lc");
        Files.writeString(liveDatabase, "open-live-database-copy-must-not-win");
        Files.writeString(databaseBackup, "matched-civic-database");
        Files.writeString(unrelated, "preserve-me");

        MatchedWorldRollbackSnapshot.capture(
                runDirectory, worldDirectory, snapshotDirectory, databaseBackup);

        Files.writeString(worldDirectory.resolve("level.dat"), "later-world");
        Files.writeString(lightmansCurrencyData, "later-lc");
        Files.writeString(liveDatabase, "later-civic-database");
        Files.writeString(worldDirectory.resolve("later-only.dat"), "remove-me");

        MatchedWorldRollbackSnapshot.restore(
                runDirectory, snapshotDirectory, worldDirectory);

        assertEquals("matched-world", Files.readString(worldDirectory.resolve("level.dat")));
        assertEquals("matched-lc", Files.readString(lightmansCurrencyData));
        assertEquals("matched-civic-database", Files.readString(liveDatabase));
        assertFalse(Files.exists(worldDirectory.resolve("later-only.dat")));
        assertEquals("preserve-me", Files.readString(unrelated));
        assertTrue(Files.isDirectory(snapshotDirectory));
    }
}
