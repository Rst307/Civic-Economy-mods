package org.civiceconomy.production;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredFacilityAccountingBaseline;
import org.civiceconomy.persistence.StoredFacilityAccountingInterface;
import org.civiceconomy.persistence.StoredFacilityClaim;
import org.civiceconomy.persistence.StoredRegisteredFacility;
import org.civiceconomy.territory.TerritoryClaimPosition;

public final class FacilityProductionMatcher {
    private final CivicDatabase database;
    private final RegisteredFacilityTerritoryAuthority territoryAuthority;
    private final Set<CreateMachineKind> supportedMachines;
    private final long maxReceiptDelayMillis;

    public FacilityProductionMatcher(
            CivicDatabase database,
            RegisteredFacilityTerritoryAuthority territoryAuthority,
            Set<CreateMachineKind> supportedMachines,
            Duration maxReceiptDelay) {
        if (database == null || territoryAuthority == null || supportedMachines == null
                || supportedMachines.stream().anyMatch(java.util.Objects::isNull)
                || maxReceiptDelay == null || maxReceiptDelay.isNegative()
                || maxReceiptDelay.isZero()) {
            throw new IllegalArgumentException("Facility Production Matcher dependencies are invalid");
        }
        this.database = database;
        this.territoryAuthority = territoryAuthority;
        this.supportedMachines = Set.copyOf(supportedMachines);
        this.maxReceiptDelayMillis = maxReceiptDelay.toMillis();
    }

    public FacilityProductionDecision match(
            CreateRecipeCompletion completion, FacilityAccountingReceipt receipt) {
        if (completion == null || receipt == null) {
            throw new IllegalArgumentException(
                    "Create Recipe Completion and Facility Accounting Receipt are required");
        }
        StoredRegisteredFacility facility = database.registeredFacilityAt(
                completion.dimensionId(), completion.blockX(), completion.blockZ());
        if (facility == null) {
            return decision(
                    completion, null, null, receipt.receiptId(),
                    FacilityProductionDecisionKind.UNMATCHED_FACILITY,
                    "Create machine is outside every Registered Facility scope");
        }
        StoredFacilityAccountingInterface accountingInterface =
                database.facilityAccountingInterface(facility.facilityId());
        if (!matches(accountingInterface, completion, receipt)) {
            return decision(
                    completion,
                    facility.facilityId(),
                    accountingInterface == null ? null : accountingInterface.interfaceId(),
                    receipt.receiptId(),
                    FacilityProductionDecisionKind.UNMATCHED_INTERFACE_RECEIPT,
                    "Create Recipe Completion does not exactly match the Facility Accounting Receipt");
        }
        if (!supportedMachines.contains(completion.machineKind())) {
            return decision(
                    completion,
                    facility.facilityId(),
                    accountingInterface.interfaceId(),
                    receipt.receiptId(),
                    FacilityProductionDecisionKind.UNSUPPORTED_MACHINE,
                    "Create machine kind is not in the version-pinned whitelist");
        }
        if (!hasEffectiveTerritory(facility)) {
            return decision(
                    completion,
                    facility.facilityId(),
                    accountingInterface.interfaceId(),
                    receipt.receiptId(),
                    FacilityProductionDecisionKind.FACILITY_TERRITORY_INEFFECTIVE,
                    "Registered Facility scope is no longer exact-Nation Effective Territory");
        }
        if (RegisteredFacilityState.BASELINING.name().equals(facility.state())) {
            return decision(
                    completion,
                    facility.facilityId(),
                    accountingInterface.interfaceId(),
                    receipt.receiptId(),
                    FacilityProductionDecisionKind.FACILITY_BASELINING,
                    "Registered Facility has not completed its Facility Accounting Baseline");
        }
        StoredFacilityAccountingBaseline baseline =
                database.facilityAccountingBaseline(facility.facilityId());
        if (baseline == null || !FacilityAccountingBaselineState.ACTIVE.name().equals(
                baseline.state()) || baseline.activatedAtEpochMillis() == null
                || completion.observedAtEpochMillis() < baseline.activatedAtEpochMillis()) {
            return decision(
                    completion,
                    facility.facilityId(),
                    accountingInterface.interfaceId(),
                    receipt.receiptId(),
                    FacilityProductionDecisionKind.FACILITY_BASELINING,
                    "Create Recipe Completion occurred before the active Facility Accounting Baseline");
        }
        boolean fixedMachine = baseline.createVersion().equals(completion.createVersion())
                && database.facilityAccountingBaselineMachines(baseline.baselineId()).stream()
                        .anyMatch(machine ->
                                machine.machineKind().equals(completion.machineKind().name())
                                        && machine.dimensionId().equals(completion.dimensionId())
                                        && machine.blockX() == completion.blockX()
                                        && machine.blockY() == completion.blockY()
                                        && machine.blockZ() == completion.blockZ());
        if (!fixedMachine) {
            return decision(
                    completion,
                    facility.facilityId(),
                    accountingInterface.interfaceId(),
                    receipt.receiptId(),
                    FacilityProductionDecisionKind.UNSUPPORTED_MACHINE,
                    "Create machine is not bound by the active Facility Accounting Baseline");
        }
        return decision(
                completion,
                facility.facilityId(),
                accountingInterface.interfaceId(),
                receipt.receiptId(),
                FacilityProductionDecisionKind.INCLUDED,
                "Create Recipe Completion exactly matched its Facility Accounting Receipt");
    }

