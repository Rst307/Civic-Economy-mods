package org.civiceconomy.nation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NationApplicationExpiryProcessorTest {
    private static final Instant APPLIED_AT = Instant.parse("2026-07-14T08:00:00Z");
    private static final Instant NOW = APPLIED_AT.plus(Duration.ofDays(7));

    @TempDir
    Path temporaryDirectory;

    @Test
    void expiresOnlyDueApplicationsWithDeterministicReplayIdentity() {
        UUID dueTeam = UUID.fromString("7cd26a18-c1f4-4a2f-a7b9-f3f78b0c3d69");
        UUID futureTeam = UUID.fromString("e1cc65dd-d702-4aad-a23a-dc22448d5f38");
        UUID dueHead = UUID.fromString("fbc00a1d-d316-4799-a1bb-a1759ed4109c");
        UUID futureHead = UUID.fromString("3b0e80d4-f28a-4145-9c82-6d88ed04cc5d");
        NationTeamDirectory teams = teams(Map.of(
                dueTeam, new NationTeam(dueTeam, dueHead, Set.of(dueHead)),
                futureTeam, new NationTeam(futureTeam, futureHead, Set.of(futureHead))));

        try (CivicDatabase database = database()) {
            NationApplicationRegistry registry = new NationApplicationRegistry(
                    database, teams, Clock.fixed(APPLIED_AT, ZoneOffset.UTC));
            NationApplication due = registry.create(new CreateNationApplication(
                    new ServiceIdentity("civiceconomy-founding"),
                    "apply-due",
                    dueTeam,
                    dueHead,
                    NOW));
            NationApplication future = registry.create(new CreateNationApplication(
                    new ServiceIdentity("civiceconomy-founding"),
                    "apply-future",
                    futureTeam,
                    futureHead,
                    NOW.plus(Duration.ofDays(1))));
            NationApplicationExpiryProcessor processor = new NationApplicationExpiryProcessor(
                    database,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    Duration.ofDays(60));

            assertEquals(Set.of(due.applicationId()), processor.expireDue().stream()
                    .map(NationApplication::applicationId)
                    .collect(java.util.stream.Collectors.toSet()));
            assertEquals(NationApplicationState.EXPIRED,
                    registry.find(due.applicationId()).orElseThrow().state());
            assertEquals(NationApplicationState.PENDING,
                    registry.find(future.applicationId()).orElseThrow().state());
            assertFalse(registry.findPendingByFtbTeam(dueTeam).isPresent());
            assertEquals(0, processor.expireDue().size());
            assertNotNull(database.nationApplicationTransitionRegistration(
                    NationApplicationExpiryProcessor.SERVICE.value(),
                    NationApplicationExpiryProcessor.requestId(due.applicationId())));
        }
    }

    private static NationTeamDirectory teams(Map<UUID, NationTeam> teams) {
        return new NationTeamDirectory() {
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
        };
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("nation-application-expiry.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("79280e9a-1d5f-41ed-818e-6a4488f93b36"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
