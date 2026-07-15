package org.civiceconomy.platform.neoforge;

import dev.architectury.event.CompoundEventResult;
import dev.ftb.mods.ftbchunks.api.ClaimResult;
import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.event.ClaimedChunkEvent;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.territory.TerritoryClaimPermitEventBridge;
import org.civiceconomy.territory.TerritoryClaimPosition;
import org.civiceconomy.territory.TerritoryClaimTarget;
import org.civiceconomy.territory.TerritoryForceLoadRestrictionMirror;

final class FtbTerritoryClaimPermitEvents {
    private static final String MISSING_PERMIT_RESULT =
            "civiceconomy:territory_claim_permit_required";
    private static final String SUSPENDED_FORCE_LOAD_RESULT =
            "civiceconomy:territory_force_load_suspended";

    private final CivicServerRuntime runtime;
    private final TerritoryClaimPermitEventBridge bridge;
    private final TerritoryForceLoadRestrictionMirror forceLoadRestrictions;

    FtbTerritoryClaimPermitEvents(
            CivicServerRuntime runtime,
            TerritoryClaimPermitEventBridge bridge,
            TerritoryForceLoadRestrictionMirror forceLoadRestrictions) {
        if (runtime == null || bridge == null || forceLoadRestrictions == null) {
            throw new IllegalArgumentException("FTB Territory event dependencies cannot be null");
        }
        this.runtime = runtime;
        this.bridge = bridge;
        this.forceLoadRestrictions = forceLoadRestrictions;
    }

    void register() {
        ClaimedChunkEvent.BEFORE_CLAIM.register(this::beforeClaim);
        ClaimedChunkEvent.AFTER_CLAIM.register(this::afterClaim);
        ClaimedChunkEvent.BEFORE_LOAD.register(this::beforeLoad);
    }

    private CompoundEventResult<ClaimResult> beforeLoad(
            CommandSourceStack source, ClaimedChunk chunk) {
        if (!runtime.territoryForceLoadRestrictionsReady()) {
            return CompoundEventResult.interruptFalse(
                    ClaimResult.customProblem(SUSPENDED_FORCE_LOAD_RESULT));
        }
        UUID ftbTeamId = chunk.getTeamData().getTeam().getId();
        var position = chunk.getPos();
        TerritoryClaimPosition target = new TerritoryClaimPosition(
                position.dimension().location().toString(), position.x(), position.z());
        return forceLoadRestrictions.blocks(ftbTeamId, target)
                ? CompoundEventResult.interruptFalse(
                        ClaimResult.customProblem(SUSPENDED_FORCE_LOAD_RESULT))
                : CompoundEventResult.pass();
    }

    private CompoundEventResult<ClaimResult> beforeClaim(
            CommandSourceStack source, ClaimedChunk chunk) {
        if (!runtime.territoryClaimAuthorizationReady()) {
            return CompoundEventResult.interruptFalse(
                    ClaimResult.customProblem(MISSING_PERMIT_RESULT));
        }
        UUID ftbTeamId = chunk.getTeamData().getTeam().getId();
        NationId nationId = runtime.nationForFtbTeam(ftbTeamId);
        if (nationId == null) {
            return CompoundEventResult.pass();
        }
        var target = target(source, chunk, nationId, ftbTeamId);
        if (target == null) {
            return CompoundEventResult.interruptFalse(
                    ClaimResult.customProblem(MISSING_PERMIT_RESULT));
        }
        return bridge.beforeClaim(target)
                ? CompoundEventResult.pass()
                : CompoundEventResult.interruptFalse(
                        ClaimResult.customProblem(MISSING_PERMIT_RESULT));
    }

    private void afterClaim(CommandSourceStack source, ClaimedChunk chunk) {
        UUID ftbTeamId = chunk.getTeamData().getTeam().getId();
        NationId nationId = runtime.nationForFtbTeam(ftbTeamId);
        var target = nationId == null ? null : target(source, chunk, nationId, ftbTeamId);
        if (target != null) {
            bridge.afterSuccessfulClaim(target);
        }
    }

    private TerritoryClaimTarget target(
            CommandSourceStack source,
            ClaimedChunk chunk,
            NationId nationId,
            UUID ftbTeamId) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return null;
        }
        var position = chunk.getPos();
        return new TerritoryClaimTarget(
                nationId,
                ftbTeamId,
                player.getUUID(),
                position.dimension().location().toString(),
                position.x(),
                position.z());
    }
}
