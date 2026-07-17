package org.civiceconomy.strength;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.CitizenshipRegistry;
import org.civiceconomy.nation.JoinCitizenship;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.OnlineTimeLedger;
import org.civiceconomy.nation.RecordOnlineTime;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.civiceconomy.territory.AssessTerritoryFiscalValidity;
import org.civiceconomy.territory.OpenTerritoryMaintenanceCycle;
import org.civiceconomy.territory.SuspendTerritoryMaintenance;
import org.civiceconomy.territory.TerritoryClaimPosition;
import org.civiceconomy.territory.TerritoryMaintenanceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NationalStrengthSnapshotBuilderTest {
    private static final Instant RECALCULATED_AT = Instant.parse("2026-07-17T00:00:00Z");

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
                            database,
                            Duration.ofDays(7),
                            Duration.ofDays(60),
                            Duration.ofHours(8),
                            4,
                            30L * 24L * 60L * 60L * 1_000L,
                            10_000L)
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

    @Test
    void activatesEffectiveCitizensFromAuthoritativePopulationEvidence() {
        NationId nationId = new NationId(
                UUID.fromString("33333333-3333-3333-3333-333333333333"));
        UUID citizenId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        try (CivicDatabase database = database()) {
            database.registerNation(
                    nationId.value(),
                    "civiceconomy-tests",
                    "citizen-strength-nation",
                    UUID.fromString("cccccccc-3333-3333-3333-333333333333"),
                    RECALCULATED_AT.minus(Duration.ofDays(10)).toEpochMilli());
            Clock evidenceClock = Clock.fixed(
                    RECALCULATED_AT.minus(Duration.ofDays(9)), ZoneOffset.UTC);
            new CitizenshipRegistry(database, Duration.ofDays(7), evidenceClock)
                    .join(new JoinCitizenship(
                            new ServiceIdentity("civiceconomy"),
                            "citizen-strength-join",
                            citizenId,
                            nationId));
            new OnlineTimeLedger(database).record(new RecordOnlineTime(
                    new ServiceIdentity("civiceconomy-server"),
                    "citizen-strength-online",
                    citizenId,
                    RECALCULATED_AT.minus(Duration.ofHours(8)).toEpochMilli(),
                    RECALCULATED_AT.toEpochMilli()));

            NationalStrengthRecalculation recalculation =
                    new NationalStrengthSnapshotBuilder(
                                    database,
                                    Duration.ofDays(7),
                                    Duration.ofDays(60),
                                    Duration.ofHours(8),
                                    4,
                                    Duration.ofDays(30).toMillis(),
                                    10_000L)
                            .recalculateAll(RECALCULATED_AT.toEpochMilli())
                            .nations()
                            .get(nationId);

            assertEquals(1, recalculation.effectiveCitizenPopulation().effectiveCitizenCount());
            assertEquals(
                    1D,
                    recalculation.effectiveCitizenPopulation().populationEquivalent(),
                    0.0000001D);
            assertEquals(
                    5_000,
                    recalculation.assessment()
                            .component(NationalStrengthComponent.EFFECTIVE_CITIZENS)
                            .normalizedInputBasisPoints());
            assertEquals(
                    NationalStrengthComponentState.ACTIVE,
                    recalculation.assessment().componentState(
                            NationalStrengthComponent.EFFECTIVE_CITIZENS));
            assertEquals(
                    NationalStrengthComponentState.PAUSED_ANOMALY,
                    recalculation.assessment().componentState(
                            NationalStrengthComponent.PRODUCTION_AND_INFRASTRUCTURE));
            assertTrue(recalculation.assessment().newMintAllocationPaused());
        }
    }

    @Test
    void scoresOnlyCurrentClaimsWithLatestEffectiveFiscalConclusion() {
        NationId nationId = new NationId(
                UUID.fromString("55555555-5555-5555-5555-555555555555"));
        UUID teamId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        TerritoryClaimPosition effective =
                new TerritoryClaimPosition("minecraft:overworld", 3, 4);
        TerritoryClaimPosition suspended =
                new TerritoryClaimPosition("minecraft:overworld", 8, 9);
        try (CivicDatabase database = database()) {
            database.registerNation(
                    nationId.value(),
                    "civiceconomy-tests",
                    "territory-strength-nation",
                    teamId,
                    RECALCULATED_AT.minus(Duration.ofDays(10)).toEpochMilli());
            TerritoryMaintenanceRegistry maintenance = new TerritoryMaintenanceRegistry(
                    database, Clock.fixed(RECALCULATED_AT, ZoneOffset.UTC));
            var fundedCycle = maintenance.openCycle(new OpenTerritoryMaintenanceCycle(
                    new ServiceIdentity("civiceconomy-territory"),
                    "territory-strength-funded-cycle",
                    RECALCULATED_AT.minus(Duration.ofDays(4)),
                    RECALCULATED_AT.minus(Duration.ofDays(3))));
            maintenance.assess(new AssessTerritoryFiscalValidity(
                    new ServiceIdentity("civiceconomy-territory"),
                    "territory-strength-effective-assessment",
                    fundedCycle.cycleId(),
                    nationId,
                    teamId,
                    effective.dimensionId(),
                    effective.chunkX(),
                    effective.chunkZ(),
                    0L,
                    "Zero-cost effective Claim"));
            maintenance.settleZeroCostAssessments(new SuspendTerritoryMaintenance(
                    new ServiceIdentity("civiceconomy-territory"),
                    "territory-strength-funded-settlement",
                    fundedCycle.cycleId(),
                    nationId,
                    "Zero-cost maintenance"));
            var unfundedCycle = maintenance.openCycle(new OpenTerritoryMaintenanceCycle(
                    new ServiceIdentity("civiceconomy-territory"),
                    "territory-strength-unfunded-cycle",
                    RECALCULATED_AT.minus(Duration.ofDays(2)),
                    RECALCULATED_AT.minus(Duration.ofDays(1))));
            maintenance.assess(new AssessTerritoryFiscalValidity(
                    new ServiceIdentity("civiceconomy-territory"),
                    "territory-strength-suspended-assessment",
                    unfundedCycle.cycleId(),
                    nationId,
                    teamId,
                    suspended.dimensionId(),
                    suspended.chunkX(),
                    suspended.chunkZ(),
                    100L,
                    "Unfunded Claim"));
            maintenance.suspend(new SuspendTerritoryMaintenance(
                    new ServiceIdentity("civiceconomy-territory"),
                    "territory-strength-unfunded-settlement",
                    unfundedCycle.cycleId(),
                    nationId,
                    "No maintenance funds"));

            NationalStrengthRecalculation recalculation =
                    new NationalStrengthSnapshotBuilder(
                                    database,
                                    new NationalStrengthSnapshotConfiguration(
                                            Duration.ofDays(7),
                                            Duration.ofDays(60),
                                            Duration.ofHours(8),
                                            4,
                                            4,
                                            Duration.ofDays(30),
                                            10_000L),
                                    Map.of(teamId, List.of(effective, suspended)))
                            .recalculateAll(RECALCULATED_AT.toEpochMilli())
                            .nations()
                            .get(nationId);

            assertEquals(2, recalculation.effectiveTerritory().currentClaimCount());
            assertEquals(1, recalculation.effectiveTerritory().effectiveClaimCount());
            assertEquals(1, recalculation.effectiveTerritory().suspendedClaimCount());
            assertEquals(0, recalculation.effectiveTerritory().unassessedClaimCount());
            assertEquals(
                    5_000,
                    recalculation.assessment()
                            .component(NationalStrengthComponent.EFFECTIVE_TERRITORY)
                            .normalizedInputBasisPoints());
            assertEquals(
                    NationalStrengthComponentState.ACTIVE,
                    recalculation.assessment().componentState(
                            NationalStrengthComponent.EFFECTIVE_TERRITORY));
            assertTrue(recalculation.assessment().newMintAllocationPaused());
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
