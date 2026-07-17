package org.civiceconomy.production;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredFacilityAccountingBaseline;
import org.civiceconomy.persistence.StoredFacilityAccountingInterface;
import org.civiceconomy.persistence.StoredFacilityBaselineInventory;
import org.civiceconomy.persistence.StoredFacilityBaselineMachine;
import org.civiceconomy.persistence.StoredRegisteredFacility;
import org.civiceconomy.territory.TerritoryClaimPosition;

public final class FacilityAccountingBaselineRegistry {
    private static final Comparator<FacilityBaselineMachine> MACHINE_ORDER = Comparator
            .comparing((FacilityBaselineMachine machine) -> machine.position().dimensionId())
            .thenComparingInt(machine -> machine.position().blockX())
            .thenComparingInt(machine -> machine.position().blockY())
            .thenComparingInt(machine -> machine.position().blockZ())
            .thenComparing(machine -> machine.machineKind().name());

    private final CivicDatabase database;
    private final RegisteredFacilityTerritoryAuthority territoryAuthority;
    private final FacilityAccountingBaselineSnapshotSource snapshotSource;
    private final Clock clock;

    public FacilityAccountingBaselineRegistry(
            CivicDatabase database,
            RegisteredFacilityTerritoryAuthority territoryAuthority,
            FacilityAccountingBaselineSnapshotSource snapshotSource,
            Clock clock) {
        if (database == null || territoryAuthority == null
                || snapshotSource == null || clock == null) {
            throw new IllegalArgumentException(
                    "Facility Accounting Baseline dependencies cannot be null");
        }
        this.database = database;
        this.territoryAuthority = territoryAuthority;
        this.snapshotSource = snapshotSource;
        this.clock = clock;
    }

