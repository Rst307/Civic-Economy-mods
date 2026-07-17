package org.civiceconomy.strength;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NationalStrengthSnapshotBuilderTest {
    @TempDir Path temporaryDirectory;

    @Test
    void recalculatesEveryRegisteredNationIntoAnImmutableConservativeSnapshot() {
        NationId first = new NationId(
                UUID.fromString("11111111-1111-1111-1111-111111111111"));
        NationId second = new NationId(
                UUID.fromString("22222222-2222-2222-2222-222222222222"));
        try (CivicDatabase database = database()) {
            database.registerNation(
                    first.value(),
                    "civiceconomy-tests",
                    "first-nation",
                    UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111"),
                    1_000L);
            database.registerNation(
                    second.value(),
                    "civiceconomy-tests",
                    "second-nation",
                    UUID.fromString("bbbbbbbb-2222-2222-2222-222222222222"),
                    2_000L);

            NationalStrengthSnapshot snapshot = new NationalStrengthSnapshotBuilder(
                            database, 30L * 24L * 60L * 60L * 1_000L, 10_000L)
                    .recalculateAll(3_000L);

            assertEquals(3_000L, snapshot.recalculatedAtEpochMillis());
            assertEquals(2, snapshot.nations().size());
            assertEquals(
                    NationalStrengthComponentState.ACTIVE,
                    snapshot.nations().get(first).assessment().componentState(
                            NationalStrengthComponent.AUDITABLE_ECONOMIC_ACTIVITY));
            assertTrue(snapshot.nations().get(second).assessment().newMintAllocationPaused());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> snapshot.nations().put(first, snapshot.nations().get(second)));
        }
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("national-strength-snapshot.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
