package org.civiceconomy.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ftb.mods.ftbchunks.api.ClaimResult;
import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.civiceconomy.CivicEconomy;
import org.civiceconomy.integration.ftb.FtbClaimFacts;
import org.civiceconomy.integration.ftb.FtbChunksAdapter;
import org.civiceconomy.integration.ftb.FtbTeamFacts;
import org.civiceconomy.integration.ftb.FtbTeamsAdapter;

@GameTestHolder(CivicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FtbIntegrationGameTests {
    private static final UUID TEST_SERVER_TEAM_ID =
            UUID.fromString("4ec0f873-c904-48d2-8672-a4cad220fefd");

    private FtbIntegrationGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void ftbTeamsAdapterReturnsExactServerTeamFacts(GameTestHelper helper) {
        Team team = FTBTeamsAPI.api()
                .getManager()
                .getTeamByID(TEST_SERVER_TEAM_ID)
                .orElseGet(() -> createServerTeam(helper));

        FtbTeamFacts facts = FtbTeamsAdapter.live()
                .find(TEST_SERVER_TEAM_ID)
                .orElseThrow(() -> new IllegalStateException("FTB Teams adapter did not find the real test team"));

        helper.assertValueEqual(team.getId(), facts.teamId(), "FTB Team UUID");
        helper.assertValueEqual(team.getTeamId(), facts.effectiveTeamId(), "effective FTB Team UUID");
        helper.assertValueEqual(team.getOwner(), facts.ownerId(), "FTB Team owner UUID");
        helper.assertValueEqual(team.getMembers(), facts.members(), "FTB Team members");
        helper.assertTrue(facts.serverTeam(), "FTB server-team type");
        helper.assertFalse(facts.partyTeam(), "FTB party-team type");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void ftbChunksAdapterReturnsExactClaimFacts(GameTestHelper helper) {
        Team team = FTBTeamsAPI.api()
                .getManager()
                .getTeamByID(TEST_SERVER_TEAM_ID)
                .orElseGet(() -> createServerTeam(helper));
        ChunkPos chunkPos = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        ChunkDimPos chunkDimPos = new ChunkDimPos(helper.getLevel().dimension(), chunkPos);
        var source = helper.getLevel().getServer().createCommandSourceStack();
        ClaimedChunk existing = FTBChunksAPI.api().getManager().getChunk(chunkDimPos);
        if (existing != null) {
            existing.unclaim(source, true);
        }
        ClaimResult claim = FTBChunksAPI.api()
                .getManager()
                .getOrCreateData(team)
                .claim(source, chunkDimPos, false);
        helper.assertTrue(claim.isSuccess(), "real FTB Chunks claim fixture");

        try {
            FtbClaimFacts facts = FtbChunksAdapter.live()
                    .find(helper.getLevel().dimension(), chunkPos)
                    .orElseThrow(() -> new IllegalStateException("FTB Chunks adapter did not find the real claim"));
            helper.assertValueEqual(team.getId(), facts.teamId(), "claiming FTB Team UUID");
            helper.assertValueEqual(helper.getLevel().dimension(), facts.dimension(), "claim dimension");
            helper.assertValueEqual(chunkPos, facts.chunkPos(), "claim chunk position");
            helper.assertTrue(facts.claimedAtEpochMillis() > 0, "claim timestamp");
            helper.assertFalse(facts.forceLoadRequested(), "force-load requested state");
            helper.assertFalse(facts.actuallyForceLoaded(), "actual force-load state");
        } finally {
            ClaimedChunk claimed = FTBChunksAPI.api().getManager().getChunk(chunkDimPos);
            if (claimed != null) {
                claimed.unclaim(source, true);
            }
        }
        helper.succeed();
    }

    private static Team createServerTeam(GameTestHelper helper) {
        try {
            return FTBTeamsAPI.api()
                    .getManager()
                    .createServerTeam(
                            helper.getLevel().getServer().createCommandSourceStack(),
                            "Civic Adapter GameTest",
                            "Stable FTB adapter fixture",
                            null,
                            TEST_SERVER_TEAM_ID);
        } catch (CommandSyntaxException failure) {
            throw new IllegalStateException("Unable to create the FTB Teams GameTest fixture", failure);
        }
    }
}
