package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MintRecoveryIncidentPersistenceTest {
    private static final UUID NATION = UUID.fromString("8395d7c8-d1e4-41aa-8910-5ae2e7589a8c");
    private static final UUID PERIOD = UUID.fromString("b7f7a057-1142-4c4b-a03e-28d76dbbd2ad");
    private static final UUID RECIPE = UUID.fromString("902f843e-8d6d-44ba-bc80-b4f5ab6b925e");
    private static final UUID MINT = UUID.fromString("4823dd23-7043-46af-9984-bdbfa7ec9ed8");
    private static final UUID ACTOR = UUID.fromString("4814fe6d-a296-40f4-af67-6f325bb29789");
    private static final UUID BATCH = UUID.fromString("b4cfeb3c-1023-48ba-b132-f03577b447c3");
    private static final UUID OPERATION = UUID.fromString("7129e380-704e-4845-b882-081bd6b39886");
    private static final long START = Instant.parse("2026-08-11T00:00:00Z").toEpochMilli();

    @TempDir Path temporaryDirectory;

    @Test
    void recoveryFailureEvidenceAccumulatesAndResolutionIsImmutableAcrossReopen() {
        UUID incidentId;
        try (CivicDatabase database = database()) {
            prepareIssuance(database);
            assertThrows(IllegalStateException.class, () -> database.recordMintRecoveryIncident(
                    OPERATION,
                    "MATERIAL_CONSUMPTION",
                    "IllegalStateException",
                    "wrong durable step",
                    START + 1_900L));

            StoredMintRecoveryIncident first = database.recordMintRecoveryIncident(
                    OPERATION,
                    "TREASURY_CREDIT",
                    "IllegalStateException",
                    "LC account unavailable",
                    START + 2_000L);
            StoredMintRecoveryIncident repeated = database.recordMintRecoveryIncident(
                    OPERATION,
                    "TREASURY_CREDIT",
                    "IllegalStateException",
                    "LC marker unavailable",
                    START + 3_000L);

            incidentId = first.incidentId();
            assertEquals(incidentId, repeated.incidentId());
            assertEquals(BATCH, repeated.batchId());
            assertEquals("OPEN", repeated.state());
            assertEquals(START + 2_000L, repeated.firstObservedAtEpochMillis());
            assertEquals(START + 3_000L, repeated.lastObservedAtEpochMillis());
            assertEquals(2L, repeated.occurrenceCount());
            assertEquals("LC marker unavailable", repeated.failureMessage());
        }

        try (CivicDatabase database = database()) {
            StoredMintRecoveryIncident reopened = database.mintRecoveryIncident(
                    OPERATION, "TREASURY_CREDIT");
            assertEquals(incidentId, reopened.incidentId());
            assertEquals(2L, reopened.occurrenceCount());

            StoredMintRecoveryIncident resolved = database.resolveMintRecoveryIncident(
                    OPERATION,
                    "TREASURY_CREDIT",
                    "STEP_CONFIRMED",
                    "lc-mint-issuance:" + OPERATION,
                    START + 4_000L);
            assertEquals("RESOLVED", resolved.state());
            assertEquals("STEP_CONFIRMED", resolved.resolutionKind());
            assertEquals("lc-mint-issuance:" + OPERATION, resolved.resolutionDetail());
            assertEquals(START + 4_000L, resolved.resolvedAtEpochMillis());
            assertEquals(resolved, database.resolveMintRecoveryIncident(
                    OPERATION,
                    "TREASURY_CREDIT",
                    "STEP_CONFIRMED",
                    "lc-mint-issuance:" + OPERATION,
                    START + 4_000L));
            assertThrows(IllegalArgumentException.class, () -> database.resolveMintRecoveryIncident(
                    OPERATION,
                    "TREASURY_CREDIT",
                    "MANUAL_OVERRIDE",
                    "changed resolution",
                    START + 5_000L));
            assertThrows(IllegalStateException.class, () -> database.recordMintRecoveryIncident(
                    OPERATION,
                    "TREASURY_CREDIT",
                    "IllegalStateException",
                    "cannot reopen",
                    START + 6_000L));
        }
    }

    private void prepareIssuance(CivicDatabase database) {
        database.registerNation(NATION, "test", "nation", UUID.randomUUID(), START - 10L);
        database.publishIssuanceQuotaPeriod(
                PERIOD, "issuance-controller", "period", START, START + 604_800_000L,
                1_000L, 400L,
                List.of(new StoredNationalIssuanceQuotaAllocation(NATION, 400L)),
                "Quota period", START - 9L);
        database.activateNationalIssuanceQuota(
                UUID.randomUUID(), "nation-governance", "activate", PERIOD, NATION, ACTOR,
                400L, "Activate quota", START - 8L);
        database.publishMintRecipeVersion(
                RECIPE, "mint-controller", "recipe", 1,
                List.of(new StoredMintRecipeIngredient(
                        0, "EXACT_ITEM", "minecraft:diamond", 1L, 100L)),
                1_000L, "Recipe", START - 7L);
        database.registerMint(
                MINT, "mint-controller", "mint", NATION, "minecraft:overworld",
                1, 70, 2, UUID.randomUUID(), UUID.randomUUID(), true,
                RECIPE, ACTOR, "Mint", START - 6L);
        database.prepareMintBatch(
                BATCH, "mint-controller", "prepare", MINT, PERIOD, NATION, RECIPE,
                300L,
                List.of(new StoredMintMaterialStack(
                        0, "EXACT_ITEM", "minecraft:diamond", "minecraft:diamond", 3L)),
                ACTOR, "Prepare", START + 1L);
        database.confirmMintBatchCustody(
                BATCH, "mint-controller", "custody", "inventory-move", START + 2L);
        database.prepareMintBatchIssuance(
                OPERATION,
                BATCH,
                "mint-controller",
                "issue",
                "Complete authorized Mint Batch",
                START + 1_500L);
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("mint-recovery-incident.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("dfe2635d-ea66-4a98-b870-fbfab73629e6"),
                        "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20"));
    }
}
