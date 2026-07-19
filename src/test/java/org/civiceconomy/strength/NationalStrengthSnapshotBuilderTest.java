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
            scheduleEffectiveCitizenStrengthPolicy(
                    database,
                    "citizen-strength-policy",
                    4,
                    RECALCULATED_AT.minus(Duration.ofDays(1)));

            NationalStrengthRecalculation recalculation =
                    new NationalStrengthSnapshotBuilder(
                                    database,
                                    Duration.ofDays(7),
                                    Duration.ofDays(60),
                                    Duration.ofHours(8),
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
    void completePopulationWithoutStrengthPolicyKeepsEffectiveCitizensPaused() {
        NationId nationId = new NationId(
                UUID.fromString("73333333-3333-3333-3333-333333333337"));
        UUID citizenId = UUID.fromString("84444444-4444-4444-4444-444444444448");
        try (CivicDatabase database = database()) {
            database.registerNation(
                    nationId.value(),
                    "civiceconomy-tests",
                    "missing-citizen-strength-policy-nation",
                    UUID.fromString("dccccccc-3333-3333-3333-33333333333d"),
                    RECALCULATED_AT.minus(Duration.ofDays(10)).toEpochMilli());
            Clock evidenceClock = Clock.fixed(
                    RECALCULATED_AT.minus(Duration.ofDays(9)), ZoneOffset.UTC);
            new CitizenshipRegistry(database, Duration.ofDays(7), evidenceClock)
                    .join(new JoinCitizenship(
                            new ServiceIdentity("civiceconomy"),
                            "missing-citizen-strength-policy-join",
                            citizenId,
                            nationId));
            new OnlineTimeLedger(database).record(new RecordOnlineTime(
                    new ServiceIdentity("civiceconomy-server"),
                    "missing-citizen-strength-policy-online",
                    citizenId,
                    RECALCULATED_AT.minus(Duration.ofHours(8)).toEpochMilli(),
                    RECALCULATED_AT.toEpochMilli()));

            NationalStrengthRecalculation recalculation =
                    new NationalStrengthSnapshotBuilder(
                                    database,
                                    Duration.ofDays(7),
                                    Duration.ofDays(60),
                                    Duration.ofHours(8),
                                    Duration.ofDays(30).toMillis(),
                                    10_000L)
                            .recalculateAll(RECALCULATED_AT.toEpochMilli())
                            .nations()
                            .get(nationId);

            assertEquals(
                    0,
                    recalculation.assessment()
                            .component(NationalStrengthComponent.EFFECTIVE_CITIZENS)
                            .normalizedInputBasisPoints());
            assertEquals(
                    NationalStrengthComponentState.PAUSED_ANOMALY,
                    recalculation.assessment().componentState(
                            NationalStrengthComponent.EFFECTIVE_CITIZENS));
        }
    }

    @Test
    void laterStrengthPolicyChangesOnlyLaterAssessmentWithoutRewritingPopulationEvidence() {
        NationId nationId = new NationId(
                UUID.fromString("93333333-3333-3333-3333-333333333339"));
        UUID citizenId = UUID.fromString("a4444444-4444-4444-4444-44444444444a");
        Instant laterPolicyAt = RECALCULATED_AT.plus(Duration.ofDays(1));
        Instant laterAssessmentAt = RECALCULATED_AT.plus(Duration.ofDays(2));
        try (CivicDatabase database = database()) {
            database.registerNation(
                    nationId.value(),
                    "civiceconomy-tests",
                    "versioned-citizen-strength-policy-nation",
                    UUID.fromString("eccccccc-3333-3333-3333-33333333333e"),
                    RECALCULATED_AT.minus(Duration.ofDays(10)).toEpochMilli());
            Clock evidenceClock = Clock.fixed(
                    RECALCULATED_AT.minus(Duration.ofDays(9)), ZoneOffset.UTC);
            new CitizenshipRegistry(database, Duration.ofDays(7), evidenceClock)
                    .join(new JoinCitizenship(
                            new ServiceIdentity("civiceconomy"),
                            "versioned-citizen-strength-policy-join",
                            citizenId,
                            nationId));
            new OnlineTimeLedger(database).record(new RecordOnlineTime(
                    new ServiceIdentity("civiceconomy-server"),
                    "versioned-citizen-strength-policy-online",
                    citizenId,
                    RECALCULATED_AT.minus(Duration.ofHours(8)).toEpochMilli(),
                    RECALCULATED_AT.toEpochMilli()));
            scheduleEffectiveCitizenStrengthPolicy(
                    database,
                    "initial-versioned-citizen-strength-policy",
                    4,
                    RECALCULATED_AT.minus(Duration.ofDays(1)));
            scheduleEffectiveCitizenStrengthPolicy(
                    database,
                    "later-versioned-citizen-strength-policy",
                    16,
                    laterPolicyAt);

            NationalStrengthRecalculation first =
                    new NationalStrengthSnapshotBuilder(
                                    database,
                                    Duration.ofDays(7),
                                    Duration.ofDays(60),
                                    Duration.ofHours(8),
                                    Duration.ofDays(30).toMillis(),
                                    10_000L)
                            .recalculateAll(RECALCULATED_AT.toEpochMilli())
                            .nations()
                            .get(nationId);
            NationalStrengthRecalculation second =
                    new NationalStrengthSnapshotBuilder(
                                    database,
                                    Duration.ofDays(7),
                                    Duration.ofDays(60),
                                    Duration.ofHours(8),
                                    Duration.ofDays(30).toMillis(),
                                    10_000L)
                            .recalculateAll(laterAssessmentAt.toEpochMilli())
                            .nations()
                            .get(nationId);

            assertEquals(1D, first.effectiveCitizenPopulation().populationEquivalent());
            assertEquals(1D, second.effectiveCitizenPopulation().populationEquivalent());
            assertEquals(
                    5_000,
                    first.assessment()
                            .component(NationalStrengthComponent.EFFECTIVE_CITIZENS)
                            .normalizedInputBasisPoints());
            assertEquals(
                    2_500,
                    second.assessment()
                            .component(NationalStrengthComponent.EFFECTIVE_CITIZENS)
                            .normalizedInputBasisPoints());
        }
    }

    @Test
    void preservesTerritoryFactsButPausesScoringWithoutPolicy() {
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
                                            Duration.ofDays(30),
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
                    0,
                    recalculation.assessment()
                            .component(NationalStrengthComponent.EFFECTIVE_TERRITORY)
                            .normalizedInputBasisPoints());
            assertEquals(
                    NationalStrengthComponentState.PAUSED_ANOMALY,
                    recalculation.assessment().componentState(
                            NationalStrengthComponent.EFFECTIVE_TERRITORY));
            assertTrue(recalculation.assessment().newMintAllocationPaused());

            Instant firstPolicyAt = RECALCULATED_AT.plusSeconds(1L);
            scheduleEffectiveTerritoryStrengthPolicy(
                    database, "territory-scale-four", 4, firstPolicyAt);
            NationalStrengthRecalculation firstPolicy =
                    new NationalStrengthSnapshotBuilder(
                                    database,
                                    new NationalStrengthSnapshotConfiguration(
                                            Duration.ofDays(7),
                                            Duration.ofDays(60),
                                            Duration.ofHours(8),
                                            Duration.ofDays(30),
                                            Duration.ofDays(30),
                                            10_000L),
                                    Map.of(teamId, List.of(effective, suspended)))
                            .recalculateAll(firstPolicyAt.toEpochMilli())
                            .nations()
                            .get(nationId);
            assertEquals(5_000, firstPolicy.assessment()
                    .component(NationalStrengthComponent.EFFECTIVE_TERRITORY)
                    .normalizedInputBasisPoints());
            assertEquals(NationalStrengthComponentState.ACTIVE,
                    firstPolicy.assessment().componentState(
                            NationalStrengthComponent.EFFECTIVE_TERRITORY));

            Instant secondPolicyAt = firstPolicyAt.plusSeconds(1L);
            scheduleEffectiveTerritoryStrengthPolicy(
                    database, "territory-scale-sixteen", 16, secondPolicyAt);
            NationalStrengthRecalculation secondPolicy =
                    new NationalStrengthSnapshotBuilder(
                                    database,
                                    new NationalStrengthSnapshotConfiguration(
                                            Duration.ofDays(7),
                                            Duration.ofDays(60),
                                            Duration.ofHours(8),
                                            Duration.ofDays(30),
                                            Duration.ofDays(30),
                                            10_000L),
                                    Map.of(teamId, List.of(effective, suspended)))
                            .recalculateAll(secondPolicyAt.toEpochMilli())
                            .nations()
                            .get(nationId);
            assertEquals(2_500, secondPolicy.assessment()
                    .component(NationalStrengthComponent.EFFECTIVE_TERRITORY)
                    .normalizedInputBasisPoints());
            assertEquals(firstPolicy.effectiveTerritory(), secondPolicy.effectiveTerritory());
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

    private static void scheduleEffectiveCitizenStrengthPolicy(
            CivicDatabase database,
            String requestId,
            int fullStrengthScaleCitizenEquivalents,
            Instant effectiveAt) {
        Clock clock = Clock.fixed(effectiveAt.minusMillis(1L), ZoneOffset.UTC);
        new EffectiveCitizenStrengthPolicyRegistry(database, clock)
                .schedule(new ScheduleEffectiveCitizenStrengthPolicy(
                        new ServiceIdentity(
                                "civiceconomy-effective-citizen-strength-policy"),
                        requestId,
                        "civic-admin-console:test",
                        new EffectiveCitizenStrengthPolicy(
                                fullStrengthScaleCitizenEquivalents),
                        effectiveAt,
                        "Trusted Effective Citizen Strength policy"));
    }

    private static void scheduleEffectiveTerritoryStrengthPolicy(
            CivicDatabase database,
            String requestId,
            int fullStrengthScaleEffectiveClaims,
            Instant effectiveAt) {
        Clock clock = Clock.fixed(effectiveAt.minusMillis(1L), ZoneOffset.UTC);
        new EffectiveTerritoryStrengthPolicyRegistry(database, clock)
                .schedule(new ScheduleEffectiveTerritoryStrengthPolicy(
                        new ServiceIdentity(
                                "civiceconomy-effective-territory-strength-policy"),
                        requestId,
                        "civic-admin-console:test",
                        new EffectiveTerritoryStrengthPolicy(
                                fullStrengthScaleEffectiveClaims),
                        effectiveAt,
                        "Trusted Effective Territory Strength policy"));
    }
}
