package org.civiceconomy.integration.ftb;

import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.ClaimedChunkManager;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import dev.ftb.mods.ftbteams.api.Team;
import java.util.Optional;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

public final class FtbChunksAdapter {
    private final ClaimedChunkManager manager;

    private FtbChunksAdapter(ClaimedChunkManager manager) {
        this.manager = manager;
    }

    public static FtbChunksAdapter live() {
        FTBChunksAPI.API api = FTBChunksAPI.api();
        if (api == null || !api.isManagerLoaded()) {
            throw new FtbIntegrationUnavailableException("FTB Chunks server manager is not loaded");
        }
        return new FtbChunksAdapter(api.getManager());
    }

    public Optional<FtbClaimFacts> find(ResourceKey<Level> dimension, ChunkPos chunkPos) {
        ClaimedChunk claimed = manager.getChunk(new ChunkDimPos(dimension, chunkPos));
        if (claimed == null) {
            return Optional.empty();
        }
        Team team = claimed.getTeamData().getTeam();
        if (team == null || !team.isValid()) {
            return Optional.empty();
        }
        return Optional.of(new FtbClaimFacts(
                team.getId(),
                claimed.getPos().dimension(),
                claimed.getPos().chunkPos(),
                claimed.getTimeClaimed(),
                claimed.isForceLoaded(),
                claimed.isActuallyForceLoaded()));
    }
}
