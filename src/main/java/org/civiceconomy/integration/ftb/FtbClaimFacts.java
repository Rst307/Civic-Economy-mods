package org.civiceconomy.integration.ftb;

import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

public record FtbClaimFacts(
        UUID teamId,
        ResourceKey<Level> dimension,
        ChunkPos chunkPos,
        long claimedAtEpochMillis,
        boolean forceLoadRequested,
        boolean actuallyForceLoaded) {
    public FtbClaimFacts {
        if (teamId == null || dimension == null || chunkPos == null) {
            throw new IllegalArgumentException("FTB claim facts cannot contain null values");
        }
        if (claimedAtEpochMillis < 0) {
            throw new IllegalArgumentException("FTB claim time cannot be negative");
        }
    }
}
