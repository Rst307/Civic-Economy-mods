package org.civiceconomy.nation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
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

class FtbTeamsNationProviderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void ordinaryUnregisteredFtbTeamIsNotANation() {
        UUID ownerId = UUID.fromString("37cb29ed-80f1-46a6-abba-49209037eea8");
        UUID teamId = UUID.fromString("9ad458bb-98cd-4a08-8037-29b34c85aab9");
        TestNationTeamDirectory teams = new TestNationTeamDirectory()
                .add(new NationTeam(teamId, ownerId, Set.of(ownerId)));

        try (CivicDatabase database = database()) {
            NationProvider provider = provider(
                    database, new NationRegistry(database, teams), teams);

            assertTrue(provider.findForCitizen(ownerId).isEmpty());
        }
    }

    @Test
    void registeredNationUsesCurrentTeamHeadAndCitizens() {
        UUID originalHeadId = UUID.fromString("abef1f46-16d0-4071-bcd2-1ecb64ed5907");
        UUID currentHeadId = UUID.fromString("e4a9c08b-66f7-4778-8515-ddb9d903bdc4");
        UUID citizenId = UUID.fromString("7c226e78-9295-43cf-ab77-fbf8565a38ea");
        UUID teamId = UUID.fromString("36d3d561-8307-42ba-9123-49e79b24a67e");
        TestNationTeamDirectory teams = new TestNationTeamDirectory()
                .add(new NationTeam(teamId, originalHeadId, Set.of(originalHeadId)));

        try (CivicDatabase database = database()) {
            NationRegistry registry = new NationRegistry(database, teams);
            RegisteredNation registered = registry.register(new RegisterNation(
                    new ServiceIdentity("civiceconomy"), "register-current-facts", teamId));
            teams.add(new NationTeam(teamId, currentHeadId, Set.of(currentHeadId, citizenId)));
            CitizenshipRegistry citizenships = citizenships(database);
            citizenships.join(new JoinCitizenship(
                    new ServiceIdentity("civiceconomy"),
                    "provider-current-head",
                    currentHeadId,
                    registered.nationId()));
            citizenships.join(new JoinCitizenship(
                    new ServiceIdentity("civiceconomy"),
                    "provider-current-citizen",
                    citizenId,
                    registered.nationId()));
            NationProvider provider = new FtbTeamsNationProvider(
                    registry,
                    citizenships,
                    new CitizenshipCorrectionGraceRegistry(database, Clock.systemUTC()),
                    teams);

            NationFacts facts = provider.find(registered.nationId()).orElseThrow();
            assertEquals(currentHeadId, facts.headId());
            assertEquals(Set.of(currentHeadId, citizenId), facts.citizens());
        }
    }

    @Test
    void missingBoundFtbTeamFailsClosedWithoutErasingTheNation() {
        UUID ownerId = UUID.fromString("0468d88a-9e47-4558-b31b-41c12a859f2f");
        UUID teamId = UUID.fromString("7f756394-73fe-431e-84e8-0a4bd1add497");
        TestNationTeamDirectory teams = new TestNationTeamDirectory()
                .add(new NationTeam(teamId, ownerId, Set.of(ownerId)));

        try (CivicDatabase database = database()) {
            NationRegistry registry = new NationRegistry(database, teams);
            RegisteredNation registered = registry.register(new RegisterNation(
                    new ServiceIdentity("civiceconomy"), "register-missing-binding", teamId));
            NationProvider provider = provider(database, registry, teams);

            teams.remove(teamId);

            assertThrows(NationFactsUnavailableException.class, () -> provider.find(registered.nationId()));
            assertTrue(registry.find(registered.nationId()).isPresent());
        }
    }

    @Test
    void registeredCitizenResolvesToTheStableNationId() {
        UUID headId = UUID.fromString("c9a00888-5bfc-407c-a4f8-5fe32e5c80b2");
        UUID citizenId = UUID.fromString("f9dbfd12-ce1d-42da-a0cc-ff1bf7ac345b");
        UUID teamId = UUID.fromString("33adab58-5ef8-4e34-a11f-ad79de96fb02");
        TestNationTeamDirectory teams = new TestNationTeamDirectory()
                .add(new NationTeam(teamId, headId, Set.of(headId, citizenId)));

        try (CivicDatabase database = database()) {
            NationRegistry registry = new NationRegistry(database, teams);
            RegisteredNation registered = registry.register(new RegisterNation(
                    new ServiceIdentity("civiceconomy"), "register-citizen", teamId));
            CitizenshipRegistry citizenships = citizenships(database);
            citizenships.join(new JoinCitizenship(
                    new ServiceIdentity("civiceconomy"),
                    "provider-registered-citizen",
                    citizenId,
                    registered.nationId()));
            NationProvider provider = new FtbTeamsNationProvider(
                    registry,
                    citizenships,
                    new CitizenshipCorrectionGraceRegistry(database, Clock.systemUTC()),
                    teams);

            NationFacts facts = provider.findForCitizen(citizenId).orElseThrow();
            assertEquals(registered.nationId(), facts.nationId());
            assertTrue(facts.citizens().contains(citizenId));
        }
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("nation-provider.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("b33754e1-66ac-47cd-b279-bf6d79d73403"),
                        "0.1.0",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }

    private static NationProvider provider(
            CivicDatabase database, NationRegistry registry, NationTeamDirectory teams) {
        return new FtbTeamsNationProvider(
                registry,
                citizenships(database),
                new CitizenshipCorrectionGraceRegistry(database, Clock.systemUTC()),
                teams);
    }

    private static CitizenshipRegistry citizenships(CivicDatabase database) {
        return new CitizenshipRegistry(database, Duration.ZERO, Clock.systemUTC());
    }

    private static final class TestNationTeamDirectory implements NationTeamDirectory {
        private final Map<UUID, NationTeam> teams = new HashMap<>();

        TestNationTeamDirectory add(NationTeam team) {
            teams.put(team.teamId(), team);
            return this;
        }

        void remove(UUID teamId) {
            teams.remove(teamId);
        }

        @Override
        public Optional<NationTeam> find(UUID teamId) {
            return Optional.ofNullable(teams.get(teamId));
        }

        @Override
        public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
            return teams.values().stream().filter(team -> team.citizens().contains(playerId)).findFirst();
        }
    }
}
