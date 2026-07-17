package org.civiceconomy.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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

class FacilityBaselineAdministrationTest {
    private static final NationId NATION = new NationId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final UUID TEAM = UUID.fromString(
            "22222222-2222-2222-2222-222222222222");
    private static final UUID ACTOR = UUID.fromString(
            "33333333-3333-3333-3333-333333333333");
    private static final UUID FACILITY = UUID.fromString(
            "44444444-4444-4444-4444-444444444444");
    private static final UUID INTERFACE = UUID.fromString(
            "55555555-5555-5555-5555-555555555555");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-17T13:00:00Z"), ZoneOffset.UTC);
    private static final TerritoryClaimPosition CLAIM =
            new TerritoryClaimPosition("minecraft:overworld", 4, 7);
    private static final FacilityAccountingInterfacePosition INTERFACE_POSITION =
            new FacilityAccountingInterfacePosition(
                    "minecraft:overworld", 72, 65, 120);

    @TempDir Path temporaryDirectory;

    @Test
    void authorizedCitizenCapturesTrustedSnapshotAndReplayNeedsNoSecondSnapshot() {
        try (CivicDatabase database = database()) {
            NationProvider provider = provider();
            NationRegistry nations = new NationRegistry(database, teams());
            database.registerNation(
                    NATION.value(), "facility-baseline-test", "register-nation", TEAM,
                    CLOCK.millis() - 1L);
            NationFiscalAuthorityRegistry authorities =
                    new NationFiscalAuthorityRegistry(database, provider, CLOCK);
            authorities.grant(new GrantNationFiscalPermission(
                    new ServiceIdentity("facility-baseline-test"),
                    "grant-facility-accounting",
                    NATION,
                    ACTOR,
                    ACTOR,
                    NationFiscalPermission.MANAGE_FACILITY_ACCOUNTING,
                    "Manage Facility Baseline"));
            RegisteredFacilityTerritoryAuthority territory =
                    (nationId, teamId, claim) -> NATION.equals(nationId)
                            && TEAM.equals(teamId)
                            && CLAIM.equals(claim);
            RegisteredFacilityRegistry facilities = new RegisteredFacilityRegistry(
                    database, territory, CLOCK, 16);
            facilities.register(new RegisterFacility(
                    FacilityAdministration.SERVICE_IDENTITY,
                    "register-baseline-facility",
                    FACILITY,
                    NATION,
                    TEAM,
                    new FacilityCorePosition("minecraft:overworld", 70, 64, 118),
                    List.of(CLAIM),
                    ACTOR,
                    "Register Facility for Baseline"));
            FacilityAccountingInterfaceRegistry interfaces =
                    new FacilityAccountingInterfaceRegistry(database, CLOCK);
            interfaces.register(new RegisterFacilityAccountingInterface(
                    FacilityAdministration.SERVICE_IDENTITY,
                    "bind-baseline-interface",
                    INTERFACE,
                    FACILITY,
                    INTERFACE_POSITION,
                    ACTOR,
                    "Bind Facility interface for Baseline"));
            FacilityBaselineAdministration administration =
                    new FacilityBaselineAdministration(
                            database,
                            nations,
                            provider,
                            authorities,
                            facilities,
                            interfaces,
                            territory,
                            CLOCK);

            FacilityBaselineCaptureWork work = assertInstanceOf(
                    FacilityBaselineCaptureWork.class,
                    administration.prepareCapture(
                            ACTOR,
                            TEAM,
                            "capture-current-baseline",
                            INTERFACE_POSITION,
                            "Capture current trusted Baseline"));
            FacilityAccountingBaselineSnapshot snapshot =
                    new FacilityAccountingBaselineSnapshot(
                            "6.0.6",
                            List.of(new FacilityBaselineMachine(
                                    CreateMachineKind.MILLSTONE,
                                    new FacilityMachinePosition(
                                            "minecraft:overworld", 74, 64, 120))),
                            List.of(new MachineInventoryChange(
                                    2,
                                    new ProductionStack(
                                            "minecraft:flour",
                                            "{id:\"minecraft:flour\"}",
                                            7))));

            FacilityAccountingBaseline captured =
                    administration.completeCapture(work, snapshot);
            FacilityBaselineCaptureReplay replay = assertInstanceOf(
                    FacilityBaselineCaptureReplay.class,
                    administration.prepareCapture(
                            ACTOR,
                            TEAM,
                            "capture-current-baseline",
                            INTERFACE_POSITION,
                            "Capture current trusted Baseline"));

            assertEquals(FACILITY, captured.facilityId());
            assertEquals(INTERFACE, captured.interfaceId());
            assertEquals(FacilityAccountingBaselineState.CAPTURED, captured.state());
            assertEquals(snapshot.machines(), captured.machines());
            assertEquals(snapshot.startingInventory(), captured.startingInventory());
            assertEquals(captured, replay.baseline());

            FacilityBaselineActivationWork activationWork = assertInstanceOf(
                    FacilityBaselineActivationWork.class,
                    administration.prepareActivation(
                            ACTOR,
                            TEAM,
                            "activate-current-baseline",
                            INTERFACE_POSITION,
                            "Activate current trusted Baseline"));
            FacilityAccountingBaseline activated = administration.completeActivation(
                    activationWork,
                    new FacilityAccountingBaselineSnapshot(
                            "6.0.6",
                            snapshot.machines(),
                            List.of(new MachineInventoryChange(
                                    2,
                                    new ProductionStack(
                                            "minecraft:flour",
                                            "{id:\"minecraft:flour\"}",
                                            11)))));
            FacilityBaselineActivationReplay activationReplay = assertInstanceOf(
                    FacilityBaselineActivationReplay.class,
                    administration.prepareActivation(
                            ACTOR,
                            TEAM,
                            "activate-current-baseline",
                            INTERFACE_POSITION,
                            "Activate current trusted Baseline"));
            FacilityAccountingStatus status = administration.status(
                    ACTOR, TEAM, INTERFACE_POSITION);

            assertEquals(FacilityAccountingBaselineState.ACTIVE, activated.state());
            assertEquals(snapshot.startingInventory(), activated.startingInventory());
            assertEquals(activated, activationReplay.baseline());
            assertEquals(RegisteredFacilityState.ACTIVE, status.facility().state());
            assertEquals(INTERFACE, status.accountingInterface().interfaceId());
            assertEquals(activated, status.baseline());
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
                temporaryDirectory.resolve("facility-baseline-administration.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
