package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredFacilityBaselineMachine(
        UUID baselineId,
        String machineKind,
        String dimensionId,
        int blockX,
        int blockY,
        int blockZ) {}
