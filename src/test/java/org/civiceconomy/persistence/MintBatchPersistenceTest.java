package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MintBatchPersistenceTest {
    private static final UUID NATION =
            UUID.fromString("70cfd498-e2c9-422c-b50f-c6f607df481e");
    private static final UUID PERIOD =
            UUID.fromString("122cff23-f64c-4a37-b71e-108a2a76d2b2");
    private static final UUID RECIPE =
            UUID.fromString("889d0fd0-fc91-4bc3-b250-d50283320bff");
    private static final UUID MINT =
            UUID.fromString("7ce37050-30f7-4085-8b91-a4436be4f039");
    private static final UUID ACTOR =
            UUID.fromString("a1ad63a2-fb36-4afb-a4fa-0479b9d499b9");
    private static final UUID BATCH =
            UUID.fromString("0fe258e7-99aa-4852-a0b3-7d6606be34ac");
    private static final long START =
            Instant.parse("2026-08-01T00:00:00Z").toEpochMilli();

    @TempDir Path temporaryDirectory;

    @Test
    void preparationAtomicallyReservesQuotaThenCustodyConfirmationStartsProcessing() {
        try (CivicDatabase database = database()) {
            setup(database);
            StoredMintBatch prepared = database.prepareMintBatch(
                    BATCH,
                    "mint-controller",
                    "prepare-300",
                    MINT,
                    PERIOD,
                    NATION,
                    RECIPE,
                    300L,
                    materials(3L),
                    ACTOR,
                    "Prepare 300-unit batch",
                    START + 1L);
            assertEquals("PREPARING", prepared.state());
            assertEquals("EXTERNAL_PENDING", prepared.custodyState());
            assertEquals(300L, database.nationalIssuanceQuota(PERIOD, NATION).reservedMinorUnits());
            assertEquals("PREPARING", database.registeredMint(MINT).transactionState());
            assertEquals(materials(3L), database.mintBatchMaterials(BATCH));
            assertNull(database.monetarySupplyEvent("mint-controller", "prepare-300"));

            StoredMintBatch processing = database.confirmMintBatchCustody(
                    BATCH,
                    "mint-controller",
                    "custody-300",
                    "inventory-move:0fe258e7-99aa-4852-a0b3-7d6606be34ac",
                    START + 2L);
            assertEquals("PROCESSING", processing.state());
            assertEquals("HELD", processing.custodyState());
            assertEquals(START + 2L, processing.processingStartedAtEpochMillis());
            assertEquals(START + 60_002L, processing.processingCompletesAtEpochMillis());
            assertEquals("PROCESSING", database.registeredMint(MINT).transactionState());
            assertEquals(300L, database.nationalIssuanceQuota(PERIOD, NATION).reservedMinorUnits());
        }

        try (CivicDatabase database = database()) {
            assertEquals("PROCESSING", database.mintBatch(BATCH).state());
            assertEquals(materials(3L), database.mintBatchMaterials(BATCH));
            assertEquals(300L, database.nationalIssuanceQuota(PERIOD, NATION).reservedMinorUnits());
        }
    }

    @Test
    void invalidMaterialAmountAndInsufficientQuotaLeaveNoBatchOrReservation() {
        try (CivicDatabase database = database()) {
            setup(database);
            assertThrows(IllegalStateException.class, () -> database.prepareMintBatch(
                    BATCH,
                    "mint-controller",
                    "wrong-material-count",
                    MINT,
                    PERIOD,
                    NATION,
                    RECIPE,
                    300L,
                    materials(2L),
                    ACTOR,
                    "Wrong materials",
                    START + 1L));
            assertNull(database.mintBatch(BATCH));
            assertEquals(0L, database.nationalIssuanceQuota(PERIOD, NATION).reservedMinorUnits());

            database.prepareMintBatch(
                    BATCH,
                    "mint-controller",
                    "prepare-300",
                    MINT,
                    PERIOD,
                    NATION,
                    RECIPE,
                    300L,
                    materials(3L),
                    ACTOR,
                    "Prepare 300-unit batch",
                    START + 1L);
            assertThrows(IllegalStateException.class, () -> database.prepareMintBatch(
                    UUID.randomUUID(),
                    "mint-controller",
                    "over-remaining-quota",
                    MINT,
                    PERIOD,
                    NATION,
                    RECIPE,
                    200L,
                    materials(2L),
                    ACTOR,
                    "Exceeds quota",
                    START + 1L));
            assertEquals(300L, database.nationalIssuanceQuota(PERIOD, NATION).reservedMinorUnits());
        }
    }

    @Test
    void exactPreparationAndCustodyRequestsReplayButChangedPayloadFailsClosed() {
        try (CivicDatabase database = database()) {
            setup(database);
            StoredMintBatch first = database.prepareMintBatch(
                    BATCH, "mint-controller", "prepare", MINT, PERIOD, NATION, RECIPE,
                    300L, materials(3L), ACTOR, "Prepare batch", START + 1L);
            StoredMintBatch replay = database.prepareMintBatch(
                    BATCH, "mint-controller", "prepare", MINT, PERIOD, NATION, RECIPE,
                    300L, materials(3L), ACTOR, "Prepare batch", START + 1L);
            assertEquals(first, replay);
            assertThrows(IllegalArgumentException.class, () -> database.prepareMintBatch(
                    BATCH, "mint-controller", "prepare", MINT, PERIOD, NATION, RECIPE,
                    200L, materials(2L), ACTOR, "Prepare batch", START + 1L));

            StoredMintBatch held = database.confirmMintBatchCustody(
                    BATCH, "mint-controller", "custody", "inventory-move:batch", START + 2L);
            assertEquals(held, database.confirmMintBatchCustody(
                    BATCH, "mint-controller", "custody", "inventory-move:batch", START + 2L));
            assertThrows(IllegalArgumentException.class, () -> database.confirmMintBatchCustody(
                    BATCH, "mint-controller", "custody", "inventory-move:changed", START + 2L));
        }
    }

    private List<StoredMintMaterialStack> materials(long diamondCount) {
        return List.of(
                new StoredMintMaterialStack(
                        0, "EXACT_ITEM", "minecraft:iron_ingot", "minecraft:iron_ingot", 2L),
                new StoredMintMaterialStack(
                        1, "EXACT_ITEM", "minecraft:diamond", "minecraft:diamond", diamondCount));
    }

    private void setup(CivicDatabase database) {
        database.registerNation(NATION, "test", "nation", UUID.randomUUID(), START - 10L);
        database.publishIssuanceQuotaPeriod(
                PERIOD,
                "issuance-controller",
                "period",
                START,
                START + 604_800_000L,
                1_000L,
                400L,
                List.of(new StoredNationalIssuanceQuotaAllocation(NATION, 400L)),
                "Quota period",
                START - 2L);
        database.activateNationalIssuanceQuota(
                UUID.randomUUID(),
                "nation-governance",
                "activate",
                PERIOD,
                NATION,
                ACTOR,
                400L,
                "Activate quota",
                START - 1L);
        database.publishMintRecipeVersion(
                RECIPE,
                "mint-controller",
                "recipe",
                1,
                List.of(
                        new StoredMintRecipeIngredient(
                                0, "EXACT_ITEM", "minecraft:iron_ingot", 2L, 0L),
                        new StoredMintRecipeIngredient(
                                1, "EXACT_ITEM", "minecraft:diamond", 1L, 100L)),
                60_000L,
                "Recipe",
                START - 1L);
        database.registerMint(
                MINT,
                "mint-controller",
                "mint",
                NATION,
                "minecraft:overworld",
                1,
                70,
                2,
                UUID.randomUUID(),
                UUID.randomUUID(),
                true,
                RECIPE,
                ACTOR,
                "Mint",
                START - 1L);
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("mint-batch.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("23b8583a-2ad5-42ad-964a-72d61a61014f"),
                        "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20"));
    }
}
