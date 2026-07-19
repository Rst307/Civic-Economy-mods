package org.civiceconomy.nation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CitizenshipReconciliationTest {
    private static final Instant JOINED_AT = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant MISSING_AT = Instant.parse("2026-07-14T08:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void missingFormalCitizenStartsGraceAndImmediatelyLosesProviderAuthority() {
        UUID teamId = UUID.fromString("4bca5fc5-3119-4e1f-897c-c2ff0d42da8d");
        UUID headId = UUID.fromString("4df7389b-b87f-48d4-8862-58da0a659f23");
        UUID citizenId = UUID.fromString("585f532f-94e6-4a0f-b209-3e5216eb690a");
        MutableTeams teams = new MutableTeams()
                .put(new NationTeam(teamId, headId, Set.of(headId, citizenId)));

        try (CivicDatabase database = database()) {
            NationRegistry nations = new NationRegistry(database, teams);
            RegisteredNation nation = nations.register(new RegisterNation(
                    new ServiceIdentity("civiceconomy"), "reconcile-register", teamId));
            CitizenshipRegistry joining = new CitizenshipRegistry(
                    database, Duration.ofDays(7), Clock.fixed(JOINED_AT, ZoneOffset.UTC));
            joining.join(new JoinCitizenship(
                    new ServiceIdentity("civiceconomy"),
                    "reconcile-head-join",
                    headId,
                    nation.nationId()));
            Citizenship citizen = joining.join(new JoinCitizenship(
                    new ServiceIdentity("civiceconomy"),
                    "reconcile-citizen-join",
                    citizenId,
                    nation.nationId()));
            CitizenshipCorrectionGraceRegistry corrections =
                    new CitizenshipCorrectionGraceRegistry(
                            database, Clock.fixed(MISSING_AT, ZoneOffset.UTC));
            NationProvider before = new FtbTeamsNationProvider(
                    nations, joining, corrections, teams);
            assertEquals(nation.nationId(), before.findForCitizen(citizenId).orElseThrow().nationId());

            teams.put(new NationTeam(teamId, headId, Set.of(headId)));
            CitizenshipReconciliationResult result = new CitizenshipReconciler(
                            database,
                            teams,
                            Duration.ofDays(2),
                            Duration.ofDays(7),
                            Clock.fixed(MISSING_AT, ZoneOffset.UTC))
                    .reconcile(nation.nationId());

            assertEquals(1, result.startedGraceCount());
            CitizenshipCorrectionGrace grace = corrections.activeForPlayer(citizenId).orElseThrow();
            assertEquals(citizen.citizenshipId(), grace.citizenshipId());
            assertEquals(MISSING_AT, grace.startedAt());
            assertEquals(MISSING_AT.plus(Duration.ofDays(2)), grace.deadline());
            assertTrue(joining.current(citizenId).isPresent());
            NationProvider suspended = new FtbTeamsNationProvider(
                    nations, joining, corrections, teams);
            assertTrue(suspended.findForCitizen(citizenId).isEmpty());
            assertEquals(Set.of(headId), suspended.find(nation.nationId()).orElseThrow().citizens());
        }
    }

    @Test
    void returningBeforeDeadlineRestoresTheSameCitizenship() {
        UUID teamId = UUID.fromString("7773c895-31f8-4941-aecd-48fa14b16cd9");
        UUID headId = UUID.fromString("870a43b2-623a-4335-a420-50bc73bd5068");
        UUID citizenId = UUID.fromString("7a285c9e-b9d9-4873-985b-426bfcc2a73c");
        MutableTeams teams = new MutableTeams()
                .put(new NationTeam(teamId, headId, Set.of(headId, citizenId)));

        try (CivicDatabase database = database()) {
            NationRegistry nations = new NationRegistry(database, teams);
            RegisteredNation nation = nations.register(new RegisterNation(
                    new ServiceIdentity("civiceconomy"), "restore-register", teamId));
            CitizenshipRegistry citizenships = new CitizenshipRegistry(
                    database, Duration.ofDays(7), Clock.fixed(JOINED_AT, ZoneOffset.UTC));
            citizenships.join(new JoinCitizenship(
                    new ServiceIdentity("civiceconomy"),
                    "restore-head-join",
                    headId,
                    nation.nationId()));
            Citizenship citizen = citizenships.join(new JoinCitizenship(
                    new ServiceIdentity("civiceconomy"),
                    "restore-citizen-join",
                    citizenId,
                    nation.nationId()));

            teams.put(new NationTeam(teamId, headId, Set.of(headId)));
            new CitizenshipReconciler(
                            database,
                            teams,
                            Duration.ofDays(2),
                            Duration.ofDays(7),
                            Clock.fixed(MISSING_AT, ZoneOffset.UTC))
                    .reconcile(nation.nationId());
            Instant returnedAt = MISSING_AT.plus(Duration.ofHours(12));
            teams.put(new NationTeam(teamId, headId, Set.of(headId, citizenId)));
            CitizenshipReconciliationResult restored = new CitizenshipReconciler(
                            database,
                            teams,
                            Duration.ofDays(2),
                            Duration.ofDays(7),
                            Clock.fixed(returnedAt, ZoneOffset.UTC))
                    .reconcile(nation.nationId());

            assertEquals(1, restored.restoredCount());
            CitizenshipCorrectionGraceRegistry corrections =
                    new CitizenshipCorrectionGraceRegistry(
                            database, Clock.fixed(returnedAt, ZoneOffset.UTC));
            assertTrue(corrections.activeForPlayer(citizenId).isEmpty());
            CitizenshipCorrectionGrace grace = corrections.history(citizenId).getLast();
            assertEquals(
                    Optional.of(CitizenshipCorrectionResolution.RESTORED),
                    grace.resolution());
            assertEquals(Optional.of(returnedAt), grace.resolvedAt());
            assertEquals(
                    citizen.citizenshipId(),
                    citizenships.current(citizenId).orElseThrow().citizenshipId());
            NationProvider provider = new FtbTeamsNationProvider(
                    nations, citizenships, corrections, teams);
            assertEquals(nation.nationId(), provider.findForCitizen(citizenId).orElseThrow().nationId());
        }
    }

    @Test
    void graceDeadlineEndsCitizenshipWithoutAutoEnrollingNewTeamMembers() {
        UUID teamId = UUID.fromString("1e47ff03-5830-43b8-aaf4-a94afdf4200f");
        UUID headId = UUID.fromString("e2521313-2782-43a5-aa87-63fa6bdf24dc");
        UUID citizenId = UUID.fromString("73b79409-aa7b-4100-9603-fba8029bb4b9");
        UUID newMemberId = UUID.fromString("e8036e25-5d7f-4ed4-9539-ac7a1178f85c");
        MutableTeams teams = new MutableTeams()
                .put(new NationTeam(teamId, headId, Set.of(headId, citizenId)));

        try (CivicDatabase database = database()) {
            NationRegistry nations = new NationRegistry(database, teams);
            RegisteredNation nation = nations.register(new RegisterNation(
                    new ServiceIdentity("civiceconomy"), "deadline-register", teamId));
            CitizenshipRegistry citizenships = new CitizenshipRegistry(
                    database, Duration.ofDays(7), Clock.fixed(JOINED_AT, ZoneOffset.UTC));
            citizenships.join(new JoinCitizenship(
                    new ServiceIdentity("civiceconomy"),
                    "deadline-head-join",
                    headId,
                    nation.nationId()));
            citizenships.join(new JoinCitizenship(
                    new ServiceIdentity("civiceconomy"),
                    "deadline-citizen-join",
                    citizenId,
                    nation.nationId()));

            teams.put(new NationTeam(teamId, headId, Set.of(headId)));
            new CitizenshipReconciler(
                            database,
                            teams,
                            Duration.ofDays(2),
                            Duration.ofDays(7),
                            Clock.fixed(MISSING_AT, ZoneOffset.UTC))
                    .reconcile(nation.nationId());
            Instant deadline = MISSING_AT.plus(Duration.ofDays(2));
            teams.put(new NationTeam(teamId, headId, Set.of(headId, newMemberId)));
            CitizenshipReconciliationResult ended = new CitizenshipReconciler(
                            database,
                            teams,
                            Duration.ofDays(2),
                            Duration.ofDays(7),
                            Clock.fixed(deadline.plus(Duration.ofHours(6)), ZoneOffset.UTC))
                    .reconcile(nation.nationId());

            assertEquals(1, ended.endedCitizenshipCount());
            assertTrue(citizenships.current(citizenId).isEmpty());
            assertEquals(
                    deadline.toEpochMilli(),
                    citizenships.history(citizenId).getLast().endedAtEpochMillis().orElseThrow());
            assertTrue(citizenships.current(newMemberId).isEmpty());
            CitizenshipCorrectionGraceRegistry corrections =
                    new CitizenshipCorrectionGraceRegistry(
                            database, Clock.fixed(deadline, ZoneOffset.UTC));
            CitizenshipCorrectionGrace grace = corrections.history(citizenId).getLast();
            assertEquals(
                    Optional.of(CitizenshipCorrectionResolution.CITIZENSHIP_ENDED),
                    grace.resolution());
            assertEquals(Optional.of(deadline), grace.resolvedAt());
            NationProvider provider = new FtbTeamsNationProvider(
                    nations, citizenships, corrections, teams);
            assertEquals(Set.of(headId), provider.find(nation.nationId()).orElseThrow().citizens());
            assertTrue(provider.findForCitizen(newMemberId).isEmpty());
        }
    }

    @Test
    void restartAfterCitizenshipLeaveCompletesTheUnresolvedGrace() {
        UUID teamId = UUID.fromString("3fb11f77-084c-475c-9196-8bf4dc1b4c24");
        UUID headId = UUID.fromString("fb074f42-256f-43f0-a3e7-05a86cc5ac20");
        UUID citizenId = UUID.fromString("0647d14e-dd50-4f5b-a88e-0a8402ff3b0f");
        MutableTeams teams = new MutableTeams()
                .put(new NationTeam(teamId, headId, Set.of(headId, citizenId)));
        NationId nationId;
        CitizenshipCorrectionGrace grace;

        try (CivicDatabase database = database()) {
            NationRegistry nations = new NationRegistry(database, teams);
            RegisteredNation nation = nations.register(new RegisterNation(
                    new ServiceIdentity("civiceconomy"), "restart-register", teamId));
            nationId = nation.nationId();
            CitizenshipRegistry citizenships = new CitizenshipRegistry(
                    database, Duration.ofDays(7), Clock.fixed(JOINED_AT, ZoneOffset.UTC));
            citizenships.join(new JoinCitizenship(
                    new ServiceIdentity("civiceconomy"),
                    "restart-citizen-join",
                    citizenId,
                    nationId));
            teams.put(new NationTeam(teamId, headId, Set.of(headId)));
            new CitizenshipReconciler(
                            database,
                            teams,
                            Duration.ofDays(2),
                            Duration.ofDays(7),
                            Clock.fixed(MISSING_AT, ZoneOffset.UTC))
                    .reconcile(nationId);
            CitizenshipCorrectionGraceRegistry corrections =
                    new CitizenshipCorrectionGraceRegistry(
                            database, Clock.fixed(MISSING_AT, ZoneOffset.UTC));
            grace = corrections.activeForPlayer(citizenId).orElseThrow();
            new CitizenshipRegistry(
                            database,
                            Duration.ofDays(7),
                            Clock.fixed(grace.deadline(), ZoneOffset.UTC))
                    .leave(new LeaveCitizenship(
                            new ServiceIdentity("civiceconomy-citizenship-reconciliation"),
                            "correction-grace-end:" + grace.graceId(),
                            citizenId,
                            nationId));
            assertTrue(corrections.activeForPlayer(citizenId).isPresent());
        }

        try (CivicDatabase reopened = database()) {
            CitizenshipReconciliationResult recovered = new CitizenshipReconciler(
                            reopened,
                            teams,
                            Duration.ofDays(2),
                            Duration.ofDays(7),
                            Clock.fixed(grace.deadline().plus(Duration.ofHours(1)), ZoneOffset.UTC))
                    .reconcile(nationId);

            assertEquals(1, recovered.endedCitizenshipCount());
            CitizenshipCorrectionGrace resolved =
                    new CitizenshipCorrectionGraceRegistry(
                                    reopened,
                                    Clock.fixed(grace.deadline(), ZoneOffset.UTC))
                            .history(citizenId)
                            .getLast();
            assertEquals(
                    Optional.of(CitizenshipCorrectionResolution.CITIZENSHIP_ENDED),
                    resolved.resolution());
            assertEquals(Optional.of(grace.deadline()), resolved.resolvedAt());
        }
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("citizenship-reconciliation.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("e576d11e-812b-4c15-a9e5-d2f1da27f0ab"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }

    private static final class MutableTeams implements NationTeamDirectory {
        private final Map<UUID, NationTeam> teams = new HashMap<>();

        MutableTeams put(NationTeam team) {
            teams.put(team.teamId(), team);
            return this;
        }

        @Override
        public Optional<NationTeam> find(UUID teamId) {
            return Optional.ofNullable(teams.get(teamId));
        }

        @Override
        public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
            return teams.values().stream()
                    .filter(team -> team.citizens().contains(playerId))
                    .findFirst();
        }
    }
}