    private boolean matches(
            StoredFacilityAccountingInterface accountingInterface,
            CreateRecipeCompletion completion,
            FacilityAccountingReceipt receipt) {
        if (accountingInterface == null
                || !accountingInterface.interfaceId().equals(receipt.interfaceId())
                || !accountingInterface.dimensionId().equals(receipt.position().dimensionId())
                || accountingInterface.blockX() != receipt.position().blockX()
                || accountingInterface.blockY() != receipt.position().blockY()
                || accountingInterface.blockZ() != receipt.position().blockZ()) {
            return false;
        }
        long delay = receipt.observedAtEpochMillis() - completion.observedAtEpochMillis();
        return delay >= 0L
                && delay <= maxReceiptDelayMillis
                && quantities(completion.inventoryDelta().outputs())
                        .equals(quantities(receipt.receivedOutputs()));
    }

    private boolean hasEffectiveTerritory(StoredRegisteredFacility facility) {
        NationId nationId = new NationId(facility.nationId());
        UUID teamId = facility.ftbTeamId();
        return database.registeredFacilityClaims(facility.facilityId()).stream()
                .map(FacilityProductionMatcher::toClaim)
                .allMatch(claim -> territoryAuthority.isEffective(nationId, teamId, claim));
    }

    private static TerritoryClaimPosition toClaim(StoredFacilityClaim claim) {
        return new TerritoryClaimPosition(
                claim.dimensionId(), claim.chunkX(), claim.chunkZ());
    }

    private static Map<StackIdentity, Long> quantities(List<MachineInventoryChange> changes) {
        Map<StackIdentity, Long> quantities = new HashMap<>();
        for (MachineInventoryChange change : changes) {
            ProductionStack stack = change.stack();
            quantities.merge(
                    new StackIdentity(stack.itemId(), stack.componentFingerprint()),
                    (long) stack.count(),
                    Math::addExact);
        }
        return Map.copyOf(quantities);
    }

    private static FacilityProductionDecision decision(
            CreateRecipeCompletion completion,
            UUID facilityId,
            UUID interfaceId,
            UUID receiptId,
            FacilityProductionDecisionKind kind,
            String reason) {
        return new FacilityProductionDecision(
                completion.observationId(),
                facilityId,
                interfaceId,
                receiptId,
                kind,
                reason);
    }

    private record StackIdentity(String itemId, String componentFingerprint) {}
}