    public FacilityAccountingBaseline capture(CaptureFacilityAccountingBaseline request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Facility Accounting Baseline capture request is required");
        }
        FacilityAccountingBaseline replay = captureReplay(request);
        if (replay != null) {
            return replay;
        }
        RegisteredFacility facility = requireFacility(request.facilityId());
        if (facility.state() != RegisteredFacilityState.BASELINING) {
            throw new IllegalStateException(
                    "Facility Accounting Baseline can only be captured while baselining");
        }
        FacilityAccountingInterface accountingInterface = requireInterface(facility.facilityId());
        requireEffectiveTerritory(facility);
        FacilityAccountingBaselineSnapshot snapshot = snapshotSource.capture(
                facility, accountingInterface);
        validateSnapshot(facility, snapshot);
        StoredFacilityAccountingBaseline stored = database.captureFacilityAccountingBaseline(
                new StoredFacilityAccountingBaseline(
                        request.baselineId(),
                        request.serviceIdentity().value(),
                        request.requestId(),
                        facility.facilityId(),
                        accountingInterface.interfaceId(),
                        snapshot.createVersion(),
                        request.actorPlayerId(),
                        FacilityAccountingBaselineState.CAPTURED.name(),
                        request.reason(),
                        clock.millis(),
                        null),
                snapshot.machines().stream()
                        .sorted(MACHINE_ORDER)
                        .map(machine -> new StoredFacilityBaselineMachine(
                                request.baselineId(),
                                machine.machineKind().name(),
                                machine.position().dimensionId(),
                                machine.position().blockX(),
                                machine.position().blockY(),
                                machine.position().blockZ()))
                        .toList(),
                snapshot.startingInventory().stream()
                        .sorted(Comparator.comparingInt(MachineInventoryChange::slot))
                        .map(change -> new StoredFacilityBaselineInventory(
                                request.baselineId(),
                                change.slot(),
                                change.stack().itemId(),
                                change.stack().componentFingerprint(),
                                change.stack().count()))
                        .toList());
        return toBaseline(stored);
    }

    public FacilityAccountingBaseline captureReplay(
            CaptureFacilityAccountingBaseline request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Facility Accounting Baseline capture request is required");
        }
        StoredFacilityAccountingBaseline replay = database.facilityAccountingBaseline(
                request.serviceIdentity().value(), request.requestId());
        if (replay == null) {
            return null;
        }
        assertCaptureReplay(replay, request);
        return toBaseline(replay);
    }

    public FacilityAccountingBaseline baseline(UUID facilityId) {
        StoredFacilityAccountingBaseline stored = database.facilityAccountingBaseline(facilityId);
        return stored == null ? null : toBaseline(stored);
    }

    public FacilityAccountingBaseline activate(ActivateFacilityAccountingBaseline request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Facility Accounting Baseline activation request is required");
        }
        StoredFacilityAccountingBaseline replay =
                database.facilityAccountingBaselineActivationReplay(
                        request.serviceIdentity().value(),
                        request.requestId(),
                        request.facilityId(),
                        request.actorPlayerId(),
                        request.reason());
        if (replay != null) {
            return toBaseline(replay);
        }
        StoredFacilityAccountingBaseline stored = database.facilityAccountingBaseline(
                request.facilityId());
        if (stored == null) {
            throw new IllegalStateException(
                    "Facility Accounting Baseline must be captured before activation");
        }
        RegisteredFacility facility = requireFacility(request.facilityId());
        FacilityAccountingInterface accountingInterface = requireInterface(facility.facilityId());
        if (!stored.interfaceId().equals(accountingInterface.interfaceId())) {
            throw new IllegalStateException(
                    "Facility Accounting Interface changed after Baseline capture");
        }
        requireEffectiveTerritory(facility);
        FacilityAccountingBaselineSnapshot current = snapshotSource.capture(
                facility, accountingInterface);
        validateSnapshot(facility, current);
        List<FacilityBaselineMachine> capturedMachines =
                database.facilityAccountingBaselineMachines(stored.baselineId()).stream()
                        .map(machine -> new FacilityBaselineMachine(
                                CreateMachineKind.valueOf(machine.machineKind()),
                                new FacilityMachinePosition(
                                        machine.dimensionId(),
                                        machine.blockX(),
                                        machine.blockY(),
                                        machine.blockZ())))
                        .sorted(MACHINE_ORDER)
                        .toList();
        List<FacilityBaselineMachine> currentMachines = current.machines().stream()
                .sorted(MACHINE_ORDER)
                .toList();
        if (!stored.createVersion().equals(current.createVersion())
                || !capturedMachines.equals(currentMachines)) {
            throw new SecurityException(
                    "Facility Accounting Baseline fixed machine bindings changed before activation");
        }
        return toBaseline(database.activateFacilityAccountingBaseline(
                stored.baselineId(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.actorPlayerId(),
                request.reason(),
                clock.millis()));
    }

    private void assertCaptureReplay(
            StoredFacilityAccountingBaseline replay,
            CaptureFacilityAccountingBaseline request) {
        if (!replay.baselineId().equals(request.baselineId())
                || !replay.facilityId().equals(request.facilityId())
                || !replay.actorPlayerId().equals(request.actorPlayerId())
                || !replay.reason().equals(request.reason())) {
            throw new IllegalStateException(
                    "Facility Accounting Baseline capture replay changed its immutable payload");
        }
    }

    private RegisteredFacility requireFacility(UUID facilityId) {
        StoredRegisteredFacility stored = database.registeredFacility(facilityId);
        if (stored == null) {
            throw new IllegalArgumentException(
                    "Unknown Registered Facility " + facilityId);
        }
        Set<TerritoryClaimPosition> scope = database.registeredFacilityClaims(facilityId).stream()
                .map(claim -> new TerritoryClaimPosition(
                        claim.dimensionId(), claim.chunkX(), claim.chunkZ()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new RegisteredFacility(
                stored.facilityId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                new NationId(stored.nationId()),
                stored.ftbTeamId(),
                new FacilityCorePosition(
                        stored.dimensionId(),
                        stored.coreBlockX(),
                        stored.coreBlockY(),
                        stored.coreBlockZ()),
                scope,
                stored.actorPlayerId(),
                RegisteredFacilityState.valueOf(stored.state()),
                stored.reason(),
                Instant.ofEpochMilli(stored.registeredAtEpochMillis()));
    }

    private FacilityAccountingInterface requireInterface(UUID facilityId) {
        StoredFacilityAccountingInterface stored = database.facilityAccountingInterface(facilityId);
        if (stored == null) {
            throw new IllegalStateException(
                    "Facility Accounting Baseline requires a registered interface");
        }
        return new FacilityAccountingInterface(
                stored.interfaceId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                stored.facilityId(),
                new FacilityAccountingInterfacePosition(
                        stored.dimensionId(),
                        stored.blockX(),
                        stored.blockY(),
                        stored.blockZ()),
                stored.actorPlayerId(),
                stored.reason(),
                Instant.ofEpochMilli(stored.registeredAtEpochMillis()));
    }

    private void requireEffectiveTerritory(RegisteredFacility facility) {
        boolean effective = facility.scope().stream().allMatch(claim ->
                territoryAuthority.isEffective(
                        facility.nationId(), facility.ftbTeamId(), claim));
        if (!effective) {
            throw new SecurityException(
                    "Facility Accounting Baseline requires exact-Nation Effective Territory");
        }
    }

    private static void validateSnapshot(
            RegisteredFacility facility, FacilityAccountingBaselineSnapshot snapshot) {
        if (snapshot == null || snapshot.machines().stream().anyMatch(machine ->
                !facility.scope().contains(machine.position().claim()))) {
            throw new IllegalArgumentException(
                    "Facility Accounting Baseline machines must be inside Facility scope");
        }
    }

    private FacilityAccountingBaseline toBaseline(StoredFacilityAccountingBaseline stored) {
        List<FacilityBaselineMachine> machines = database.facilityAccountingBaselineMachines(
                        stored.baselineId())
                .stream()
                .map(machine -> new FacilityBaselineMachine(
                        CreateMachineKind.valueOf(machine.machineKind()),
                        new FacilityMachinePosition(
                                machine.dimensionId(),
                                machine.blockX(),
                                machine.blockY(),
                                machine.blockZ())))
                .toList();
        List<MachineInventoryChange> inventory = database.facilityAccountingBaselineInventory(
                        stored.baselineId())
                .stream()
                .map(change -> new MachineInventoryChange(
                        change.slot(),
                        new ProductionStack(
                                change.itemId(),
                                change.componentFingerprint(),
                                change.count())))
                .toList();
        return new FacilityAccountingBaseline(
                stored.baselineId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                stored.facilityId(),
                stored.interfaceId(),
                stored.createVersion(),
                machines,
                inventory,
                stored.actorPlayerId(),
                FacilityAccountingBaselineState.valueOf(stored.state()),
                stored.reason(),
                Instant.ofEpochMilli(stored.capturedAtEpochMillis()),
                stored.activatedAtEpochMillis() == null
                        ? null
                        : Instant.ofEpochMilli(stored.activatedAtEpochMillis()));
    }
}
