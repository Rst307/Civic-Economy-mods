package org.civiceconomy.nation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NationEffectiveCitizenPopulationTest {
    private static final Instant JOINED_AT = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant AS_OF = Instant.parse("2026-07-14T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void aggregatesFractionalContributionsWithPerCitizenExplanation() {
        UUID fullCitizen = UUID.fromString("9486715f-9ddc-4405-b634-99d42b2738f7");
        UUID halfCitizen = UUID.fromString("08ea4e79-e9c4-4e26-9197-bc4a1d25569f");
        UUID formerQuarterCitizen = UUID.fromString("27db5369-71d2-4375-83cf-3de5fe0e24df");
        UUID inactiveCitizen = UUID.fromString("c41867ce-c8b7-41f7-877b-1c0b81eeeb27");

        try (CivicDatabase database = database()) {
            NationId nationId = registerNation(database, "population-aggregate").nationId();
            CitizenshipRegistry joining = new CitizenshipRegistry(
                    database, Duration.ZERO, Clock.fixed(JOINED_AT, ZoneOffset.UTC));
            join(joining, fullCitizen, nationId, "population-full-join");
            join(joining, halfCitizen, nationId, "population-half-join");
            join(joining, formerQuarterCitizen, nationId, "population-former-join");
            join(joining, inactiveCitizen, nationId, "population-inactive-join");
            new CitizenshipRegistry(
                            database,
                            Duration.ZERO,
                            Clock.fixed(AS_OF.minus(Duration.ofDays(1)), ZoneOffset.UTC))
                    .leave(new LeaveCitizenship(
                            new ServiceIdentity("civiceconomy"),
                            "population-former-leave",
                            formerQuarterCitizen,
                            nationId));
            OnlineTimeLedger onlineTime = new OnlineTimeLedger(database);
            record(onlineTime, fullCitizen, "population-full", Duration.ofHours(8), AS_OF);
            record(onlineTime, halfCitizen, "population-half", Duration.ofHours(4), AS_OF);
            record(
                    onlineTime,
                    formerQuarterCitizen,
                    "population-former",
                    Duration.ofHours(2),
                    AS_OF.minus(Duration.ofDays(1)));
            NationPopulationCalculator calculator = new NationPopulationCalculator(
                    joining,
                    new CitizenshipCorrectionGraceRegistry(
                            database, Clock.fixed(AS_OF, ZoneOffset.UTC)),
                    onlineTime,
                    Duration.ofDays(60),
                    Duration.ofHours(8));

            NationEffectiveCitizenPopulation population = calculator.calculate(nationId, AS_OF);

            assertEquals(4, population.citizens().size());
            assertEquals(3, population.effectiveCitizenCount());
            assertEquals(1.75D, population.populationEquivalent(), 0.0000001D);
            Map<UUID, EffectiveCitizenContribution> details = population.citizens().stream()
                    .collect(Collectors.toMap(
                            EffectiveCitizenContribution::playerId, Function.identity()));
            assertEquals(1D, details.get(fullCitizen).contribution(), 0.0000001D);
            assertEquals(0.5D, details.get(halfCitizen).contribution(), 0.0000001D);
            assertEquals(0.25D, details.get(formerQuarterCitizen).contribution(), 0.0000001D);
            assertEquals(0D, details.get(inactiveCitizen).contribution(), 0.0000001D);
        }
    }

    private static void join(
            CitizenshipRegistry registry, UUID playerId, NationId nationId, String requestId) {
        registry.join(new JoinCitizenship(
                new ServiceIdentity("civiceconomy"), requestId, playerId, nationId));
    }

    private static void record(
            OnlineTimeLedger onlineTime,
            UUID playerId,
            String requestId,
            Duration duration,
            Instant endedAt) {
        onlineTime.record(new RecordOnlineTime(
                new ServiceIdentity("civiceconomy-server"),
                requestId,
                playerId,
                endedAt.minus(duration).toEpochMilli(),
                endedAt.toEpochMilli()));
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
                temporaryDirectory.resolve("nation-effective-population.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("28143724-b923-421d-bf26-5f21c6239b87"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
