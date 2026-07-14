package org.civiceconomy.nation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EffectiveCitizenCalculatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void onlineTimeCrossingATransferIsProratedByActualCitizenship() {
        UUID playerId = UUID.fromString("a860d67a-7007-4f16-a095-d3bf0b7871b5");
        Instant firstJoin = Instant.parse("2026-07-01T00:00:00Z");
        Instant transfer = Instant.parse("2026-07-03T00:00:00Z");
        Instant asOf = Instant.parse("2026-07-04T00:00:00Z");

        try (CivicDatabase database = database()) {
            NationId firstNationId = registerNation(database, "effective-first").nationId();
            NationId secondNationId = registerNation(database, "effective-second").nationId();
            CitizenshipRegistry firstCitizenship = new CitizenshipRegistry(
                    database, Duration.ZERO, Clock.fixed(firstJoin, ZoneOffset.UTC));
            firstCitizenship.join(new JoinCitizenship(
                    new ServiceIdentity("civiceconomy"), "effective-join-first", playerId, firstNationId));
            CitizenshipRegistry transferCitizenship = new CitizenshipRegistry(
                    database, Duration.ZERO, Clock.fixed(transfer, ZoneOffset.UTC));
            transferCitizenship.leave(new LeaveCitizenship(
                    new ServiceIdentity("civiceconomy"), "effective-leave-first", playerId, firstNationId));
            transferCitizenship.join(new JoinCitizenship(
                    new ServiceIdentity("civiceconomy"), "effective-join-second", playerId, secondNationId));
            OnlineTimeLedger onlineTime = new OnlineTimeLedger(database);
            onlineTime.record(new RecordOnlineTime(
                    new ServiceIdentity("civiceconomy-server"),
                    "effective-cross-transfer",
                    playerId,
                    Instant.parse("2026-07-02T22:00:00Z").toEpochMilli(),
                    Instant.parse("2026-07-03T02:00:00Z").toEpochMilli()));
            EffectiveCitizenCalculator calculator = new EffectiveCitizenCalculator(
                    transferCitizenship,
                    new CitizenshipCorrectionGraceRegistry(database, Clock.fixed(asOf, ZoneOffset.UTC)),
                    onlineTime,
                    Duration.ofDays(60),
                    Duration.ofHours(8));

            EffectiveCitizenContribution first = calculator.contribution(playerId, firstNationId, asOf);
            EffectiveCitizenContribution second = calculator.contribution(playerId, secondNationId, asOf);

            assertEquals(Duration.ofHours(2).toMillis(), first.attributedOnlineMillis());
            assertEquals(0.25D, first.contribution(), 0.0000001D);
            assertEquals(Duration.ofHours(2).toMillis(), second.attributedOnlineMillis());
            assertEquals(0.25D, second.contribution(), 0.0000001D);
        }
    }

    @Test
    void contributionExcludesTimeOutsideWindowAndCapsAtOne() {
        UUID playerId = UUID.fromString("9c2dd1dd-6962-44d0-8f41-d59253902a46");
        Instant asOf = Instant.parse("2026-07-14T00:00:00Z");

        try (CivicDatabase database = database()) {
            NationId nationId = registerNation(database, "effective-window").nationId();
            CitizenshipRegistry citizenships = new CitizenshipRegistry(
                    database,
                    Duration.ZERO,
                    Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC));
            citizenships.join(new JoinCitizenship(
                    new ServiceIdentity("civiceconomy"), "effective-window-join", playerId, nationId));
            OnlineTimeLedger onlineTime = new OnlineTimeLedger(database);
            onlineTime.record(new RecordOnlineTime(
                    new ServiceIdentity("civiceconomy-server"),
                    "effective-window-old",
                    playerId,
                    Instant.parse("2026-05-01T00:00:00Z").toEpochMilli(),
                    Instant.parse("2026-05-01T05:00:00Z").toEpochMilli()));
            onlineTime.record(new RecordOnlineTime(
                    new ServiceIdentity("civiceconomy-server"),
                    "effective-window-recent",
                    playerId,
                    Instant.parse("2026-07-10T00:00:00Z").toEpochMilli(),
                    Instant.parse("2026-07-10T10:00:00Z").toEpochMilli()));
            EffectiveCitizenCalculator calculator = new EffectiveCitizenCalculator(
                    citizenships,
                    new CitizenshipCorrectionGraceRegistry(database, Clock.fixed(asOf, ZoneOffset.UTC)),
                    onlineTime,
                    Duration.ofDays(60),
                    Duration.ofHours(8));

            EffectiveCitizenContribution contribution = calculator.contribution(playerId, nationId, asOf);

            assertEquals(Duration.ofHours(10).toMillis(), contribution.attributedOnlineMillis());
            assertEquals(1D, contribution.contribution(), 0.0000001D);
        }
    }

    @Test
    void correctionGraceImmediatelySuspendsPopulationContribution() {
        UUID playerId = UUID.fromString("0754f914-13ae-43c4-a667-334e8b8e6ed1");
        UUID teamId = UUID.fromString("cf059f2f-6fb2-446c-a465-eeb5095f571c");
        Instant joinedAt = Instant.parse("2026-07-01T00:00:00Z");
        Instant missingAt = Instant.parse("2026-07-14T08:00:00Z");
        Instant asOf = missingAt.plus(Duration.ofHours(2));

        try (CivicDatabase database = database()) {
            NationId nationId = registerNation(database, "effective-correction-grace").nationId();
            CitizenshipRegistry citizenships = new CitizenshipRegistry(
                    database, Duration.ZERO, Clock.fixed(joinedAt, ZoneOffset.UTC));
            Citizenship citizenship = citizenships.join(new JoinCitizenship(
                    new ServiceIdentity("civiceconomy"),
                    "effective-correction-join",
                    playerId,
                    nationId));
            CitizenshipCorrectionGraceRegistry corrections =
                    new CitizenshipCorrectionGraceRegistry(
                            database, Clock.fixed(missingAt, ZoneOffset.UTC));
            corrections.start(new StartCitizenshipCorrectionGrace(
                    new ServiceIdentity("civiceconomy-citizenship-reconciliation"),
                    "effective-correction-start",
                    citizenship.citizenshipId(),
                    playerId,
                    nationId,
                    teamId,
                    missingAt.plus(Duration.ofDays(2)),
                    "Player left the bound team"));
            OnlineTimeLedger onlineTime = new OnlineTimeLedger(database);
            onlineTime.record(new RecordOnlineTime(
                    new ServiceIdentity("civiceconomy-server"),
                    "effective-correction-interval",
                    playerId,
                    missingAt.minus(Duration.ofHours(1)).toEpochMilli(),
                    missingAt.plus(Duration.ofHours(2)).toEpochMilli()));
            EffectiveCitizenCalculator calculator = new EffectiveCitizenCalculator(
                    citizenships,
                    corrections,
                    onlineTime,
                    Duration.ofDays(60),
                    Duration.ofHours(8));

            EffectiveCitizenContribution contribution =
                    calculator.contribution(playerId, nationId, asOf);

            assertEquals(Duration.ofHours(1).toMillis(), contribution.attributedOnlineMillis());
            assertEquals(0.125D, contribution.contribution(), 0.0000001D);
        }
    }

    private RegisteredNation registerNation(CivicDatabase database, String requestId) {
        UUID teamId = UUID.nameUUIDFromBytes(requestId.getBytes(StandardCharsets.UTF_8));
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
                temporaryDirectory.resolve("effective-citizen.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("25d8a637-8f87-4ebd-82c4-1da82d122390"),
                        "0.1.0",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
