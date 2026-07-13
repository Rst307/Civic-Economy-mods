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
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CitizenshipRegistryTest {
    private static final Clock JOIN_TIME =
            Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void citizenshipJoinReplayKeepsOneActivePeriodAcrossRestart() {
        UUID playerId = UUID.fromString("b8282472-2df9-44f0-9145-d0f88e6cccfb");
        NationId nationId;
        Citizenship joined;

        try (CivicDatabase database = database()) {
            nationId = registerNation(database, "join-team").nationId();
            CitizenshipRegistry registry = new CitizenshipRegistry(database, Duration.ofDays(7), JOIN_TIME);
            JoinCitizenship request = new JoinCitizenship(
                    new ServiceIdentity("civiceconomy"), "join-citizen", playerId, nationId);

            joined = registry.join(request);

            assertEquals(joined, registry.join(request));
            assertEquals(joined, registry.current(playerId).orElseThrow());
            assertEquals(Instant.parse("2026-07-14T00:00:00Z").toEpochMilli(), joined.joinedAtEpochMillis());
        }

        try (CivicDatabase reopened = database()) {
            CitizenshipRegistry registry = new CitizenshipRegistry(reopened, Duration.ofDays(7), JOIN_TIME);

            assertEquals(joined, registry.current(playerId).orElseThrow());
            assertEquals(joined, registry.history(playerId).getFirst());
        }
    }

    @Test
    void citizenshipJoinRequestCannotBeReusedForAnotherNation() {
        UUID playerId = UUID.fromString("ed79b55a-bd48-4f45-a89f-6914b60b3aa2");

        try (CivicDatabase database = database()) {
            NationId firstNationId = registerNation(database, "first-team").nationId();
            NationId secondNationId = registerNation(database, "second-team").nationId();
            CitizenshipRegistry registry = new CitizenshipRegistry(database, Duration.ofDays(7), JOIN_TIME);
            ServiceIdentity identity = new ServiceIdentity("civiceconomy");
            Citizenship joined = registry.join(
                    new JoinCitizenship(identity, "join-conflict", playerId, firstNationId));

            assertThrows(
                    IdempotencyConflictException.class,
                    () -> registry.join(new JoinCitizenship(
                            identity, "join-conflict", playerId, secondNationId)));
            assertEquals(joined, registry.current(playerId).orElseThrow());
        }
    }

    @Test
    void playerCannotHoldActiveCitizenshipInTwoNations() {
        UUID playerId = UUID.fromString("27c4b9f9-c7fd-43cf-9a63-ee71a3ac56f4");

        try (CivicDatabase database = database()) {
            NationId firstNationId = registerNation(database, "active-first-team").nationId();
            NationId secondNationId = registerNation(database, "active-second-team").nationId();
            CitizenshipRegistry registry = new CitizenshipRegistry(database, Duration.ofDays(7), JOIN_TIME);
            ServiceIdentity identity = new ServiceIdentity("civiceconomy");
            Citizenship joined = registry.join(
                    new JoinCitizenship(identity, "join-active-first", playerId, firstNationId));

            assertThrows(
                    ActiveCitizenshipException.class,
                    () -> registry.join(new JoinCitizenship(
                            identity, "join-active-second", playerId, secondNationId)));
            assertEquals(joined, registry.current(playerId).orElseThrow());
        }
    }

    @Test
    void playerCannotJoinUnknownNation() {
        UUID playerId = UUID.fromString("e426f85e-e946-48ed-a0d8-08ec8f4af4d2");
        NationId unknownNationId = new NationId(
                UUID.fromString("3a78a773-80f4-493c-9f30-b41bced29de2"));

        try (CivicDatabase database = database()) {
            CitizenshipRegistry registry = new CitizenshipRegistry(database, Duration.ofDays(7), JOIN_TIME);

            assertThrows(
                    UnknownNationException.class,
                    () -> registry.join(new JoinCitizenship(
                            new ServiceIdentity("civiceconomy"),
                            "join-unknown-nation",
                            playerId,
                            unknownNationId)));
            assertEquals(Optional.empty(), registry.current(playerId));
        }
    }

    @Test
    void citizenshipLeaveReplayClosesThePeriodAcrossRestart() {
        UUID playerId = UUID.fromString("032054a1-3f7b-4797-8978-f6baf449aefa");
        Clock leaveTime = Clock.offset(JOIN_TIME, Duration.ofDays(1));
        Citizenship ended;

        try (CivicDatabase database = database()) {
            NationId nationId = registerNation(database, "leave-team").nationId();
            CitizenshipRegistry joining = new CitizenshipRegistry(database, Duration.ofDays(7), JOIN_TIME);
            joining.join(new JoinCitizenship(
                    new ServiceIdentity("civiceconomy"), "join-before-leave", playerId, nationId));
            CitizenshipRegistry leaving = new CitizenshipRegistry(database, Duration.ofDays(7), leaveTime);
            LeaveCitizenship request = new LeaveCitizenship(
                    new ServiceIdentity("civiceconomy"), "leave-citizen", playerId, nationId);

            ended = leaving.leave(request);

            assertEquals(ended, leaving.leave(request));
            assertFalse(ended.active());
            assertEquals(Instant.parse("2026-07-15T00:00:00Z").toEpochMilli(), ended.endedAtEpochMillis().orElseThrow());
            assertEquals(Optional.empty(), leaving.current(playerId));
            assertEquals(ended, leaving.history(playerId).getFirst());
        }

        try (CivicDatabase reopened = database()) {
            CitizenshipRegistry registry = new CitizenshipRegistry(reopened, Duration.ofDays(7), leaveTime);

            assertEquals(Optional.empty(), registry.current(playerId));
            assertEquals(ended, registry.history(playerId).getFirst());
        }
    }

    @Test
    void transferCooldownBlocksJoiningAnotherNationTooSoon() {
        UUID playerId = UUID.fromString("0d718f97-e528-4a90-8352-b1f04788d11f");
        Duration cooldown = Duration.ofDays(7);

        try (CivicDatabase database = database()) {
            NationId firstNationId = registerNation(database, "cooldown-first-team").nationId();
            NationId secondNationId = registerNation(database, "cooldown-second-team").nationId();
            CitizenshipRegistry joining = new CitizenshipRegistry(database, cooldown, JOIN_TIME);
            joining.join(new JoinCitizenship(
                    new ServiceIdentity("civiceconomy"), "join-before-cooldown", playerId, firstNationId));
            CitizenshipRegistry leaving = new CitizenshipRegistry(
                    database, cooldown, Clock.offset(JOIN_TIME, Duration.ofDays(1)));
            leaving.leave(new LeaveCitizenship(
                    new ServiceIdentity("civiceconomy"), "leave-before-cooldown", playerId, firstNationId));
            CitizenshipRegistry tooSoon = new CitizenshipRegistry(
                    database, cooldown, Clock.offset(JOIN_TIME, Duration.ofDays(7)));

            assertThrows(
                    CitizenshipTransferCooldownException.class,
                    () -> tooSoon.join(new JoinCitizenship(
                            new ServiceIdentity("civiceconomy"),
                            "join-during-cooldown",
                            playerId,
                            secondNationId)));
            assertEquals(Optional.empty(), tooSoon.current(playerId));
            assertEquals(1, tooSoon.history(playerId).size());
        }
    }

    @Test
    void playerCanJoinAnotherNationWhenTransferCooldownExpires() {
        UUID playerId = UUID.fromString("555a8e9b-d8ce-401e-85a7-c1799afb753b");
        Duration cooldown = Duration.ofDays(7);

        try (CivicDatabase database = database()) {
            NationId firstNationId = registerNation(database, "expired-first-team").nationId();
            NationId secondNationId = registerNation(database, "expired-second-team").nationId();
            CitizenshipRegistry joining = new CitizenshipRegistry(database, cooldown, JOIN_TIME);
            Citizenship first = joining.join(new JoinCitizenship(
                    new ServiceIdentity("civiceconomy"), "join-before-expiry", playerId, firstNationId));
            CitizenshipRegistry leaving = new CitizenshipRegistry(
                    database, cooldown, Clock.offset(JOIN_TIME, Duration.ofDays(1)));
            Citizenship ended = leaving.leave(new LeaveCitizenship(
                    new ServiceIdentity("civiceconomy"), "leave-before-expiry", playerId, firstNationId));
            CitizenshipRegistry expired = new CitizenshipRegistry(
                    database, cooldown, Clock.offset(JOIN_TIME, Duration.ofDays(8)));

            Citizenship second = expired.join(new JoinCitizenship(
                    new ServiceIdentity("civiceconomy"), "join-after-expiry", playerId, secondNationId));

            assertEquals(second, expired.current(playerId).orElseThrow());
            assertEquals(secondNationId, second.nationId());
            assertEquals(Instant.parse("2026-07-22T00:00:00Z").toEpochMilli(), second.joinedAtEpochMillis());
            assertEquals(java.util.List.of(ended, second), expired.history(playerId));
            assertEquals(first.citizenshipId(), ended.citizenshipId());
        }
    }

    @Test
    void staleLeaveRequestCannotEndCitizenshipInAnotherNation() {
        UUID playerId = UUID.fromString("e999a522-800e-46dc-aeea-31202cbd45e7");

        try (CivicDatabase database = database()) {
            NationId actualNationId = registerNation(database, "leave-actual-team").nationId();
            NationId staleNationId = registerNation(database, "leave-stale-team").nationId();
            CitizenshipRegistry registry = new CitizenshipRegistry(database, Duration.ofDays(7), JOIN_TIME);
            Citizenship joined = registry.join(new JoinCitizenship(
                    new ServiceIdentity("civiceconomy"), "join-before-stale-leave", playerId, actualNationId));

            assertThrows(
                    CitizenshipNationMismatchException.class,
                    () -> registry.leave(new LeaveCitizenship(
                            new ServiceIdentity("civiceconomy"),
                            "stale-leave",
                            playerId,
                            staleNationId)));
            assertEquals(joined, registry.current(playerId).orElseThrow());
        }
    }

    private RegisteredNation registerNation(CivicDatabase database, String requestId) {
        UUID teamId = UUID.nameUUIDFromBytes(requestId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        NationTeamDirectory teams = new NationTeamDirectory() {
            @Override
            public Optional<NationTeam> find(UUID requestedTeamId) {
                return requestedTeamId.equals(teamId)
                        ? Optional.of(new NationTeam(teamId, teamId, Set.of(teamId)))
                        : Optional.empty();
            }

            @Override
            public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
                return Optional.empty();
            }
        };
        return new NationRegistry(database, teams).register(
                new RegisterNation(new ServiceIdentity("civiceconomy"), requestId, teamId));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("citizenship.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("17e14af5-19b1-4a34-8d1d-67ea060c1265"),
                        "0.1.0",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
