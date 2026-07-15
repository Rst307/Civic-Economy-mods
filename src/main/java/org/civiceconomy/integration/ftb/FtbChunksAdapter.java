package org.civiceconomy.integration.ftb;

import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.ClaimedChunkManager;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import dev.ftb.mods.ftbteams.api.Team;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    public List<FtbClaimFacts> claimsForTeam(UUID ftbTeamId) {
        if (ftbTeamId == null) {
            throw new IllegalArgumentException("FTB Team ID cannot be null");
        }
        return manager.getAllClaimedChunks().stream()
                .filter(claimed -> claimed.getTeamData().getTeam() != null)
                .filter(claimed -> claimed.getTeamData().getTeam().isValid())
                .filter(claimed -> claimed.getTeamData().getTeam().getId().equals(ftbTeamId))
                .map(claimed -> new FtbClaimFacts(
                        ftbTeamId,
                        claimed.getPos().dimension(),
                        claimed.getPos().chunkPos(),
                        claimed.getTimeClaimed(),
                        claimed.isForceLoaded(),
                        claimed.isActuallyForceLoaded()))
                .sorted(Comparator
                        .comparing((FtbClaimFacts claim) -> claim.dimension().location().toString())
                        .thenComparingInt(claim -> claim.chunkPos().x)
                        .thenComparingInt(claim -> claim.chunkPos().z))
                .toList();
    }
}
