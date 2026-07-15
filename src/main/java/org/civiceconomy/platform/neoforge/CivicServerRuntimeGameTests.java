package org.civiceconomy.platform.neoforge;

import com.mojang.authlib.GameProfile;
import io.github.lightman314.lightmanscurrency.api.money.bank.BankAPI;
import io.github.lightman314.lightmanscurrency.api.money.coins.CoinAPI;
import io.github.lightman314.lightmanscurrency.api.money.MoneyAPI;
import io.github.lightman314.lightmanscurrency.api.money.value.builtin.CoinValue;
import io.github.lightman314.lightmanscurrency.common.data.CustomSaveData;
import io.github.lightman314.lightmanscurrency.common.data.types.BankDataCache;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamRank;
import dev.ftb.mods.ftbteams.data.AbstractTeamBase;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.data.ChunkTeamDataImpl;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.civiceconomy.CivicEconomy;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.ExternalPayment;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.integration.ftb.FtbNationTeamDirectory;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyFiscalAccounts;
import org.civiceconomy.integration.lightmanscurrency.FiscalAccountKind;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyPayments;
import org.civiceconomy.nation.OnlineTimeLedger;
import org.civiceconomy.nation.RecordOnlineTime;
import org.civiceconomy.nation.CreateNationApplication;
import org.civiceconomy.nation.NationApplicationId;
import org.civiceconomy.nation.NationApplicationRegistry;
import org.civiceconomy.nation.NationTeam;
import org.civiceconomy.nation.NationTeamDirectory;
import org.civiceconomy.nation.CitizenshipRegistry;
import org.civiceconomy.nation.JoinCitizenship;
import org.civiceconomy.nation.GrantNationFiscalPermission;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.FtbTeamsNationProvider;
import org.civiceconomy.nation.CitizenshipCorrectionGraceRegistry;
import org.civiceconomy.nation.NationRegistry;
import org.civiceconomy.nation.RegisterNation;
import org.civiceconomy.territory.IssueTerritoryClaimPermit;
import org.civiceconomy.territory.TerritoryClaimPermit;
import org.civiceconomy.territory.TerritoryClaimPermitRegistry;
import org.civiceconomy.territory.TerritoryClaimPermitState;
import org.civiceconomy.territory.AssessTerritoryFiscalValidity;
import org.civiceconomy.territory.OpenTerritoryMaintenanceCycle;
import org.civiceconomy.territory.SuspendTerritoryMaintenance;
import org.civiceconomy.territory.TerritoryClaimPosition;
import org.civiceconomy.territory.TerritoryMaintenancePriority;
import org.civiceconomy.territory.TerritoryMaintenanceRegistry;
import org.civiceconomy.persistence.StoredMintRecipeIngredient;
import org.civiceconomy.persistence.StoredMintMaterialStack;
import org.civiceconomy.persistence.StoredNationalIssuanceQuotaAllocation;
import org.civiceconomy.mint.MintBatch;

