package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredProductionInventoryChange(
        UUID sourceId,
        String role,
        int slot,
        String itemId,
        String componentFingerprint,
        int count) {}
