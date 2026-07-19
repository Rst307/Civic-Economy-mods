package org.civiceconomy.nation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NationRegistryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void registrationReplayKeepsOneStableNationIdAcrossRestart() {
        UUID ftbTeamId = UUID.fromString("7dd612bb-f7ae-4bb4-9e15-604eb38ebcde");
        RegisterNation request = new RegisterNation(
                new ServiceIdentity("civiceconomy"), "register-aurora", ftbTeamId);
        RegisteredNation registered;

        try (CivicDatabase database = database()) {
            NationRegistry registry = new NationRegistry(database, teamDirectory(ftbTeamId));
            registered = registry.register(request);

            assertEquals(registered, registry.register(request));
            assertEquals(ftbTeamId, registered.ftbTeamId());
            assertEquals(registered, registry.find(registered.nationId()).orElseThrow());
        }

        try (CivicDatabase reopened = database()) {
            NationRegistry registry = new NationRegistry(reopened, teamDirectory(ftbTeamId));

            assertEquals(registered, registry.register(request));
            assertEquals(registered, registry.findByFtbTeam(ftbTeamId).orElseThrow());
        }
    }

    @Test
    void oneFtbTeamCannotBeBoundToTwoNations() {
        UUID ftbTeamId = UUID.fromString("451a6550-0586-4510-b5bd-11cd0a334741");

        try (CivicDatabase database = database()) {
            NationRegistry registry = new NationRegistry(database, teamDirectory(ftbTeamId));
            RegisteredNation first = registry.register(new RegisterNation(
                    new ServiceIdentity("civiceconomy"), "register-first", ftbTeamId));

            assertThrows(
                    FtbTeamAlreadyBoundException.class,
                    () -> registry.register(new RegisterNation(
                            new ServiceIdentity("civiceconomy"), "register-second", ftbTeamId)));
            assertEquals(first, registry.findByFtbTeam(ftbTeamId).orElseThrow());
        }
    }

    @Test
    void registrationRequestCannotBeReusedForAnotherFtbTeam() {
        UUID firstTeamId = UUID.fromString("42cb6a15-6fc9-4428-94b2-c85eecfae2c1");
        UUID secondTeamId = UUID.fromString("87ac08fe-6ee5-4be8-a8e5-3f4f77681e6c");
        RegisterNation firstRequest = new RegisterNation(
                new ServiceIdentity("civiceconomy"), "register-conflict", firstTeamId);
        RegisterNation conflictingRequest = new RegisterNation(
                new ServiceIdentity("civiceconomy"), "register-conflict", secondTeamId);

        try (CivicDatabase database = database()) {
            NationRegistry registry = new NationRegistry(database, teamDirectory(firstTeamId, secondTeamId));
            RegisteredNation registered = registry.register(firstRequest);

            assertThrows(IdempotencyConflictException.class, () -> registry.register(conflictingRequest));
            assertEquals(registered, registry.findByFtbTeam(firstTeamId).orElseThrow());
            assertFalse(registry.findByFtbTeam(secondTeamId).isPresent());
        }
    }

    @Test
    void unknownFtbTeamCannotCreateANation() {
        UUID unknownTeamId = UUID.fromString("32c2bfe3-311d-4e08-91e0-38e906b95a47");

        try (CivicDatabase database = database()) {
            NationRegistry registry = new NationRegistry(database, teamDirectory());

            assertThrows(
                    UnknownFtbTeamException.class,
                    () -> registry.register(new RegisterNation(
                            new ServiceIdentity("civiceconomy"), "register-unknown", unknownTeamId)));
            assertFalse(registry.findByFtbTeam(unknownTeamId).isPresent());
        }
    }

    @Test
    void differentFtbTeamsReceiveDistinctStableNationIds() {
        UUID firstTeamId = UUID.fromString("08a92a11-6667-4a30-b5c8-e519c7a3cd76");
        UUID secondTeamId = UUID.fromString("71585d49-d587-4342-b6db-c3ad6a4b40c3");

        try (CivicDatabase database = database()) {
            NationRegistry registry = new NationRegistry(database, teamDirectory(firstTeamId, secondTeamId));

            RegisteredNation first = registry.register(new RegisterNation(
                    new ServiceIdentity("civiceconomy"), "register-north", firstTeamId));
            RegisteredNation second = registry.register(new RegisterNation(
                    new ServiceIdentity("civiceconomy"), "register-south", secondTeamId));

            assertNotEquals(first.nationId(), second.nationId());
            assertEquals(first, registry.find(first.nationId()).orElseThrow());
            assertEquals(second, registry.find(second.nationId()).orElseThrow());
        }
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("nation-registry.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("83fa5ab8-c5dc-4589-a51c-9d0408b71302"),
                        "0.1.0",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }

    private static NationTeamDirectory teamDirectory(UUID... teamIds) {
        Set<UUID> knownTeams = Set.of(teamIds);
        return new NationTeamDirectory() {
            @Override
            public Optional<NationTeam> find(UUID teamId) {
                return knownTeams.contains(teamId)
                        ? Optional.of(new NationTeam(teamId, teamId, Set.of(teamId)))
                        : Optional.empty();
            }

            @Override
            public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
                return Optional.empty();
            }
        };
    }
}
