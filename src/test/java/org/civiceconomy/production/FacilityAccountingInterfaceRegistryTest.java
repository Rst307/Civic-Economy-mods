package org.civiceconomy.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.civiceconomy.territory.TerritoryClaimPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FacilityAccountingInterfaceRegistryTest {
    private static final NationId NATION = new NationId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final UUID TEAM = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACTOR = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID FACILITY = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID INTERFACE = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final ServiceIdentity SERVICE = new ServiceIdentity("facility-interface-test");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @TempDir Path temporaryDirectory;

    @Test
    void registersOneFixedInterfaceInsideItsFacilityAndRestoresIt() {
        Path file = temporaryDirectory.resolve("facility-interface.sqlite3");
        FacilityAccountingInterface expected;
        try (CivicDatabase database = database(file)) {
            registerFacility(database);
            FacilityAccountingInterfaceRegistry registry =
                    new FacilityAccountingInterfaceRegistry(database, CLOCK);
            RegisterFacilityAccountingInterface request = new RegisterFacilityAccountingInterface(
                    SERVICE,
                    "register-interface",
                    INTERFACE,
                    FACILITY,
                    new FacilityAccountingInterfacePosition("minecraft:overworld", 17, 72, 4),
                    ACTOR,
                    "Initial Facility Accounting Interface");

            expected = registry.register(request);

            assertEquals(expected, registry.register(request));
            assertEquals(FACILITY, expected.facilityId());
            assertEquals(claim(1, 0), expected.position().claim());
        }
        try (CivicDatabase reopened = database(file)) {
            FacilityAccountingInterface restored =
                    new FacilityAccountingInterfaceRegistry(reopened, CLOCK).interfaceFor(FACILITY);
            assertEquals(expected, restored);
        }
    }

    @Test
    void rejectsAnInterfaceOutsideItsRegisteredFacilityScope() {
        try (CivicDatabase database = database(temporaryDirectory.resolve("outside.sqlite3"))) {
            registerFacility(database);
            FacilityAccountingInterfaceRegistry registry =
                    new FacilityAccountingInterfaceRegistry(database, CLOCK);

            assertThrows(IllegalArgumentException.class, () -> registry.register(
                    new RegisterFacilityAccountingInterface(
                            SERVICE,
                            "outside-interface",
                            INTERFACE,
                            FACILITY,
                            new FacilityAccountingInterfacePosition(
                                    "minecraft:overworld", 48, 72, 4),
                            ACTOR,
                            "Outside Facility scope")));
            assertEquals(null, registry.interfaceFor(FACILITY));
        }
    }

    @Test
    void enforcesOneImmutableInterfacePerFacility() {
        try (CivicDatabase database = database(temporaryDirectory.resolve("unique.sqlite3"))) {
            registerFacility(database);
            FacilityAccountingInterfaceRegistry registry =
                    new FacilityAccountingInterfaceRegistry(database, CLOCK);
            RegisterFacilityAccountingInterface first = new RegisterFacilityAccountingInterface(
                    SERVICE,
                    "register-interface",
                    INTERFACE,
                    FACILITY,
                    new FacilityAccountingInterfacePosition("minecraft:overworld", 17, 72, 4),
                    ACTOR,
                    "Immutable interface");
            registry.register(first);

            assertThrows(IllegalStateException.class, () -> registry.register(
                    new RegisterFacilityAccountingInterface(
                            SERVICE,
                            "register-interface",
                            INTERFACE,
                            FACILITY,
                            new FacilityAccountingInterfacePosition(
                                    "minecraft:overworld", 18, 72, 4),
                            ACTOR,
                            "Immutable interface")));
            assertThrows(IllegalStateException.class, () -> registry.register(
                    new RegisterFacilityAccountingInterface(
                            SERVICE,
                            "second-interface",
                            UUID.fromString("88888888-8888-8888-8888-888888888888"),
                            FACILITY,
                            new FacilityAccountingInterfacePosition(
                                    "minecraft:overworld", 18, 72, 4),
                            ACTOR,
                            "Second interface")));
        }
    }

    private void registerFacility(CivicDatabase database) {
        database.registerNation(NATION.value(), SERVICE.value(), "nation", TEAM, CLOCK.millis() - 1L);
        new RegisteredFacilityRegistry(
                        database,
                        (nation, team, claim) -> NATION.equals(nation) && TEAM.equals(team),
                        CLOCK,
                        4)
                .register(new RegisterFacility(
                        SERVICE,
                        "register-facility",
                        FACILITY,
                        NATION,
                        TEAM,
                        new FacilityCorePosition("minecraft:overworld", 1, 70, 1),
                        List.of(claim(0, 0), claim(1, 0)),
                        ACTOR,
                        "Facility for interface"));
    }

    private static TerritoryClaimPosition claim(int chunkX, int chunkZ) {
        return new TerritoryClaimPosition("minecraft:overworld", chunkX, chunkZ);
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
