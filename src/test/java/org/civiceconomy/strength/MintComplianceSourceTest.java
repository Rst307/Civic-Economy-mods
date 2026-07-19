package org.civiceconomy.strength;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.civiceconomy.mint.MintIssuanceRecovery;
import org.civiceconomy.monetary.CorrectMonetaryStock;
import org.civiceconomy.monetary.MonetaryStockCorrectionRegistry;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.civiceconomy.persistence.StoredMintMaterialStack;
import org.civiceconomy.persistence.StoredMintRecipeIngredient;
import org.civiceconomy.persistence.StoredNationalIssuanceQuotaAllocation;
import org.civiceconomy.nation.NationId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MintComplianceSourceTest {
    private static final UUID NATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TEAM = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final UUID PERIOD = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RECIPE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID MINT = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ACTOR = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID BATCH = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final long START = Instant.parse("2026-08-10T00:00:00Z").toEpochMilli();
    private static final Clock DUE = Clock.fixed(
            Instant.ofEpochMilli(START + 60_002L), ZoneOffset.UTC);

    @TempDir Path temporaryDirectory;

    @Test
    void openIncidentPausesExactNationComplianceWithoutCountingRetries() {
        try (CivicDatabase database = database()) {
            setupProcessingBatch(database);
            MintIssuanceRecovery recovery = new MintIssuanceRecovery(database, DUE);
            var pending = recovery.pendingExternal().getFirst();
            recovery.recordFailure(pending, new IllegalStateException("LC unavailable"));
            recovery.recordFailure(pending, new IllegalStateException("LC still unavailable"));
            new MintCompliancePolicyRegistry(
                            database,
                            Clock.fixed(Instant.ofEpochMilli(START), ZoneOffset.UTC))
                    .schedule(new ScheduleMintCompliancePolicy(
                            new org.civiceconomy.fiscal.ServiceIdentity(
                                    "civiceconomy-mint-compliance-policy"),
                            "source-test-policy",
                            "civic-admin-console:test",
                            new MintCompliancePolicy(Duration.ofDays(30), 5_000),
                            Instant.ofEpochMilli(START + 1L),
                            "Test Mint Compliance policy"));

            MintComplianceAssessment assessment = new MintComplianceSource(database).assess(
                    new NationId(NATION), START, START + 120_000L, 5_000);

            assertEquals(1, assessment.observationCount());
            assertEquals(1, assessment.openIncidentCount());
            assertEquals(0, assessment.normalizedBasisPoints());
            assertTrue(assessment.anomalous());

            NationalStrengthRecalculation recalculation =
                    new NationalStrengthSnapshotBuilder(
                                    database,
                                    new NationalStrengthSnapshotConfiguration(
                                            Duration.ofDays(7),
                                            Duration.ofDays(60),
                                            Duration.ofHours(8)),
                                    Map.of(TEAM, List.of()))
                            .recalculateAll(START + 120_000L)
                            .nations()
                            .get(new NationId(NATION));
            assertEquals(1, recalculation.mintCompliance().openIncidentCount());
            assertEquals(
                    NationalStrengthComponentState.PAUSED_ANOMALY,
                    recalculation.assessment().componentState(
                            NationalStrengthComponent.COMPLIANCE));
        }
    }

    @Test
    void correctedButUncommittedBatchRemainsAQuarantinedComplianceObservation() {
        try (CivicDatabase database = database()) {
            setupProcessingBatch(database);
            MintIssuanceRecovery recovery = new MintIssuanceRecovery(database, DUE);
            var pending = recovery.pendingExternal().getFirst();
            var incident = recovery.recordFailure(
                    pending, new IllegalStateException("LC confirmation unavailable"));
            new MonetaryStockCorrectionRegistry(database, DUE).correct(
                    new CorrectMonetaryStock(
                            "civic-admin-console:Server",
                            "correct-quarantined-compliance-batch",
                            incident.incidentId(),
                            "incident-ticket:CE-compliance",
                            "Treasury credit independently confirmed"));

            MintComplianceAssessment assessment = new MintComplianceSource(database).assess(
                    new NationId(NATION), START, START + 120_000L, 5_000);

            assertEquals(1, assessment.observationCount());
            assertEquals(1, assessment.quarantinedRecoveryCount());
            assertEquals(0, assessment.normalizedBasisPoints());
            assertTrue(assessment.anomalous());
        }
    }

    @Test
    void batchCannotContributeToAnotherNationsCompliance() {
        try (CivicDatabase database = database()) {
            setupProcessingBatch(database);
            MintIssuanceRecovery recovery = new MintIssuanceRecovery(database, DUE);
            var pending = recovery.pendingExternal().getFirst();
            recovery.recordFailure(pending, new IllegalStateException("LC unavailable"));

            MintComplianceAssessment assessment = new MintComplianceSource(database).assess(
                    new NationId(UUID.fromString(
                            "99999999-9999-9999-9999-999999999999")),
                    START,
                    START + 120_000L,
                    5_000);

            assertEquals(0, assessment.observationCount());
            assertEquals(0, assessment.normalizedBasisPoints());
        }
    }

    @Test
    void operationPreparationUsesAHalfOpenComplianceWindow() {
        try (CivicDatabase database = database()) {
            setupProcessingBatch(database);
            MintIssuanceRecovery recovery = new MintIssuanceRecovery(database, DUE);
            var pending = recovery.pendingExternal().getFirst();
            recovery.recordFailure(pending, new IllegalStateException("LC unavailable"));
            MintComplianceSource source = new MintComplianceSource(database);

            assertEquals(1, source.assess(
                            new NationId(NATION), DUE.millis(), DUE.millis() + 1L, 5_000)
                    .observationCount());
            assertEquals(0, source.assess(
                            new NationId(NATION), START, DUE.millis(), 5_000)
                    .observationCount());
        }
    }

    @Test
    void multipleResolvedIncidentsBecomeOneRecoveredCommitObservation() {
        try (CivicDatabase database = database()) {
            setupProcessingBatch(database);
            MintIssuanceRecovery recovery = new MintIssuanceRecovery(database, DUE);
            var treasuryCredit = recovery.pendingExternal().getFirst();
            recovery.recordFailure(
                    treasuryCredit, new IllegalStateException("LC confirmation unavailable"));
            recovery.confirmExternal(treasuryCredit);
            var materialConsumption = recovery.pendingExternal().getFirst();
            recovery.recordFailure(
                    materialConsumption, new IllegalStateException("Custody unavailable"));
            recovery.confirmExternal(materialConsumption);

            MintComplianceAssessment assessment = new MintComplianceSource(database).assess(
                    new NationId(NATION), START, START + 120_000L, 5_000);

            assertEquals(1, assessment.observationCount());
            assertEquals(1, assessment.recoveredCommitCount());
            assertEquals(5_000, assessment.normalizedBasisPoints());
            assertFalse(assessment.anomalous());
        }
    }

    private void setupProcessingBatch(CivicDatabase database) {
        database.registerNation(NATION, "test", "nation", TEAM, START - 10L);
        database.publishIssuanceQuotaPeriod(
                PERIOD, "issuance-controller", "period", START, START + 604_800_000L,
                1_000L, 400L,
                List.of(new StoredNationalIssuanceQuotaAllocation(NATION, 400L)),
                "Quota period", START - 2L);
        database.activateNationalIssuanceQuota(
                UUID.randomUUID(), "nation-governance", "activate", PERIOD, NATION, ACTOR,
                400L, "Activate quota", START - 1L);
        database.publishMintRecipeVersion(
                RECIPE, "mint-controller", "recipe", 1,
                List.of(new StoredMintRecipeIngredient(
                        0, "EXACT_ITEM", "minecraft:diamond", 1L, 100L)),
                60_000L, "Recipe", START - 1L);
        database.registerMint(
                MINT, "mint-controller", "mint", NATION, "minecraft:overworld",
                1, 70, 2, UUID.randomUUID(), UUID.randomUUID(), true,
                RECIPE, ACTOR, "Mint", START - 1L);
        database.prepareMintBatch(
                BATCH, "mint-controller", "prepare", MINT, PERIOD, NATION, RECIPE,
                300L,
                List.of(new StoredMintMaterialStack(
                        0, "EXACT_ITEM", "minecraft:diamond", "minecraft:diamond", 3L)),
                ACTOR, "Prepare", START + 1L);
        database.confirmMintBatchCustody(
                BATCH, "mint-controller", "custody", "inventory-move", START + 2L);
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("mint-compliance.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("77777777-7777-7777-7777-777777777777"),
                        "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20"));
    }
}
