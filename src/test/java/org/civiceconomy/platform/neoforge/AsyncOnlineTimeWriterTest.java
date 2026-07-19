package org.civiceconomy.platform.neoforge;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AsyncOnlineTimeWriterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void databaseAdministrationRunsOnTheDedicatedSqliteThread() throws Exception {
        String testThread = Thread.currentThread().getName();
        CivicDatabase database = CivicDatabase.open(
                temporaryDirectory.resolve("async-administration.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("37ea6961-c55b-4d7d-a20d-9704cd9bb9dc"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));

        try (AsyncOnlineTimeWriter writer = new AsyncOnlineTimeWriter(database)) {
            String workerThread = writer.submitDatabase(
                            ignored -> Thread.currentThread().getName())
                    .get(5, TimeUnit.SECONDS);

            assertNotEquals(testThread, workerThread);
            assertTrue(workerThread.startsWith("Civic-Economy-SQLite"));
        }
    }
}
