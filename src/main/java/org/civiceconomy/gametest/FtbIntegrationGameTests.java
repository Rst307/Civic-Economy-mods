package org.civiceconomy.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ftb.mods.ftbchunks.api.ClaimResult;
import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
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
import org.civiceconomy.integration.ftb.FtbNationTeamDirectory;
import org.civiceconomy.integration.ftb.FtbTeamFacts;
import org.civiceconomy.integration.ftb.FtbTeamsAdapter;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.FtbTeamsNationProvider;
import org.civiceconomy.nation.CitizenshipCorrectionGraceRegistry;
import org.civiceconomy.nation.CitizenshipRegistry;
import org.civiceconomy.nation.JoinCitizenship;
import org.civiceconomy.nation.NationFacts;
import org.civiceconomy.nation.NationProvider;
import org.civiceconomy.nation.NationRegistry;
import org.civiceconomy.nation.RegisterNation;
import org.civiceconomy.nation.RegisteredNation;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;

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

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void ftbChunksAdapterSnapshotsOneTeamsClaimsInStableOrder(
            GameTestHelper helper) {
        Team team = FTBTeamsAPI.api()
                .getManager()
                .getTeamByID(TEST_SERVER_TEAM_ID)
                .orElseGet(() -> createServerTeam(helper));
        ChunkPos first = new ChunkPos(1200, 1201);
        ChunkPos second = new ChunkPos(1202, 1201);
        var source = helper.getLevel().getServer().createCommandSourceStack();
        var teamData = FTBChunksAPI.api().getManager().getOrCreateData(team);
        List<ChunkDimPos> positions = List.of(
                new ChunkDimPos(helper.getLevel().dimension(), second),
                new ChunkDimPos(helper.getLevel().dimension(), first));
        for (ChunkDimPos position : positions) {
            ClaimedChunk existing = FTBChunksAPI.api().getManager().getChunk(position);
            if (existing != null) {
                existing.unclaim(source, true);
            }
            helper.assertTrue(
                    teamData.claim(source, position, false).isSuccess(),
                    "real FTB Team snapshot claim fixture");
        }

        try {
            List<FtbClaimFacts> snapshot = FtbChunksAdapter.live().claimsForTeam(team.getId());
            List<FtbClaimFacts> fixtureClaims = snapshot.stream()
                    .filter(claim -> claim.chunkPos().equals(first) || claim.chunkPos().equals(second))
                    .toList();
            helper.assertValueEqual(2, fixtureClaims.size(), "Team claim snapshot size");
            helper.assertValueEqual(first, fixtureClaims.get(0).chunkPos(), "first stable Team claim");
            helper.assertValueEqual(second, fixtureClaims.get(1).chunkPos(), "second stable Team claim");
            helper.assertTrue(
                    fixtureClaims.stream().allMatch(claim -> claim.teamId().equals(team.getId())),
                    "Team claim snapshot identity");
        } finally {
            for (ChunkDimPos position : positions) {
                ClaimedChunk claimed = FTBChunksAPI.api().getManager().getChunk(position);
                if (claimed != null) {
                    claimed.unclaim(source, true);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void ftbNationProviderResolvesOnlyARegisteredTeam(GameTestHelper helper) {
        Team team = FTBTeamsAPI.api()
                .getManager()
                .getTeamByID(TEST_SERVER_TEAM_ID)
                .orElseGet(() -> createServerTeam(helper));
        FtbNationTeamDirectory teams = FtbNationTeamDirectory.live();
        Path temporaryDirectory = createTemporaryDirectory();

        try (CivicDatabase database = CivicDatabase.open(
                temporaryDirectory.resolve("civic.sqlite3"),
                new DatabaseIdentity(
                        UUID.randomUUID(), "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20"))) {
            NationRegistry registry = new NationRegistry(database, teams);
            CitizenshipRegistry citizenships =
                    new CitizenshipRegistry(database, Duration.ZERO, Clock.systemUTC());
            NationProvider provider = new FtbTeamsNationProvider(
                    registry,
                    citizenships,
                    new CitizenshipCorrectionGraceRegistry(database, Clock.systemUTC()),
                    teams);
            helper.assertTrue(
                    provider.findForCitizen(team.getOwner()).isEmpty(),
                    "an ordinary unregistered FTB Team is not a Nation");

            RegisteredNation registered = registry.register(new RegisterNation(
                    new ServiceIdentity("civiceconomy-gametest"),
                    "register-real-ftb-team-" + UUID.randomUUID(),
                    team.getId()));
            for (UUID memberId : team.getMembers()) {
                citizenships.join(new JoinCitizenship(
                        new ServiceIdentity("civiceconomy-gametest"),
                        "join-real-ftb-citizen-" + memberId + "-" + UUID.randomUUID(),
                        memberId,
                        registered.nationId()));
            }
            NationFacts facts = provider.find(registered.nationId()).orElseThrow();

            helper.assertFalse(
                    facts.nationId().value().equals(team.getId()), "NationId is not the FTB Team UUID");
            helper.assertValueEqual(team.getOwner(), facts.headId(), "temporary Nation head");
            helper.assertValueEqual(team.getMembers(), facts.citizens(), "temporary Nation citizens");
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
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

    private static Path createTemporaryDirectory() {
        try {
            return Files.createTempDirectory("civiceconomy-ftb-nation-gametest-");
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to create Civic FTB Nation GameTest directory", failure);
        }
    }

    private static void deleteTemporaryDirectory(Path directory) {
        try (var files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException failure) {
                    throw new IllegalStateException("Unable to delete Civic FTB Nation GameTest file " + path, failure);
                }
            });
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to clean Civic FTB Nation GameTest directory", failure);
        }
    }
}
