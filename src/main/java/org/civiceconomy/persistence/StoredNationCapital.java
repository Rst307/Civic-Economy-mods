package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredNationCapital(
        UUID nationId,
        String dimensionId,
        int chunkX,
        int chunkZ,
        long establishedAtEpochMillis) {}