@GameTestHolder(CivicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CivicServerRuntimeGameTests {
    private CivicServerRuntimeGameTests() {}

    @GameTest(
            template = "empty",
            timeoutTicks = 12_000,
            batch = "runtime-mint-issuance")
    public static void runtimeMintBatchCompletesRealLcIssuanceExactlyOnce(
            GameTestHelper helper) {
        if (MintProcessRestartDrill.verifying()) {
            verifyMintProcessRestart(helper);
            return;
        }
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-runtime-mint"),
                ClientInformation.createDefault());
        player.setPos(helper.absolutePos(new BlockPos(8, 0, 8)).getCenter());
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
        Team team = createHeadOwnedFtbTeamFixture(player);
        claimCapital(helper, team, player);
        NationTeam teamSnapshot = new NationTeam(team.getId(), team.getOwner(), team.getMembers());
        UUID periodId = UUID.randomUUID();
        UUID recipeId = UUID.randomUUID();
        UUID mintId = UUID.randomUUID();
        String requestId = "runtime-mint-" + UUID.randomUUID();
        String cancellationRequestId = "runtime-mint-cancel-" + UUID.randomUUID();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicReference<UUID> batchId = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean issuanceCompleted = new AtomicBoolean();
        AtomicReference<UUID> issuanceBatchId = new AtomicReference<>();
        java.time.Instant now = java.time.Instant.now();
        java.time.Clock setupClock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC);
        ChunkPos chunk = new ChunkPos(player.blockPosition());

        CivicServerRuntime runtime = CivicServerRuntime.current();
        runtime.submitDatabase(database -> {
                    NationRegistry nations = new NationRegistry(database, snapshot(teamSnapshot));
                    var nation = nations.register(new RegisterNation(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "runtime-mint-nation-" + UUID.randomUUID(),
                            team.getId()));
                    CitizenshipRegistry citizenships = new CitizenshipRegistry(
                            database, java.time.Duration.ofDays(7L), setupClock);
                    citizenships.join(new JoinCitizenship(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "runtime-mint-citizenship-" + UUID.randomUUID(),
                            player.getUUID(),
                            nation.nationId()));
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            citizenships,
                            new CitizenshipCorrectionGraceRegistry(database, setupClock),
                            snapshot(teamSnapshot));
                    new NationFiscalAuthorityRegistry(database, provider, setupClock)
                            .grant(new GrantNationFiscalPermission(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "runtime-mint-authority-" + UUID.randomUUID(),
                                    nation.nationId(),
                                    player.getUUID(),
                                    player.getUUID(),
                                    NationFiscalPermission.MANAGE_ISSUANCE,
                                    "Real runtime Mint GameTest"));
                    database.publishIssuanceQuotaPeriod(
                            periodId,
                            "issuance-gametest",
                            "runtime-mint-period-" + UUID.randomUUID(),
                            now.minusSeconds(60L).toEpochMilli(),
                            now.plusSeconds(3600L).toEpochMilli(),
                            10_000L,
                            1_000L,
                            java.util.List.of(new StoredNationalIssuanceQuotaAllocation(
                                    nation.nationId().value(), 1_000L)),
                            "Runtime Mint period",
                            now.minusSeconds(30L).toEpochMilli());
                    database.activateNationalIssuanceQuota(
                            UUID.randomUUID(),
                            "issuance-gametest",
                            "runtime-mint-activation-" + UUID.randomUUID(),
                            periodId,
                            nation.nationId().value(),
                            player.getUUID(),
                            1_000L,
                            "Runtime Mint activation",
                            now.minusSeconds(20L).toEpochMilli());
                    database.publishMintRecipeVersion(
                            recipeId,
                            "civiceconomy-mint",
                            "runtime-mint-recipe-" + UUID.randomUUID(),
                            1,
                            java.util.List.of(new StoredMintRecipeIngredient(
                                    0, "EXACT_ITEM", "minecraft:diamond", 1L, 100L)),
                             1L,
                            "Runtime Mint recipe",
                            now.minusSeconds(10L).toEpochMilli());
                    database.registerMint(
                            mintId,
                            "civiceconomy-mint",
                            "runtime-mint-facility-" + UUID.randomUUID(),
                            nation.nationId().value(),
                            player.level().dimension().location().toString(),
                            player.blockPosition().getX(),
                            player.blockPosition().getY(),
                            player.blockPosition().getZ(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            false,
                            recipeId,
                            player.getUUID(),
                            "Runtime Mint facility",
                            now.minusSeconds(5L).toEpochMilli());
                    TerritoryMaintenanceRegistry territory =
                            new TerritoryMaintenanceRegistry(database, setupClock);
                    var activeCycle = database.territoryMaintenanceCycleAt(now.toEpochMilli());
                    var cycle = activeCycle == null
                            ? territory.openCycle(new OpenTerritoryMaintenanceCycle(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "runtime-mint-cycle-" + UUID.randomUUID(),
                                    now.minusSeconds(60L),
                                    now.plusSeconds(3600L)))
                            : territory.cycle(activeCycle.cycleId());
                    territory.assess(new AssessTerritoryFiscalValidity(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "runtime-mint-assessment-" + UUID.randomUUID(),
                            cycle.cycleId(),
                            nation.nationId(),
                            team.getId(),
                            player.level().dimension().location().toString(),
                            chunk.x,
                            chunk.z,
                            0L,
                            "Runtime Mint effective territory"));
                    territory.settleZeroCostAssessments(new SuspendTerritoryMaintenance(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "runtime-mint-settlement-" + UUID.randomUUID(),
                            cycle.cycleId(),
                            nation.nationId(),
                            "Runtime Mint zero-cost settlement"));
                    return nation.nationId();
                })
                .thenCompose(nationId -> {
                    CompletableFuture<Void> treasuryCreated = new CompletableFuture<>();
                    helper.getLevel().getServer().execute(() -> {
                        try {
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel()).create(
                                    new AccountId("nation:" + nationId.value() + ":treasury"),
                                    FiscalAccountKind.NATIONAL_TREASURY,
                                    "Runtime Mint Treasury");
                            treasuryCreated.complete(null);
                        } catch (Throwable failure) {
                            treasuryCreated.completeExceptionally(failure);
                        }
                    });
                    return treasuryCreated;
                })
                .thenCompose(ignored -> runtime.startMintBatch(
                        player, requestId, mintId, periodId, 300L))
                .thenCompose(batch -> {
                    batchId.set(batch.batchId());
                    return runtime.startMintBatch(player, requestId, mintId, periodId, 300L);
                })
                .thenCompose(replay -> runtime.startMintBatch(
                                player, requestId, mintId, periodId, 301L)
                        .handle((changed, failure) -> {
                            helper.assertTrue(
                                    failure != null,
                                    "changed-payload runtime Mint replay must fail closed");
                            return replay;
                        }))
                .thenCompose(issuanceBatch -> {
                    issuanceBatchId.set(issuanceBatch.batchId());
                    CompletableFuture<MintBatch> finished = new CompletableFuture<>();
                    helper.runAfterDelay(10L, () -> awaitMintCommit(
                            helper, runtime, issuanceBatch, finished, 8));
                    return finished;
                })
                .thenCompose(issued -> runtime.submitDatabase(database -> {
                    helper.assertValueEqual(
                            "COMMITTED",
                            database.mintBatch(issued.batchId()).state(),
                            "runtime Mint issuance state");
                    helper.assertValueEqual(
                            300L,
                            database.nationalIssuanceQuota(periodId, issued.nationId().value())
                                    .usedMinorUnits(),
                            "runtime Mint used quota");
                    helper.assertValueEqual(
                            1L,
                            database.monetarySupplyEvents().stream()
                                    .filter(event -> event.requestId().equals("issue:" + issued.requestId()))
                                    .count(),
                            "one runtime Mint issuance event");
                    return issued;
                }))
                .whenComplete((cancelled, failure) -> helper.getLevel().getServer().execute(() -> {
                    if (failure != null) {
                        asyncFailure.set(failure);
                    } else {
                        helper.assertValueEqual(
                                0,
                                player.getInventory().getItem(0).getCount(),
                                "consumed runtime Mint diamonds");
                        helper.assertValueEqual(
                                300L,
                                LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                        .balance(new AccountId(
                                                "nation:" + cancelled.nationId().value() + ":treasury"))
                                        .minorUnits(),
                                "real LC runtime Mint Treasury credit");
                        completed.set(true);
                        issuanceCompleted.set(true);
                        unclaimCapital(helper, player);
                    }
                }));

        helper.succeedWhen(() -> {
            Throwable failure = asyncFailure.get();
            helper.assertTrue(
                    failure == null,
                    failure == null
                            ? "runtime Mint async state"
                            : "runtime Mint async failure: " + failure.getMessage());
            helper.assertTrue(batchId.get() != null, "runtime Mint Batch ID");
            helper.assertTrue(completed.get(), "runtime Mint start/cancel completion");
            helper.assertTrue(issuanceBatchId.get() != null, "runtime issuance Mint Batch ID");
            helper.assertTrue(issuanceCompleted.get(), "runtime Mint issuance completion");
        });
    }

    private static void verifyMintProcessRestart(GameTestHelper helper) {
        MintProcessRestartDrill.Marker marker =
                MintProcessRestartDrill.readMarker(helper.getLevel().getServer());
        AtomicBoolean queryQueued = new AtomicBoolean();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        CivicServerRuntime runtime = CivicServerRuntime.current();

        helper.succeedWhen(() -> {
            Throwable failure = asyncFailure.get();
            helper.assertTrue(
                    failure == null,
                    failure == null
                            ? "Mint process restart async state"
                            : "Mint process restart failure: " + failure.getMessage());
            if (!completed.get() && queryQueued.compareAndSet(false, true)) {
                runtime.triggerMintRecovery();
                runtime.mintBatchStatus(marker.batchId())
                        .whenComplete((status, statusFailure) ->
                                helper.getLevel().getServer().execute(() -> {
                                    try {
                                        if (statusFailure != null) {
                                            throw new IllegalStateException(
                                                    "Unable to read restarted Mint Batch",
                                                    statusFailure);
                                        }
                                        if (!"COMMITTED".equals(status.batch().state())) {
                                            queryQueued.set(false);
                                            return;
                                        }
                                        helper.assertValueEqual(
                                                "COMMITTED",
                                                status.issuance().state(),
                                                "restarted Mint issuance state");
                                        helper.assertValueEqual(
                                                marker.operationId(),
                                                status.issuance().operationId(),
                                                "restarted Mint operation identity");
                                        helper.assertValueEqual(
                                                marker.amountMinorUnits(),
                                                LightmansCurrencyFiscalAccounts
                                                        .forLevel(helper.getLevel())
                                                        .balance(new AccountId(
                                                                marker.treasuryAccount()))
                                                        .minorUnits(),
                                                "restarted Mint exact Treasury credit");
                                        var custody = MintMaterialCustodyData
                                                .get(helper.getLevel().getServer())
                                                .operation(marker.batchId());
                                        helper.assertTrue(
                                                custody != null,
                                                "restarted Mint custody operation");
                                        helper.assertValueEqual(
                                                "CONSUMED",
                                                custody.state(),
                                                "restarted Mint custody state");
                                        helper.assertValueEqual(
                                                marker.operationId(),
                                                custody.consumptionOperationId(),
                                                "restarted Mint consumption identity");
                                        completed.set(true);
                                    } catch (Throwable verificationFailure) {
                                        asyncFailure.set(verificationFailure);
                                    }
                                }));
            }
            helper.assertTrue(completed.get(), "matched Mint process restart completion");
        });
    }

    private static void awaitMintCommit(
            GameTestHelper helper,
            CivicServerRuntime runtime,
            MintBatch batch,
            CompletableFuture<MintBatch> finished,
            int attemptsRemaining) {
        if (finished.isDone()) {
            return;
        }
        if (attemptsRemaining <= 0) {
            finished.completeExceptionally(
                    new IllegalStateException("Mint issuance did not commit after recovery"));
            return;
        }
        runtime.recoverMintBatchesNowForGameTest();
        helper.runAfterDelay(10L, () -> runtime
                .submitDatabase(database -> database.mintBatch(batch.batchId()))
                .whenComplete((stored, recoveryFailure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (recoveryFailure != null) {
                                finished.completeExceptionally(recoveryFailure);
                            } else if ("COMMITTED".equals(stored.state())) {
                                finished.complete(batch);
                            } else {
                                awaitMintCommit(
                                        helper,
                                        runtime,
                                        batch,
                                        finished,
                                        attemptsRemaining - 1);
                            }
                        })));
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void playerLifecyclePersistsObservedOnlineTimeOffThread(GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-runtime-test"),
                ClientInformation.createDefault());
        Path civicDirectory = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy");
        Path databaseFile = civicDirectory.resolve("civic.sqlite3");
        Path identityFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve("civiceconomy_world_identity.dat");
        helper.assertTrue(Files.isRegularFile(databaseFile), "world-bound Civic SQLite file");
        helper.assertTrue(Files.isRegularFile(identityFile), "persisted Civic world identity");

        CivicServerRuntime.current().observeLogin(player);
        helper.runAfterDelay(2L, () -> {
            CivicServerRuntime.current().observeLogout(player);
            helper.succeedWhen(() -> assertPersistedInterval(helper, databaseFile, player));
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void fiscalAdministrationCommandsAreRegistered(GameTestHelper helper) {
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        var civic = dispatcher.getRoot().getChild("civic");
        var economy = civic.getChild("economy");
        var service = dispatcher.getRoot()
                .getChild("civic")
                .getChild("economy")
                .getChild("admin")
                .getChild("service");
        var backup = dispatcher.getRoot()
                .getChild("civic")
                .getChild("economy")
                .getChild("admin")
                .getChild("backup");
        helper.assertValueEqual(
                Set.of("list", "show", "register", "grant", "revoke", "disable", "enable"),
                service.getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "trusted fiscal administration command actions");
        helper.assertValueEqual(
                Set.of("status", "recovery", "correction"),
                dispatcher.getRoot().getChild("civic").getChild("economy")
                        .getChild("admin").getChild("mint").getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "trusted Mint recovery command actions");
        helper.assertValueEqual(
                Set.of("apply", "status"),
                dispatcher.getRoot().getChild("civic").getChild("economy")
                        .getChild("admin").getChild("mint").getChild("correction")
                        .getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "trusted Monetary Stock Correction command actions");
        helper.assertValueEqual(
                Set.of("recovery"),
                economy.getChild("admin").getChild("withdrawal").getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "trusted Treasury Withdrawal inspection actions");
        helper.assertValueEqual(
                Set.of("status"),
                economy.getChild("admin").getChild("withdrawal")
                        .getChild("recovery").getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "read-only Treasury Withdrawal recovery actions");
        helper.assertValueEqual(
                Set.of("status", "trigger", "restore"),
                backup.getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "trusted asynchronous database backup command actions");
        helper.assertValueEqual(
                Set.of("status", "stage", "cancel"),
                backup.getChild("restore").getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "trusted restart-only database restore command actions");
        helper.assertValueEqual(
                Set.of(
                        "apply",
                        "status",
                        "cancel",
                        "activate",
                        "population",
                        "role",
                        "mint",
                        "territory",
                        "treasury"),
                economy.getChild("nation").getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "server-authoritative Nation Application command actions");
        helper.assertValueEqual(
                Set.of("allowance", "prepare", "cancel", "restore"),
                economy.getChild("nation")
                        .getChild("territory")
                        .getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "server-authoritative Territory Claim command actions");
        helper.assertValueEqual(
                Set.of("start", "status", "cancel"),
                economy.getChild("nation")
                        .getChild("mint")
                        .getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "server-authoritative Mint command actions");
        helper.assertValueEqual(
                Set.of("destroy", "withdraw"),
                economy.getChild("nation")
                        .getChild("treasury")
                        .getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "server-authoritative National Treasury command actions");
        helper.assertValueEqual(
                Set.of("approve", "approval", "policy", "requestId"),
                economy.getChild("nation")
                        .getChild("treasury")
                        .getChild("withdraw")
                        .getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "server-authoritative Treasury Withdrawal approval actions");
        helper.assertValueEqual(
                Set.of("schedule", "status"),
                economy.getChild("nation")
                        .getChild("treasury")
                        .getChild("withdraw")
                        .getChild("policy")
                        .getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "future-effective Treasury Withdrawal policy actions");
        helper.assertValueEqual(
                Set.of("list", "status"),
                economy.getChild("nation")
                        .getChild("treasury")
                        .getChild("withdraw")
                        .getChild("approval")
                        .getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "Nation-scoped Treasury Withdrawal approval inspection actions");
        if (CivicDebugWorldCommands.dedicatedStartupPermit()) {
            helper.assertValueEqual(
                    Set.of("status", "enable"),
                    civic.getChild("debug").getChildren().stream()
                            .map(node -> node.getName())
                            .collect(Collectors.toSet()),
                    "startup-permitted dedicated DEBUG WORLD command actions");
        } else {
            helper.assertTrue(
                    civic.getChild("debug") == null,
                    "dedicated GameTest server omits DEBUG WORLD writes by default");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void consoleCanRegisterFiscalServiceOffThread(GameTestHelper helper) {
        String serviceIdentity = "command-gametest-" + UUID.randomUUID();
        String requestId = "register-command-gametest-" + UUID.randomUUID();
        var server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "civic economy admin service register " + serviceIdentity
                        + " civiceconomy " + requestId + " \"Command GameTest Service\""
                        + " Verify audited asynchronous registration");
        Path databaseFile = server.getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");

        helper.succeedWhen(() -> assertFiscalServiceRegistered(
                helper,
                databaseFile,
                serviceIdentity,
                "civiceconomy",
                requestId));
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void consoleAppliesAuditedMonetaryStockCorrectionOffThread(
            GameTestHelper helper) {
        String requestId = "stock-correction-command-" + UUID.randomUUID();
        String evidenceReference = "incident-ticket:CE-GT-" + UUID.randomUUID();
        String reason = "Independent LC Treasury evidence confirms untracked issuance";
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicReference<UUID> incidentId = new AtomicReference<>();
        AtomicReference<Integer> commandResult = new AtomicReference<>();
        AtomicBoolean commandStarted = new AtomicBoolean();
        var server = helper.getLevel().getServer();
        Path databaseFile = server.getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");

        CivicServerRuntime.current()
                .submitDatabase(CivicServerRuntimeGameTests::prepareStockCorrectionIncident)
                .whenComplete((preparedIncidentId, failure) -> server.execute(() -> {
                    if (failure != null) {
                        asyncFailure.set(failure);
                        return;
                    }
                    try {
                        incidentId.set(preparedIncidentId);
                        commandResult.set(server.getCommands().getDispatcher().execute(
                                "civic economy admin mint correction apply "
                                        + preparedIncidentId + " " + requestId + " "
                                        + "\"" + evidenceReference + "\" " + reason,
                                server.createCommandSourceStack()));
                        commandStarted.set(true);
                    } catch (Throwable commandFailure) {
                        asyncFailure.set(commandFailure);
                    }
                }));

        helper.succeedWhen(() -> {
            Throwable failure = asyncFailure.get();
            helper.assertTrue(
                    failure == null,
                    failure == null
                            ? "Monetary Stock Correction command async state"
                            : "Monetary Stock Correction command failure: "
                                    + failure.getMessage());
            helper.assertTrue(commandStarted.get(), "Monetary Stock Correction command queued");
            helper.assertValueEqual(
                    1,
                    commandResult.get(),
                    "Monetary Stock Correction command accepted");
            UUID observedIncidentId = incidentId.get();
            helper.assertTrue(observedIncidentId != null, "prepared Mint Recovery Incident");
            assertMonetaryStockCorrection(
                    helper,
                    databaseFile,
                    observedIncidentId,
                    requestId,
                    evidenceReference,
                    reason);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void consoleCreatesAuditedOnlineDatabaseBackupOffThread(GameTestHelper helper) {
        String requestId = "backup-command-gametest-" + UUID.randomUUID();
        var server = helper.getLevel().getServer();
        Path civicDirectory = server.getWorldPath(LevelResource.ROOT).resolve("civiceconomy");
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "civic economy admin backup trigger " + requestId
                        + " GameTest manual online backup");

        helper.succeedWhen(() ->
                assertDatabaseBackupCommitted(helper, civicDirectory, requestId));
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void consoleStagesAndCancelsRestartOnlyDatabaseRestoreOffThread(
            GameTestHelper helper) {
        String stageRequestId = "restore-stage-gametest-" + UUID.randomUUID();
        String cancelRequestId = "restore-cancel-gametest-" + UUID.randomUUID();
        AtomicBoolean stageIssued = new AtomicBoolean();
        AtomicBoolean cancelIssued = new AtomicBoolean();
        var server = helper.getLevel().getServer();
        Path civicDirectory = server.getWorldPath(LevelResource.ROOT).resolve("civiceconomy");
        Path databaseFile = civicDirectory.resolve("civic.sqlite3");

        helper.succeedWhen(() -> {
            if (!stageIssued.get()) {
                UUID backupOperationId = latestCommittedBackupOperation(databaseFile);
                helper.assertTrue(backupOperationId != null, "committed restore source backup");
                if (stageIssued.compareAndSet(false, true)) {
                    server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack(),
                            "civic economy admin backup restore stage " + backupOperationId
                                    + " " + stageRequestId + " GameTest staged restart restore");
                }
                helper.assertTrue(false, "waiting for staged restore");
            }

            RestoreCommandRow restore = databaseRestoreByRequest(databaseFile, stageRequestId);
            helper.assertTrue(restore != null, "durable staged database restore row");
            if (!cancelIssued.get()) {
                helper.assertValueEqual("STAGED", restore.state(), "staged restore state");
                helper.assertValueEqual(1L, restore.stagedAuditCount(), "single staged audit");
                helper.assertTrue(
                        Files.isRegularFile(civicDirectory.resolve("restore/pending.properties")),
                        "external staged restore manifest");
                if (cancelIssued.compareAndSet(false, true)) {
                    server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack(),
                            "civic economy admin backup restore cancel " + restore.operationId()
                                    + " " + cancelRequestId + " GameTest cancelled restart restore");
                }
                helper.assertTrue(false, "waiting for cancelled restore");
            }

            restore = databaseRestoreByRequest(databaseFile, stageRequestId);
            helper.assertValueEqual("CANCELLED", restore.state(), "cancelled restore state");
            helper.assertValueEqual(1L, restore.cancelledAuditCount(), "single cancelled audit");
            helper.assertTrue(
                    !Files.exists(civicDirectory.resolve("restore/pending.properties")),
                    "cancelled restore manifest is no longer pending");
            helper.assertTrue(
                    Files.isRegularFile(civicDirectory
                            .resolve("restore/history")
                            .resolve(restore.operationId() + "-cancelled.properties")),
                    "cancelled restore manifest history");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void consoleSchedulesTerritoryPolicyOffThread(GameTestHelper helper) {
        String requestId = "territory-policy-command-" + UUID.randomUUID();
        String pricingRequestId = "territory-pricing-command-" + UUID.randomUUID();
        String maintenanceRequestId = "territory-maintenance-command-" + UUID.randomUUID();
        long effectiveAt = java.time.Instant.now().plusSeconds(60L).toEpochMilli();
        var server = helper.getLevel().getServer();
        Path databaseFile = server.getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        long maintenanceEffectiveAt = Math.max(
                effectiveAt, nextTerritoryMaintenancePolicyBoundary(databaseFile));
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "civic economy admin territory policy schedule 9 4 " + effectiveAt
                        + " " + requestId + " GameTest territory policy");
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "civic economy admin territory pricing schedule 100 50 " + effectiveAt
                        + " " + pricingRequestId + " GameTest territory pricing");
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "civic economy admin territory maintenance schedule 604800000 75 15000 25 30 1209600000 6000 "
                        + maintenanceEffectiveAt + " " + maintenanceRequestId
                        + " GameTest territory maintenance");

        helper.succeedWhen(() -> {
            assertTerritoryPolicyScheduled(helper, databaseFile, requestId, effectiveAt);
            assertTerritoryPricingScheduled(
                    helper, databaseFile, pricingRequestId, effectiveAt);
            assertTerritoryMaintenancePolicyScheduled(
                    helper, databaseFile, maintenanceRequestId, maintenanceEffectiveAt);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void ftbTeamHeadCanCreateNationApplicationOffThread(GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-founding-test"),
                ClientInformation.createDefault());
        Team team = createHeadOwnedFtbTeamFixture(player);
        UUID teamId = team.getId();
        helper.assertValueEqual(player.getUUID(), team.getOwner(), "FTB founding team head");
        helper.assertValueEqual(teamId, team.getId(), "FTB founding team ID");
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withSuppressedOutput(),
                "civic economy nation apply");
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withSuppressedOutput(),
                "civic economy nation apply");
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");

        helper.succeedWhen(() -> assertNationApplicationCreated(
                helper, databaseFile, teamId, player.getUUID()));
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void ftbTeamHeadCancelsNationApplicationOffThread(GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-cancellation-test"),
                ClientInformation.createDefault());
        Team team = createHeadOwnedFtbTeamFixture(player);
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        AtomicBoolean cancellationStarted = new AtomicBoolean();
        AtomicReference<PendingApplicationRow> observedApplication = new AtomicReference<>();

        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withSuppressedOutput(),
                "civic economy nation apply");
        helper.succeedWhen(() -> {
            PendingApplicationRow application = observedApplication.get();
            if (application == null) {
                application = pendingApplication(databaseFile, team.getId());
                if (application != null) {
                    observedApplication.compareAndSet(null, application);
                }
            }
            helper.assertTrue(application != null, "PENDING Nation Application before cancellation");
            if (cancellationStarted.compareAndSet(false, true)) {
                helper.getLevel().getServer().getCommands().performPrefixedCommand(
                        player.createCommandSourceStack().withSuppressedOutput(),
                        "civic economy nation cancel GameTest cancellation");
            }
            assertNationApplicationCancelled(
                    helper, databaseFile, application.applicationId(), player.getUUID());
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void runtimeExpiresDueNationApplicationOffThread(GameTestHelper helper) {
        UUID teamId = UUID.randomUUID();
        UUID headId = UUID.randomUUID();
        NationTeam team = new NationTeam(teamId, headId, Set.of(headId));
        NationTeamDirectory teams = snapshot(team);
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        AtomicReference<NationApplicationId> applicationId = new AtomicReference<>();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        java.time.Instant appliedAt = java.time.Instant.now();

        CivicServerRuntime.current()
                .submitDatabase(database -> new NationApplicationRegistry(
                                database,
                                teams,
                                java.time.Clock.fixed(appliedAt, java.time.ZoneOffset.UTC))
                        .create(new CreateNationApplication(
                                new ServiceIdentity("civiceconomy-gametest"),
                                "runtime-expiry-" + UUID.randomUUID(),
                                teamId,
                                headId,
                                appliedAt.plusMillis(250))))
                .whenComplete((application, failure) -> {
                    if (failure == null) {
                        applicationId.set(application.applicationId());
                    } else {
                        asyncFailure.set(failure);
                    }
                });

        helper.succeedWhen(() -> {
            helper.assertTrue(asyncFailure.get() == null, "automatic expiry setup");
            NationApplicationId observed = applicationId.get();
            helper.assertTrue(observed != null, "scheduled-expiry Nation Application");
            assertNationApplicationAutomaticallyExpired(
                    helper, databaseFile, observed, headId);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void runtimeStartsCorrectionGraceForRealFtbDepartureOffThread(
            GameTestHelper helper) {
        ServerPlayer owner = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-reconciliation-owner"),
                ClientInformation.createDefault());
        Team team = createHeadOwnedFtbTeamFixture(owner);
        UUID citizenId = UUID.randomUUID();
        ((AbstractTeamBase) team).addMember(citizenId, TeamRank.MEMBER);
        team.markDirty();
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        java.time.Instant joinedAt = java.time.Instant.now();
        AtomicBoolean departed = new AtomicBoolean();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");

        CivicServerRuntime.current()
                .submitDatabase(database -> {
                    NationRegistry nations = new NationRegistry(database, snapshot(teamSnapshot));
                    var nation = nations.register(new RegisterNation(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "reconciliation-nation-" + UUID.randomUUID(),
                            teamSnapshot.teamId()));
                    new CitizenshipRegistry(
                                    database,
                                    java.time.Duration.ofDays(7),
                                    java.time.Clock.fixed(joinedAt, java.time.ZoneOffset.UTC))
                            .join(new JoinCitizenship(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "reconciliation-citizen-" + UUID.randomUUID(),
                                    citizenId,
                                    nation.nationId()));
                    return nation.nationId();
                })
                .whenComplete((nationId, failure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                                return;
                            }
                            ((AbstractTeamBase) team).removeMember(citizenId);
                            team.markDirty();
                            departed.set(true);
                        }));

        helper.succeedWhen(() -> {
            helper.assertTrue(asyncFailure.get() == null, "Citizenship reconciliation setup");
            helper.assertTrue(departed.get(), "real FTB member departure");
            assertCitizenshipCorrectionGraceStarted(
                    helper, databaseFile, team.getId(), citizenId);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void realFtbNationHeadGrantsOwnFiscalPermissionOffThread(
            GameTestHelper helper) {
        ServerPlayer head = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-fiscal-role-head"),
                ClientInformation.createDefault());
        Team team = createHeadOwnedFtbTeamFixture(head);
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        java.time.Instant joinedAt = java.time.Instant.now();
        AtomicBoolean commandStarted = new AtomicBoolean();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");

        CivicServerRuntime.current()
                .submitDatabase(database -> {
                    NationRegistry nations = new NationRegistry(database, snapshot(teamSnapshot));
                    var nation = nations.register(new RegisterNation(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "fiscal-role-nation-" + UUID.randomUUID(),
                            teamSnapshot.teamId()));
                    new CitizenshipRegistry(
                                    database,
                                    java.time.Duration.ofDays(7),
                                    java.time.Clock.fixed(joinedAt, java.time.ZoneOffset.UTC))
                            .join(new JoinCitizenship(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "fiscal-role-head-" + UUID.randomUUID(),
                                    head.getUUID(),
                                    nation.nationId()));
                    return nation.nationId();
                })
                .whenComplete((nationId, failure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                                return;
                            }
                            try {
                                int result = helper.getLevel()
                                        .getServer()
                                        .getCommands()
                                        .getDispatcher()
                                        .execute(
                                                "civic economy nation role grant " + head.getUUID()
                                                        + " APPROVE_BUDGET GameTest appointment",
                                                head.createCommandSourceStack().withSuppressedOutput());
                                helper.assertValueEqual(
                                        1, result, "Nation fiscal role grant command result");
                                commandStarted.set(true);
                            } catch (Throwable commandFailure) {
                                asyncFailure.set(commandFailure);
                            }
                        }));

        helper.succeedWhen(() -> {
            Throwable failure = asyncFailure.get();
            helper.assertTrue(
                    failure == null,
                    failure == null
                            ? "Nation fiscal role async state"
                            : "Nation fiscal role async failure: " + failure.getMessage());
            helper.assertTrue(commandStarted.get(), "Nation fiscal role command started");
            assertNationFiscalPermissionGranted(
                    helper, databaseFile, team.getId(), head.getUUID());
        });
    }

    @GameTest(
            template = "empty",
            timeoutTicks = 600,
            batch = "runtime-permanent-destruction-command")
    public static void playerDestroysExactNationalTreasuryThroughRealLcExactlyOnce(
            GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-treasury-destruction"),
                ClientInformation.createDefault());
        Team team = createHeadOwnedFtbTeamFixture(player);
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        String unauthorizedRequestId = "unauthorized-destruction-" + UUID.randomUUID();
        String requestId = "player-destruction-" + UUID.randomUUID();
        String forgedSource = "nation:22222222-2222-2222-2222-222222222222:treasury";
        String reason = "GameTest Permanent Destruction; attempted-source=" + forgedSource;
        AtomicReference<org.civiceconomy.nation.NationId> nationId = new AtomicReference<>();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicReference<Throwable> unauthorizedFailure = new AtomicReference<>();
        AtomicReference<Throwable> changedReplayFailure = new AtomicReference<>();
        AtomicBoolean setupReady = new AtomicBoolean();
        AtomicBoolean unauthorizedFinished = new AtomicBoolean();
        AtomicBoolean permissionReady = new AtomicBoolean();
        AtomicBoolean commandStarted = new AtomicBoolean();
        AtomicBoolean changedReplayFinished = new AtomicBoolean();
        AtomicLong issuanceAfterSeed = new AtomicLong();
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        CivicServerRuntime runtime = CivicServerRuntime.current();
        java.time.Instant now = java.time.Instant.now();
        java.time.Clock setupClock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC);

        runtime.submitDatabase(database -> {
                    NationRegistry nations = new NationRegistry(database, snapshot(teamSnapshot));
                    var nation = nations.register(new RegisterNation(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "player-destruction-nation-" + UUID.randomUUID(),
                            teamSnapshot.teamId()));
                    CitizenshipRegistry citizenships = new CitizenshipRegistry(
                            database, java.time.Duration.ofDays(7), setupClock);
                    citizenships.join(new JoinCitizenship(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "player-destruction-citizenship-" + UUID.randomUUID(),
                            player.getUUID(),
                            nation.nationId()));
                    long issuanceBefore = database.cumulativeNetIssuanceMinorUnits();
                    database.confirmMonetarySupplyChange(
                            UUID.randomUUID(),
                            "civiceconomy-gametest",
                            "player-destruction-issuance-" + UUID.randomUUID(),
                            "ISSUANCE",
                            1_000L,
                            "mint-batch:player-destruction:" + UUID.randomUUID(),
                            "Seed player Permanent Destruction capacity",
                            now.toEpochMilli(),
                            Math.addExact(issuanceBefore, 10_000L));
                    issuanceAfterSeed.set(database.cumulativeNetIssuanceMinorUnits());
                    return nation.nationId();
                })
                .whenComplete((registeredNationId, failure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                                return;
                            }
                            try {
                                nationId.set(registeredNationId);
                                fundTreasury(
                                        helper,
                                        registeredNationId,
                                        "Player Permanent Destruction Treasury",
                                        1_000L);
                                setupReady.set(true);
                            } catch (Throwable setupFailure) {
                                asyncFailure.set(setupFailure);
                            }
                        }));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Permanent Destruction setup");
                    helper.assertTrue(setupReady.get(), "Permanent Destruction setup complete");
                })
                .thenExecute(() -> runtime.destroyNationalTreasury(
                                player,
                                unauthorizedRequestId,
                                100L,
                                "Must fail before fiscal or LC writes")
                        .whenComplete((ignored, failure) -> {
                            if (failure == null) {
                                asyncFailure.set(new AssertionError(
                                        "Unauthorized Permanent Destruction unexpectedly succeeded"));
                            } else {
                                unauthorizedFailure.set(rootCause(failure));
                            }
                            unauthorizedFinished.set(true);
                        }))
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Unauthorized Permanent Destruction");
                    helper.assertTrue(
                            unauthorizedFinished.get(),
                            "unauthorized Permanent Destruction completed");
                    helper.assertTrue(
                            unauthorizedFailure.get() instanceof SecurityException,
                            "unauthorized request fails at exact Nation fiscal permission");
                    AccountId treasury = new AccountId(
                            "nation:" + nationId.get().value() + ":treasury");
                    helper.assertValueEqual(
                            1_000L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(treasury)
                                    .minorUnits(),
                            "unauthorized request leaves LC Treasury unchanged");
                    helper.assertTrue(
                            permanentDestructionByRequest(databaseFile, unauthorizedRequestId) == null,
                            "unauthorized request creates no Permanent Destruction operation");
                })
                .thenExecute(() -> runtime.submitDatabase(database -> {
                            NationRegistry nations = new NationRegistry(
                                    database, snapshot(teamSnapshot));
                            CitizenshipRegistry citizenships = new CitizenshipRegistry(
                                    database, java.time.Duration.ofDays(7), setupClock);
                            var provider = new FtbTeamsNationProvider(
                                    nations,
                                    citizenships,
                                    new CitizenshipCorrectionGraceRegistry(database, setupClock),
                                    snapshot(teamSnapshot));
                            new NationFiscalAuthorityRegistry(database, provider, setupClock)
                                    .grant(new GrantNationFiscalPermission(
                                            new ServiceIdentity("civiceconomy-gametest"),
                                            "player-destruction-authority-" + UUID.randomUUID(),
                                            nationId.get(),
                                            player.getUUID(),
                                            player.getUUID(),
                                            NationFiscalPermission.MANAGE_ISSUANCE,
                                            "Authorize real player Permanent Destruction"));
                            return null;
                        })
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                            } else {
                                permissionReady.set(true);
                            }
                        }))
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Permanent Destruction permission");
                    helper.assertTrue(permissionReady.get(), "Permanent Destruction permission ready");
                })
                .thenExecute(() -> {
                    try {
                        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
                        String command = "civic economy nation treasury destroy "
                                + requestId + " 300 " + reason;
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        command,
                                        player.createCommandSourceStack().withSuppressedOutput()),
                                "Permanent Destruction command result");
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        command,
                                        player.createCommandSourceStack().withSuppressedOutput()),
                                "Permanent Destruction replay command result");
                        commandStarted.set(true);
                    } catch (Throwable commandFailure) {
                        asyncFailure.set(commandFailure);
                    }
                })
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Permanent Destruction command");
                    helper.assertTrue(commandStarted.get(), "Permanent Destruction command started");
                    PermanentDestructionRow operation =
                            permanentDestructionByRequest(databaseFile, requestId);
                    helper.assertTrue(operation != null, "persisted Permanent Destruction operation");
                    helper.assertValueEqual(
                            "COMMITTED", operation.state(), "Permanent Destruction state");
                    helper.assertValueEqual(
                            "nation:" + nationId.get().value() + ":treasury",
                            operation.sourceAccount(),
                            "server-derived exact National Treasury source");
                    helper.assertValueEqual(
                            300L, operation.amountMinorUnits(), "Permanent Destruction amount");
                    helper.assertValueEqual(
                            "player:" + player.getUUID(),
                            operation.operatorIdentity(),
                            "server-derived player operator audit");
                    helper.assertValueEqual(
                            reason, operation.reason(), "immutable Permanent Destruction reason");
                    AccountId treasury = new AccountId(operation.sourceAccount());
                    helper.assertValueEqual(
                            700L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(treasury)
                                    .minorUnits(),
                            "real LC National Treasury destruction exactly once");
                    helper.assertValueEqual(
                            issuanceAfterSeed.get() - 300L,
                            cumulativeNetIssuance(databaseFile),
                            "cumulative net issuance decreases exactly once");
                })
                .thenExecute(() -> runtime.destroyNationalTreasury(
                                player, requestId, 301L, reason)
                        .whenComplete((ignored, failure) -> {
                            if (failure == null) {
                                asyncFailure.set(new AssertionError(
                                        "Changed Permanent Destruction replay unexpectedly succeeded"));
                            } else {
                                changedReplayFailure.set(rootCause(failure));
                            }
                            changedReplayFinished.set(true);
                        }))
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Changed Permanent Destruction replay");
                    helper.assertTrue(
                            changedReplayFinished.get(),
                            "changed Permanent Destruction replay completed");
                    helper.assertTrue(
                            changedReplayFailure.get()
                                    instanceof org.civiceconomy.fiscal.IdempotencyConflictException,
                            "changed replay fails with an idempotency conflict");
                    PermanentDestructionRow operation =
                            permanentDestructionByRequest(databaseFile, requestId);
                    helper.assertValueEqual(
                            300L, operation.amountMinorUnits(), "replay amount remains immutable");
                    helper.assertValueEqual(
                            700L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(new AccountId(operation.sourceAccount()))
                                    .minorUnits(),
                            "changed replay does not repeat LC destruction");
                })
                .thenSucceed();
    }

    @GameTest(
            template = "empty",
            timeoutTicks = 800,
            batch = "runtime-treasury-withdrawal-command")
    public static void playerWithdrawsExactNationalTreasuryCashThroughRealLcExactlyOnce(
            GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-treasury-withdrawal"),
                ClientInformation.createDefault());
        Team team = createHeadOwnedFtbTeamFixture(player);
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        String unauthorizedRequestId = "unauthorized-withdrawal-" + UUID.randomUUID();
        String requestId = "player-withdrawal-" + UUID.randomUUID();
        String forgedSource = "nation:99999999-9999-9999-9999-999999999999:treasury";
        String reason = "GameTest Treasury Withdrawal; attempted-source=" + forgedSource;
        AtomicReference<org.civiceconomy.nation.NationId> nationId = new AtomicReference<>();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicReference<Throwable> unauthorizedFailure = new AtomicReference<>();
        AtomicReference<Throwable> capacityFailure = new AtomicReference<>();
        AtomicReference<Throwable> changedReplayFailure = new AtomicReference<>();
        AtomicBoolean setupReady = new AtomicBoolean();
        AtomicBoolean unauthorizedFinished = new AtomicBoolean();
        AtomicBoolean permissionReady = new AtomicBoolean();
        AtomicBoolean capacityAttemptFinished = new AtomicBoolean();
        AtomicBoolean commandStarted = new AtomicBoolean();
        AtomicBoolean changedReplayFinished = new AtomicBoolean();
        AtomicLong issuanceBeforeWithdrawal = new AtomicLong();
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        CivicServerRuntime runtime = CivicServerRuntime.current();
        java.time.Instant now = java.time.Instant.now();
        java.time.Clock setupClock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC);

        runtime.submitDatabase(database -> {
                    NationRegistry nations = new NationRegistry(database, snapshot(teamSnapshot));
                    var nation = nations.register(new RegisterNation(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "player-withdrawal-nation-" + UUID.randomUUID(),
                            teamSnapshot.teamId()));
                    new CitizenshipRegistry(
                                    database, java.time.Duration.ofDays(7), setupClock)
                            .join(new JoinCitizenship(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "player-withdrawal-citizenship-" + UUID.randomUUID(),
                                    player.getUUID(),
                                    nation.nationId()));
                    issuanceBeforeWithdrawal.set(
                            database.cumulativeNetIssuanceMinorUnits());
                    return nation.nationId();
                })
                .whenComplete((registeredNationId, failure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                                return;
                            }
                            try {
                                nationId.set(registeredNationId);
                                fundTreasury(
                                        helper,
                                        registeredNationId,
                                        "Player Treasury Withdrawal Treasury",
                                        1_000L);
                                setupReady.set(true);
                            } catch (Throwable setupFailure) {
                                asyncFailure.set(setupFailure);
                            }
                        }));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Treasury Withdrawal setup");
                    helper.assertTrue(setupReady.get(), "Treasury Withdrawal setup complete");
                })
                .thenExecute(() -> runtime.withdrawNationalTreasury(
                                player,
                                unauthorizedRequestId,
                                100L,
                                "Must fail before fiscal or LC writes")
                        .whenComplete((ignored, failure) -> {
                            if (failure == null) {
                                asyncFailure.set(new AssertionError(
                                        "Unauthorized Treasury Withdrawal unexpectedly succeeded"));
                            } else {
                                unauthorizedFailure.set(rootCause(failure));
                            }
                            unauthorizedFinished.set(true);
                        }))
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Unauthorized Treasury Withdrawal");
                    helper.assertTrue(
                            unauthorizedFinished.get(),
                            "unauthorized Treasury Withdrawal completed");
                    helper.assertTrue(
                            unauthorizedFailure.get() instanceof SecurityException,
                            "unauthorized request fails at exact Nation fiscal permission");
                    AccountId treasury = new AccountId(
                            "nation:" + nationId.get().value() + ":treasury");
                    helper.assertValueEqual(
                            1_000L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(treasury)
                                    .minorUnits(),
                            "unauthorized request leaves LC Treasury unchanged");
                    helper.assertTrue(
                            treasuryWithdrawalByRequest(databaseFile, unauthorizedRequestId) == null,
                            "unauthorized request creates no Treasury Withdrawal operation");
                })
                .thenExecute(() -> runtime.submitDatabase(database -> {
                            NationRegistry nations = new NationRegistry(
                                    database, snapshot(teamSnapshot));
                            CitizenshipRegistry citizenships = new CitizenshipRegistry(
                                    database, java.time.Duration.ofDays(7), setupClock);
                            var provider = new FtbTeamsNationProvider(
                                    nations,
                                    citizenships,
                                    new CitizenshipCorrectionGraceRegistry(database, setupClock),
                                    snapshot(teamSnapshot));
                            new NationFiscalAuthorityRegistry(database, provider, setupClock)
                                    .grant(new GrantNationFiscalPermission(
                                            new ServiceIdentity("civiceconomy-gametest"),
                                            "player-withdrawal-authority-" + UUID.randomUUID(),
                                            nationId.get(),
                                            player.getUUID(),
                                            player.getUUID(),
                                            NationFiscalPermission.MANAGE_WITHDRAWAL,
                                            "Authorize real player Treasury Withdrawal"));
                            return null;
                        })
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                            } else {
                                permissionReady.set(true);
                            }
                        }))
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Treasury Withdrawal permission");
                    helper.assertTrue(permissionReady.get(), "Treasury Withdrawal permission ready");
                })
                .thenExecute(() -> {
                    for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                        player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
                    }
                    player.getInventory().setChanged();
                    runtime.withdrawNationalTreasury(player, requestId, 300L, reason)
                            .whenComplete((ignored, failure) -> {
                                if (failure == null) {
                                    asyncFailure.set(new AssertionError(
                                            "Full-inventory Treasury Withdrawal unexpectedly succeeded"));
                                } else {
                                    capacityFailure.set(rootCause(failure));
                                }
                                capacityAttemptFinished.set(true);
                            });
                })
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Full inventory Treasury Withdrawal");
                    helper.assertTrue(
                            capacityAttemptFinished.get(),
                            "full inventory Treasury Withdrawal completed");
                    helper.assertTrue(
                            capacityFailure.get()
                                    instanceof org.civiceconomy.integration.lightmanscurrency
                                            .InsufficientTreasuryWithdrawalInventoryCapacityException,
                            "full inventory fails at exact LC capacity simulation");
                    TreasuryWithdrawalRow prepared =
                            treasuryWithdrawalByRequest(databaseFile, requestId);
                    helper.assertTrue(prepared != null, "capacity failure leaves durable operation");
                    helper.assertValueEqual("PREPARED", prepared.state(), "pending withdrawal state");
                    helper.assertValueEqual(
                            1_000L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(new AccountId(prepared.sourceAccount()))
                                    .minorUnits(),
                            "capacity failure does not debit the Treasury");
                })
                .thenExecute(() -> {
                    player.getInventory().clearContent();
                    player.getInventory().setChanged();
                    try {
                        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
                        String command = "civic economy nation treasury withdraw "
                                + requestId + " 300 " + reason;
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        command,
                                        player.createCommandSourceStack().withSuppressedOutput()),
                                "Treasury Withdrawal command result");
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        command,
                                        player.createCommandSourceStack().withSuppressedOutput()),
                                "Treasury Withdrawal replay command result");
                        commandStarted.set(true);
                    } catch (Throwable commandFailure) {
                        asyncFailure.set(commandFailure);
                    }
                })
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Treasury Withdrawal command");
                    helper.assertTrue(commandStarted.get(), "Treasury Withdrawal command started");
                    TreasuryWithdrawalRow operation =
                            treasuryWithdrawalByRequest(databaseFile, requestId);
                    helper.assertTrue(operation != null, "persisted Treasury Withdrawal operation");
                    helper.assertValueEqual("COMMITTED", operation.state(), "withdrawal state");
                    helper.assertValueEqual(
                            nationId.get().value().toString(),
                            operation.nationId(),
                            "server-derived formal Nation");
                    helper.assertValueEqual(
                            "nation:" + nationId.get().value() + ":treasury",
                            operation.sourceAccount(),
                            "server-derived exact National Treasury source");
                    helper.assertValueEqual(
                            player.getUUID().toString(),
                            operation.actorPlayerId(),
                            "server-derived player operator audit");
                    helper.assertValueEqual(300L, operation.amountMinorUnits(), "withdrawal amount");
                    helper.assertValueEqual(reason, operation.reason(), "immutable withdrawal purpose");
                    helper.assertValueEqual(
                            700L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(new AccountId(operation.sourceAccount()))
                                    .minorUnits(),
                            "real LC National Treasury debited exactly once");
                    helper.assertValueEqual(
                            300L,
                            playerInventoryMoney(player),
                            "exact real LC coin value delivered to player inventory");
                    helper.assertValueEqual(
                            issuanceBeforeWithdrawal.get(),
                            cumulativeNetIssuance(databaseFile),
                            "Treasury Withdrawal does not change cumulative net issuance");
                })
                .thenExecute(() -> runtime.withdrawNationalTreasury(
                                player, requestId, 301L, reason)
                        .whenComplete((ignored, failure) -> {
                            if (failure == null) {
                                asyncFailure.set(new AssertionError(
                                        "Changed Treasury Withdrawal replay unexpectedly succeeded"));
                            } else {
                                changedReplayFailure.set(rootCause(failure));
                            }
                            changedReplayFinished.set(true);
                        }))
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Changed Treasury Withdrawal replay");
                    helper.assertTrue(
                            changedReplayFinished.get(),
                            "changed Treasury Withdrawal replay completed");
                    helper.assertTrue(
                            changedReplayFailure.get()
                                    instanceof org.civiceconomy.fiscal.IdempotencyConflictException,
                            "changed replay fails with an idempotency conflict");
                    helper.assertValueEqual(
                            700L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(new AccountId(
                                            "nation:" + nationId.get().value() + ":treasury"))
                                    .minorUnits(),
                            "changed replay does not repeat Treasury debit");
                    helper.assertValueEqual(
                            300L,
                            playerInventoryMoney(player),
                            "changed replay does not repeat cash delivery");
                })
                .thenSucceed();
    }

    @GameTest(
            template = "empty",
            timeoutTicks = 800,
            batch = "runtime-treasury-withdrawal-approval-command")
    public static void distinctCitizensApproveGovernedTreasuryWithdrawalBeforeRealLcDebit(
            GameTestHelper helper) {
        ServerPlayer initiator = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-withdrawal-initiator"),
                ClientInformation.createDefault());
        ServerPlayer approver = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-withdrawal-approver"),
                ClientInformation.createDefault());
        Team team = createHeadOwnedFtbTeamFixture(initiator);
        TeamManagerImpl.INSTANCE.getPersonalTeamForPlayerID(approver.getUUID());
        ((AbstractTeamBase) team).addMember(approver.getUUID(), TeamRank.MEMBER);
        team.markDirty();
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        String withdrawalRequestId = "governed-withdrawal-" + UUID.randomUUID();
        String approvalRequestId = "governed-approval-" + UUID.randomUUID();
        String policyRequestId = "future-withdrawal-policy-" + UUID.randomUUID();
        String reason = "GameTest governed Treasury Withdrawal";
        AtomicReference<org.civiceconomy.nation.NationId> nationId = new AtomicReference<>();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicBoolean setupReady = new AtomicBoolean();
        AtomicBoolean policyCommandStarted = new AtomicBoolean();
        AtomicBoolean withdrawalCommandStarted = new AtomicBoolean();
        AtomicBoolean inspectionCommandsStarted = new AtomicBoolean();
        AtomicBoolean approvalCommandStarted = new AtomicBoolean();
        AtomicBoolean recoveryInspectionStarted = new AtomicBoolean();
        AtomicBoolean replayCommandStarted = new AtomicBoolean();
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        CivicServerRuntime runtime = CivicServerRuntime.current();
        Instant now = Instant.now();
        Clock setupClock = Clock.fixed(now, ZoneOffset.UTC);

        runtime.submitDatabase(database -> {
                    NationRegistry nations = new NationRegistry(database, snapshot(teamSnapshot));
                    var nation = nations.register(new RegisterNation(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "governed-withdrawal-nation-" + UUID.randomUUID(),
                            teamSnapshot.teamId()));
                    CitizenshipRegistry citizenships = new CitizenshipRegistry(
                            database, java.time.Duration.ofDays(7), setupClock);
                    citizenships.join(new JoinCitizenship(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "governed-withdrawal-initiator-citizenship-" + UUID.randomUUID(),
                            initiator.getUUID(),
                            nation.nationId()));
                    citizenships.join(new JoinCitizenship(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "governed-withdrawal-approver-citizenship-" + UUID.randomUUID(),
                            approver.getUUID(),
                            nation.nationId()));
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            citizenships,
                            new CitizenshipCorrectionGraceRegistry(database, setupClock),
                            snapshot(teamSnapshot));
                    NationFiscalAuthorityRegistry authorities =
                            new NationFiscalAuthorityRegistry(database, provider, setupClock);
                    authorities.grant(new GrantNationFiscalPermission(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "grant-governed-withdrawal-initiator-" + UUID.randomUUID(),
                            nation.nationId(),
                            initiator.getUUID(),
                            initiator.getUUID(),
                            NationFiscalPermission.MANAGE_WITHDRAWAL,
                            "Initiate governed Treasury Withdrawal"));
                    authorities.grant(new GrantNationFiscalPermission(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "grant-withdrawal-policy-manager-" + UUID.randomUUID(),
                            nation.nationId(),
                            initiator.getUUID(),
                            initiator.getUUID(),
                            NationFiscalPermission.MANAGE_APPROVAL_POLICY,
                            "Manage governed Withdrawal policy"));
                    authorities.grant(new GrantNationFiscalPermission(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "grant-governed-withdrawal-approver-" + UUID.randomUUID(),
                            nation.nationId(),
                            initiator.getUUID(),
                            approver.getUUID(),
                            NationFiscalPermission.MANAGE_WITHDRAWAL,
                            "Approve governed Treasury Withdrawal"));
                    new org.civiceconomy.fiscal.WithdrawalApprovalPolicyRegistry(
                                    database,
                                    Clock.fixed(
                                            now.minus(java.time.Duration.ofDays(2)),
                                            ZoneOffset.UTC))
                            .schedule(new org.civiceconomy.fiscal
                                    .ScheduleWithdrawalApprovalPolicy(
                                    new ServiceIdentity("civiceconomy-gametest-governance"),
                                    "active-two-person-policy-" + UUID.randomUUID(),
                                    nation.nationId(),
                                    initiator.getUUID(),
                                    List.of(new org.civiceconomy.fiscal
                                            .WithdrawalApprovalTier(
                                            MoneyAmount.ZERO, 2)),
                                    now.minus(java.time.Duration.ofDays(1)),
                                    "Require two distinct Citizens"));
                    return nation.nationId();
                })
                .whenComplete((registeredNationId, failure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                                return;
                            }
                            try {
                                nationId.set(registeredNationId);
                                initiator.getInventory().clearContent();
                                fundTreasury(
                                        helper,
                                        registeredNationId,
                                        "Governed Withdrawal Treasury",
                                        1_000L);
                                setupReady.set(true);
                            } catch (Throwable setupFailure) {
                                asyncFailure.set(setupFailure);
                            }
                        }));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "governed Withdrawal setup");
                    helper.assertTrue(setupReady.get(), "governed Withdrawal setup ready");
                })
                .thenExecute(() -> {
                    try {
                        long effectiveAt = now.plus(java.time.Duration.ofDays(7)).toEpochMilli();
                        String command = "civic economy nation treasury withdraw policy schedule "
                                + policyRequestId + " " + effectiveAt
                                + " 500 3 Future governed Withdrawal policy";
                        helper.assertValueEqual(
                                1,
                                helper.getLevel().getServer().getCommands().getDispatcher()
                                        .execute(
                                                command,
                                                initiator.createCommandSourceStack()
                                                        .withSuppressedOutput()),
                                "Withdrawal policy schedule command result");
                        policyCommandStarted.set(true);
                    } catch (Throwable failure) {
                        asyncFailure.set(failure);
                    }
                })
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Withdrawal policy command");
                    helper.assertTrue(policyCommandStarted.get(), "policy command started");
                    helper.assertTrue(
                            withdrawalApprovalPolicyExists(databaseFile, policyRequestId),
                            "future-effective Withdrawal policy persisted");
                })
                .thenExecute(() -> {
                    try {
                        String command = "civic economy nation treasury withdraw "
                                + withdrawalRequestId + " 300 " + reason;
                        helper.assertValueEqual(
                                1,
                                helper.getLevel().getServer().getCommands().getDispatcher()
                                        .execute(
                                                command,
                                                initiator.createCommandSourceStack()
                                                        .withSuppressedOutput()),
                                "governed Withdrawal initiation command result");
                        withdrawalCommandStarted.set(true);
                    } catch (Throwable failure) {
                        asyncFailure.set(failure);
                    }
                })
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "governed Withdrawal initiation");
                    helper.assertTrue(withdrawalCommandStarted.get(), "initiation command started");
                    TreasuryWithdrawalApprovalRow approval =
                            treasuryWithdrawalApprovalByRequest(
                                    databaseFile, withdrawalRequestId);
                    helper.assertTrue(approval != null, "durable Withdrawal approval request");
                    helper.assertValueEqual("PENDING", approval.state(), "pending approval state");
                    helper.assertValueEqual(2, approval.requiredApprovals(), "pinned approval count");
                    helper.assertValueEqual(1, approval.approvalCount(), "initiator approval count");
                    helper.assertTrue(
                            treasuryWithdrawalByRequest(databaseFile, withdrawalRequestId) == null,
                            "pending approval creates no Withdrawal operation");
                    helper.assertValueEqual(
                            1_000L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(new AccountId(
                                            "nation:" + nationId.get().value() + ":treasury"))
                                    .minorUnits(),
                            "pending approval does not debit real LC");
                })
                .thenExecute(() -> {
                    try {
                        TreasuryWithdrawalApprovalRow approval =
                                treasuryWithdrawalApprovalByRequest(
                                        databaseFile, withdrawalRequestId);
                        var dispatcher = helper.getLevel().getServer()
                                .getCommands().getDispatcher();
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        "civic economy nation treasury withdraw policy status",
                                        initiator.createCommandSourceStack()
                                                .withSuppressedOutput()),
                                "Withdrawal policy status command result");
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        "civic economy nation treasury withdraw approval list",
                                        approver.createCommandSourceStack()
                                                .withSuppressedOutput()),
                                "Withdrawal approval list command result");
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        "civic economy nation treasury withdraw approval status "
                                                + approval.approvalRequestId(),
                                        approver.createCommandSourceStack()
                                                .withSuppressedOutput()),
                                "Withdrawal approval status command result");
                        inspectionCommandsStarted.set(true);
                    } catch (Throwable failure) {
                        asyncFailure.set(failure);
                    }
                })
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Withdrawal inspection commands");
                    helper.assertTrue(
                            inspectionCommandsStarted.get(),
                            "Withdrawal inspection commands started");
                    TreasuryWithdrawalApprovalRow approval =
                            treasuryWithdrawalApprovalByRequest(
                                    databaseFile, withdrawalRequestId);
                    helper.assertValueEqual(
                            "PENDING", approval.state(),
                            "read-only player inspection preserves pending state");
                })
                .thenExecute(() -> runtime.submitDatabase(database ->
                                new org.civiceconomy.fiscal
                                                .TreasuryWithdrawalApprovalRegistry(
                                                database, setupClock)
                                        .approve(new org.civiceconomy.fiscal
                                                .ApproveTreasuryWithdrawal(
                                                org.civiceconomy.fiscal
                                                        .TreasuryWithdrawalFiscalServiceProvisioner
                                                        .SERVICE_IDENTITY,
                                                approvalRequestId,
                                                treasuryWithdrawalApprovalByRequest(
                                                                databaseFile,
                                                                withdrawalRequestId)
                                                        .approvalRequestId(),
                                                approver.getUUID(),
                                                "Independent Treasury officer approval")))
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                            } else {
                                approvalCommandStarted.set(true);
                            }
                        }))
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "distinct Withdrawal approval");
                    helper.assertTrue(approvalCommandStarted.get(), "approval persisted");
                    TreasuryWithdrawalApprovalRow approval =
                            treasuryWithdrawalApprovalByRequest(
                                    databaseFile, withdrawalRequestId);
                    helper.assertValueEqual("APPROVED", approval.state(), "approved decision state");
                    helper.assertValueEqual(2, approval.approvalCount(), "two distinct approvals");
                    helper.assertTrue(
                            treasuryWithdrawalByRequest(databaseFile, withdrawalRequestId) == null,
                            "approval alone creates no LC operation before execution");
                    helper.assertValueEqual(
                            1_000L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(new AccountId(
                                            "nation:" + nationId.get().value() + ":treasury"))
                                    .minorUnits(),
                            "approval alone does not debit real LC");
                })
                .thenExecute(() -> {
                    try {
                        helper.assertValueEqual(
                                1,
                                helper.getLevel().getServer().getCommands().getDispatcher()
                                        .execute(
                                                "civic economy admin withdrawal recovery status",
                                                helper.getLevel().getServer()
                                                        .createCommandSourceStack()
                                                        .withSuppressedOutput()),
                                "Withdrawal recovery inspection command result");
                        recoveryInspectionStarted.set(true);
                    } catch (Throwable failure) {
                        asyncFailure.set(failure);
                    }
                })
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(
                            helper, asyncFailure, "Withdrawal recovery inspection");
                    helper.assertTrue(
                            recoveryInspectionStarted.get(),
                            "Withdrawal recovery inspection started");
                    TreasuryWithdrawalApprovalRow approval =
                            treasuryWithdrawalApprovalByRequest(
                                    databaseFile, withdrawalRequestId);
                    helper.assertValueEqual(
                            "APPROVED", approval.state(),
                            "read-only OP inspection preserves approved state");
                    helper.assertTrue(
                            treasuryWithdrawalByRequest(databaseFile, withdrawalRequestId) == null,
                            "OP inspection does not prepare a Withdrawal operation");
                })
                .thenExecute(() -> {
                    try {
                        String command = "civic economy nation treasury withdraw "
                                + withdrawalRequestId + " 300 " + reason;
                        helper.assertValueEqual(
                                1,
                                helper.getLevel().getServer().getCommands().getDispatcher()
                                        .execute(
                                                command,
                                                initiator.createCommandSourceStack()
                                                        .withSuppressedOutput()),
                                "approved Withdrawal recovery command result");
                        replayCommandStarted.set(true);
                    } catch (Throwable failure) {
                        asyncFailure.set(failure);
                    }
                })
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "approved Withdrawal recovery");
                    helper.assertTrue(replayCommandStarted.get(), "recovery command started");
                    TreasuryWithdrawalApprovalRow approval =
                            treasuryWithdrawalApprovalByRequest(
                                    databaseFile, withdrawalRequestId);
                    TreasuryWithdrawalRow operation =
                            treasuryWithdrawalByRequest(databaseFile, withdrawalRequestId);
                    helper.assertValueEqual("EXECUTED", approval.state(), "approval executed state");
                    helper.assertValueEqual("COMMITTED", operation.state(), "committed Withdrawal state");
                    helper.assertValueEqual(
                            700L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(new AccountId(operation.sourceAccount()))
                                    .minorUnits(),
                            "approved real LC Treasury debit");
                    helper.assertValueEqual(
                            300L,
                            playerInventoryMoney(initiator),
                            "approved physical LC cash delivery");
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void debugWorldHeadActivatesClaimedCapitalOffThread(GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-activation-test"),
                ClientInformation.createDefault());
        player.setPos(helper.absolutePos(BlockPos.ZERO).getCenter());
        Team team = createHeadOwnedFtbTeamFixture(player);
        helper.assertValueEqual(
                team.getId(),
                FtbNationTeamDirectory.live()
                        .findOwnedTeamForPlayer(player.getUUID())
                        .orElseThrow(() -> new IllegalStateException("FTB fixture owner is unresolved"))
                        .teamId(),
                "live FTB founding team ownership");
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        AtomicBoolean activationStarted = new AtomicBoolean();
        AtomicBoolean claimCleaned = new AtomicBoolean();
        AtomicReference<PendingApplicationRow> observedApplication = new AtomicReference<>();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();

        int applyResult;
        try {
            applyResult = helper.getLevel().getServer().getCommands().getDispatcher().execute(
                    "civic economy nation apply",
                    player.createCommandSourceStack().withSuppressedOutput());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
            throw new IllegalStateException("Nation Application command rejected", failure);
        }
        helper.assertValueEqual(1, applyResult, "Nation Application command result");
        helper.succeedWhen(() -> {
            Throwable failure = asyncFailure.get();
            helper.assertTrue(
                    failure == null,
                    failure == null
                            ? "Nation activation async state"
                            : "Nation activation async failure: " + failure.getMessage());
            PendingApplicationRow pending = observedApplication.get();
            if (pending == null) {
                pending = pendingApplication(databaseFile, team.getId());
                if (pending != null) {
                    observedApplication.compareAndSet(null, pending);
                }
            }
            helper.assertTrue(pending != null, "PENDING Nation Application before activation");
            PendingApplicationRow application = pending;
            if (activationStarted.compareAndSet(false, true)) {
                CivicServerRuntime.current()
                        .submitDatabase(database -> {
                            new OnlineTimeLedger(database).record(new RecordOnlineTime(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "activation-command-evidence-" + UUID.randomUUID(),
                                    player.getUUID(),
                                    application.createdAtEpochMillis(),
                                    application.createdAtEpochMillis()
                                            + java.time.Duration.ofHours(1).toMillis()));
                            return null;
                        })
                        .whenComplete((ignored, evidenceFailure) ->
                                helper.getLevel().getServer().execute(() -> {
                                    if (evidenceFailure != null) {
                                        asyncFailure.set(evidenceFailure);
                                        return;
                                    }
                                    try {
                                        claimCapital(helper, team, player);
                                        CivicDebugWorldData.get(helper.getLevel().getServer()).enable();
                                        helper.getLevel().getServer().getCommands().performPrefixedCommand(
                                                player.createCommandSourceStack().withSuppressedOutput(),
                                                "civic economy nation activate");
                                    } catch (Throwable activationFailure) {
                                        asyncFailure.set(activationFailure);
                                    }
                                }));
            }
            AccountId treasuryAccount = activatedTreasury(
                    helper, databaseFile, application.applicationId(), player.getUUID(), player);
            helper.assertValueEqual(
                    0L,
                    LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                            .balance(treasuryAccount)
                            .minorUnits(),
                    "activated real LC National Treasury balance");
            if (claimCleaned.compareAndSet(false, true)) {
                unclaimCapital(helper, player);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void realFtbClaimConsumesPermitOnlyAfterSuccessfulMutation(
            GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-permit-claim-test"),
                ClientInformation.createDefault());
        player.setPos(helper.absolutePos(new BlockPos(8, 0, 8)).getCenter());
        Team team = createHeadOwnedFtbTeamFixture(player);
        ChunkDimPos position = new ChunkDimPos(
                player.level().dimension(), new ChunkPos(player.blockPosition()));
        var manager = FTBChunksAPI.api().getManager();
        ClaimedChunk existing = manager.getChunk(position);
        if (existing != null) {
            existing.unclaim(player.createCommandSourceStack(), true);
        }
        var teamData = manager.getOrCreateData(team);
        teamData.setExtraClaimChunks(Math.max(100, teamData.getExtraClaimChunks()));
        ((ChunkTeamDataImpl) teamData).updateLimits();
        AtomicReference<TerritoryClaimPermitState> observedState = new AtomicReference<>();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicBoolean cleanupNeeded = new AtomicBoolean();

        CivicServerRuntime.current()
                .submitDatabase(database -> {
                    NationTeam nationTeam = new NationTeam(
                            team.getId(), team.getOwner(), team.getMembers());
                    var nation = new NationRegistry(database, snapshot(nationTeam)).register(
                            new RegisterNation(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "permit-event-nation-" + UUID.randomUUID(),
                                    team.getId()));
                    return new TerritoryClaimPermitRegistry(
                                    database,
                                    (transactionId, nationId, amount) -> true,
                                    java.time.Clock.systemUTC())
                            .issue(new IssueTerritoryClaimPermit(
                                    new ServiceIdentity("civiceconomy-territory"),
                                    "permit-event-" + UUID.randomUUID(),
                                    nation.nationId(),
                                    team.getId(),
                                    player.getUUID(),
                                    position.dimension().location().toString(),
                                    position.x(),
                                    position.z(),
                                    17,
                                    17,
                                    250L,
                                    UUID.randomUUID(),
                                    java.time.Instant.now().plusSeconds(120L)));
                })
                .whenComplete((permit, setupFailure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (setupFailure != null) {
                                asyncFailure.set(setupFailure);
                                return;
                            }
                            try {
                                CivicServerRuntime.current().publishTerritoryClaimPermit(permit);
                                var source = player.createCommandSourceStack().withSuppressedOutput();
                                helper.assertTrue(
                                        teamData.claim(source, position, true).isSuccess(),
                                        "first simulated FTB claim");
                                helper.assertTrue(
                                        teamData.claim(source, position, true).isSuccess(),
                                        "repeated simulated FTB claim");
                                helper.assertTrue(
                                        teamData.claim(source, position, false).isSuccess(),
                                        "real FTB claim authorized by READY Permit");
                                cleanupNeeded.set(true);
                                CivicServerRuntime.current()
                                        .submitDatabase(database -> new TerritoryClaimPermitRegistry(
                                                        database,
                                                        (transactionId, nationId, amount) -> false,
                                                        java.time.Clock.systemUTC())
                                                .find(permit.permitId())
                                                .orElseThrow()
                                                .state())
                                        .whenComplete((state, inspectionFailure) -> {
                                            if (inspectionFailure != null) {
                                                asyncFailure.set(inspectionFailure);
                                            } else {
                                                observedState.set(state);
                                            }
                                        });
                            } catch (Throwable claimFailure) {
                                asyncFailure.set(claimFailure);
                            }
                        }));

        helper.succeedWhen(() -> {
            Throwable failure = asyncFailure.get();
            helper.assertTrue(
                    failure == null,
                    failure == null
                            ? "Permit event async state"
                            : "Permit event async failure: " + failure.getMessage());
            helper.assertValueEqual(
                    TerritoryClaimPermitState.CONSUMED,
                    observedState.get(),
                    "durably consumed Territory Claim Permit");
            if (cleanupNeeded.compareAndSet(true, false)) {
                ClaimedChunk claimed = manager.getChunk(position);
                if (claimed != null) {
                    claimed.unclaim(player.createCommandSourceStack(), true);
                }
            }
        });
    }

    @GameTest(
            template = "empty",
            timeoutTicks = 1800,
            batch = "territory-force-load-guard")
    public static void suspendedTerritoryBlocksRealFtbForceLoadUntilEffectiveRestoration(
            GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-force-load-guard-test"),
                ClientInformation.createDefault());
        player.setPos(helper.absolutePos(new BlockPos(72, 0, 72)).getCenter());
        Team team = createHeadOwnedFtbTeamFixture(player);
        ChunkDimPos position = new ChunkDimPos(
                player.level().dimension(), new ChunkPos(player.blockPosition()));
        TerritoryClaimPosition civicPosition = new TerritoryClaimPosition(
                position.dimension().location().toString(), position.x(), position.z());
        var manager = FTBChunksAPI.api().getManager();
        ClaimedChunk existing = manager.getChunk(position);
        if (existing != null) {
            existing.unclaim(player.createCommandSourceStack(), true);
        }
        var teamData = manager.getOrCreateData(team);
        teamData.setExtraClaimChunks(Math.max(100, teamData.getExtraClaimChunks()));
        teamData.setExtraForceLoadChunks(Math.max(100, teamData.getExtraForceLoadChunks()));
        ((ChunkTeamDataImpl) teamData).updateLimits();
        helper.assertTrue(
                teamData.claim(player.createCommandSourceStack(), position, false).isSuccess(),
                "force-load guard Claim fixture");

        java.time.Instant now = java.time.Instant.now();
        ServiceIdentity maintenanceService =
                new ServiceIdentity("force-load-gametest-" + UUID.randomUUID());
        AtomicReference<org.civiceconomy.nation.NationId> nationId = new AtomicReference<>();
        AtomicReference<java.time.Instant> restoredCycleStart = new AtomicReference<>();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicBoolean restorationCommitted = new AtomicBoolean();

        CivicServerRuntime.current()
                .submitDatabase(database -> {
                    NationTeam nationTeam = new NationTeam(
                            team.getId(), team.getOwner(), team.getMembers());
                    var nation = new NationRegistry(database, snapshot(nationTeam)).register(
                            new RegisterNation(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "force-load-guard-nation-" + UUID.randomUUID(),
                                    team.getId()));
                    nationId.set(nation.nationId());
                    TerritoryMaintenanceRegistry maintenance =
                            new TerritoryMaintenanceRegistry(database);
                    long suspendedStartMillis =
                            now.minus(java.time.Duration.ofHours(25L)).toEpochMilli() - 4L;
                    while (true) {
                        var occupiedAtSuspension =
                                database.territoryMaintenanceCycleAt(suspendedStartMillis);
                        if (occupiedAtSuspension != null) {
                            suspendedStartMillis =
                                    occupiedAtSuspension.startsAtEpochMillis() - 4L;
                            continue;
                        }
                        var occupiedAtRestoration =
                                database.territoryMaintenanceCycleAt(suspendedStartMillis + 2L);
                        if (occupiedAtRestoration != null) {
                            suspendedStartMillis =
                                    occupiedAtRestoration.startsAtEpochMillis() - 4L;
                            continue;
                        }
                        break;
                    }
                    java.time.Instant suspendedCycleStart =
                            java.time.Instant.ofEpochMilli(suspendedStartMillis);
                    java.time.Instant suspendedCycleEnd = suspendedCycleStart.plusMillis(1L);
                    restoredCycleStart.set(suspendedCycleEnd.plusMillis(1L));
                    var suspendedCycle = maintenance.openCycle(
                            new OpenTerritoryMaintenanceCycle(
                                    maintenanceService,
                                    "force-load-guard-cycle-" + UUID.randomUUID(),
                                    suspendedCycleStart,
                                    suspendedCycleEnd));
                    maintenance.assess(new AssessTerritoryFiscalValidity(
                            maintenanceService,
                            "force-load-guard-assessment-" + UUID.randomUUID(),
                            suspendedCycle.cycleId(),
                            nation.nationId(),
                            team.getId(),
                            civicPosition.dimensionId(),
                            civicPosition.chunkX(),
                            civicPosition.chunkZ(),
                            10L,
                            TerritoryMaintenancePriority.ORDINARY,
                            "Post-grace force-load guard GameTest"));
                    maintenance.suspend(new SuspendTerritoryMaintenance(
                            maintenanceService,
                            "force-load-guard-settlement-" + UUID.randomUUID(),
                            suspendedCycle.cycleId(),
                            nation.nationId(),
                            "Post-grace force-load guard GameTest"));
                    return nation.nationId();
                })
                .whenComplete((ignored, setupFailure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (setupFailure != null) {
                                asyncFailure.set(setupFailure);
                            } else {
                                CivicServerRuntime.current()
                                        .refreshTerritoryForceLoadRestrictions();
                            }
                        }));

        CivicServerRuntime runtime = CivicServerRuntime.current();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    Throwable failure = asyncFailure.get();
                    helper.assertTrue(
                            failure == null,
                            failure == null
                                    ? "force-load guard async state"
                                    : "force-load guard async failure: " + failure.getMessage());
                    helper.assertTrue(
                            runtime.territoryForceLoadRestrictionsReady(),
                            "force-load Restriction mirror ready");
                    helper.assertTrue(
                            runtime.territoryForceLoadBlocked(team.getId(), civicPosition),
                            "exact post-grace suspended Claim mirrored");
                })
                .thenExecute(() -> {
                    helper.assertFalse(
                            teamData.forceLoad(
                                            player.createCommandSourceStack()
                                                    .withSuppressedOutput(),
                                            position,
                                            false)
                                    .isSuccess(),
                            "real FTB BEFORE_LOAD rejects post-grace suspended Territory");
                    helper.assertFalse(
                            manager.getChunk(position).isForceLoaded(),
                            "rejected force-load does not mutate FTB state");
                    runtime.submitDatabase(database -> {
                                TerritoryMaintenanceRegistry maintenance =
                                        new TerritoryMaintenanceRegistry(database);
                                var restoredCycle = maintenance.openCycle(
                                        new OpenTerritoryMaintenanceCycle(
                                                maintenanceService,
                                                "force-load-restored-cycle-" + UUID.randomUUID(),
                                                restoredCycleStart.get(),
                                                restoredCycleStart.get().plusMillis(1L)));
                                maintenance.assess(new AssessTerritoryFiscalValidity(
                                        maintenanceService,
                                        "force-load-restored-assessment-" + UUID.randomUUID(),
                                        restoredCycle.cycleId(),
                                        nationId.get(),
                                        team.getId(),
                                        civicPosition.dimensionId(),
                                        civicPosition.chunkX(),
                                        civicPosition.chunkZ(),
                                        0L,
                                        TerritoryMaintenancePriority.ORDINARY,
                                        "Effective restoration mirror GameTest"));
                                maintenance.settleZeroCostAssessments(
                                        new SuspendTerritoryMaintenance(
                                                maintenanceService,
                                                "force-load-restored-settlement-" + UUID.randomUUID(),
                                                restoredCycle.cycleId(),
                                                nationId.get(),
                                                "Effective restoration mirror GameTest"));
                                return null;
                            })
                            .whenComplete((ignored, restorationFailure) ->
                                    helper.getLevel().getServer().execute(() -> {
                                        if (restorationFailure != null) {
                                            asyncFailure.set(restorationFailure);
                                        } else {
                                            restorationCommitted.set(true);
                                            runtime.refreshTerritoryForceLoadRestrictions();
                                        }
                                    }));
                })
                .thenWaitUntil(() -> {
                    Throwable failure = asyncFailure.get();
                    helper.assertTrue(
                            failure == null,
                            failure == null
                                    ? "restoration async state"
                                    : "restoration async failure: " + failure.getMessage());
                    helper.assertTrue(restorationCommitted.get(), "effective restoration committed");
                    helper.assertFalse(
                            runtime.territoryForceLoadBlocked(team.getId(), civicPosition),
                            "effective restoration removes exact Restriction");
                })
                .thenExecute(() -> {
                    helper.assertTrue(
                            teamData.forceLoad(
                                            player.createCommandSourceStack().withSuppressedOutput(),
                                            position,
                                            false)
                                    .isSuccess(),
                            "authorized player can manually re-enable FTB force-load after restoration");
                    helper.assertTrue(
                            manager.getChunk(position).isForceLoaded(),
                            "restored Claim force-load requested");
                    helper.assertTrue(
                            teamData.unForceLoad(player.createCommandSourceStack(), position, false)
                                    .isSuccess(),
                            "force-load guard cleanup unload");
                    manager.getChunk(position).unclaim(player.createCommandSourceStack(), true);
                })
                .thenSucceed();
    }

    @GameTest(
            template = "empty",
            timeoutTicks = 1800,
            batch = "territory-player-restoration-command")
    public static void playerRestoreCommandPaysRealLcAndReleasesExactFtbRestrictionExactlyOnce(
            GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-player-restoration-test"),
                ClientInformation.createDefault());
        player.setPos(helper.absolutePos(new BlockPos(88, 0, 88)).getCenter());
        Team team = createHeadOwnedFtbTeamFixture(player);
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        String requestId = "player-territory-restoration-" + UUID.randomUUID();
        UUID sourceAssessmentId = UUID.randomUUID();
        ChunkDimPos position = new ChunkDimPos(
                player.level().dimension(), new ChunkPos(player.blockPosition()));
        TerritoryClaimPosition civicPosition = new TerritoryClaimPosition(
                position.dimension().location().toString(), position.x(), position.z());
        var manager = FTBChunksAPI.api().getManager();
        ClaimedChunk existing = manager.getChunk(position);
        if (existing != null) {
            existing.unclaim(player.createCommandSourceStack(), true);
        }
        var teamData = manager.getOrCreateData(team);
        teamData.setExtraClaimChunks(Math.max(100, teamData.getExtraClaimChunks()));
        teamData.setExtraForceLoadChunks(Math.max(100, teamData.getExtraForceLoadChunks()));
        ((ChunkTeamDataImpl) teamData).updateLimits();
        helper.assertTrue(
                teamData.claim(
                                player.createCommandSourceStack().withSuppressedOutput(),
                                position,
                                false)
                        .isSuccess(),
                "out-of-Cycle Restoration FTB Claim fixture");
        helper.assertFalse(
                manager.getChunk(position).isForceLoaded(),
                "Restoration fixture does not begin force-loaded");

        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        AtomicReference<org.civiceconomy.nation.NationId> nationId = new AtomicReference<>();
        AtomicReference<Long> issuanceAfterSeed = new AtomicReference<>();
        AtomicReference<Long> publicFundBefore = new AtomicReference<>();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicBoolean setupReady = new AtomicBoolean();
        AtomicBoolean commandStarted = new AtomicBoolean();
        AtomicBoolean replayStarted = new AtomicBoolean();
        AtomicBoolean replayCompleted = new AtomicBoolean();
        AtomicBoolean cleanupDone = new AtomicBoolean();
        java.time.Instant now = java.time.Instant.now();
        java.time.Clock setupClock =
                java.time.Clock.fixed(now, java.time.ZoneOffset.UTC);

        CivicServerRuntime runtime = CivicServerRuntime.current();
        runtime.submitDatabase(database -> {
                    NationRegistry nations = new NationRegistry(database, snapshot(teamSnapshot));
                    java.time.Instant appliedAt = now.minus(java.time.Duration.ofHours(2L));
                    var application = new NationApplicationRegistry(
                                    database,
                                    snapshot(teamSnapshot),
                                    java.time.Clock.fixed(appliedAt, java.time.ZoneOffset.UTC))
                            .create(new CreateNationApplication(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "player-restoration-application-" + UUID.randomUUID(),
                                    team.getId(),
                                    player.getUUID(),
                                    now.plus(java.time.Duration.ofDays(1L))));
                    new OnlineTimeLedger(database).record(new RecordOnlineTime(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "player-restoration-evidence-" + UUID.randomUUID(),
                            player.getUUID(),
                            appliedAt.toEpochMilli(),
                            now.toEpochMilli()));
                    var activated = new org.civiceconomy.nation.NationActivationCoordinator(
                                    database,
                                    (activatedNationId, treasuryAccountId) -> {},
                                    org.civiceconomy.nation.NationFoundingPolicy.debugWorld(
                                            2,
                                            java.time.Duration.ofDays(30L),
                                            java.time.Duration.ofDays(7L)),
                                    setupClock)
                            .activate(new org.civiceconomy.nation.ActivateNationApplication(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "player-restoration-activation-" + UUID.randomUUID(),
                                    application.applicationId(),
                                    new org.civiceconomy.nation.Capital(
                                            civicPosition.dimensionId(),
                                            civicPosition.chunkX(),
                                            civicPosition.chunkZ()),
                                    "Formal GameTest activation for player Restoration"));
                    var nation = activated.nation();
                    CitizenshipRegistry citizenships = new CitizenshipRegistry(
                            database, java.time.Duration.ofDays(7L), setupClock);
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            citizenships,
                            new CitizenshipCorrectionGraceRegistry(database, setupClock),
                            snapshot(teamSnapshot));
                    new NationFiscalAuthorityRegistry(database, provider, setupClock)
                            .grant(new GrantNationFiscalPermission(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "player-restoration-authority-" + UUID.randomUUID(),
                                    nation.nationId(),
                                    player.getUUID(),
                                    player.getUUID(),
                                    NationFiscalPermission.MANAGE_TERRITORY_FINANCE,
                                    "Real player out-of-Cycle Restoration GameTest"));
                    java.time.Instant policyEffectiveAt = java.time.Instant.now().minusMillis(1L);
                    java.time.Duration cycleDuration = java.time.Duration.ofDays(7L);
                    java.time.Instant nextPolicyEffectiveAt =
                            policyEffectiveAt.plus(cycleDuration).minusMillis(1L);
                    database.scheduleTerritoryFreeAllocationPolicy(
                            UUID.randomUUID(),
                            org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                                    .SERVICE_IDENTITY
                                    .value(),
                            "player-restoration-free-policy-" + UUID.randomUUID(),
                            "gametest",
                            0,
                            0,
                            nextPolicyEffectiveAt.toEpochMilli(),
                            "No free Territory in player Restoration GameTest",
                            policyEffectiveAt.toEpochMilli());
                    database.scheduleTerritoryMaintenancePolicy(
                            UUID.randomUUID(),
                            org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                                    .SERVICE_IDENTITY
                                    .value(),
                            "player-restoration-maintenance-policy-" + UUID.randomUUID(),
                            "gametest",
                            cycleDuration.toMillis(),
                            100L,
                            15_000,
                            0L,
                            30L,
                            java.time.Duration.ofDays(14L).toMillis(),
                            6_000,
                            policyEffectiveAt.toEpochMilli(),
                            "Exact real-LC player Restoration policy",
                            policyEffectiveAt.toEpochMilli());
                    database.scheduleTerritoryMaintenancePolicy(
                            UUID.randomUUID(),
                            org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                                    .SERVICE_IDENTITY
                                    .value(),
                            "player-restoration-next-maintenance-policy-" + UUID.randomUUID(),
                            "gametest",
                            cycleDuration.toMillis(),
                            100L,
                            15_000,
                            0L,
                            30L,
                            java.time.Duration.ofDays(14L).toMillis(),
                            6_000,
                            nextPolicyEffectiveAt.toEpochMilli(),
                            "Exact next-Cycle real-LC player Restoration policy",
                            policyEffectiveAt.toEpochMilli());
                    TerritoryMaintenanceRegistry maintenance =
                            new TerritoryMaintenanceRegistry(database, setupClock);
                    long suspendedStartMillis =
                            now.minus(java.time.Duration.ofHours(25L)).toEpochMilli();
                    while (true) {
                        var occupiedAtStart =
                                database.territoryMaintenanceCycleAt(suspendedStartMillis);
                        if (occupiedAtStart != null) {
                            suspendedStartMillis = occupiedAtStart.startsAtEpochMillis() - 4L;
                            continue;
                        }
                        var occupiedAtEnd =
                                database.territoryMaintenanceCycleAt(suspendedStartMillis + 1L);
                        if (occupiedAtEnd != null) {
                            suspendedStartMillis = occupiedAtEnd.startsAtEpochMillis() - 4L;
                            continue;
                        }
                        break;
                    }
                    var suspendedCycle = maintenance.openCycle(
                            new OpenTerritoryMaintenanceCycle(
                                    org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                                            .SERVICE_IDENTITY,
                                    "player-restoration-cycle-" + UUID.randomUUID(),
                                    java.time.Instant.ofEpochMilli(suspendedStartMillis),
                                    java.time.Instant.ofEpochMilli(suspendedStartMillis + 1L)));
                    database.assessTerritoryFiscalValidity(
                            sourceAssessmentId,
                            org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                                    .SERVICE_IDENTITY
                                    .value(),
                            "player-restoration-assessment-" + UUID.randomUUID(),
                            suspendedCycle.cycleId(),
                            nation.nationId().value(),
                            team.getId(),
                            civicPosition.dimensionId(),
                            civicPosition.chunkX(),
                            civicPosition.chunkZ(),
                            100L,
                            TerritoryMaintenancePriority.CAPITAL.name(),
                            "Latest suspended target for real player Restoration",
                            suspendedStartMillis);
                    maintenance.suspend(new SuspendTerritoryMaintenance(
                            org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                                    .SERVICE_IDENTITY,
                            "player-restoration-suspension-" + UUID.randomUUID(),
                            suspendedCycle.cycleId(),
                            nation.nationId(),
                            "Insufficient funds before player Restoration"));
                    long issuanceBefore = database.cumulativeNetIssuanceMinorUnits();
                    database.confirmMonetarySupplyChange(
                            UUID.randomUUID(),
                            org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                                    .SERVICE_IDENTITY
                                    .value(),
                            "player-restoration-issuance-" + UUID.randomUUID(),
                            "ISSUANCE",
                            130L,
                            "mint-batch:player-restoration:" + UUID.randomUUID(),
                            "Seed real player Restoration destruction capacity",
                            now.toEpochMilli(),
                            Math.addExact(issuanceBefore, 10_000L));
                    issuanceAfterSeed.set(database.cumulativeNetIssuanceMinorUnits());
                    return nation.nationId();
                })
                .whenComplete((registeredNationId, setupFailure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (setupFailure != null) {
                                asyncFailure.set(setupFailure);
                                return;
                            }
                            try {
                                nationId.set(registeredNationId);
                                fundTreasury(
                                        helper,
                                        registeredNationId,
                                        "Player Restoration GameTest Treasury",
                                        130L);
                                publicFundBefore.set(
                                        LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                                .balance(org.civiceconomy.integration
                                                        .lightmanscurrency
                                                        .LightmansCurrencyPublicMaintenanceFundProvisioner
                                                        .ACCOUNT_ID)
                                                .minorUnits());
                                setupReady.set(true);
                                runtime.refreshTerritoryForceLoadRestrictions();
                            } catch (Throwable preparationFailure) {
                                asyncFailure.set(preparationFailure);
                            }
                        }));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    Throwable failure = asyncFailure.get();
                    helper.assertTrue(
                            failure == null,
                            failure == null
                                    ? "player Restoration setup state"
                                    : "player Restoration setup failure: "
                                            + failure.getMessage());
                    helper.assertTrue(setupReady.get(), "player Restoration setup complete");
                    helper.assertTrue(
                            runtime.territoryForceLoadBlocked(team.getId(), civicPosition),
                            "latest suspended target is force-load restricted before command");
                })
                .thenExecute(() -> {
                    try {
                        int result = helper.getLevel()
                                .getServer()
                                .getCommands()
                                .getDispatcher()
                                .execute(
                                        "civic economy nation territory restore " + requestId,
                                        player.createCommandSourceStack().withSuppressedOutput());
                        helper.assertValueEqual(1, result, "Territory Restoration command result");
                        commandStarted.set(true);
                    } catch (Throwable commandFailure) {
                        asyncFailure.set(commandFailure);
                    }
                })
                .thenWaitUntil(() -> {
                    Throwable failure = asyncFailure.get();
                    helper.assertTrue(
                            failure == null,
                            failure == null
                                    ? "player Restoration command state"
                                    : "player Restoration command failure: "
                                            + failure.getMessage());
                    helper.assertTrue(commandStarted.get(), "Territory Restoration command started");
                    TerritoryRestorationRow restoration =
                            territoryRestorationByRequest(databaseFile, requestId);
                    helper.assertTrue(restoration != null, "persisted player Restoration");
                    helper.assertValueEqual(
                            "CIVIC_COMMITTED", restoration.state(), "committed Restoration state");
                    helper.assertValueEqual(
                            sourceAssessmentId,
                            restoration.sourceAssessmentId(),
                            "exact source suspended Assessment");
                    helper.assertValueEqual(100L, restoration.prepayment(), "next-Cycle prepayment");
                    helper.assertValueEqual(30L, restoration.fee(), "Restoration fee");
                    helper.assertValueEqual(130L, restoration.total(), "Restoration total");
                    helper.assertValueEqual(
                            "SUSPENDED",
                            restoration.sourceValidity(),
                            "source Assessment remains immutable");
                    helper.assertFalse(
                            runtime.territoryForceLoadBlocked(team.getId(), civicPosition),
                            "committed exact Restoration releases force-load Restriction");
                })
                .thenExecute(() -> {
                    AccountId treasury = new AccountId(
                            "nation:" + nationId.get().value() + ":treasury");
                    var accounts = LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
                    helper.assertValueEqual(
                            0L, accounts.balance(treasury).minorUnits(), "Restoration Treasury balance");
                    helper.assertValueEqual(
                            publicFundBefore.get() + 52L,
                            accounts.balance(org.civiceconomy.integration.lightmanscurrency
                                            .LightmansCurrencyPublicMaintenanceFundProvisioner
                                            .ACCOUNT_ID)
                                    .minorUnits(),
                            "Restoration Public Maintenance Fund delta");
                    helper.assertValueEqual(
                            issuanceAfterSeed.get() - 78L,
                            cumulativeNetIssuance(databaseFile),
                            "Restoration permanent-destruction delta");
                    helper.assertFalse(
                            manager.getChunk(position).isForceLoaded(),
                            "Restoration does not automatically enable FTB force-load");
                    replayStarted.set(true);
                    runtime.restoreTerritory(
                                    teamSnapshot,
                                    player.getUUID(),
                                    requestId,
                                    civicPosition.dimensionId(),
                                    civicPosition.chunkX(),
                                    civicPosition.chunkZ())
                            .whenComplete((replay, replayFailure) -> {
                                if (replayFailure != null) {
                                    asyncFailure.set(replayFailure);
                                } else {
                                    replayCompleted.set(true);
                                }
                            });
                })
                .thenWaitUntil(() -> {
                    Throwable failure = asyncFailure.get();
                    helper.assertTrue(
                            failure == null,
                            failure == null
                                    ? "player Restoration replay state"
                                    : "player Restoration replay failure: "
                                            + failure.getMessage());
                    helper.assertTrue(replayStarted.get(), "Territory Restoration replay started");
                    helper.assertTrue(replayCompleted.get(), "Territory Restoration replay completed");
                    AccountId treasury = new AccountId(
                            "nation:" + nationId.get().value() + ":treasury");
                    var accounts = LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
                    helper.assertValueEqual(
                            0L,
                            accounts.balance(treasury).minorUnits(),
                            "Restoration replay does not debit Treasury twice");
                    helper.assertValueEqual(
                            publicFundBefore.get() + 52L,
                            accounts.balance(org.civiceconomy.integration.lightmanscurrency
                                            .LightmansCurrencyPublicMaintenanceFundProvisioner
                                            .ACCOUNT_ID)
                                    .minorUnits(),
                            "Restoration replay does not credit public fund twice");
                    helper.assertValueEqual(
                            issuanceAfterSeed.get() - 78L,
                            cumulativeNetIssuance(databaseFile),
                            "Restoration replay does not destroy money twice");
                })
                .thenExecute(() -> {
                    if (cleanupDone.compareAndSet(false, true)) {
                        ClaimedChunk claimed = manager.getChunk(position);
                        if (claimed != null) {
                            claimed.unclaim(player.createCommandSourceStack(), true);
                        }
                        clearFiscalAccountAmount(
                                helper,
                                org.civiceconomy.integration.lightmanscurrency
                                        .LightmansCurrencyPublicMaintenanceFundProvisioner
                                        .ACCOUNT_ID,
                                52L);
                    }
                })
                .thenSucceed();
    }

    @GameTest(
            template = "empty",
            timeoutTicks = 500,
            batch = "territory-player-paid-claim")
    public static void playerPrepareCommandPaysRealLcAndAuthorizesRealFtbClaim(
            GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-paid-claim-test"),
                ClientInformation.createDefault());
        player.setPos(helper.absolutePos(new BlockPos(24, 0, 24)).getCenter());
        Team team = createHeadOwnedFtbTeamFixture(player);
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        String requestId = "player-paid-claim-" + UUID.randomUUID();
        ChunkDimPos position = new ChunkDimPos(
                player.level().dimension(), new ChunkPos(player.blockPosition()));
        var manager = FTBChunksAPI.api().getManager();
        ClaimedChunk existing = manager.getChunk(position);
        if (existing != null) {
            existing.unclaim(player.createCommandSourceStack(), true);
        }
        var teamData = manager.getOrCreateData(team);
        teamData.setExtraClaimChunks(Math.max(100, teamData.getExtraClaimChunks()));
        ((ChunkTeamDataImpl) teamData).updateLimits();
        ChunkDimPos seedPosition = paidClaimSeedPosition(position);
        helper.assertTrue(
                teamData.claim(
                                player.createCommandSourceStack().withSuppressedOutput(),
                                seedPosition,
                                false)
                        .isSuccess(),
                "paid claim pricing seed");
        AtomicReference<org.civiceconomy.nation.NationId> nationId = new AtomicReference<>();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicBoolean commandStarted = new AtomicBoolean();
        AtomicBoolean claimStarted = new AtomicBoolean();
        AtomicBoolean cleanupDone = new AtomicBoolean();
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        java.time.Instant now = java.time.Instant.now();
        java.time.Clock setupClock = java.time.Clock.fixed(
                now, java.time.ZoneOffset.UTC);

        CivicServerRuntime.current()
                .submitDatabase(database -> {
                    NationRegistry nations = new NationRegistry(database, snapshot(teamSnapshot));
                    var nation = nations.register(new RegisterNation(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "paid-claim-nation-" + UUID.randomUUID(),
                            team.getId()));
                    CitizenshipRegistry citizenships = new CitizenshipRegistry(
                            database, java.time.Duration.ofDays(7), setupClock);
                    citizenships.join(new JoinCitizenship(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "paid-claim-citizenship-" + UUID.randomUUID(),
                            player.getUUID(),
                            nation.nationId()));
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            citizenships,
                            new CitizenshipCorrectionGraceRegistry(database, setupClock),
                            snapshot(teamSnapshot));
                    new NationFiscalAuthorityRegistry(database, provider, setupClock)
                            .grant(new GrantNationFiscalPermission(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "paid-claim-authority-" + UUID.randomUUID(),
                                    nation.nationId(),
                                    player.getUUID(),
                                    player.getUUID(),
                                    NationFiscalPermission.MANAGE_TERRITORY_FINANCE,
                                    "Real player Territory preparation GameTest"));
                    database.scheduleTerritoryFreeAllocationPolicy(
                            UUID.randomUUID(),
                            "civiceconomy-gametest",
                            "paid-claim-free-policy-" + UUID.randomUUID(),
                            "gametest",
                            0,
                            0,
                            now.minusSeconds(2L).toEpochMilli(),
                            "No free claims in paid command GameTest",
                            now.minusSeconds(3L).toEpochMilli());
                    database.scheduleTerritoryExpansionPricingPolicy(
                            UUID.randomUUID(),
                            "civiceconomy-gametest",
                            "paid-claim-pricing-" + UUID.randomUUID(),
                            "gametest",
                            250L,
                            0L,
                            now.minusSeconds(2L).toEpochMilli(),
                            "Flat paid claim GameTest pricing",
                            now.minusSeconds(3L).toEpochMilli());
                    return nation.nationId();
                })
                .whenComplete((registeredNationId, setupFailure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (setupFailure != null) {
                                asyncFailure.set(setupFailure);
                                return;
                            }
                            try {
                                nationId.set(registeredNationId);
                                AccountId treasury = new AccountId(
                                        "nation:" + registeredNationId.value() + ":treasury");
                                var accounts = LightmansCurrencyFiscalAccounts.forLevel(
                                        helper.getLevel());
                                accounts.create(
                                        treasury,
                                        FiscalAccountKind.NATIONAL_TREASURY,
                                        "Paid Claim GameTest Treasury");
                                UUID fundingPlayerId = UUID.randomUUID();
                                BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
                                var funding = bankData.getAccount(fundingPlayerId);
                                funding.getMoneyStorage().clear();
                                helper.assertTrue(
                                        BankAPI.getApi().BankDepositFromServer(
                                                funding,
                                                CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, 1_000L)),
                                        "seed paid claim LC funding account");
                                LightmansCurrencyPayments.live(helper.getLevel()).apply(
                                        new ExternalPayment(
                                                UUID.randomUUID(),
                                                new AccountId("player:" + fundingPlayerId),
                                                treasury,
                                                MoneyAmount.ofMinorUnits(1_000L)));
                                bankData.deleteAccount(fundingPlayerId);
                                int result = helper.getLevel()
                                        .getServer()
                                        .getCommands()
                                        .getDispatcher()
                                        .execute(
                                                "civic economy nation territory prepare "
                                                        + requestId,
                                                player.createCommandSourceStack()
                                                        .withSuppressedOutput());
                                helper.assertValueEqual(
                                        1, result, "Territory preparation command result");
                                commandStarted.set(true);
                            } catch (Throwable commandFailure) {
                                asyncFailure.set(commandFailure);
                            }
                        }));

        helper.succeedWhen(() -> {
            Throwable failure = asyncFailure.get();
            helper.assertTrue(
                    failure == null,
                    failure == null
                            ? "paid claim async state"
                            : "paid claim async failure: " + failure.getMessage());
            helper.assertTrue(commandStarted.get(), "Territory preparation command started");
            var permit = territoryPermitByRequest(databaseFile, requestId + ":permit");
            helper.assertTrue(permit != null, "READY Permit from player preparation command");
            AccountId treasury = new AccountId(
                    "nation:" + nationId.get().value() + ":treasury");
            var accounts = LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
            helper.assertValueEqual(
                    750L, accounts.balance(treasury).minorUnits(), "paid claim Treasury balance");
            helper.assertValueEqual(
                    250L,
                    accounts.balance(org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                                    .CLEARING_ACCOUNT_ID)
                            .minorUnits(),
                    "paid claim clearing balance");
            if (claimStarted.compareAndSet(false, true)) {
                var claim = teamData.claim(
                        player.createCommandSourceStack().withSuppressedOutput(),
                        position,
                        false);
                helper.assertTrue(claim.isSuccess(), "real paid FTB claim");
            }
            var consumed = territoryPermitByRequest(databaseFile, requestId + ":permit");
            helper.assertValueEqual(
                    TerritoryClaimPermitState.CONSUMED,
                    consumed.state(),
                    "player-prepared Permit consumed after real claim");
            if (cleanupDone.compareAndSet(false, true)) {
                ClaimedChunk claimed = manager.getChunk(position);
                if (claimed != null) {
                    claimed.unclaim(player.createCommandSourceStack(), true);
                }
                ClaimedChunk seed = manager.getChunk(seedPosition);
                if (seed != null) {
                    seed.unclaim(player.createCommandSourceStack(), true);
                }
                clearFiscalAccount(
                        helper,
                        org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                                .CLEARING_ACCOUNT_ID);
            }
        });
    }

    @GameTest(
            template = "empty",
            timeoutTicks = 500,
            batch = "territory-player-cancelled-claim")
    public static void playerCancelCommandRefundsRealLcAndRemovesClaimAuthorization(
            GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-cancel-claim-test"),
                ClientInformation.createDefault());
        player.setPos(helper.absolutePos(new BlockPos(40, 0, 40)).getCenter());
        Team team = createHeadOwnedFtbTeamFixture(player);
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        String prepareRequestId = "player-cancel-prepare-" + UUID.randomUUID();
        String cancelRequestId = "player-cancel-refund-" + UUID.randomUUID();
        ChunkDimPos position = new ChunkDimPos(
                player.level().dimension(), new ChunkPos(player.blockPosition()));
        var manager = FTBChunksAPI.api().getManager();
        ClaimedChunk existing = manager.getChunk(position);
        if (existing != null) {
            existing.unclaim(player.createCommandSourceStack(), true);
        }
        var teamData = manager.getOrCreateData(team);
        teamData.setExtraClaimChunks(Math.max(100, teamData.getExtraClaimChunks()));
        ((ChunkTeamDataImpl) teamData).updateLimits();
        ChunkDimPos seedPosition = paidClaimSeedPosition(position);
        helper.assertTrue(
                teamData.claim(
                                player.createCommandSourceStack().withSuppressedOutput(),
                                seedPosition,
                                false)
                        .isSuccess(),
                "cancelled claim pricing seed");
        AtomicReference<org.civiceconomy.nation.NationId> nationId = new AtomicReference<>();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicBoolean prepareStarted = new AtomicBoolean();
        AtomicBoolean cancelStarted = new AtomicBoolean();
        AtomicBoolean claimChecked = new AtomicBoolean();
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        java.time.Instant now = java.time.Instant.now();
        java.time.Clock setupClock = java.time.Clock.fixed(
                now, java.time.ZoneOffset.UTC);

        CivicServerRuntime.current()
                .submitDatabase(database -> setupPaidClaimNation(
                        database, teamSnapshot, player.getUUID(), now, setupClock))
                .whenComplete((registeredNationId, setupFailure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (setupFailure != null) {
                                asyncFailure.set(setupFailure);
                                return;
                            }
                            try {
                                nationId.set(registeredNationId);
                                fundTreasury(
                                        helper,
                                        registeredNationId,
                                        "Cancelled Claim GameTest Treasury");
                                int result = helper.getLevel()
                                        .getServer()
                                        .getCommands()
                                        .getDispatcher()
                                        .execute(
                                                "civic economy nation territory prepare "
                                                        + prepareRequestId,
                                                player.createCommandSourceStack()
                                                        .withSuppressedOutput());
                                helper.assertValueEqual(
                                        1, result, "cancellable Territory preparation command result");
                                prepareStarted.set(true);
                            } catch (Throwable commandFailure) {
                                asyncFailure.set(commandFailure);
                            }
                        }));

        helper.succeedWhen(() -> {
            Throwable failure = asyncFailure.get();
            helper.assertTrue(
                    failure == null,
                    failure == null
                            ? "cancelled claim async state"
                            : "cancelled claim async failure: " + failure.getMessage());
            helper.assertTrue(prepareStarted.get(), "cancellable preparation command started");
            TerritoryPermitRow permit = territoryPermitByRequest(
                    databaseFile, prepareRequestId + ":permit");
            helper.assertTrue(permit != null, "READY Permit before player cancellation");
            if (cancelStarted.compareAndSet(false, true)) {
                try {
                    int result = helper.getLevel()
                            .getServer()
                            .getCommands()
                            .getDispatcher()
                            .execute(
                                    "civic economy nation territory cancel "
                                            + permit.permitId() + " " + cancelRequestId
                                            + " Player changed plans",
                                    player.createCommandSourceStack().withSuppressedOutput());
                    helper.assertValueEqual(
                            1, result, "Territory cancellation command result");
                } catch (Throwable cancellationFailure) {
                    asyncFailure.set(cancellationFailure);
                }
            }
            TerritoryPermitRow cancelled = territoryPermitByRequest(
                    databaseFile, prepareRequestId + ":permit");
            helper.assertValueEqual(
                    TerritoryClaimPermitState.CANCELLED,
                    cancelled.state(),
                    "player-cancelled Permit state");
            AccountId treasury = new AccountId(
                    "nation:" + nationId.get().value() + ":treasury");
            var accounts = LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
            helper.assertValueEqual(
                    1_000L,
                    accounts.balance(treasury).minorUnits(),
                    "cancelled claim Treasury refund");
            helper.assertValueEqual(
                    0L,
                    accounts.balance(org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                                    .CLEARING_ACCOUNT_ID)
                            .minorUnits(),
                    "cancelled claim clearing balance");
            if (claimChecked.compareAndSet(false, true)) {
                var claim = teamData.claim(
                        player.createCommandSourceStack().withSuppressedOutput(),
                        position,
                        false);
                helper.assertFalse(
                        claim.isSuccess(), "cancelled Permit cannot authorize a real FTB claim");
                ClaimedChunk seed = manager.getChunk(seedPosition);
                if (seed != null) {
                    seed.unclaim(player.createCommandSourceStack(), true);
                }
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void playerPrepareCommandAuthorizesFreeClaimWithoutFiscalMovement(
            GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-free-claim-test"),
                ClientInformation.createDefault());
        player.setPos(helper.absolutePos(new BlockPos(56, 0, 56)).getCenter());
        Team team = createHeadOwnedFtbTeamFixture(player);
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        String requestId = "player-free-claim-" + UUID.randomUUID();
        ChunkDimPos position = new ChunkDimPos(
                player.level().dimension(), new ChunkPos(player.blockPosition()));
        var manager = FTBChunksAPI.api().getManager();
        ClaimedChunk existing = manager.getChunk(position);
        if (existing != null) {
            existing.unclaim(player.createCommandSourceStack(), true);
        }
        var teamData = manager.getOrCreateData(team);
        teamData.setExtraClaimChunks(Math.max(100, teamData.getExtraClaimChunks()));
        ((ChunkTeamDataImpl) teamData).updateLimits();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicReference<org.civiceconomy.nation.NationId> nationId = new AtomicReference<>();
        AtomicBoolean commandStarted = new AtomicBoolean();
        AtomicBoolean claimed = new AtomicBoolean();
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        java.time.Instant now = java.time.Instant.now();
        java.time.Clock setupClock = java.time.Clock.fixed(
                now, java.time.ZoneOffset.UTC);

        CivicServerRuntime.current()
                .submitDatabase(database -> {
                    var registeredNationId = setupPaidClaimNation(
                            database, teamSnapshot, player.getUUID(), now, setupClock);
                    database.scheduleTerritoryFreeAllocationPolicy(
                            UUID.randomUUID(),
                            "civiceconomy-gametest",
                            "free-claim-policy-" + UUID.randomUUID(),
                            "gametest",
                            1,
                            0,
                            now.minusSeconds(1L).toEpochMilli(),
                            "One free claim for real FTB GameTest",
                            now.minusSeconds(2L).toEpochMilli());
                    return registeredNationId;
                })
                .whenComplete((registeredNationId, setupFailure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (setupFailure != null) {
                                asyncFailure.set(setupFailure);
                                return;
                            }
                            try {
                                nationId.set(registeredNationId);
                                int result = helper.getLevel()
                                        .getServer()
                                        .getCommands()
                                        .getDispatcher()
                                        .execute(
                                                "civic economy nation territory prepare "
                                                        + requestId,
                                                player.createCommandSourceStack()
                                                        .withSuppressedOutput());
                                helper.assertValueEqual(
                                        1, result, "Free Claim preparation command result");
                                commandStarted.set(true);
                            } catch (Throwable commandFailure) {
                                asyncFailure.set(commandFailure);
                            }
                        }));

        helper.succeedWhen(() -> {
            Throwable failure = asyncFailure.get();
            helper.assertTrue(
                    failure == null,
                    failure == null
                            ? "free claim async state"
                            : "free claim async failure: " + failure.getMessage());
            helper.assertTrue(commandStarted.get(), "Free Claim preparation command started");
            helper.assertTrue(
                    territoryPermitByRequest(databaseFile, requestId + ":permit") == null,
                    "Free Claim creates no paid Permit row");
            helper.assertValueEqual(
                    0,
                    territoryFiscalRequestCount(databaseFile, requestId),
                    "Free Claim creates no fiscal request rows");
            try {
                LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel()).balance(
                        new AccountId("nation:" + nationId.get().value() + ":treasury"));
                throw new IllegalStateException(
                        "Free Claim unexpectedly created a National Treasury account");
            } catch (IllegalArgumentException expectedMissingTreasury) {
                // Expected: Free Claim preparation has no fiscal-account side effect.
            }
            var source = player.createCommandSourceStack().withSuppressedOutput();
            helper.assertTrue(
                    teamData.claim(source, position, true).isSuccess(),
                    "simulated Free Claim authorization");
            helper.assertTrue(
                    teamData.claim(source, position, true).isSuccess(),
                    "repeated simulated Free Claim authorization");
            if (claimed.compareAndSet(false, true)) {
                helper.assertTrue(
                        teamData.claim(source, position, false).isSuccess(),
                        "real FTB claim through Free Claim Authorization");
                ClaimedChunk claimedChunk = manager.getChunk(position);
                helper.assertTrue(claimedChunk != null, "real free FTB ownership");
                claimedChunk.unclaim(source, true);
            }
        });
    }

    private static void assertPersistedInterval(GameTestHelper helper, Path databaseFile, ServerPlayer player) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT COUNT(*), COALESCE(SUM(ended_at_epoch_millis - started_at_epoch_millis), 0)
                        FROM online_time_interval
                        WHERE player_id = ?
                        """)) {
            query.setString(1, player.getUUID().toString());
            try (var result = query.executeQuery()) {
                helper.assertValueEqual(1, result.getInt(1), "persisted online-time interval count");
                helper.assertTrue(result.getLong(2) > 0L, "persisted online-time duration");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect Civic runtime GameTest database", failure);
        }
    }

    private static TerritoryPermitRow territoryPermitByRequest(
            Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT permit_id, state
                        FROM territory_claim_permit
                        WHERE service_identity = ? AND request_id = ?
                        """)) {
            query.setString(
                    1,
                    org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                            .SERVICE_IDENTITY
                            .value());
            query.setString(2, requestId);
            try (var result = query.executeQuery()) {
                return result.next()
                        ? new TerritoryPermitRow(
                                UUID.fromString(result.getString(1)),
                                TerritoryClaimPermitState.valueOf(result.getString(2)))
                        : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect player-prepared Territory Claim Permit", failure);
        }
    }

    private static int territoryFiscalRequestCount(Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT
                          (SELECT COUNT(*) FROM fiscal_reservation
                           WHERE service_identity = ? AND request_id = ?)
                          +
                          (SELECT COUNT(*) FROM payment_transaction
                           WHERE service_identity = ? AND request_id = ?)
                        """)) {
            query.setString(
                    1,
                    org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                            .SERVICE_IDENTITY
                            .value());
            query.setString(2, requestId + ":reserve");
            query.setString(
                    3,
                    org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                            .SERVICE_IDENTITY
                            .value());
            query.setString(4, requestId + ":payment");
            try (var result = query.executeQuery()) {
                return result.getInt(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Free Claim fiscal side effects", failure);
        }
    }

    private static org.civiceconomy.nation.NationId setupPaidClaimNation(
            org.civiceconomy.persistence.CivicDatabase database,
            NationTeam team,
            UUID playerId,
            java.time.Instant now,
            java.time.Clock setupClock) {
        NationRegistry nations = new NationRegistry(database, snapshot(team));
        var nation = nations.register(new RegisterNation(
                new ServiceIdentity("civiceconomy-gametest"),
                "paid-claim-nation-" + UUID.randomUUID(),
                team.teamId()));
        CitizenshipRegistry citizenships = new CitizenshipRegistry(
                database, java.time.Duration.ofDays(7), setupClock);
        citizenships.join(new JoinCitizenship(
                new ServiceIdentity("civiceconomy-gametest"),
                "paid-claim-citizenship-" + UUID.randomUUID(),
                playerId,
                nation.nationId()));
        var provider = new FtbTeamsNationProvider(
                nations,
                citizenships,
                new CitizenshipCorrectionGraceRegistry(database, setupClock),
                snapshot(team));
        new NationFiscalAuthorityRegistry(database, provider, setupClock)
                .grant(new GrantNationFiscalPermission(
                        new ServiceIdentity("civiceconomy-gametest"),
                        "paid-claim-authority-" + UUID.randomUUID(),
                        nation.nationId(),
                        playerId,
                        playerId,
                        NationFiscalPermission.MANAGE_TERRITORY_FINANCE,
                        "Real player Territory preparation GameTest"));
        database.scheduleTerritoryFreeAllocationPolicy(
                UUID.randomUUID(),
                "civiceconomy-gametest",
                "paid-claim-free-policy-" + UUID.randomUUID(),
                "gametest",
                0,
                0,
                now.minusSeconds(2L).toEpochMilli(),
                "No free claims in paid command GameTest",
                now.minusSeconds(3L).toEpochMilli());
        database.scheduleTerritoryExpansionPricingPolicy(
                UUID.randomUUID(),
                "civiceconomy-gametest",
                "paid-claim-pricing-" + UUID.randomUUID(),
                "gametest",
                250L,
                0L,
                now.minusSeconds(2L).toEpochMilli(),
                "Flat paid claim GameTest pricing",
                now.minusSeconds(3L).toEpochMilli());
        return nation.nationId();
    }

    private static void fundTreasury(
            GameTestHelper helper,
            org.civiceconomy.nation.NationId nationId,
            String displayName) {
        fundTreasury(helper, nationId, displayName, 1_000L);
    }

    private static void fundTreasury(
            GameTestHelper helper,
            org.civiceconomy.nation.NationId nationId,
            String displayName,
            long amountMinorUnits) {
        AccountId treasury = new AccountId(
                "nation:" + nationId.value() + ":treasury");
        var accounts = LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
        accounts.create(treasury, FiscalAccountKind.NATIONAL_TREASURY, displayName);
        UUID fundingPlayerId = UUID.randomUUID();
        BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
        var funding = bankData.getAccount(fundingPlayerId);
        funding.getMoneyStorage().clear();
        if (!BankAPI.getApi().BankDepositFromServer(
                funding, CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, amountMinorUnits))) {
            throw new IllegalStateException("Unable to seed paid claim LC funding account");
        }
        LightmansCurrencyPayments.live(helper.getLevel()).apply(new ExternalPayment(
                UUID.randomUUID(),
                new AccountId("player:" + fundingPlayerId),
                treasury,
                MoneyAmount.ofMinorUnits(amountMinorUnits)));
        bankData.deleteAccount(fundingPlayerId);
    }

    private static void clearFiscalAccount(GameTestHelper helper, AccountId accountId) {
        var accounts = LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
        MoneyAmount balance = accounts.balance(accountId);
        if (balance.equals(MoneyAmount.ZERO)) {
            return;
        }
        UUID sinkPlayerId = UUID.randomUUID();
        BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
        bankData.getAccount(sinkPlayerId).getMoneyStorage().clear();
        LightmansCurrencyPayments.live(helper.getLevel()).apply(new ExternalPayment(
                UUID.randomUUID(),
                accountId,
                new AccountId("player:" + sinkPlayerId),
                balance));
        bankData.deleteAccount(sinkPlayerId);
    }

    private static void clearFiscalAccountAmount(
            GameTestHelper helper, AccountId accountId, long amountMinorUnits) {
        if (amountMinorUnits == 0L) {
            return;
        }
        UUID sinkPlayerId = UUID.randomUUID();
        BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
        bankData.getAccount(sinkPlayerId).getMoneyStorage().clear();
        LightmansCurrencyPayments.live(helper.getLevel()).apply(new ExternalPayment(
                UUID.randomUUID(),
                accountId,
                new AccountId("player:" + sinkPlayerId),
                MoneyAmount.ofMinorUnits(amountMinorUnits)));
        bankData.deleteAccount(sinkPlayerId);
    }

    private static TerritoryRestorationRow territoryRestorationByRequest(
            Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT restoration.restoration_id,
                               restoration.state,
                               restoration.source_suspended_assessment_id,
                               restoration.next_cycle_prepayment_minor_units,
                               restoration.restoration_fee_minor_units,
                               restoration.total_due_minor_units,
                               assessment.validity
                        FROM territory_maintenance_restoration restoration
                        JOIN territory_fiscal_assessment assessment
                          ON assessment.assessment_id =
                             restoration.source_suspended_assessment_id
                        WHERE restoration.service_identity = ?
                          AND restoration.request_id = ?
                        """)) {
            query.setString(
                    1,
                    org.civiceconomy.territory.TerritoryFiscalServiceProvisioner
                            .SERVICE_IDENTITY
                            .value());
            query.setString(2, requestId);
            try (var result = query.executeQuery()) {
                return result.next()
                        ? new TerritoryRestorationRow(
                                UUID.fromString(result.getString(1)),
                                result.getString(2),
                                UUID.fromString(result.getString(3)),
                                result.getLong(4),
                                result.getLong(5),
                                result.getLong(6),
                                result.getString(7))
                        : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect player Territory Restoration", failure);
        }
    }

    private static long cumulativeNetIssuance(Path databaseFile) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT cumulative_net_issuance_minor_units
                        FROM monetary_supply_summary
                        WHERE singleton = 1
                        """)) {
            try (var result = query.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("Monetary Supply summary is missing");
                }
                return result.getLong(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect cumulative net issuance", failure);
        }
    }

    private static long playerInventoryMoney(ServerPlayer player) {
        var unit = CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, 1L);
        return MoneyAPI.getApi()
                .GetContainersMoneyHandler(player.getInventory(), player)
                .getStoredMoney()
                .valueOf(unit.getUniqueName())
                .getCoreValue();
    }

    private static TreasuryWithdrawalRow treasuryWithdrawalByRequest(
            Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT nation_id,
                               source_account,
                               actor_player_id,
                               amount_minor_units,
                               reason,
                               state,
                               approval_request_id
                        FROM treasury_withdrawal_operation
                        WHERE service_identity = ? AND request_id = ?
                        """)) {
            query.setString(
                    1,
                    org.civiceconomy.fiscal.TreasuryWithdrawalFiscalServiceProvisioner
                            .SERVICE_IDENTITY
                            .value());
            query.setString(2, requestId);
            try (var result = query.executeQuery()) {
                return result.next()
                        ? new TreasuryWithdrawalRow(
                                result.getString(1),
                                result.getString(2),
                                result.getString(3),
                                result.getLong(4),
                                result.getString(5),
                                result.getString(6),
                                result.getString(7))
                        : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Treasury Withdrawal command result", failure);
        }
    }

    private static TreasuryWithdrawalApprovalRow treasuryWithdrawalApprovalByRequest(
            Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT approval.approval_request_id,
                               approval.state,
                               approval.required_approvals,
                               COUNT(vote.vote_id)
                        FROM treasury_withdrawal_approval_request approval
                        LEFT JOIN treasury_withdrawal_approval_vote vote
                          ON vote.approval_request_id = approval.approval_request_id
                        WHERE approval.service_identity = ?
                          AND approval.request_id = ?
                        GROUP BY approval.approval_request_id,
                                 approval.state,
                                 approval.required_approvals
                        """)) {
            query.setString(
                    1,
                    org.civiceconomy.fiscal.TreasuryWithdrawalFiscalServiceProvisioner
                            .SERVICE_IDENTITY
                            .value());
            query.setString(2, requestId);
            try (var result = query.executeQuery()) {
                return result.next()
                        ? new TreasuryWithdrawalApprovalRow(
                                UUID.fromString(result.getString(1)),
                                result.getString(2),
                                result.getInt(3),
                                result.getInt(4))
                        : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Treasury Withdrawal approval", failure);
        }
    }

    private static boolean withdrawalApprovalPolicyExists(
            Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT 1 FROM withdrawal_approval_policy
                        WHERE service_identity = ? AND request_id = ?
                        """)) {
            query.setString(1, "civiceconomy-withdrawal-governance");
            query.setString(2, requestId);
            try (var result = query.executeQuery()) {
                return result.next();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Withdrawal Approval Policy", failure);
        }
    }

    private static PermanentDestructionRow permanentDestructionByRequest(
            Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT source_account,
                               amount_minor_units,
                               operator_identity,
                               reason,
                               state
                        FROM permanent_destruction_operation
                        WHERE service_identity = ? AND request_id = ?
                        """)) {
            query.setString(
                    1,
                    org.civiceconomy.monetary.PermanentDestructionFiscalServiceProvisioner
                            .SERVICE_IDENTITY
                            .value());
            query.setString(2, requestId);
            try (var result = query.executeQuery()) {
                return result.next()
                        ? new PermanentDestructionRow(
                                result.getString(1),
                                result.getLong(2),
                                result.getString(3),
                                result.getString(4),
                                result.getString(5))
                        : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Permanent Destruction command result", failure);
        }
    }

    private static void assertNoAsyncFailure(
            GameTestHelper helper,
            AtomicReference<Throwable> asyncFailure,
            String operation) {
        Throwable failure = asyncFailure.get();
        helper.assertTrue(
                failure == null,
                failure == null ? operation + " state" : operation + " failure: " + failure);
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static UUID prepareStockCorrectionIncident(
            org.civiceconomy.persistence.CivicDatabase database) {
        UUID nationId = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
        UUID recipeId = UUID.randomUUID();
        UUID mintId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        int recipeVersionNumber = 1
                + Math.floorMod(recipeId.hashCode(), Integer.MAX_VALUE - 1);
        int mintBlockX = mintId.hashCode();
        int mintBlockZ = Long.hashCode(mintId.getLeastSignificantBits());
        long periodStart = 1_000_000L
                + Math.floorMod(periodId.getLeastSignificantBits(), 1_000_000_000_000L);
        long amountMinorUnits = 300L;

        database.registerNation(
                nationId,
                "civiceconomy-gametest",
                "stock-correction-nation-" + UUID.randomUUID(),
                UUID.randomUUID(),
                periodStart - 1_000L);
        database.publishIssuanceQuotaPeriod(
                periodId,
                "civiceconomy-gametest",
                "stock-correction-period-" + UUID.randomUUID(),
                periodStart,
                periodStart + 60_000L,
                Long.MAX_VALUE,
                1_000L,
                java.util.List.of(new StoredNationalIssuanceQuotaAllocation(
                        nationId, 1_000L)),
                "Monetary Stock Correction command GameTest period",
                periodStart - 900L);
        database.activateNationalIssuanceQuota(
                UUID.randomUUID(),
                "civiceconomy-gametest",
                "stock-correction-activation-" + UUID.randomUUID(),
                periodId,
                nationId,
                actorId,
                1_000L,
                "Monetary Stock Correction command GameTest quota",
                periodStart - 800L);
        database.publishMintRecipeVersion(
                recipeId,
                "civiceconomy-gametest",
                "stock-correction-recipe-" + UUID.randomUUID(),
                recipeVersionNumber,
                java.util.List.of(new StoredMintRecipeIngredient(
                        0, "EXACT_ITEM", "minecraft:diamond", 1L, 100L)),
                1L,
                "Monetary Stock Correction command GameTest recipe",
                periodStart - 700L);
        database.registerMint(
                mintId,
                "civiceconomy-gametest",
                "stock-correction-mint-" + UUID.randomUUID(),
                nationId,
                "minecraft:overworld",
                mintBlockX,
                64,
                mintBlockZ,
                UUID.randomUUID(),
                UUID.randomUUID(),
                false,
                recipeId,
                actorId,
                "Monetary Stock Correction command GameTest Mint",
                periodStart - 600L);
        database.prepareMintBatch(
                batchId,
                "civiceconomy-gametest",
                "stock-correction-batch-" + UUID.randomUUID(),
                mintId,
                periodId,
                nationId,
                recipeId,
                amountMinorUnits,
                java.util.List.of(new StoredMintMaterialStack(
                        0,
                        "EXACT_ITEM",
                        "minecraft:diamond",
                        "minecraft:diamond",
                        3L)),
                actorId,
                "Monetary Stock Correction command GameTest Batch",
                periodStart + 100L);
        database.confirmMintBatchCustody(
                batchId,
                "civiceconomy-gametest",
                "stock-correction-custody-" + UUID.randomUUID(),
                "gametest-custody:" + UUID.randomUUID(),
                periodStart + 200L);
        database.prepareMintBatchIssuance(
                operationId,
                batchId,
                "civiceconomy-gametest",
                "stock-correction-issuance-" + UUID.randomUUID(),
                "Monetary Stock Correction command GameTest issuance",
                periodStart + 300L);
        return database.recordMintRecoveryIncident(
                        operationId,
                        "TREASURY_CREDIT",
                        "IllegalStateException",
                        "LC Treasury confirmation requires independent evidence",
                        periodStart + 400L)
                .incidentId();
    }

    private static void assertMonetaryStockCorrection(
            GameTestHelper helper,
            Path databaseFile,
            UUID incidentId,
            String requestId,
            String evidenceReference,
            String reason) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT correction.correction_id,
                               correction.administrator_identity,
                               correction.request_id,
                               correction.amount_minor_units,
                               correction.evidence_reference,
                               correction.reason,
                               event.change_kind,
                               event.external_reference,
                               incident.state,
                               incident.resolution_kind,
                               incident.resolution_detail,
                               batch.state,
                               batch.custody_state,
                               quota.reserved_minor_units,
                               quota.used_minor_units
                        FROM monetary_stock_correction correction
                        JOIN monetary_supply_event event
                          ON event.event_id = correction.event_id
                        JOIN mint_recovery_incident incident
                          ON incident.incident_id = correction.incident_id
                        JOIN mint_batch batch
                          ON batch.batch_id = correction.batch_id
                        JOIN national_issuance_quota quota
                          ON quota.period_id = batch.period_id
                         AND quota.nation_id = batch.nation_id
                        WHERE correction.incident_id = ?
                        """)) {
            query.setString(1, incidentId.toString());
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "persisted Monetary Stock Correction");
                String correctionId = result.getString(1);
                helper.assertTrue(
                        result.getString(2).startsWith("civic-admin-console:"),
                        "correction administrator derives from command source");
                helper.assertValueEqual(requestId, result.getString(3), "correction request ID");
                helper.assertValueEqual(300L, result.getLong(4), "incident-derived amount");
                helper.assertValueEqual(
                        evidenceReference, result.getString(5), "immutable evidence reference");
                helper.assertValueEqual(reason, result.getString(6), "immutable correction reason");
                helper.assertValueEqual(
                        "STOCK_CORRECTION_INCREASE",
                        result.getString(7),
                        "separate Monetary Supply event kind");
                helper.assertValueEqual(
                        "mint-recovery-incident:" + incidentId,
                        result.getString(8),
                        "incident-scoped supply evidence");
                helper.assertValueEqual("RESOLVED", result.getString(9), "incident state");
                helper.assertValueEqual(
                        "STOCK_CORRECTION", result.getString(10), "incident resolution kind");
                helper.assertValueEqual(
                        correctionId, result.getString(11), "immutable correction resolution");
                helper.assertValueEqual("COMMITTING", result.getString(12), "Batch quarantine");
                helper.assertValueEqual("HELD", result.getString(13), "material quarantine");
                helper.assertValueEqual(300L, result.getLong(14), "reserved quota quarantine");
                helper.assertValueEqual(0L, result.getLong(15), "used quota remains unchanged");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Monetary Stock Correction command result", failure);
        }
    }

    private static ChunkDimPos paidClaimSeedPosition(ChunkDimPos target) {
        return new ChunkDimPos(
                target.dimension(), new ChunkPos(target.x() + 1, target.z()));
    }

    private static void assertFiscalServiceRegistered(
            GameTestHelper helper,
            Path databaseFile,
            String serviceIdentity,
            String ownerModId,
            String requestId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT s.owner_mod_id, a.administrator_identity, a.request_id, a.reason
                        FROM fiscal_service s
                        JOIN fiscal_service_registration_audit a
                          ON a.service_identity = s.service_identity
                        WHERE s.service_identity = ?
                        """)) {
            query.setString(1, serviceIdentity);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "registered fiscal service row");
                helper.assertValueEqual(ownerModId, result.getString(1), "registered owner Mod ID");
                helper.assertTrue(
                        result.getString(2).startsWith("civic-admin-console:"),
                        "registration administrator identity");
                helper.assertValueEqual(requestId, result.getString(3), "registration request ID");
                helper.assertValueEqual(
                        "Verify audited asynchronous registration",
                        result.getString(4),
                        "registration reason");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect fiscal service command result", failure);
        }
    }

    private static void assertDatabaseBackupCommitted(
            GameTestHelper helper, Path civicDirectory, String requestId) {
        Path databaseFile = civicDirectory.resolve("civic.sqlite3");
        String fileName;
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT operation.file_name,
                               operation.state,
                               operation.size_bytes,
                               operation.sha256,
                               operation.administrator_identity,
                               operation.reason,
                               COUNT(audit.audit_id) AS committed_audit_count
                        FROM database_backup_operation operation
                        JOIN database_backup_audit audit
                          ON audit.operation_id = operation.operation_id
                         AND audit.action = 'COMMITTED'
                        WHERE operation.request_id = ?
                        GROUP BY operation.operation_id
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "committed manual database backup row");
                fileName = result.getString(1);
                helper.assertValueEqual("COMMITTED", result.getString(2), "backup state");
                helper.assertTrue(result.getLong(3) > 0L, "backup size evidence");
                helper.assertValueEqual(64, result.getString(4).length(), "backup SHA-256");
                helper.assertTrue(
                        result.getString(5).startsWith("civic-admin-console:"),
                        "backup administrator identity");
                helper.assertValueEqual(
                        "GameTest manual online backup", result.getString(6), "backup reason");
                helper.assertValueEqual(1L, result.getLong(7), "single commit audit entry");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect database backup command", failure);
        }

        Path backupFile = civicDirectory.resolve("backups").resolve(fileName);
        helper.assertTrue(Files.isRegularFile(backupFile), "published database backup file");
        try (var backup = DriverManager.getConnection(
                        "jdbc:sqlite:" + backupFile.toAbsolutePath());
                var statement = backup.createStatement();
                var integrity = statement.executeQuery("PRAGMA integrity_check")) {
            helper.assertTrue(integrity.next(), "backup integrity result");
            helper.assertValueEqual("ok", integrity.getString(1), "backup SQLite integrity");
            try (var version = statement.executeQuery("PRAGMA user_version")) {
                helper.assertTrue(version.next(), "backup schema version result");
                helper.assertValueEqual(56, version.getInt(1), "backup schema version");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to validate published database backup", failure);
        }
    }

    private static UUID latestCommittedBackupOperation(Path databaseFile) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT operation_id
                        FROM database_backup_operation
                        WHERE state = 'COMMITTED'
                        ORDER BY committed_at_epoch_millis DESC, rowid DESC
                        LIMIT 1
                        """);
                var result = query.executeQuery()) {
            return result.next() ? UUID.fromString(result.getString(1)) : null;
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to find a restore source backup", failure);
        }
    }

    private static RestoreCommandRow databaseRestoreByRequest(
            Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT operation.operation_id,
                               operation.state,
                               SUM(CASE WHEN audit.action = 'STAGED' THEN 1 ELSE 0 END),
                               SUM(CASE WHEN audit.action = 'CANCELLED' THEN 1 ELSE 0 END)
                        FROM database_restore_operation operation
                        LEFT JOIN database_restore_audit audit
                          ON audit.operation_id = operation.operation_id
                        WHERE operation.request_id = ?
                        GROUP BY operation.operation_id
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                return result.next()
                        ? new RestoreCommandRow(
                                UUID.fromString(result.getString(1)),
                                result.getString(2),
                                result.getLong(3),
                                result.getLong(4))
                        : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect database restore command", failure);
        }
    }

    private static void assertNationFiscalPermissionGranted(
            GameTestHelper helper,
            Path databaseFile,
            UUID ftbTeamId,
            UUID headPlayerId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT grant_row.actor_player_id,
                               grant_row.player_id,
                               grant_row.permission,
                               grant_row.reason
                        FROM nation_fiscal_permission_grant grant_row
                        JOIN nation_registry nation
                          ON nation.nation_id = grant_row.nation_id
                        LEFT JOIN nation_fiscal_permission_revocation revocation
                          ON revocation.grant_id = grant_row.grant_id
                        WHERE nation.ftb_team_id = ?
                          AND revocation.grant_id IS NULL
                        """)) {
            query.setString(1, ftbTeamId.toString());
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "persistent Nation Fiscal Permission grant");
                helper.assertValueEqual(
                        headPlayerId.toString(), result.getString(1), "server-derived Nation head actor");
                helper.assertValueEqual(
                        headPlayerId.toString(), result.getString(2), "exact Citizen scope");
                helper.assertValueEqual("APPROVE_BUDGET", result.getString(3), "exact fiscal permission");
                helper.assertValueEqual("GameTest appointment", result.getString(4), "grant audit reason");
                helper.assertFalse(result.next(), "duplicate Nation Fiscal Permission grant");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect Nation fiscal role command result", failure);
        }
    }

    private static void assertTerritoryPolicyScheduled(
            GameTestHelper helper,
            Path databaseFile,
            String requestId,
            long effectiveAtEpochMillis) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT actor_identity, base_chunks,
                               chunks_per_effective_citizen,
                               effective_at_epoch_millis, reason
                        FROM territory_free_allocation_policy
                        WHERE service_identity = 'civiceconomy-territory-policy'
                          AND request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "persistent Territory Free Allocation policy");
                helper.assertTrue(
                        result.getString(1).startsWith("civic-admin-console:"),
                        "server-derived territory policy administrator");
                helper.assertValueEqual(9, result.getInt(2), "territory base chunks");
                helper.assertValueEqual(4, result.getInt(3), "territory chunks per Effective Citizen");
                helper.assertValueEqual(
                        effectiveAtEpochMillis, result.getLong(4), "territory policy effective time");
                helper.assertValueEqual(
                        "GameTest territory policy", result.getString(5), "territory policy reason");
                helper.assertFalse(result.next(), "duplicate Territory Free Allocation policy");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect territory policy command result", failure);
        }
    }

    private static void assertTerritoryPricingScheduled(
            GameTestHelper helper,
            Path databaseFile,
            String requestId,
            long effectiveAtEpochMillis) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT actor_identity, first_overage_chunk_cost,
                               additional_marginal_cost,
                               effective_at_epoch_millis, reason
                        FROM territory_expansion_pricing_policy
                        WHERE service_identity = 'civiceconomy-territory-pricing'
                          AND request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "persistent Territory Expansion pricing policy");
                helper.assertTrue(
                        result.getString(1).startsWith("civic-admin-console:"),
                        "server-derived territory pricing administrator");
                helper.assertValueEqual(100L, result.getLong(2), "first overage chunk cost");
                helper.assertValueEqual(50L, result.getLong(3), "additional marginal cost");
                helper.assertValueEqual(
                        effectiveAtEpochMillis, result.getLong(4), "territory pricing effective time");
                helper.assertValueEqual(
                        "GameTest territory pricing", result.getString(5), "territory pricing reason");
                helper.assertFalse(result.next(), "duplicate Territory Expansion pricing policy");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect territory pricing command result", failure);
        }
    }

    private static void assertTerritoryMaintenancePolicyScheduled(
            GameTestHelper helper,
            Path databaseFile,
            String requestId,
            long effectiveAtEpochMillis) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT actor_identity, cycle_duration_millis,
                               base_maintenance_per_chargeable_claim_minor_units,
                               enclave_cross_dimension_multiplier_basis_points,
                               force_load_surcharge_minor_units,
                               restoration_fee_minor_units, restoration_cooldown_millis,
                               destruction_basis_points,
                               effective_at_epoch_millis, reason
                        FROM territory_maintenance_policy
                        WHERE service_identity = 'civiceconomy-territory-maintenance-policy'
                          AND request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "persistent Territory Maintenance policy");
                helper.assertTrue(
                        result.getString(1).startsWith("civic-admin-console:"),
                        "server-derived maintenance policy administrator");
                helper.assertValueEqual(604_800_000L, result.getLong(2), "maintenance cycle duration");
                helper.assertValueEqual(75L, result.getLong(3), "base maintenance per claim");
                helper.assertValueEqual(15_000, result.getInt(4), "enclave multiplier");
                helper.assertValueEqual(25L, result.getLong(5), "force-load surcharge");
                helper.assertValueEqual(30L, result.getLong(6), "restoration fee");
                helper.assertValueEqual(1_209_600_000L, result.getLong(7), "restoration cooldown");
                helper.assertValueEqual(6_000, result.getInt(8), "destruction basis points");
                helper.assertValueEqual(
                        effectiveAtEpochMillis, result.getLong(9), "maintenance policy effective time");
                helper.assertValueEqual(
                        "GameTest territory maintenance", result.getString(10), "maintenance policy reason");
                helper.assertFalse(result.next(), "duplicate Territory Maintenance policy");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect territory maintenance policy command result", failure);
        }
    }

    private static long nextTerritoryMaintenancePolicyBoundary(Path databaseFile) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT MAX(boundary) FROM (
                            SELECT COALESCE(MAX(ends_at_epoch_millis), 0) AS boundary
                            FROM territory_maintenance_cycle
                            UNION ALL
                            SELECT COALESCE(MAX(effective_at_epoch_millis), 0) + 1 AS boundary
                            FROM territory_maintenance_policy
                        )
                        """)) {
            try (var result = query.executeQuery()) {
                return result.getLong(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to read next Territory Maintenance policy boundary", failure);
        }
    }

    private static void assertNationApplicationCreated(
            GameTestHelper helper,
            Path databaseFile,
            UUID ftbTeamId,
            UUID applicantPlayerId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT applicant_player_id, state,
                               expires_at_epoch_millis - created_at_epoch_millis
                        FROM nation_application
                        WHERE ftb_team_id = ?
                        ORDER BY created_at_epoch_millis DESC
                        """)) {
            query.setString(1, ftbTeamId.toString());
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "persistent Nation Application row");
                helper.assertValueEqual(
                        applicantPlayerId.toString(), result.getString(1), "application team head");
                helper.assertValueEqual("PENDING", result.getString(2), "application state");
                helper.assertValueEqual(
                        java.time.Duration.ofDays(7).toMillis(),
                        result.getLong(3),
                        "application lifetime");
                helper.assertFalse(result.next(), "duplicate pending Nation Application");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect Nation Application command result", failure);
        }
    }

    private static PendingApplicationRow pendingApplication(Path databaseFile, UUID ftbTeamId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT application_id, created_at_epoch_millis
                        FROM nation_application
                        WHERE ftb_team_id = ? AND state = 'PENDING'
                        """)) {
            query.setString(1, ftbTeamId.toString());
            try (var result = query.executeQuery()) {
                return result.next()
                        ? new PendingApplicationRow(
                                UUID.fromString(result.getString(1)), result.getLong(2))
                        : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to read PENDING Nation Application", failure);
        }
    }

    private static void assertNationApplicationCancelled(
            GameTestHelper helper,
            Path databaseFile,
            UUID applicationId,
            UUID applicantPlayerId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT application.state,
                               COUNT(candidate.player_id),
                               SUM(CASE WHEN candidate.ended_at_epoch_millis IS NOT NULL THEN 1 ELSE 0 END),
                               transition.actor_player_id,
                               transition.to_state,
                               transition.reason
                        FROM nation_application application
                        JOIN nation_application_candidate candidate
                          ON candidate.application_id = application.application_id
                        JOIN nation_application_transition transition
                          ON transition.application_id = application.application_id
                        WHERE application.application_id = ?
                        GROUP BY application.state, transition.actor_player_id,
                                 transition.to_state, transition.reason
                        """)) {
            query.setString(1, applicationId.toString());
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "cancelled Nation Application transition");
                helper.assertValueEqual("CANCELLED", result.getString(1), "application cancellation state");
                helper.assertValueEqual(result.getInt(2), result.getInt(3), "ended Candidate affiliations");
                helper.assertValueEqual(
                        applicantPlayerId.toString(), result.getString(4), "cancellation actor");
                helper.assertValueEqual("CANCELLED", result.getString(5), "cancellation transition");
                helper.assertValueEqual("GameTest cancellation", result.getString(6), "cancellation reason");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect cancelled Nation Application", failure);
        }
    }

    private static void assertNationApplicationAutomaticallyExpired(
            GameTestHelper helper,
            Path databaseFile,
            NationApplicationId applicationId,
            UUID candidatePlayerId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT application.state,
                               candidate.ended_at_epoch_millis,
                               transition.service_identity,
                               transition.request_id,
                               transition.actor_player_id,
                               transition.to_state
                        FROM nation_application application
                        JOIN nation_application_candidate candidate
                          ON candidate.application_id = application.application_id
                         AND candidate.player_id = ?
                        JOIN nation_application_transition transition
                          ON transition.application_id = application.application_id
                        WHERE application.application_id = ?
                        """)) {
            query.setString(1, candidatePlayerId.toString());
            query.setString(2, applicationId.value().toString());
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "automatic Nation Application expiry transition");
                helper.assertValueEqual("EXPIRED", result.getString(1), "automatic expiry state");
                helper.assertTrue(result.getObject(2) != null, "ended automatic-expiry Candidate affiliation");
                helper.assertValueEqual(
                        "civiceconomy-nation-application-expiry",
                        result.getString(3),
                        "automatic expiry service");
                helper.assertValueEqual(
                        "automatic-expiry:" + applicationId.value(),
                        result.getString(4),
                        "automatic expiry request identity");
                helper.assertTrue(result.getString(5) == null, "automatic expiry has no player actor");
                helper.assertValueEqual("EXPIRED", result.getString(6), "automatic expiry transition state");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect automatic Nation Application expiry", failure);
        }
    }

    private static void assertCitizenshipCorrectionGraceStarted(
            GameTestHelper helper,
            Path databaseFile,
            UUID ftbTeamId,
            UUID playerId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT grace.ftb_team_id, grace.resolution,
                               grace.deadline_epoch_millis - grace.started_at_epoch_millis,
                               citizenship.ended_at_epoch_millis
                        FROM citizenship_correction_grace grace
                        JOIN citizenship_period citizenship
                          ON citizenship.citizenship_id = grace.citizenship_id
                        WHERE grace.player_id = ?
                        ORDER BY grace.started_at_epoch_millis DESC
                        LIMIT 1
                        """)) {
            query.setString(1, playerId.toString());
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "persistent Citizenship Correction Grace");
                helper.assertValueEqual(ftbTeamId.toString(), result.getString(1), "grace FTB Team");
                helper.assertTrue(result.getString(2) == null, "active Citizenship Correction Grace");
                helper.assertValueEqual(
                        java.time.Duration.ofDays(2).toMillis(),
                        result.getLong(3),
                        "Citizenship Correction Grace duration");
                helper.assertTrue(
                        result.getObject(4) == null,
                        "formal Citizenship remains during Correction Grace");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect Citizenship Correction Grace", failure);
        }
    }

    private static AccountId activatedTreasury(
            GameTestHelper helper,
            Path databaseFile,
            UUID applicationId,
            UUID playerId,
            ServerPlayer player) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT activation.treasury_account_id, application.state,
                               capital.dimension_id, capital.chunk_x, capital.chunk_z,
                               COUNT(citizenship.citizenship_id)
                        FROM nation_application application
                        JOIN nation_application_activation activation
                          ON activation.application_id = application.application_id
                        JOIN nation_capital capital ON capital.nation_id = activation.nation_id
                        LEFT JOIN citizenship_period citizenship
                          ON citizenship.nation_id = activation.nation_id
                         AND citizenship.player_id = ?
                         AND citizenship.ended_at_epoch_millis IS NULL
                        WHERE application.application_id = ?
                        GROUP BY activation.treasury_account_id, application.state,
                                 capital.dimension_id, capital.chunk_x, capital.chunk_z
                        """)) {
            query.setString(1, playerId.toString());
            query.setString(2, applicationId.toString());
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "committed Nation activation row");
                helper.assertValueEqual("ACTIVATED", result.getString(2), "application activation state");
                helper.assertValueEqual(
                        player.level().dimension().location().toString(),
                        result.getString(3),
                        "Capital dimension");
                ChunkPos expectedCapital = new ChunkPos(player.blockPosition());
                helper.assertValueEqual(expectedCapital.x, result.getInt(4), "Capital chunk X");
                helper.assertValueEqual(expectedCapital.z, result.getInt(5), "Capital chunk Z");
                helper.assertValueEqual(1, result.getInt(6), "active founder Citizenship");
                return new AccountId(result.getString(1));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect committed Nation activation", failure);
        }
    }

    private static void claimCapital(GameTestHelper helper, Team team, ServerPlayer player) {
        ChunkPos chunk = new ChunkPos(player.blockPosition());
        ChunkDimPos position = new ChunkDimPos(player.level().dimension(), chunk);
        var manager = FTBChunksAPI.api().getManager();
        ClaimedChunk existing = manager.getChunk(position);
        if (existing != null) {
            existing.unclaim(helper.getLevel().getServer().createCommandSourceStack(), true);
        }
        var teamData = manager.getOrCreateData(team);
        teamData.setExtraClaimChunks(Math.max(100, teamData.getExtraClaimChunks()));
        ((ChunkTeamDataImpl) teamData).updateLimits();
        var claim = teamData.claim(
                helper.getLevel().getServer().createCommandSourceStack(), position, false);
        if (!claim.isSuccess()) {
            throw new IllegalStateException(
                    "Real FTB Capital claim failed: " + claim.getResultId()
                            + " max=" + teamData.getMaxClaimChunks()
                            + " claimed=" + teamData.getClaimedChunks().size());
        }
    }

    private static void unclaimCapital(GameTestHelper helper, ServerPlayer player) {
        ChunkDimPos position = new ChunkDimPos(
                player.level().dimension(), new ChunkPos(player.blockPosition()));
        ClaimedChunk claimed = FTBChunksAPI.api().getManager().getChunk(position);
        if (claimed != null) {
            claimed.unclaim(helper.getLevel().getServer().createCommandSourceStack(), true);
        }
    }

    private static Team createHeadOwnedFtbTeamFixture(ServerPlayer player) {
        try {
            var method = TeamManagerImpl.class.getDeclaredMethod(
                    "createPartyTeamInternal", UUID.class, ServerPlayer.class, String.class);
            method.setAccessible(true);
            Team team = (Team) method.invoke(
                    FTBTeamsAPI.api().getManager(),
                    player.getUUID(),
                    null,
                    "Civic Founding " + UUID.randomUUID());
            ((AbstractTeamBase) team).addMember(player.getUUID(), TeamRank.OWNER);
            team.markDirty();
            return team;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "Unable to create exact-version FTB founding GameTest fixture", failure);
        }
    }

    private static NationTeamDirectory snapshot(NationTeam team) {
        return new NationTeamDirectory() {
            @Override
            public java.util.Optional<NationTeam> find(UUID teamId) {
                return team.teamId().equals(teamId)
                        ? java.util.Optional.of(team)
                        : java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
                return team.citizens().contains(playerId)
                        ? java.util.Optional.of(team)
                        : java.util.Optional.empty();
            }
        };
    }

    private record PendingApplicationRow(UUID applicationId, long createdAtEpochMillis) {}

    private record TerritoryPermitRow(UUID permitId, TerritoryClaimPermitState state) {}

    private record RestoreCommandRow(
            UUID operationId,
            String state,
            long stagedAuditCount,
            long cancelledAuditCount) {}

    private record TerritoryRestorationRow(
            UUID restorationId,
            String state,
            UUID sourceAssessmentId,
            long prepayment,
            long fee,
            long total,
            String sourceValidity) {}

    private record PermanentDestructionRow(
            String sourceAccount,
            long amountMinorUnits,
            String operatorIdentity,
            String reason,
            String state) {}

    private record TreasuryWithdrawalRow(
            String nationId,
            String sourceAccount,
            String actorPlayerId,
            long amountMinorUnits,
            String reason,
            String state,
            String approvalRequestId) {}

    private record TreasuryWithdrawalApprovalRow(
            UUID approvalRequestId,
            String state,
            int requiredApprovals,
            int approvalCount) {}
}
