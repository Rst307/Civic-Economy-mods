package org.civiceconomy.mint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.civiceconomy.persistence.StoredMintMaterialStack;
import org.civiceconomy.persistence.StoredMintRecoveryIncident;
import org.civiceconomy.persistence.StoredMintRecipeIngredient;
import org.civiceconomy.persistence.StoredNationalIssuanceQuotaAllocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MintIssuanceRecoveryTest {
    private static final UUID NATION = UUID.fromString("33c98df6-5400-4f9d-8cbd-8c691c976972");
    private static final UUID PERIOD = UUID.fromString("ab33daa7-d29b-431a-b47f-eb7b8397bd17");
    private static final UUID RECIPE = UUID.fromString("3dccdd88-9ec4-43c7-b411-a6255a64bcc7");
    private static final UUID MINT = UUID.fromString("3d5636ff-3e71-45e1-81c4-634371b8c3ee");
    private static final UUID ACTOR = UUID.fromString("e6300ef1-5d9a-440d-ae66-cd2145bcd69e");
    private static final UUID BATCH = UUID.fromString("d0979071-02bd-4b8d-a262-7a7eb1c0b999");
    private static final long START = Instant.parse("2026-08-10T00:00:00Z").toEpochMilli();
    private static final Clock DUE_CLOCK = Clock.fixed(
            Instant.ofEpochMilli(START + 60_002L), ZoneOffset.UTC);

    @TempDir Path temporaryDirectory;

    @Test
    void replayAcrossLcAndMaterialCrashWindowsCommitsOneIssuance() {
        FakeIssuances issuances = new FakeIssuances();
        FakeCustody custody = new FakeCustody();

        try (CivicDatabase database = database()) {
            setupProcessingBatch(database);
            PendingMintIssuanceStep pending = onlyPending(database);
            PendingMintTreasuryCredit credit =
                    assertInstanceOf(PendingMintTreasuryCredit.class, pending);
            credit.apply(issuances, custody);
            assertEquals(MoneyAmount.ofMinorUnits(300L), issuances.balance);
        }

        try (CivicDatabase database = database()) {
            PendingMintTreasuryCredit replay = assertInstanceOf(
                    PendingMintTreasuryCredit.class, onlyPending(database));
            replay.apply(issuances, custody);
            assertEquals(MoneyAmount.ofMinorUnits(300L), issuances.balance);
            new MintIssuanceRecovery(database, DUE_CLOCK).confirmExternal(replay);
        }

        try (CivicDatabase database = database()) {
            PendingMintMaterialConsumption consumption = assertInstanceOf(
                    PendingMintMaterialConsumption.class, onlyPending(database));
            consumption.apply(issuances, custody);
            assertEquals(1, custody.consumed.size());
        }

        try (CivicDatabase database = database()) {
            PendingMintMaterialConsumption replay = assertInstanceOf(
                    PendingMintMaterialConsumption.class, onlyPending(database));
            replay.apply(issuances, custody);
            assertEquals(1, custody.consumed.size());
            new MintIssuanceRecovery(database, DUE_CLOCK).confirmExternal(replay);

            assertEquals("COMMITTED", database.mintBatch(BATCH).state());
            assertEquals(0L, database.nationalIssuanceQuota(PERIOD, NATION).reservedMinorUnits());
            assertEquals(300L, database.nationalIssuanceQuota(PERIOD, NATION).usedMinorUnits());
            assertEquals(300L, database.cumulativeNetIssuanceMinorUnits());
            assertEquals(1, database.monetarySupplyEvents().size());
            assertEquals(List.of(), new MintIssuanceRecovery(database, DUE_CLOCK).pendingExternal());
        }
    }

    @Test
    void treasuryRecoveryFailureIsPersistedAndResolvedByDurableConfirmation() {
        try (CivicDatabase database = database()) {
            setupProcessingBatch(database);
            MintIssuanceRecovery recovery = new MintIssuanceRecovery(database, DUE_CLOCK);
            PendingMintTreasuryCredit credit = assertInstanceOf(
                    PendingMintTreasuryCredit.class, onlyPending(database));

            StoredMintRecoveryIncident incident = recovery.recordFailure(
                    credit, new IllegalStateException("LC Treasury unavailable"));
            assertEquals("OPEN", incident.state());
            assertEquals("TREASURY_CREDIT", incident.step());
            assertEquals("IllegalStateException", incident.failureKind());
            assertEquals("LC Treasury unavailable", incident.failureMessage());

            recovery.confirmExternal(credit);
            StoredMintRecoveryIncident resolved = database.mintRecoveryIncident(
                    credit.operationId(), "TREASURY_CREDIT");
            assertEquals("RESOLVED", resolved.state());
            assertEquals("STEP_CONFIRMED", resolved.resolutionKind());
            assertEquals("lc-mint-issuance:" + credit.operationId(), resolved.resolutionDetail());
        }
    }

    @Test
    void materialRecoveryFailureIsUnwrappedAndResolvedByDurableConfirmation() {
        try (CivicDatabase database = database()) {
            setupProcessingBatch(database);
            MintIssuanceRecovery recovery = new MintIssuanceRecovery(database, DUE_CLOCK);
            PendingMintTreasuryCredit credit = assertInstanceOf(
                    PendingMintTreasuryCredit.class, onlyPending(database));
            recovery.confirmExternal(credit);
            PendingMintMaterialConsumption consumption = assertInstanceOf(
                    PendingMintMaterialConsumption.class, onlyPending(database));

            StoredMintRecoveryIncident incident = recovery.recordFailure(
                    consumption,
                    new CompletionException(
                            new IllegalStateException("Mint custody owner offline")));
            assertEquals("MATERIAL_CONSUMPTION", incident.step());
            assertEquals("IllegalStateException", incident.failureKind());
            assertEquals("Mint custody owner offline", incident.failureMessage());

            recovery.confirmExternal(consumption);
            StoredMintRecoveryIncident resolved = database.mintRecoveryIncident(
                    consumption.operationId(), "MATERIAL_CONSUMPTION");
            assertEquals("RESOLVED", resolved.state());
            assertEquals(
                    "mint-material-consume:" + consumption.operationId(),
                    resolved.resolutionDetail());
        }
    }

    private PendingMintIssuanceStep onlyPending(CivicDatabase database) {
        List<PendingMintIssuanceStep> pending =
                new MintIssuanceRecovery(database, DUE_CLOCK).pendingExternal();
        assertEquals(1, pending.size());
        return pending.getFirst();
    }

    private void setupProcessingBatch(CivicDatabase database) {
        database.registerNation(NATION, "test", "nation", UUID.randomUUID(), START - 10L);
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
                temporaryDirectory.resolve("mint-issuance.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("6977a2c0-d3fd-4aa8-95f5-bb9f13c9a839"),
                        "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20"));
    }

    private static final class FakeIssuances implements ExternalMintIssuances {
        private final Set<UUID> applied = new HashSet<>();
        private MoneyAmount balance = MoneyAmount.ZERO;

        @Override
        public void apply(ExternalMintIssuance issuance) {
            if (applied.add(issuance.issuanceId())) {
                balance = balance.plus(issuance.amount());
            }
        }
    }

    private static final class FakeCustody implements ExternalMintMaterialCustody {
        private final Set<UUID> consumed = new HashSet<>();

        @Override
        public void take(MintMaterialCustodyTransfer transfer) {}

        @Override
        public void returnToSource(MintMaterialCustodyReturn operation) {}

        @Override
        public void consume(MintMaterialCustodyConsumption operation) {
            consumed.add(operation.operationId());
        }
    }
}
