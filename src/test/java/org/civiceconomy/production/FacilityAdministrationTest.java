package org.civiceconomy.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.GrantNationFiscalPermission;
import org.civiceconomy.nation.NationFacts;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.NationProvider;
import org.civiceconomy.nation.NationRegistry;
import org.civiceconomy.nation.NationTeam;
import org.civiceconomy.nation.NationTeamDirectory;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.civiceconomy.territory.TerritoryClaimPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FacilityAdministrationTest {
    private static final NationId NATION = new NationId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final UUID TEAM = UUID.fromString(
            "22222222-2222-2222-2222-222222222222");
    private static final UUID ACTOR = UUID.fromString(
            "33333333-3333-3333-3333-333333333333");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-17T12:00:00Z"), ZoneOffset.UTC);

    @TempDir Path temporaryDirectory;

    @Test
    void authorizedCitizenRegistersServerDerivedFacilityExactlyOnce() {
        try (CivicDatabase database = database()) {
            database.registerNation(
                    NATION.value(), "facility-test", "register-nation", TEAM,
                    CLOCK.millis() - 1L);
            NationProvider provider = provider();
            NationFiscalAuthorityRegistry authorities =
                    new NationFiscalAuthorityRegistry(database, provider, CLOCK);
            authorities.grant(new GrantNationFiscalPermission(
                    new ServiceIdentity("facility-test"),
                    "grant-facility-accounting",
                    NATION,
                    ACTOR,
                    ACTOR,
                    NationFiscalPermission.MANAGE_FACILITY_ACCOUNTING,
                    "Manage Facility accounting"));
            TerritoryClaimPosition currentClaim = new TerritoryClaimPosition(
                    "minecraft:overworld", 4, 7);
            RegisteredFacilityRegistry facilities = new RegisteredFacilityRegistry(
                    database,
                    (nationId, teamId, claim) -> NATION.equals(nationId)
                            && TEAM.equals(teamId)
                            && currentClaim.equals(claim),
                    CLOCK,
                    16);
            FacilityAdministration administration = new FacilityAdministration(
                    new NationRegistry(database, teams()),
                    provider,
                    authorities,
                    facilities,
                    new FacilityAccountingInterfaceRegistry(database, CLOCK));
            FacilityCorePosition currentPosition = new FacilityCorePosition(
                    "minecraft:overworld", 70, 64, 118);

            RegisteredFacility first = administration.register(
                    ACTOR,
                    TEAM,
                    "register-current-facility",
                    currentPosition,
                    List.of(currentClaim),
                    "Register current Facility");
            RegisteredFacility replay = administration.register(
                    ACTOR,
                    TEAM,
                    "register-current-facility",
                    currentPosition,
                    List.of(currentClaim),
                    "Register current Facility");

            assertEquals(first, replay);
            assertEquals(NATION, first.nationId());
            assertEquals(TEAM, first.ftbTeamId());
            assertEquals(ACTOR, first.actorPlayerId());
            assertEquals(currentPosition, first.core());
            assertEquals(Set.of(currentClaim), first.scope());
            assertEquals(RegisteredFacilityState.BASELINING, first.state());
        }
    }

    @Test
    void citizenWithoutFacilityAccountingPermissionFailsBeforeRegistration() {
        try (CivicDatabase database = database()) {
            database.registerNation(
                    NATION.value(), "facility-test", "register-nation", TEAM,
                    CLOCK.millis() - 1L);
            NationProvider provider = provider();
            TerritoryClaimPosition currentClaim = new TerritoryClaimPosition(
                    "minecraft:overworld", 4, 7);
            FacilityAdministration administration = new FacilityAdministration(
                    new NationRegistry(database, teams()),
                    provider,
                    new NationFiscalAuthorityRegistry(database, provider, CLOCK),
                    new RegisteredFacilityRegistry(
                            database,
                            (nationId, teamId, claim) -> true,
                            CLOCK,
                            16),
                    new FacilityAccountingInterfaceRegistry(database, CLOCK));

            assertThrows(SecurityException.class, () -> administration.register(
                    ACTOR,
                    TEAM,
                    "unauthorized-facility",
                    new FacilityCorePosition("minecraft:overworld", 70, 64, 118),
                    List.of(currentClaim),
                    "Must fail before persistence"));
            assertNull(database.registeredFacility(
                    FacilityAdministration.SERVICE_IDENTITY.value(),
                    "unauthorized-facility"));
        }
    }

    @Test
    void authorizedCitizenBindsServerLocatedInterfaceExactlyOnce() {
        try (CivicDatabase database = database()) {
            database.registerNation(
                    NATION.value(), "facility-test", "register-nation", TEAM,
                    CLOCK.millis() - 1L);
            NationProvider provider = provider();
            NationFiscalAuthorityRegistry authorities =
                    new NationFiscalAuthorityRegistry(database, provider, CLOCK);
            authorities.grant(new GrantNationFiscalPermission(
                    new ServiceIdentity("facility-test"),
                    "grant-facility-accounting",
                    NATION,
                    ACTOR,
                    ACTOR,
                    NationFiscalPermission.MANAGE_FACILITY_ACCOUNTING,
                    "Manage Facility accounting"));
            FacilityAdministration administration = new FacilityAdministration(
                    new NationRegistry(database, teams()),
                    provider,
                    authorities,
                    new RegisteredFacilityRegistry(
                            database,
                            (nationId, teamId, claim) -> true,
                            CLOCK,
                            16),
                    new FacilityAccountingInterfaceRegistry(database, CLOCK));
            RegisteredFacility facility = administration.register(
                    ACTOR,
                    TEAM,
                    "register-interface-facility",
                    new FacilityCorePosition("minecraft:overworld", 70, 64, 118),
                    List.of(new TerritoryClaimPosition("minecraft:overworld", 4, 7)),
                    "Register Facility for interface binding");
            FacilityAccountingInterfacePosition locatedInterface =
                    new FacilityAccountingInterfacePosition(
                            "minecraft:overworld", 72, 65, 120);

            FacilityAccountingInterface first = administration.bindInterface(
                    ACTOR,
                    TEAM,
                    "bind-current-interface",
                    locatedInterface,
                    "Bind the real Civic interface");
            FacilityAccountingInterface replay = administration.bindInterface(
                    ACTOR,
                    TEAM,
                    "bind-current-interface",
                    locatedInterface,
                    "Bind the real Civic interface");

            assertEquals(first, replay);
            assertEquals(facility.facilityId(), first.facilityId());
            assertEquals(locatedInterface, first.position());
            assertEquals(ACTOR, first.actorPlayerId());
        }
    }

    private NationProvider provider() {
        NationFacts facts = new NationFacts(NATION, ACTOR, Set.of(ACTOR));
        return new NationProvider() {
            @Override
            public Optional<NationFacts> find(NationId nationId) {
                return NATION.equals(nationId) ? Optional.of(facts) : Optional.empty();
            }

            @Override
            public Optional<NationFacts> findForCitizen(UUID playerId) {
                return ACTOR.equals(playerId) ? Optional.of(facts) : Optional.empty();
            }
        };
    }

    private NationTeamDirectory teams() {
        NationTeam team = new NationTeam(TEAM, ACTOR, Set.of(ACTOR));
        return new NationTeamDirectory() {
            @Override
            public Optional<NationTeam> find(UUID teamId) {
                return TEAM.equals(teamId) ? Optional.of(team) : Optional.empty();
            }

            @Override
            public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
                return ACTOR.equals(playerId) ? Optional.of(team) : Optional.empty();
            }
        };
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("facility-administration.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
