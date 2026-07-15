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
import net.minecraft.commands.CommandSourceStack;
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

    public boolean disableForceLoadIfOwned(
            UUID expectedFtbTeamId,
            ResourceKey<Level> dimension,
            ChunkPos chunkPos,
            CommandSourceStack source) {
        if (expectedFtbTeamId == null
                || dimension == null
                || chunkPos == null
                || source == null) {
            throw new IllegalArgumentException(
                    "FTB force-load enforcement cannot contain null values");
        }
        ChunkDimPos position = new ChunkDimPos(dimension, chunkPos);
        ClaimedChunk claimed = manager.getChunk(position);
        if (claimed == null) {
            throw new IllegalStateException(
                    "FTB force-load enforcement requires the exact current Claim");
        }
        Team team = claimed.getTeamData().getTeam();
        if (team == null
                || !team.isValid()
                || !team.getId().equals(expectedFtbTeamId)) {
            throw new SecurityException(
                    "FTB force-load enforcement Team does not own the exact current Claim");
        }
        if (!claimed.isForceLoaded()) {
            return false;
        }
        var result = claimed.getTeamData().unForceLoad(source, position, false);
        if (!result.isSuccess()) {
            throw new IllegalStateException("FTB Chunks rejected force-load disable");
        }
        ClaimedChunk updated = manager.getChunk(position);
        if (updated == null
                || updated.getTeamData().getTeam() == null
                || !updated.getTeamData().getTeam().isValid()
                || !updated.getTeamData().getTeam().getId().equals(expectedFtbTeamId)
                || updated.isForceLoaded()) {
            throw new IllegalStateException(
                    "FTB force-load disable did not preserve exact Claim ownership");
        }
        return true;
    }
}
