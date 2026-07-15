package org.civiceconomy.monetary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.civiceconomy.persistence.StoredMintMaterialStack;
import org.civiceconomy.persistence.StoredMintRecipeIngredient;
import org.civiceconomy.persistence.StoredNationalIssuanceQuotaAllocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MonetaryStockCorrectionTest {
    private static final UUID NATION = UUID.fromString("cff4323b-fea3-416f-8a1e-77672da6617f");
    private static final UUID PERIOD = UUID.fromString("6db3c216-3703-4d7b-80ef-e6d7901e3005");
    private static final UUID RECIPE = UUID.fromString("76e3a7c7-75ad-4574-a0d5-12a770fc8e81");
    private static final UUID MINT = UUID.fromString("f5fed5d8-f898-47e9-9bf1-16ee9a34a480");
    private static final UUID ACTOR = UUID.fromString("9b26cd43-2090-4c6d-aedc-bb77fa1fa10b");
    private static final UUID BATCH = UUID.fromString("1e66ba8d-686f-48e8-9ac9-a29a90faf4df");
    private static final UUID OPERATION = UUID.fromString("c7edc714-a081-42e4-b178-f4ad2af999aa");
    private static final long START = Instant.parse("2026-08-13T00:00:00Z").toEpochMilli();
    private static final Clock CORRECTION_CLOCK = Clock.fixed(
            Instant.ofEpochMilli(START + 3_000L), ZoneOffset.UTC);

    @TempDir Path temporaryDirectory;

    @Test
    void correctionAccountsTheExactIncidentAmountWithoutMovingQuotaOrResumingRecovery() {
        UUID incidentId;
        MonetaryStockCorrection corrected;
        try (CivicDatabase database = database()) {
            prepareIncident(database);
            incidentId = database.mintRecoveryIncident(
                    OPERATION, "TREASURY_CREDIT").incidentId();
            MonetaryStockCorrectionRegistry corrections =
                    new MonetaryStockCorrectionRegistry(database, CORRECTION_CLOCK);

            corrected = corrections.correct(new CorrectMonetaryStock(
                    "civic-admin-console:Server",
                    "correction-1",
                    incidentId,
                    "incident-ticket:CE-1042",
                    "LC Treasury credit independently confirmed"));

            assertEquals(MoneyAmount.ofMinorUnits(300L), corrected.amount());
            assertEquals(MonetarySupplyChange.STOCK_CORRECTION_INCREASE, corrected.event().change());
            assertEquals(MoneyAmount.ofMinorUnits(300L), corrections.cumulativeNetIssuance());
            assertEquals("RESOLVED", database.mintRecoveryIncident(
                    OPERATION, "TREASURY_CREDIT").state());
            assertEquals("STOCK_CORRECTION", database.mintRecoveryIncident(
                    OPERATION, "TREASURY_CREDIT").resolutionKind());
            assertEquals(corrected.correctionId().toString(), database.mintRecoveryIncident(
                    OPERATION, "TREASURY_CREDIT").resolutionDetail());
            assertEquals(List.of(), database.recoverableMintIssuanceOperations());
            assertEquals("COMMITTING", database.mintBatch(BATCH).state());
            assertEquals("HELD", database.mintBatch(BATCH).custodyState());
            assertEquals(300L,
                    database.nationalIssuanceQuota(PERIOD, NATION).reservedMinorUnits());
            assertEquals(0L,
                    database.nationalIssuanceQuota(PERIOD, NATION).usedMinorUnits());
        }

        try (CivicDatabase database = database()) {
            MonetaryStockCorrectionRegistry corrections =
                    new MonetaryStockCorrectionRegistry(database, CORRECTION_CLOCK);
            assertEquals(corrected, corrections.correction(incidentId));
            assertEquals(corrected, corrections.correct(new CorrectMonetaryStock(
                    "civic-admin-console:Server",
                    "correction-1",
                    incidentId,
                    "incident-ticket:CE-1042",
                    "LC Treasury credit independently confirmed")));
            assertThrows(IdempotencyConflictException.class, () -> corrections.correct(
                    new CorrectMonetaryStock(
                            "civic-admin-console:Server",
                            "correction-1",
                            incidentId,
                            "incident-ticket:changed",
                            "LC Treasury credit independently confirmed")));
            assertEquals(1, corrections.events().size());
            assertEquals(MoneyAmount.ofMinorUnits(300L), corrections.cumulativeNetIssuance());
            assertThrows(IllegalArgumentException.class, () ->
                    corrections.correction(UUID.randomUUID()));
        }
    }

    @Test
    void correctionFailsBeforeAuditWhenTheExactIncidentAmountWouldExceedTheHardCap() {
        try (CivicDatabase database = database()) {
            prepareIncident(database);
            UUID incidentId = database.mintRecoveryIncident(
                    OPERATION, "TREASURY_CREDIT").incidentId();
            database.confirmMonetarySupplyChange(
                    UUID.randomUUID(),
                    "issuance-controller",
                    "seed-800",
                    "ISSUANCE",
                    800L,
                    "seed:800",
                    "Existing confirmed issuance",
                    START + 2_500L,
                    1_000L);
            MonetaryStockCorrectionRegistry corrections =
                    new MonetaryStockCorrectionRegistry(database, CORRECTION_CLOCK);

            assertThrows(IssuanceHardCapExceededException.class, () -> corrections.correct(
                    new CorrectMonetaryStock(
                            "civic-admin-console:Server",
                            "correction-over-cap",
                            incidentId,
                            "incident-ticket:CE-1043",
                            "Would exceed the hard cap")));

            assertEquals(MoneyAmount.ofMinorUnits(800L), corrections.cumulativeNetIssuance());
            assertEquals("OPEN", database.mintRecoveryIncident(
                    OPERATION, "TREASURY_CREDIT").state());
            assertEquals(1, database.recoverableMintIssuanceOperations().size());
            assertEquals(1, corrections.events().size());
            assertEquals(null, database.monetaryStockCorrection(
                    "civic-admin-console:Server", "correction-over-cap"));
        }
    }

    @Test
    void correctionRejectsMaterialConsumptionIncidentsBeforeChangingSupply() {
        try (CivicDatabase database = database()) {
            prepareIncident(database);
            database.confirmMintBatchIssuanceExternal(
                    OPERATION,
                    "lc-mint-issuance:" + OPERATION,
                    START + 2_500L);
            UUID materialIncidentId = database.recordMintRecoveryIncident(
                            OPERATION,
                            "MATERIAL_CONSUMPTION",
                            "IllegalStateException",
                            "Mint custody confirmation unavailable",
                            START + 2_600L)
                    .incidentId();
            MonetaryStockCorrectionRegistry corrections =
                    new MonetaryStockCorrectionRegistry(database, CORRECTION_CLOCK);

            assertThrows(IllegalStateException.class, () -> corrections.correct(
                    new CorrectMonetaryStock(
                            "civic-admin-console:Server",
                            "correction-material-incident",
                            materialIncidentId,
                            "incident-ticket:CE-1044",
                            "Material evidence cannot change Monetary Supply")));

            assertEquals(MoneyAmount.ZERO, corrections.cumulativeNetIssuance());
            assertEquals("OPEN", database.mintRecoveryIncident(
                    OPERATION, "MATERIAL_CONSUMPTION").state());
            assertEquals(null, database.monetaryStockCorrection(materialIncidentId));
        }
    }

    private void prepareIncident(CivicDatabase database) {
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
        database.recordMintRecoveryIncident(
                OPERATION,
                "TREASURY_CREDIT",
                "IllegalStateException",
                "LC confirmation unavailable",
                START + 2_000L);
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("monetary-stock-correction.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("106c7e45-6f8c-4b71-9099-33b357d74e4f"),
                        "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20"));
    }
}
