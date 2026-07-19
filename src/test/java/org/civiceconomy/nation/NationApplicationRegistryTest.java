package org.civiceconomy.nation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NationApplicationRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-14T08:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void teamHeadCreatesPersistentPendingApplicationWithoutCreatingNation() {
        UUID teamId = UUID.fromString("618c89ad-781f-475c-9d23-253b3ab777c3");
        UUID headId = UUID.fromString("fc06f15b-e2f4-4011-a9f9-7d85038b15c2");
        UUID memberId = UUID.fromString("97403357-784b-4b08-b731-bfbb1c2a6676");
        CreateNationApplication request = new CreateNationApplication(
                new ServiceIdentity("civiceconomy-founding"),
                "apply-aurora",
                teamId,
                headId,
                NOW.plusSeconds(86_400));
        NationApplication created;

        try (CivicDatabase database = database()) {
            NationApplicationRegistry registry = registry(database, teamId, headId, memberId);
            created = registry.create(request);

            assertEquals(created, registry.create(request));
            assertEquals(NationApplicationState.PENDING, created.state());
            assertEquals(teamId, created.ftbTeamId());
            assertEquals(
                    Set.of(headId, memberId),
                    registry.candidates(created.applicationId()).stream()
                            .map(NationApplicationCandidate::playerId)
                            .collect(java.util.stream.Collectors.toSet()));
            CitizenshipRegistry citizenships = new CitizenshipRegistry(
                    database,
                    java.time.Duration.ZERO,
                    Clock.fixed(NOW, ZoneOffset.UTC));
            assertFalse(citizenships.current(headId).isPresent());
            assertFalse(citizenships.current(memberId).isPresent());
            assertFalse(new NationRegistry(database, teamDirectory(teamId, headId))
                    .findByFtbTeam(teamId)
                    .isPresent());
        }

        try (CivicDatabase reopened = database()) {
            NationApplicationRegistry registry = registry(reopened, teamId, headId, memberId);

            assertEquals(created, registry.find(created.applicationId()).orElseThrow());
            assertEquals(created, registry.findPendingByFtbTeam(teamId).orElseThrow());
            assertEquals(2, registry.candidates(created.applicationId()).size());
        }
    }

    @Test
    void nonHeadAndSecondPendingApplicationFailClosed() {
        UUID teamId = UUID.fromString("aa75bac6-5762-44f9-b7ba-4dfd45bf7bce");
        UUID headId = UUID.fromString("9a3483d2-d86f-42ae-810e-fb40eb1d14c8");
        UUID memberId = UUID.fromString("aed624dd-0195-45b1-9676-384f9b104155");

        try (CivicDatabase database = database()) {
            NationApplicationRegistry registry = registry(database, teamId, headId, memberId);

            assertThrows(
                    NationApplicationHeadRequiredException.class,
                    () -> registry.create(new CreateNationApplication(
                            new ServiceIdentity("civiceconomy-founding"),
                            "apply-not-head",
                            teamId,
                            memberId,
                            NOW.plusSeconds(86_400))));
            NationApplication first = registry.create(new CreateNationApplication(
                    new ServiceIdentity("civiceconomy-founding"),
                    "apply-first",
                    teamId,
                    headId,
                    NOW.plusSeconds(86_400)));
            NationApplicationAlreadyPendingException conflict = assertThrows(
                    NationApplicationAlreadyPendingException.class,
                    () -> registry.create(new CreateNationApplication(
                            new ServiceIdentity("civiceconomy-founding"),
                            "apply-second",
                            teamId,
                            headId,
                            NOW.plusSeconds(172_800))));
            assertEquals(first.applicationId(), conflict.applicationId());
        }
    }

    @Test
    void candidateEvidenceClaimsOnlyEligibleOnlineIntervalsExactlyOnce() {
        UUID teamId = UUID.fromString("28fa31d1-65d8-49bd-aa83-aaf49743a3b9");
        UUID headId = UUID.fromString("27412a39-9a48-4bcb-8185-259caf213a04");
        UUID memberId = UUID.fromString("160ae9ac-0eaf-46a7-b5ef-adce84618d4a");
        UUID outsiderId = UUID.fromString("88b0a9e1-dbe8-4bf8-b098-e95aac81e3df");

        try (CivicDatabase database = database()) {
            NationApplication application = registry(database, teamId, headId, memberId).create(
                    new CreateNationApplication(
                            new ServiceIdentity("civiceconomy-founding"),
                            "apply-evidence",
                            teamId,
                            headId,
                            NOW.plus(Duration.ofDays(7))));
            OnlineTimeLedger onlineTime = new OnlineTimeLedger(database);
            onlineTime.record(new RecordOnlineTime(
                    new ServiceIdentity("civiceconomy-server"),
                    "head-before-application",
                    headId,
                    NOW.minus(Duration.ofHours(2)).toEpochMilli(),
                    NOW.minus(Duration.ofHours(1)).toEpochMilli()));
            onlineTime.record(new RecordOnlineTime(
                    new ServiceIdentity("civiceconomy-server"),
                    "head-overlapping-application",
                    headId,
                    NOW.minus(Duration.ofHours(1)).toEpochMilli(),
                    NOW.plus(Duration.ofHours(2)).toEpochMilli()));
            onlineTime.record(new RecordOnlineTime(
                    new ServiceIdentity("civiceconomy-server"),
                    "member-during-application",
                    memberId,
                    NOW.plus(Duration.ofMinutes(30)).toEpochMilli(),
                    NOW.plus(Duration.ofMinutes(90)).toEpochMilli()));
            onlineTime.record(new RecordOnlineTime(
                    new ServiceIdentity("civiceconomy-server"),
                    "outsider-during-application",
                    outsiderId,
                    NOW.toEpochMilli(),
                    NOW.plus(Duration.ofHours(4)).toEpochMilli()));
            NationApplicationRegistry later = new NationApplicationRegistry(
                    database,
                    teamDirectory(teamId, headId, memberId),
                    Clock.fixed(NOW.plus(Duration.ofHours(3)), ZoneOffset.UTC));

            assertEquals(
                    Set.of(
                            new NationApplicationEvidence(
                                    application.applicationId(), headId, Duration.ofHours(2).toMillis()),
                            new NationApplicationEvidence(
                                    application.applicationId(), memberId, Duration.ofHours(1).toMillis())),
                    Set.copyOf(later.claimCandidateEvidence(
                            application.applicationId(), Duration.ofDays(60))));
            assertEquals(
                    Set.copyOf(later.candidateEvidence(application.applicationId())),
                    Set.copyOf(later.claimCandidateEvidence(
                            application.applicationId(), Duration.ofDays(60))));
        }
    }

    @Test
    void cancellationReleasesCandidateAffiliationWithoutReusingClaimedEvidence() {
        UUID teamId = UUID.fromString("2dff56a5-4058-4a9d-adca-1b86b9f94f72");
        UUID headId = UUID.fromString("7c94fa71-75f8-4cf7-bbfd-45c7cb08973e");
        UUID memberId = UUID.fromString("9e902167-5405-4133-a1de-bf602cb0ae14");

        try (CivicDatabase database = database()) {
            NationApplicationRegistry atCreation = registry(database, teamId, headId, memberId);
            NationApplication first = atCreation.create(new CreateNationApplication(
                    new ServiceIdentity("civiceconomy-founding"),
                    "apply-first-cancelled",
                    teamId,
                    headId,
                    NOW.plus(Duration.ofDays(7))));
            new OnlineTimeLedger(database).record(new RecordOnlineTime(
                    new ServiceIdentity("civiceconomy-server"),
                    "first-application-member-time",
                    memberId,
                    NOW.toEpochMilli(),
                    NOW.plus(Duration.ofHours(2)).toEpochMilli()));
            NationApplicationRegistry afterEvidence = new NationApplicationRegistry(
                    database,
                    teamDirectory(teamId, headId, memberId),
                    Clock.fixed(NOW.plus(Duration.ofHours(3)), ZoneOffset.UTC));

            NationApplication cancelled = afterEvidence.cancel(new CancelNationApplication(
                    new ServiceIdentity("civiceconomy-founding"),
                    "cancel-first-application",
                    first.applicationId(),
                    headId,
                    Duration.ofDays(60),
                    "Team withdrew its founding application"));
            assertEquals(NationApplicationState.CANCELLED, cancelled.state());
            assertEquals(cancelled, afterEvidence.cancel(new CancelNationApplication(
                    new ServiceIdentity("civiceconomy-founding"),
                    "cancel-first-application",
                    first.applicationId(),
                    headId,
                    Duration.ofDays(60),
                    "Team withdrew its founding application")));
            assertEquals(
                    Set.of(memberId),
                    afterEvidence.candidateEvidence(first.applicationId()).stream()
                            .map(NationApplicationEvidence::playerId)
                            .collect(java.util.stream.Collectors.toSet()));
            assertEquals(0, afterEvidence.candidates(first.applicationId()).stream()
                    .filter(candidate -> candidate.endedAt().isEmpty())
                    .count());

            NationApplication second = afterEvidence.create(new CreateNationApplication(
                    new ServiceIdentity("civiceconomy-founding"),
                    "apply-after-cancellation",
                    teamId,
                    headId,
                    NOW.plus(Duration.ofDays(8))));
            assertEquals(
                    Set.of(),
                    Set.copyOf(afterEvidence.claimCandidateEvidence(
                            second.applicationId(), Duration.ofDays(60))));
        }
    }

    @Test
    void expiryClaimsOnlyUntilDeadlineAndReleasesCandidates() {
        UUID teamId = UUID.fromString("24496c91-c3c1-4d3d-a0f7-569f76516577");
        UUID headId = UUID.fromString("e3122ea8-ee51-4b5d-908b-a8ae851ef81f");
        UUID memberId = UUID.fromString("e5471ec1-54ac-4bef-a1b8-b1cfe84a2791");

        try (CivicDatabase database = database()) {
            NationApplication application = registry(database, teamId, headId, memberId).create(
                    new CreateNationApplication(
                            new ServiceIdentity("civiceconomy-founding"),
                            "apply-to-expire",
                            teamId,
                            headId,
                            NOW.plus(Duration.ofHours(2))));
            new OnlineTimeLedger(database).record(new RecordOnlineTime(
                    new ServiceIdentity("civiceconomy-server"),
                    "member-crosses-expiry",
                    memberId,
                    NOW.toEpochMilli(),
                    NOW.plus(Duration.ofHours(4)).toEpochMilli()));
            NationApplicationRegistry beforeExpiry = new NationApplicationRegistry(
                    database,
                    teamDirectory(teamId, headId, memberId),
                    Clock.fixed(NOW.plus(Duration.ofHours(1)), ZoneOffset.UTC));
            assertThrows(
                    IllegalStateException.class,
                    () -> beforeExpiry.expire(new ExpireNationApplication(
                            new ServiceIdentity("civiceconomy-founding"),
                            "expire-before-deadline",
                            application.applicationId(),
                            Duration.ofDays(60),
                            "Founding application expired")));

            NationApplicationRegistry afterExpiry = new NationApplicationRegistry(
                    database,
                    teamDirectory(teamId, headId, memberId),
                    Clock.fixed(NOW.plus(Duration.ofHours(3)), ZoneOffset.UTC));
            NationApplication expired = afterExpiry.expire(new ExpireNationApplication(
                    new ServiceIdentity("civiceconomy-founding"),
                    "expire-after-deadline",
                    application.applicationId(),
                    Duration.ofDays(60),
                    "Founding application expired"));
            assertEquals(NationApplicationState.EXPIRED, expired.state());
            assertEquals(
                    Set.of(new NationApplicationEvidence(
                            application.applicationId(),
                            memberId,
                            Duration.ofHours(2).toMillis())),
                    Set.copyOf(afterExpiry.candidateEvidence(application.applicationId())));
            assertEquals(
                    Set.of(application.expiresAt()),
                    afterExpiry.candidates(application.applicationId()).stream()
                            .map(candidate -> candidate.endedAt().orElseThrow())
                            .collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test
    void formalActivationCreatesNationCitizenshipsCapitalAndTreasuryExactlyOnce() {
        UUID teamId = UUID.fromString("32081220-b75e-490d-a42e-0e11434908a5");
        UUID headId = UUID.fromString("d89ac2c8-f8d3-4794-92dc-8e07895fbca8");
        UUID memberId = UUID.fromString("530277d9-523a-443c-bc9f-14e53ff83243");
        Capital capital = new Capital("minecraft:overworld", 12, -7);

        try (CivicDatabase database = database()) {
            NationApplication application = registry(database, teamId, headId, memberId).create(
                    new CreateNationApplication(
                            new ServiceIdentity("civiceconomy-founding"),
                            "apply-for-activation",
                            teamId,
                            headId,
                            NOW.plus(Duration.ofDays(7))));
            OnlineTimeLedger onlineTime = new OnlineTimeLedger(database);
            onlineTime.record(new RecordOnlineTime(
                    new ServiceIdentity("civiceconomy-server"),
                    "activation-head-time",
                    headId,
                    NOW.toEpochMilli(),
                    NOW.plus(Duration.ofHours(1)).toEpochMilli()));
            onlineTime.record(new RecordOnlineTime(
                    new ServiceIdentity("civiceconomy-server"),
                    "activation-member-time",
                    memberId,
                    NOW.toEpochMilli(),
                    NOW.plus(Duration.ofHours(1)).toEpochMilli()));
            Set<AccountId> provisionedTreasuries = new java.util.HashSet<>();
            AtomicInteger provisionCalls = new AtomicInteger();
            NationalTreasuryProvisioner provisioner = (nationId, treasuryAccountId) -> {
                provisionCalls.incrementAndGet();
                provisionedTreasuries.add(treasuryAccountId);
            };
            NationActivationCoordinator coordinator = new NationActivationCoordinator(
                    database,
                    provisioner,
                    NationFoundingPolicy.formal(
                            2, Duration.ofDays(60), Duration.ZERO),
                    Clock.fixed(NOW.plus(Duration.ofHours(2)), ZoneOffset.UTC));
            ActivateNationApplication request = new ActivateNationApplication(
                    new ServiceIdentity("civiceconomy-founding"),
                    "activate-application",
                    application.applicationId(),
                    capital,
                    "Minimum Effective Candidate threshold satisfied");

            ActivatedNation activated = coordinator.activate(request);

            assertEquals(activated, coordinator.activate(request));
            assertEquals(1, provisionCalls.get());
            assertEquals(Set.of(activated.treasuryAccountId()), provisionedTreasuries);
            assertEquals(capital, activated.capital());
            assertEquals(teamId, activated.nation().ftbTeamId());
            assertEquals(
                    activated.nation().nationId(),
                    new CitizenshipRegistry(database, Duration.ZERO, Clock.fixed(
                            NOW.plus(Duration.ofHours(2)), ZoneOffset.UTC))
                            .current(headId).orElseThrow().nationId());
            assertEquals(
                    activated.nation().nationId(),
                    new CitizenshipRegistry(database, Duration.ZERO, Clock.fixed(
                            NOW.plus(Duration.ofHours(2)), ZoneOffset.UTC))
                            .current(memberId).orElseThrow().nationId());
            assertEquals(
                    NationApplicationState.ACTIVATED,
                    registry(database, teamId, headId, memberId)
                            .find(application.applicationId()).orElseThrow().state());
        }
    }

    @Test
    void onlyTrustedDebugWorldPolicyBypassesMinimumCandidateCount() {
        UUID teamId = UUID.fromString("c5855f2a-bf3d-420d-a761-7a6f348254ed");
        UUID headId = UUID.fromString("99d6547b-3030-4ec3-880a-6869a7e84cb7");

        try (CivicDatabase database = database()) {
            NationApplication application = registry(database, teamId, headId).create(
                    new CreateNationApplication(
                            new ServiceIdentity("civiceconomy-founding"),
                            "apply-single-candidate",
                            teamId,
                            headId,
                            NOW.plus(Duration.ofDays(7))));
            new OnlineTimeLedger(database).record(new RecordOnlineTime(
                    new ServiceIdentity("civiceconomy-server"),
                    "single-candidate-time",
                    headId,
                    NOW.toEpochMilli(),
                    NOW.plus(Duration.ofHours(1)).toEpochMilli()));
            Clock activationClock = Clock.fixed(
                    NOW.plus(Duration.ofHours(2)), ZoneOffset.UTC);
            AtomicInteger provisionCalls = new AtomicInteger();
            NationalTreasuryProvisioner provisioner = (nationId, accountId) ->
                    provisionCalls.incrementAndGet();
            ActivateNationApplication request = new ActivateNationApplication(
                    new ServiceIdentity("civiceconomy-founding"),
                    "activate-single-candidate",
                    application.applicationId(),
                    new Capital("minecraft:overworld", 0, 0),
                    "DEBUG WORLD single-player founding");

            NationActivationCoordinator formal = new NationActivationCoordinator(
                    database,
                    provisioner,
                    NationFoundingPolicy.formal(2, Duration.ofDays(60), Duration.ZERO),
                    activationClock);
            InsufficientEffectiveCandidatesException denied = assertThrows(
                    InsufficientEffectiveCandidatesException.class,
                    () -> formal.activate(request));
            assertEquals(2, denied.required());
            assertEquals(1, denied.actual());
            assertEquals(0, provisionCalls.get());
            assertFalse(new NationRegistry(database, teamDirectory(teamId, headId))
                    .findByFtbTeam(teamId).isPresent());

            NationActivationCoordinator debugWorld = new NationActivationCoordinator(
                    database,
                    provisioner,
                    NationFoundingPolicy.debugWorld(2, Duration.ofDays(60), Duration.ZERO),
                    activationClock);
            ActivatedNation activated = debugWorld.activate(request);
            assertEquals(teamId, activated.nation().ftbTeamId());
            assertEquals(1, provisionCalls.get());
        }
    }

    @Test
    void activationRecoversAfterTreasuryProvisionBeforeCivicRecording() {
        UUID teamId = UUID.fromString("e4ad4f8e-8682-40d3-863b-d15e61fac258");
        UUID headId = UUID.fromString("29144f82-58da-437b-8d23-d70823cadbcf");
        NationApplicationId applicationId;
        Set<AccountId> externalTreasuries = new java.util.HashSet<>();
        AtomicBoolean crashOnce = new AtomicBoolean(true);
        ActivateNationApplication request;

        try (CivicDatabase database = database()) {
            NationApplication application = registry(database, teamId, headId).create(
                    new CreateNationApplication(
                            new ServiceIdentity("civiceconomy-founding"),
                            "apply-crash-recovery",
                            teamId,
                            headId,
                            NOW.plus(Duration.ofDays(7))));
            applicationId = application.applicationId();
            new OnlineTimeLedger(database).record(new RecordOnlineTime(
                    new ServiceIdentity("civiceconomy-server"),
                    "crash-recovery-candidate-time",
                    headId,
                    NOW.toEpochMilli(),
                    NOW.plus(Duration.ofHours(1)).toEpochMilli()));
            request = new ActivateNationApplication(
                    new ServiceIdentity("civiceconomy-founding"),
                    "activate-crash-recovery",
                    applicationId,
                    new Capital("minecraft:overworld", 4, 5),
                    "Recover activation after external Treasury provision");
            NationActivationCoordinator crashing = new NationActivationCoordinator(
                    database,
                    (nationId, accountId) -> {
                        externalTreasuries.add(accountId);
                        if (crashOnce.getAndSet(false)) {
                            throw new SimulatedNationActivationCrash();
                        }
                    },
                    NationFoundingPolicy.debugWorld(2, Duration.ofDays(60), Duration.ZERO),
                    Clock.fixed(NOW.plus(Duration.ofHours(2)), ZoneOffset.UTC));

            assertThrows(SimulatedNationActivationCrash.class, () -> crashing.activate(request));
            assertEquals(1, externalTreasuries.size());
            assertFalse(new NationRegistry(database, teamDirectory(teamId, headId))
                    .findByFtbTeam(teamId).isPresent());
            assertEquals(
                    NationApplicationState.PENDING,
                    registry(database, teamId, headId).find(applicationId).orElseThrow().state());
        }

        try (CivicDatabase reopened = database()) {
            NationActivationCoordinator recovered = new NationActivationCoordinator(
                    reopened,
                    (nationId, accountId) -> externalTreasuries.add(accountId),
                    NationFoundingPolicy.debugWorld(2, Duration.ofDays(60), Duration.ZERO),
                    Clock.fixed(NOW.plus(Duration.ofHours(2)), ZoneOffset.UTC));

            ActivatedNation activated = recovered.activate(request);
            assertEquals(1, externalTreasuries.size());
            assertEquals(
                    activated.nation().nationId(),
                    new CitizenshipRegistry(reopened, Duration.ZERO, Clock.fixed(
                            NOW.plus(Duration.ofHours(2)), ZoneOffset.UTC))
                            .current(headId).orElseThrow().nationId());
            assertEquals(
                    NationApplicationState.ACTIVATED,
                    registry(reopened, teamId, headId).find(applicationId).orElseThrow().state());
        }
    }

    private static final class SimulatedNationActivationCrash extends RuntimeException {}

    private NationApplicationRegistry registry(
            CivicDatabase database, UUID teamId, UUID headId, UUID... members) {
        return new NationApplicationRegistry(
                database,
                teamDirectory(teamId, headId, members),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static NationTeamDirectory teamDirectory(
            UUID teamId, UUID headId, UUID... members) {
        Set<UUID> citizens = new java.util.HashSet<>(Set.of(members));
        citizens.add(headId);
        NationTeam team = new NationTeam(teamId, headId, citizens);
        return new NationTeamDirectory() {
            @Override
            public Optional<NationTeam> find(UUID requestedTeamId) {
                return teamId.equals(requestedTeamId) ? Optional.of(team) : Optional.empty();
            }

            @Override
            public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
                return citizens.contains(playerId) ? Optional.of(team) : Optional.empty();
            }
        };
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("nation-application.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("2d2d3cc5-3fa4-441f-b34a-12300dafbd90"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
