package org.civiceconomy.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.civiceconomy.territory.TerritoryClaimPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegisteredFacilityRegistryTest {
    private static final NationId NATION = new NationId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final UUID TEAM = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACTOR = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID FACILITY = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final ServiceIdentity SERVICE = new ServiceIdentity("facility-registry-test");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @TempDir Path temporaryDirectory;

    @Test
    void registersOneContiguousExactNationFacilityInBaseliningState() {
        Path file = temporaryDirectory.resolve("facility.sqlite3");
        Set<TerritoryClaimPosition> effective = Set.of(
                claim(0, 0), claim(1, 0), claim(1, 1));
        try (CivicDatabase database = database(file)) {
            registerNation(database);
            RegisteredFacilityRegistry registry = registry(database, effective, 4);

            RegisteredFacility first = registry.register(request(
                    "register", FACILITY, TEAM, List.copyOf(effective), "Initial facility"));
            RegisteredFacility replay = registry.register(request(
                    "register", FACILITY, TEAM, List.copyOf(effective), "Initial facility"));

            assertEquals(first, replay);
            assertEquals(RegisteredFacilityState.BASELINING, first.state());
            assertEquals(effective, first.scope());
            assertEquals(claim(0, 0), first.core().claim());
        }
        try (CivicDatabase reopened = database(file)) {
            RegisteredFacility restored = new RegisteredFacilityRegistry(
                            reopened, (nation, team, claim) -> false, CLOCK, 4)
                    .facility(FACILITY);
            assertEquals(RegisteredFacilityState.BASELINING, restored.state());
            assertEquals(effective, restored.scope());
        }
    }

    @Test
    void rejectsDisconnectedOversizedOrCorelessScopeBeforePersistence() {
        try (CivicDatabase database = database(temporaryDirectory.resolve("invalid.sqlite3"))) {
            registerNation(database);
            RegisteredFacilityRegistry registry = registry(
                    database, Set.of(claim(0, 0), claim(1, 0), claim(2, 0)), 2);

            assertThrows(IllegalArgumentException.class, () -> registry.register(request(
                    "disconnected", FACILITY, TEAM,
                    List.of(claim(0, 0), claim(2, 0)), "Disconnected")));
            assertThrows(IllegalArgumentException.class, () -> registry.register(request(
                    "oversized", FACILITY, TEAM,
                    List.of(claim(0, 0), claim(1, 0), claim(2, 0)), "Oversized")));
            assertThrows(IllegalArgumentException.class, () -> registry.register(new RegisterFacility(
                    SERVICE,
                    "core-outside",
                    FACILITY,
                    NATION,
                    TEAM,
                    new FacilityCorePosition("minecraft:overworld", 32, 70, 0),
                    List.of(claim(0, 0), claim(1, 0)),
                    ACTOR,
                    "Core outside")));
            assertEquals(null, registry.facility(FACILITY));
        }
    }

    @Test
    void rejectsForeignTerritoryOverlapAndChangedReplay() {
        try (CivicDatabase database = database(temporaryDirectory.resolve("authority.sqlite3"))) {
            registerNation(database);
            RegisteredFacilityRegistry registry = registry(
                    database, Set.of(claim(0, 0), claim(1, 0)), 4);

            assertThrows(SecurityException.class, () -> registry.register(request(
                    "foreign-team", FACILITY,
                    UUID.fromString("99999999-9999-9999-9999-999999999999"),
                    List.of(claim(0, 0)), "Foreign Team")));
            RegisteredFacility registered = registry.register(request(
                    "register", FACILITY, TEAM,
                    List.of(claim(0, 0), claim(1, 0)), "Registered"));
            assertEquals(FACILITY, registered.facilityId());

            assertThrows(IllegalStateException.class, () -> registry.register(request(
                    "register", FACILITY, TEAM,
                    List.of(claim(0, 0)), "Changed replay")));
            assertThrows(IllegalStateException.class, () -> registry.register(request(
                    "overlap",
                    UUID.fromString("55555555-5555-5555-5555-555555555555"),
                    TEAM,
                    List.of(claim(0, 0)),
                    "Overlapping facility")));
        }
    }

    private RegisteredFacilityRegistry registry(
            CivicDatabase database,
            Set<TerritoryClaimPosition> effective,
            int maxScopeChunks) {
        return new RegisteredFacilityRegistry(
                database,
                (nationId, ftbTeamId, claim) ->
                        NATION.equals(nationId) && TEAM.equals(ftbTeamId) && effective.contains(claim),
                CLOCK,
                maxScopeChunks);
    }

    private RegisterFacility request(
            String requestId,
            UUID facilityId,
            UUID teamId,
            List<TerritoryClaimPosition> scope,
            String reason) {
        return new RegisterFacility(
                SERVICE,
                requestId,
                facilityId,
                NATION,
                teamId,
                new FacilityCorePosition("minecraft:overworld", 1, 70, 1),
                scope,
                ACTOR,
                reason);
    }

    private static TerritoryClaimPosition claim(int chunkX, int chunkZ) {
        return new TerritoryClaimPosition("minecraft:overworld", chunkX, chunkZ);
    }

    private void registerNation(CivicDatabase database) {
        database.registerNation(
                NATION.value(), SERVICE.value(), "nation", TEAM, CLOCK.millis() - 1L);
    }

    private CivicDatabase database(Path file) {
        return CivicDatabase.open(
                file,
                new DatabaseIdentity(
                        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
