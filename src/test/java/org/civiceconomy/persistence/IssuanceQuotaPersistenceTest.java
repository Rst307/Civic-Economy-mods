package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IssuanceQuotaPersistenceTest {
    private static final UUID FIRST_NATION = UUID.fromString("8ab94d8b-44ee-4316-bbe4-e7be7515fcda");
    private static final UUID SECOND_NATION = UUID.fromString("994485b3-05cb-4fc4-8e60-77ca812db236");
    private static final UUID PERIOD = UUID.fromString("7c4bf1bf-f904-46e2-b3b6-758c78f5f348");
    private static final long START = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli();
    private static final long END = Instant.parse("2026-08-08T00:00:00Z").toEpochMilli();

    @TempDir Path temporaryDirectory;

    @Test
    void publishesExactGlobalAndNationalCeilingsAndActivatesAcrossReopen() {
        UUID actor = UUID.fromString("6654f36d-c401-46fa-a6e3-7e8905ac6bb6");
        try (CivicDatabase database = database()) {
            registerNations(database);
            StoredIssuanceQuotaPeriod period = database.publishIssuanceQuotaPeriod(
                    PERIOD,
                    "issuance-controller",
                    "publish-august-1",
                    START,
                    END,
                    1_000L,
                    600L,
                    List.of(
                            new StoredNationalIssuanceQuotaAllocation(FIRST_NATION, 400L),
                            new StoredNationalIssuanceQuotaAllocation(SECOND_NATION, 200L)),
                    "Conservative weekly quota",
                    START - 1L);
            assertEquals(600L, period.globalQuotaMinorUnits());
            assertEquals(400L, database.nationalIssuanceQuota(PERIOD, FIRST_NATION).ceilingMinorUnits());
            assertEquals(200L, database.nationalIssuanceQuota(PERIOD, SECOND_NATION).ceilingMinorUnits());

            StoredNationalIssuanceQuotaActivation activation = database.activateNationalIssuanceQuota(
                    UUID.randomUUID(),
                    "nation-governance",
                    "activate-first-300",
                    PERIOD,
                    FIRST_NATION,
                    actor,
                    300L,
                    "Enable only part of the ceiling",
                    START);
            assertEquals(300L, activation.activatedMinorUnits());
            assertEquals(300L, database.nationalIssuanceQuota(PERIOD, FIRST_NATION).activatedMinorUnits());
        }

        try (CivicDatabase database = database()) {
            assertNotNull(database.issuanceQuotaPeriod(PERIOD));
            assertEquals(300L, database.nationalIssuanceQuota(PERIOD, FIRST_NATION).activatedMinorUnits());
            assertEquals(
                    300L,
                    database.nationalIssuanceQuotaActivation("nation-governance", "activate-first-300")
                            .activatedMinorUnits());
        }
    }

    @Test
    void rejectsOverAllocationAndHardCapSpaceAtomically() {
        try (CivicDatabase database = database()) {
            registerNations(database);
            assertThrows(IllegalStateException.class, () -> database.publishIssuanceQuotaPeriod(
                    PERIOD,
                    "issuance-controller",
                    "over-allocated",
                    START,
                    END,
                    1_000L,
                    600L,
                    List.of(
                            new StoredNationalIssuanceQuotaAllocation(FIRST_NATION, 401L),
                            new StoredNationalIssuanceQuotaAllocation(SECOND_NATION, 200L)),
                    "Must roll back",
                    START - 1L));
            assertNull(database.issuanceQuotaPeriod(PERIOD));

            database.confirmMonetarySupplyChange(
                    UUID.randomUUID(),
                    "seed",
                    "seed-issuance",
                    "ISSUANCE",
                    800L,
                    "mint-batch:seed",
                    "Seed existing net issuance",
                    START - 10L,
                    1_000L);
            assertThrows(IllegalStateException.class, () -> database.publishIssuanceQuotaPeriod(
                    PERIOD,
                    "issuance-controller",
                    "over-hard-cap-space",
                    START,
                    END,
                    1_000L,
                    201L,
                    List.of(new StoredNationalIssuanceQuotaAllocation(FIRST_NATION, 201L)),
                    "Must also roll back",
                    START - 1L));
            assertNull(database.issuanceQuotaPeriod(PERIOD));
        }
    }

    @Test
    void activationCannotExceedCeilingAndChangedReplayCannotMutateQuota() {
        UUID actor = UUID.randomUUID();
        try (CivicDatabase database = database()) {
            registerNations(database);
            database.publishIssuanceQuotaPeriod(
                    PERIOD,
                    "issuance-controller",
                    "publish",
                    START,
                    END,
                    1_000L,
                    400L,
                    List.of(new StoredNationalIssuanceQuotaAllocation(FIRST_NATION, 400L)),
                    "Publish quota",
                    START - 1L);
            assertThrows(IllegalStateException.class, () -> database.activateNationalIssuanceQuota(
                    UUID.randomUUID(), "nation-governance", "activate", PERIOD, FIRST_NATION,
                    actor, 401L, "Too much", START));
            database.activateNationalIssuanceQuota(
                    UUID.randomUUID(), "nation-governance", "activate", PERIOD, FIRST_NATION,
                    actor, 300L, "Valid", START);
            assertThrows(IllegalArgumentException.class, () -> database.activateNationalIssuanceQuota(
                    UUID.randomUUID(), "nation-governance", "activate", PERIOD, FIRST_NATION,
                    actor, 301L, "Changed replay", START + 1L));
            assertEquals(300L, database.nationalIssuanceQuota(PERIOD, FIRST_NATION).activatedMinorUnits());
        }
    }

    @Test
    void overlappingPeriodRejectionRestoresTheConnectionForLaterPublication() {
        UUID laterPeriod = UUID.fromString("6d26d9a0-4e04-4139-9a38-f05b5d54e0bf");
        try (CivicDatabase database = database()) {
            registerNations(database);
            database.publishIssuanceQuotaPeriod(
                    PERIOD,
                    "issuance-controller",
                    "publish-first",
                    START,
                    END,
                    1_000L,
                    400L,
                    List.of(new StoredNationalIssuanceQuotaAllocation(FIRST_NATION, 400L)),
                    "First quota period",
                    START - 1L);

            assertThrows(IllegalStateException.class, () -> database.publishIssuanceQuotaPeriod(
                    UUID.randomUUID(),
                    "issuance-controller",
                    "publish-overlap",
                    START + 1L,
                    END + 1L,
                    1_000L,
                    200L,
                    List.of(new StoredNationalIssuanceQuotaAllocation(SECOND_NATION, 200L)),
                    "Overlapping quota period",
                    START));

            StoredIssuanceQuotaPeriod later = database.publishIssuanceQuotaPeriod(
                    laterPeriod,
                    "issuance-controller",
                    "publish-later",
                    END,
                    END + (END - START),
                    1_000L,
                    200L,
                    List.of(new StoredNationalIssuanceQuotaAllocation(SECOND_NATION, 200L)),
                    "Later quota period",
                    END - 1L);

            assertEquals(laterPeriod, later.periodId());
            assertNotNull(database.issuanceQuotaPeriod(laterPeriod));
        }
    }

    private void registerNations(CivicDatabase database) {
        database.registerNation(FIRST_NATION, "test", "first-nation", UUID.randomUUID(), START - 100L);
        database.registerNation(SECOND_NATION, "test", "second-nation", UUID.randomUUID(), START - 100L);
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("issuance-quota.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("12f6a90c-6408-493c-9839-174e091aee3b"),
                        "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20"));
    }
}
