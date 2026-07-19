package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredFacilityBaselineInventory(
        UUID baselineId,
        int slot,
        String itemId,
        String componentFingerprint,
        int count) {}
