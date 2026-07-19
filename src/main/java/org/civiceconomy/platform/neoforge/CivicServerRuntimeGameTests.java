package org.civiceconomy.platform.neoforge;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.github.lightman314.lightmanscurrency.api.money.bank.BankAPI;
import io.github.lightman314.lightmanscurrency.api.money.coins.CoinAPI;
import io.github.lightman314.lightmanscurrency.api.money.MoneyAPI;
import io.github.lightman314.lightmanscurrency.api.money.value.builtin.CoinValue;
import io.github.lightman314.lightmanscurrency.common.data.CustomSaveData;
import io.github.lightman314.lightmanscurrency.common.data.types.BankDataCache;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamRank;
import dev.ftb.mods.ftbteams.data.AbstractTeam;
import dev.ftb.mods.ftbteams.data.AbstractTeamBase;
import dev.ftb.mods.ftbteams.data.PlayerTeam;
import dev.ftb.mods.ftbteams.data.TeamManagerImpl;
import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.data.ChunkTeamDataImpl;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.civiceconomy.CivicEconomy;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.BudgetDisbursementApprovalRegistry;
import org.civiceconomy.fiscal.BudgetDisbursementPaymentCoordinator;
import org.civiceconomy.fiscal.BudgetFiscalServiceProvisioner;
import org.civiceconomy.fiscal.ExternalPayment;
import org.civiceconomy.fiscal.FiscalBillFiscalServiceProvisioner;
import org.civiceconomy.fiscal.FiscalBillKind;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.InitiateBudgetDisbursementApproval;
import org.civiceconomy.fiscal.NationBudgetDisbursementApprovalCoordinator;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.integration.ftb.FtbNationTeamDirectory;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyFiscalAccounts;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyNationalTreasuryProvisioner;
import org.civiceconomy.integration.lightmanscurrency.FiscalAccountKind;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyPayments;
import org.civiceconomy.nation.OnlineTimeLedger;
import org.civiceconomy.nation.RecordOnlineTime;
import org.civiceconomy.nation.ActivateNationApplication;
import org.civiceconomy.nation.ActivatedNation;
import org.civiceconomy.nation.Capital;
import org.civiceconomy.nation.CreateNationApplication;
import org.civiceconomy.nation.NationActivationCoordinator;
import org.civiceconomy.nation.NationApplicationId;
import org.civiceconomy.nation.NationApplicationRegistry;
import org.civiceconomy.nation.NationFoundingPolicy;
import org.civiceconomy.nation.NationId;
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
import org.civiceconomy.strength.NationalStrengthRecalculation;
import org.civiceconomy.strength.NationalStrengthSnapshot;
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
import org.civiceconomy.persistence.StoredFacilityClaim;
import org.civiceconomy.persistence.StoredFacilityAccountingBaseline;
import org.civiceconomy.persistence.StoredFacilityAccountingInterface;
import org.civiceconomy.persistence.StoredFacilityBaselineInventory;
import org.civiceconomy.persistence.StoredFacilityBaselineMachine;
import org.civiceconomy.persistence.StoredFacilityAccountingReceipt;
import org.civiceconomy.persistence.StoredFacilityProductionDecision;
import org.civiceconomy.persistence.StoredProductionInventoryChange;
import org.civiceconomy.persistence.StoredProductionInventoryExport;
import org.civiceconomy.persistence.StoredProductionMarginalReturnContribution;
import org.civiceconomy.persistence.StoredRegisteredFacility;
import org.civiceconomy.persistence.StoredRegisteredFacilityStateTransition;
import org.civiceconomy.production.FacilityAdministration;
import org.civiceconomy.production.FacilityAccountingBaseline;
import org.civiceconomy.production.FacilityAccountingStatus;
import org.civiceconomy.production.ProductionInventoryExport;
import org.civiceconomy.production.ProductionInventoryExportKind;
import org.civiceconomy.production.GlobalReferencePriceRegistry;
import org.civiceconomy.production.ProductionIndustryAssignmentRegistry;
import org.civiceconomy.production.ProductionIndustryId;
import org.civiceconomy.production.ProductionMarginalReturnPolicy;
import org.civiceconomy.production.ProductionMarginalReturnPolicyRegistry;
import org.civiceconomy.production.ProductionStrengthPolicy;
import org.civiceconomy.production.ProductionStrengthPolicyRegistry;
import org.civiceconomy.production.ScheduleGlobalReferencePrice;
import org.civiceconomy.production.ScheduleProductionIndustryAssignment;
import org.civiceconomy.production.ScheduleProductionMarginalReturnPolicy;
import org.civiceconomy.production.ScheduleProductionStrengthPolicy;
import org.civiceconomy.production.RegisteredFacility;
import org.civiceconomy.production.RegisteredFacilityScopePolicy;
import org.civiceconomy.production.RegisteredFacilityScopePolicyRegistry;
import org.civiceconomy.production.ScheduleRegisteredFacilityScopePolicy;
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

    static void prepareBudgetDisbursementProcessRestart(
            GameTestHelper helper) {
        CivicServerRuntime runtime = CivicServerRuntime.current();
        java.time.Instant now = java.time.Instant.now();
        java.time.Clock setupClock =
                java.time.Clock.fixed(now, java.time.ZoneOffset.UTC);
        org.civiceconomy.nation.NationId nationId =
                new org.civiceconomy.nation.NationId(UUID.randomUUID());
        UUID actorPlayerId = UUID.randomUUID();
        UUID recipientPlayerId = UUID.randomUUID();
        String requestId = BudgetDisbursementProcessRestartDrill.REQUEST_PREFIX
                + UUID.randomUUID();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicReference<BudgetDisbursementRestartPreparation> preparation =
                new AtomicReference<>();

        runtime.submitDatabase(database -> {
                    database.registerNation(
                            nationId.value(),
                            "civiceconomy-budget-disbursement-restart-drill",
                            "register-" + requestId,
                            UUID.randomUUID(),
                            now.minusSeconds(60L).toEpochMilli());
                    UUID budgetId = UUID.randomUUID();
                    database.createBudget(
                            budgetId,
                            BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                            "budget-" + requestId,
                            "nation:" + nationId.value() + ":treasury",
                            100L,
                            "PUBLIC_WORKS",
                            "Matched process restart Budget",
                            now.plus(java.time.Duration.ofDays(1L)).toEpochMilli());
                    database.approveBudget(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                            "approve-budget-" + requestId,
                            budgetId,
                            actorPlayerId,
                            "Approve matched process restart Budget",
                            now.minusSeconds(30L).toEpochMilli());
                    var approval = new BudgetDisbursementApprovalRegistry(
                                    database, setupClock)
                            .initiate(new InitiateBudgetDisbursementApproval(
                                    NationBudgetDisbursementApprovalCoordinator
                                            .SERVICE_IDENTITY,
                                    requestId,
                                    nationId,
                                    budgetId,
                                    new AccountId("player:" + recipientPlayerId),
                                    MoneyAmount.ofMinorUnits(100L),
                                    actorPlayerId,
                                    "Matched process restart Disbursement"));
                    var prepared = new BudgetDisbursementPaymentCoordinator(
                                    database, ignored -> {})
                            .prepare(approval.approvalRequestId());
                    return new BudgetDisbursementRestartPreparation(
                            nationId,
                            recipientPlayerId);
                })
                .whenComplete((prepared, failure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                                return;
                            }
                            try {
                                clearPlayerBank(prepared.recipientPlayerId());
                                fundTreasury(
                                        helper,
                                        prepared.nationId(),
                                        "Budget restart drill Treasury",
                                        500L);
                                preparation.set(prepared);
                            } catch (Throwable setupFailure) {
                                asyncFailure.set(setupFailure);
                            }
                        }));

        helper.succeedWhen(() -> {
            Throwable failure = asyncFailure.get();
            helper.assertTrue(
                    failure == null,
                    failure == null
                            ? "Budget Disbursement restart preparation state"
                            : "Budget Disbursement restart preparation failed: "
                                    + failure.getMessage());
            helper.assertTrue(
                    preparation.get() != null,
                    "Budget Disbursement restart preparation ready");
            runtime.triggerBudgetDisbursementRecovery();
            helper.assertTrue(
                    false,
                    "Budget Disbursement restart preparation expected controlled process halt");
        });
    }

    static void verifyBudgetDisbursementProcessRestart(
            GameTestHelper helper) {
        BudgetDisbursementProcessRestartDrill.Marker marker =
                BudgetDisbursementProcessRestartDrill.readMarker(
                        helper.getLevel().getServer());
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        CivicServerRuntime runtime = CivicServerRuntime.current();

        helper.succeedWhen(() -> {
            BudgetDisbursementRow recovered = budgetDisbursementByRequest(
                    databaseFile, marker.requestId());
            helper.assertTrue(
                    recovered != null,
                    "restarted Budget Disbursement row exists");
            if (!"CIVIC_COMMITTED".equals(recovered.paymentState())) {
                runtime.triggerBudgetDisbursementRecovery();
                helper.assertTrue(
                        false,
                        "waiting for matched Budget Disbursement restart recovery");
            }
            helper.assertValueEqual(
                    "EXECUTED",
                    recovered.approvalState(),
                    "restarted Budget Disbursement approval state");
            helper.assertValueEqual(
                    marker.approvalRequestId(),
                    recovered.approvalRequestId(),
                    "restarted Budget Disbursement approval identity");
            helper.assertValueEqual(
                    marker.transactionId(),
                    recovered.transactionId(),
                    "restarted Budget Disbursement transaction identity");
            helper.assertValueEqual(
                    1,
                    recovered.paymentCount(),
                    "restarted Budget Disbursement has one Payment");
            helper.assertValueEqual(
                    400L,
                    LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                            .balance(new AccountId(marker.treasuryAccount()))
                            .minorUnits(),
                    "restarted Budget Disbursement debits Treasury once");
            helper.assertValueEqual(
                    100L,
                    playerBankBalance(marker.recipientPlayerId()),
                    "restarted Budget Disbursement credits recipient once");
            assertRestartedBudgetDisbursementAccounting(
                    helper, databaseFile, marker);
        });
    }

    static void prepareMatchedWorldRollback(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        ServerPlayer player = connectMockServerPlayer(
                helper, playerId, "civic-rollback");
        Team team = createHeadOwnedFtbTeamFixture(player);
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        String requestId = MatchedWorldRollbackDrill.REQUEST_PREFIX + UUID.randomUUID();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicReference<PermanentDestructionRestartPreparation> preparation =
                new AtomicReference<>();
        AtomicBoolean snapshotReady = new AtomicBoolean();
        AtomicBoolean destructionStarted = new AtomicBoolean();
        CivicServerRuntime runtime = CivicServerRuntime.current();
        Instant now = Instant.now();
        Clock setupClock = Clock.fixed(now, ZoneOffset.UTC);
        Path databaseBackup = MatchedWorldRollbackDrill.databaseBackup(
                helper.getLevel().getServer());

        runtime.submitDatabase(database -> {
                    NationRegistry nations = new NationRegistry(
                            database, snapshot(teamSnapshot));
                    var nation = nations.register(new RegisterNation(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "matched-rollback-nation-" + UUID.randomUUID(),
                            team.getId()));
                    CitizenshipRegistry citizenships = new CitizenshipRegistry(
                            database, Duration.ofDays(7L), setupClock);
                    citizenships.join(new JoinCitizenship(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "matched-rollback-citizenship-" + UUID.randomUUID(),
                            playerId,
                            nation.nationId()));
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            citizenships,
                            new CitizenshipCorrectionGraceRegistry(database, setupClock),
                            snapshot(teamSnapshot));
                    new NationFiscalAuthorityRegistry(database, provider, setupClock)
                            .grant(new GrantNationFiscalPermission(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "matched-rollback-authority-" + UUID.randomUUID(),
                                    nation.nationId(),
                                    playerId,
                                    playerId,
                                    NationFiscalPermission.MANAGE_ISSUANCE,
                                    "Matched full-world rollback drill"));
                    long issuanceBefore = database.cumulativeNetIssuanceMinorUnits();
                    database.confirmMonetarySupplyChange(
                            UUID.randomUUID(),
                            "civiceconomy-gametest",
                            "matched-rollback-issuance-" + UUID.randomUUID(),
                            "ISSUANCE",
                            500L,
                            "mint-batch:matched-rollback:" + UUID.randomUUID(),
                            "Seed matched rollback state",
                            now.toEpochMilli(),
                            Math.addExact(issuanceBefore, 5_000L));
                    return new PermanentDestructionRestartPreparation(
                            nation.nationId(),
                            database.cumulativeNetIssuanceMinorUnits());
                })
                .whenComplete((prepared, failure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                                return;
                            }
                            try {
                                fundTreasury(
                                        helper,
                                        prepared.nationId(),
                                        "Matched rollback Treasury",
                                        500L);
                                if (!helper.getLevel().getServer()
                                        .saveEverything(false, true, false)) {
                                    throw new IllegalStateException(
                                            "Unable to flush matched rollback baseline world");
                                }
                                preparation.set(prepared);
                                runtime.submitDatabase(database -> {
                                            database.backup(databaseBackup);
                                            return null;
                                        })
                                        .whenComplete((ignored, backupFailure) ->
                                                helper.getLevel().getServer().execute(() -> {
                                                    if (backupFailure != null) {
                                                        asyncFailure.set(backupFailure);
                                                        return;
                                                    }
                                                    try {
                                                        MatchedWorldRollbackDrill.capture(
                                                                helper.getLevel().getServer(),
                                                                databaseBackup);
                                                        snapshotReady.set(true);
                                                    } catch (Throwable captureFailure) {
                                                        asyncFailure.set(captureFailure);
                                                    }
                                                }));
                            } catch (Throwable setupFailure) {
                                asyncFailure.set(setupFailure);
                            }
                        }));

        helper.succeedWhen(() -> {
            assertNoAsyncFailure(helper, asyncFailure, "Matched world rollback preparation");
            helper.assertTrue(snapshotReady.get(), "matched world snapshot is ready");
            if (destructionStarted.compareAndSet(false, true)) {
                runtime.destroyNationalTreasury(
                                player,
                                requestId,
                                100L,
                                "Later state after matched rollback snapshot")
                        .whenComplete((ignored, failure) ->
                                helper.getLevel().getServer().execute(() -> {
                                    if (failure != null) {
                                        asyncFailure.set(failure);
                                        return;
                                    }
                                    PermanentDestructionRestartPreparation prepared =
                                            preparation.get();
                                    MatchedWorldRollbackDrill.haltAfterLaterState(
                                            helper.getLevel().getServer(),
                                            new MatchedWorldRollbackDrill.Marker(
                                                    requestId,
                                                    prepared.nationId().value(),
                                                    "nation:" + prepared.nationId().value()
                                                            + ":treasury",
                                                    prepared.issuanceBeforeMinorUnits(),
                                                    100L));
                                }));
            }
            helper.assertTrue(false, "matched rollback preparation expected exit code 95");
        });
    }

    static void verifyMatchedWorldRollback(GameTestHelper helper) {
        MatchedWorldRollbackDrill.Marker marker = MatchedWorldRollbackDrill.readMarker(
                helper.getLevel().getServer());
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        helper.assertValueEqual(
                500L,
                LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                        .balance(new AccountId(marker.treasuryAccount()))
                        .minorUnits(),
                "matched rollback restores the Treasury balance");
        helper.assertValueEqual(
                marker.issuanceMinorUnits(),
                cumulativeNetIssuance(databaseFile),
                "matched rollback restores Cumulative Net Issuance");
        helper.assertTrue(
                permanentDestructionByRequest(databaseFile, marker.requestId()) == null,
                "later Permanent Destruction operation is absent after matched rollback");
        helper.assertValueEqual(
                0L,
                permanentDestructionEventCountByRequest(databaseFile, marker.requestId()),
                "later Permanent Destruction event is absent after matched rollback");
        helper.succeed();
    }

    static void preparePermanentDestructionProcessRestart(
            GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        ServerPlayer player = connectMockServerPlayer(
                helper, playerId, "civic-pd-restart");
        Team team = createHeadOwnedFtbTeamFixture(player);
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        String requestId = PermanentDestructionProcessRestartDrill.REQUEST_PREFIX
                + UUID.randomUUID();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicReference<PermanentDestructionRestartPreparation> preparation =
                new AtomicReference<>();
        AtomicBoolean destructionStarted = new AtomicBoolean();
        CivicServerRuntime runtime = CivicServerRuntime.current();
        Instant now = Instant.now();
        Clock setupClock = Clock.fixed(now, ZoneOffset.UTC);

        runtime.submitDatabase(database -> {
                    NationRegistry nations = new NationRegistry(
                            database, snapshot(teamSnapshot));
                    var nation = nations.register(new RegisterNation(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "destruction-restart-nation-" + UUID.randomUUID(),
                            team.getId()));
                    CitizenshipRegistry citizenships = new CitizenshipRegistry(
                            database, Duration.ofDays(7L), setupClock);
                    citizenships.join(new JoinCitizenship(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "destruction-restart-citizenship-" + UUID.randomUUID(),
                            playerId,
                            nation.nationId()));
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            citizenships,
                            new CitizenshipCorrectionGraceRegistry(database, setupClock),
                            snapshot(teamSnapshot));
                    new NationFiscalAuthorityRegistry(database, provider, setupClock)
                            .grant(new GrantNationFiscalPermission(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "destruction-restart-authority-" + UUID.randomUUID(),
                                    nation.nationId(),
                                    playerId,
                                    playerId,
                                    NationFiscalPermission.MANAGE_ISSUANCE,
                                    "Matched Permanent Destruction process restart"));
                    long issuanceBefore = database.cumulativeNetIssuanceMinorUnits();
                    database.confirmMonetarySupplyChange(
                            UUID.randomUUID(),
                            "civiceconomy-gametest",
                            "destruction-restart-issuance-" + UUID.randomUUID(),
                            "ISSUANCE",
                            500L,
                            "mint-batch:destruction-restart:" + UUID.randomUUID(),
                            "Seed Permanent Destruction restart capacity",
                            now.toEpochMilli(),
                            Math.addExact(issuanceBefore, 5_000L));
                    return new PermanentDestructionRestartPreparation(
                            nation.nationId(),
                            database.cumulativeNetIssuanceMinorUnits());
                })
                .whenComplete((prepared, failure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                                return;
                            }
                            try {
                                fundTreasury(
                                        helper,
                                        prepared.nationId(),
                                        "Permanent Destruction restart drill Treasury",
                                        500L);
                                String treasury =
                                        "nation:" + prepared.nationId().value() + ":treasury";
                                PermanentDestructionProcessRestartDrill.expect(
                                        requestId,
                                        treasury,
                                        100L,
                                        prepared.issuanceBeforeMinorUnits());
                                preparation.set(prepared);
                            } catch (Throwable setupFailure) {
                                asyncFailure.set(setupFailure);
                            }
                        }));

        helper.succeedWhen(() -> {
            assertNoAsyncFailure(
                    helper, asyncFailure, "Permanent Destruction restart preparation");
            helper.assertTrue(
                    preparation.get() != null,
                    "Permanent Destruction restart preparation ready");
            if (destructionStarted.compareAndSet(false, true)) {
                runtime.destroyNationalTreasury(
                                player,
                                requestId,
                                100L,
                                "Matched Permanent Destruction process restart")
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                            } else {
                                asyncFailure.set(new AssertionError(
                                        "Permanent Destruction restart preparation completed without halt"));
                            }
                        });
            }
            helper.assertTrue(
                    false,
                    "Permanent Destruction restart preparation expected controlled process halt");
        });
    }

    static void verifyPermanentDestructionProcessRestart(
            GameTestHelper helper) {
        PermanentDestructionProcessRestartDrill.Marker marker =
                PermanentDestructionProcessRestartDrill.readMarker(
                        helper.getLevel().getServer());
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        CivicServerRuntime runtime = CivicServerRuntime.current();

        PermanentDestructionRow prepared = permanentDestructionByRequest(
                databaseFile, marker.requestId());
        helper.assertTrue(prepared != null, "restarted Permanent Destruction row exists");
        helper.assertValueEqual(
                marker.operationId(),
                prepared.operationId(),
                "restarted Permanent Destruction operation identity");
        helper.assertValueEqual(
                "PREPARED", prepared.state(), "restart-window destruction state");
        helper.assertValueEqual(
                marker.haltedAfterExternalRecord(),
                prepared.externalAppliedAtEpochMillis() != null,
                "restart-window external-application record state");
        helper.assertValueEqual(
                400L,
                LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                        .balance(new AccountId(marker.treasuryAccount()))
                        .minorUnits(),
                "restart-window Treasury debit persisted once");
        helper.assertValueEqual(
                marker.issuanceBeforeMinorUnits(),
                cumulativeNetIssuance(databaseFile),
                "restart-window Cumulative Net Issuance is unchanged");
        helper.assertValueEqual(
                0L,
                permanentDestructionEventCount(databaseFile, marker.operationId()),
                "restart-window has no Monetary Supply destruction event");

        helper.succeedWhen(() -> {
            runtime.recoverPermanentDestructionsNowForGameTest();
            PermanentDestructionRow recovered = permanentDestructionByRequest(
                    databaseFile, marker.requestId());
            if (!"COMMITTED".equals(recovered.state())) {
                helper.assertTrue(
                        false,
                        "waiting for matched Permanent Destruction restart recovery");
            }
            helper.assertValueEqual(
                    marker.operationId(),
                    recovered.operationId(),
                    "recovered Permanent Destruction identity");
            helper.assertValueEqual(
                    400L,
                    LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                            .balance(new AccountId(marker.treasuryAccount()))
                            .minorUnits(),
                    "recovered Treasury is not debited twice");
            helper.assertValueEqual(
                    marker.issuanceBeforeMinorUnits() - marker.amountMinorUnits(),
                    cumulativeNetIssuance(databaseFile),
                    "recovered Cumulative Net Issuance decreases once");
            helper.assertValueEqual(
                    1L,
                    permanentDestructionEventCount(databaseFile, marker.operationId()),
                    "one Permanent Destruction Monetary Supply event");
        });
    }

    static void prepareTreasuryWithdrawalProcessRestart(
            GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        ServerPlayer player = connectMockServerPlayer(
                helper, playerId, "civic-wd-restart");
        player.getInventory().clearContent();
        player.getInventory().setChanged();
        Team team = createHeadOwnedFtbTeamFixture(player);
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        String requestId = TreasuryWithdrawalProcessRestartDrill.REQUEST_PREFIX
                + UUID.randomUUID();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicBoolean setupReady = new AtomicBoolean();
        AtomicBoolean withdrawalStarted = new AtomicBoolean();
        CivicServerRuntime runtime = CivicServerRuntime.current();
        java.time.Instant now = java.time.Instant.now();
        java.time.Clock setupClock =
                java.time.Clock.fixed(now, java.time.ZoneOffset.UTC);

        runtime.submitDatabase(database -> {
                    NationRegistry nations = new NationRegistry(
                            database, snapshot(teamSnapshot));
                    var nation = nations.register(new RegisterNation(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "withdrawal-restart-nation-" + UUID.randomUUID(),
                            team.getId()));
                    CitizenshipRegistry citizenships = new CitizenshipRegistry(
                            database, java.time.Duration.ofDays(7L), setupClock);
                    citizenships.join(new JoinCitizenship(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "withdrawal-restart-citizenship-" + UUID.randomUUID(),
                            playerId,
                            nation.nationId()));
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            citizenships,
                            new CitizenshipCorrectionGraceRegistry(database, setupClock),
                            snapshot(teamSnapshot));
                    new NationFiscalAuthorityRegistry(database, provider, setupClock)
                            .grant(new GrantNationFiscalPermission(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "withdrawal-restart-authority-" + UUID.randomUUID(),
                                    nation.nationId(),
                                    playerId,
                                    playerId,
                                    NationFiscalPermission.MANAGE_WITHDRAWAL,
                                    "Matched Treasury Withdrawal process restart"));
                    return nation.nationId();
                })
                .whenComplete((nationId, failure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                                return;
                            }
                            try {
                                fundTreasury(
                                        helper,
                                        nationId,
                                        "Treasury Withdrawal restart drill Treasury",
                                        500L);
                                TreasuryWithdrawalProcessRestartDrill.expect(
                                        requestId, playerId);
                                setupReady.set(true);
                            } catch (Throwable setupFailure) {
                                asyncFailure.set(setupFailure);
                            }
                        }));

        helper.succeedWhen(() -> {
            assertNoAsyncFailure(
                    helper, asyncFailure, "Treasury Withdrawal restart preparation");
            helper.assertTrue(
                    setupReady.get(),
                    "Treasury Withdrawal restart preparation ready");
            if (withdrawalStarted.compareAndSet(false, true)) {
                runtime.withdrawNationalTreasury(
                                player,
                                requestId,
                                100L,
                                "Matched Treasury Withdrawal process restart")
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                            } else {
                                asyncFailure.set(new AssertionError(
                                        "Treasury Withdrawal restart preparation completed without halt"));
                            }
                        });
            }
            helper.assertTrue(
                    false,
                    "Treasury Withdrawal restart preparation expected controlled process halt");
        });
    }

    static void prepareNationActivationProcessRestart(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(playerId, "civic-nation-restart"),
                ClientInformation.createDefault());
        Team team = createHeadOwnedFtbTeamFixture(player);
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        ChunkPos capitalChunk = new ChunkPos(player.blockPosition());
        Capital capital = new Capital(
                player.level().dimension().location().toString(),
                capitalChunk.x,
                capitalChunk.z);
        String requestId = NationActivationProcessRestartDrill.REQUEST_PREFIX
                + UUID.randomUUID();
        Instant now = Instant.now();
        Instant appliedAt = now.minus(Duration.ofHours(2L));
        Clock applicationClock = Clock.fixed(appliedAt, ZoneOffset.UTC);
        Clock activationClock = Clock.fixed(now, ZoneOffset.UTC);
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicReference<NationApplicationId> applicationId = new AtomicReference<>();
        AtomicBoolean setupReady = new AtomicBoolean();
        AtomicBoolean activationStarted = new AtomicBoolean();
        CivicServerRuntime runtime = CivicServerRuntime.current();

        runtime.submitDatabase(database -> {
                    NationApplicationRegistry applications = new NationApplicationRegistry(
                            database, snapshot(teamSnapshot), applicationClock);
                    var application = applications.create(new CreateNationApplication(
                            new ServiceIdentity(
                                    NationActivationProcessRestartDrill.SERVICE_IDENTITY),
                            "nation-activation-restart-application-" + UUID.randomUUID(),
                            team.getId(),
                            playerId,
                            now.plus(Duration.ofDays(1L))));
                    new OnlineTimeLedger(database).record(new RecordOnlineTime(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "nation-activation-restart-evidence-" + UUID.randomUUID(),
                            playerId,
                            appliedAt.toEpochMilli(),
                            now.toEpochMilli()));
                    return application.applicationId();
                })
                .whenComplete((createdApplicationId, failure) ->
                        helper.getLevel().getServer().execute(() -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                                return;
                            }
                            applicationId.set(createdApplicationId);
                            NationActivationProcessRestartDrill.expect(
                                    createdApplicationId.value(),
                                    requestId,
                                    team.getId(),
                                    playerId,
                                    capital);
                            setupReady.set(true);
                        }));

        helper.succeedWhen(() -> {
            assertNoAsyncFailure(
                    helper, asyncFailure, "Nation Activation restart preparation");
            helper.assertTrue(setupReady.get(), "Nation Activation restart setup ready");
            if (activationStarted.compareAndSet(false, true)) {
                runtime.submitDatabase(database -> new NationActivationCoordinator(
                                database,
                                NationActivationProcessRestartDrill.provisioner(
                                        helper.getLevel().getServer()),
                                NationFoundingPolicy.debugWorld(
                                        2,
                                        Duration.ofDays(60L),
                                        Duration.ofDays(7L)),
                                activationClock)
                        .activate(new ActivateNationApplication(
                                new ServiceIdentity(
                                        NationActivationProcessRestartDrill.SERVICE_IDENTITY),
                                requestId,
                                applicationId.get(),
                                capital,
                                NationActivationProcessRestartDrill.REASON)))
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                            } else {
                                asyncFailure.set(new AssertionError(
                                        "Nation Activation restart preparation completed without halt"));
                            }
                        });
            }
            helper.assertTrue(
                    false,
                    "Nation Activation restart preparation expected controlled process halt");
        });
    }

    static void verifyNationActivationProcessRestart(GameTestHelper helper) {
        NationActivationProcessRestartDrill.Marker marker =
                NationActivationProcessRestartDrill.readMarker(
                        helper.getLevel().getServer());
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        NationActivationRow prepared = nationActivationByRequest(
                databaseFile,
                NationActivationProcessRestartDrill.SERVICE_IDENTITY,
                marker.requestId());
        helper.assertTrue(prepared != null, "restarted Nation Activation row exists");
        helper.assertValueEqual(marker.applicationId(), prepared.applicationId(),
                "restarted Nation Application identity");
        helper.assertValueEqual(marker.nationId(), prepared.nationId(),
                "restarted Nation identity");
        helper.assertValueEqual(marker.ftbTeamId(), prepared.ftbTeamId(),
                "restarted FTB Team binding");
        helper.assertValueEqual(marker.treasuryAccount(), prepared.treasuryAccount(),
                "restarted National Treasury identity");
        helper.assertValueEqual("PREPARED", prepared.activationState(),
                "restart-window Nation Activation state");
        helper.assertValueEqual("PENDING", prepared.applicationState(),
                "restart-window Nation Application state");
        helper.assertValueEqual(0, prepared.nationCount(),
                "restart-window permanent Nation count");
        helper.assertValueEqual(
                0L,
                LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                        .balance(new AccountId(marker.treasuryAccount()))
                        .minorUnits(),
                "restart-window real National Treasury exists at zero balance");

        AtomicReference<ActivatedNation> activated = new AtomicReference<>();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicBoolean recoveryStarted = new AtomicBoolean();
        CivicServerRuntime runtime = CivicServerRuntime.current();
        Clock recoveryClock = Clock.fixed(Instant.now(), ZoneOffset.UTC);

        helper.succeedWhen(() -> {
            assertNoAsyncFailure(helper, asyncFailure, "Nation Activation restart recovery");
            if (recoveryStarted.compareAndSet(false, true)) {
                runtime.submitDatabase(database -> new NationActivationCoordinator(
                                database,
                                NationActivationProcessRestartDrill.provisioner(
                                        helper.getLevel().getServer()),
                                NationFoundingPolicy.debugWorld(
                                        2,
                                        Duration.ofDays(60L),
                                        Duration.ofDays(7L)),
                                recoveryClock)
                        .activate(new ActivateNationApplication(
                                new ServiceIdentity(
                                        NationActivationProcessRestartDrill.SERVICE_IDENTITY),
                                marker.requestId(),
                                new NationApplicationId(marker.applicationId()),
                                marker.capital(),
                                NationActivationProcessRestartDrill.REASON)))
                        .whenComplete((result, failure) -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                            } else {
                                activated.set(result);
                            }
                        });
            }
            helper.assertTrue(activated.get() != null,
                    "waiting for Nation Activation restart recovery");
            NationActivationRow committed = nationActivationByRequest(
                    databaseFile,
                    NationActivationProcessRestartDrill.SERVICE_IDENTITY,
                    marker.requestId());
            helper.assertValueEqual("COMMITTED", committed.activationState(),
                    "recovered Nation Activation state");
            helper.assertValueEqual("ACTIVATED", committed.applicationState(),
                    "recovered Nation Application state");
            helper.assertValueEqual(1, committed.nationCount(),
                    "one permanent Nation after recovery");
            helper.assertValueEqual(1, committed.citizenshipCount(),
                    "one founder Citizenship after recovery");
            helper.assertValueEqual(1, committed.capitalCount(),
                    "one Capital after recovery");
            helper.assertValueEqual(marker.nationId(), activated.get().nation().nationId().value(),
                    "recovered Nation identity retained");
            helper.assertValueEqual(marker.treasuryAccount(),
                    activated.get().treasuryAccountId().value(),
                    "recovered Treasury identity retained");
            helper.assertValueEqual(
                    0L,
                    LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                            .balance(new AccountId(marker.treasuryAccount()))
                            .minorUnits(),
                    "recovered National Treasury is not duplicated or changed");
        });
    }

    static void verifyTreasuryWithdrawalProcessRestart(
            GameTestHelper helper) {
        TreasuryWithdrawalProcessRestartDrill.Marker marker =
                TreasuryWithdrawalProcessRestartDrill.readMarker(
                        helper.getLevel().getServer());
        ServerPlayer player = connectMockServerPlayer(
                helper, marker.playerId(), "civic-wd-restart");
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        CivicServerRuntime runtime = CivicServerRuntime.current();

        TreasuryWithdrawalRow prepared =
                treasuryWithdrawalByRequest(databaseFile, marker.requestId());
        helper.assertTrue(prepared != null, "restarted Treasury Withdrawal row exists");
        helper.assertValueEqual(
                marker.withdrawalId(),
                prepared.withdrawalId(),
                "restarted Treasury Withdrawal identity");
        helper.assertValueEqual("PREPARED", prepared.state(), "restart-window operation state");
        helper.assertValueEqual(
                400L,
                LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                        .balance(new AccountId(marker.treasuryAccount()))
                        .minorUnits(),
                "restart-window Treasury debit persisted once");
        helper.assertValueEqual(
                marker.haltedAfterDelivery() ? 100L : 0L,
                playerInventoryMoney(player),
                "restart-window player inventory state");

        helper.succeedWhen(() -> {
            runtime.recoverTreasuryWithdrawalsNowForGameTest();
            TreasuryWithdrawalRow recovered =
                    treasuryWithdrawalByRequest(databaseFile, marker.requestId());
            if (!"COMMITTED".equals(recovered.state())) {
                helper.assertTrue(false, "waiting for Treasury Withdrawal restart recovery");
            }
            helper.assertValueEqual(
                    marker.withdrawalId(),
                    recovered.withdrawalId(),
                    "recovered Treasury Withdrawal identity");
            helper.assertValueEqual(
                    marker.playerId().toString(),
                    recovered.actorPlayerId(),
                    "recovered Treasury Withdrawal player");
            helper.assertValueEqual(
                    marker.amountMinorUnits(),
                    recovered.amountMinorUnits(),
                    "recovered Treasury Withdrawal amount");
            helper.assertValueEqual(
                    400L,
                    LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                            .balance(new AccountId(marker.treasuryAccount()))
                            .minorUnits(),
                    "recovered Treasury is not debited twice");
            helper.assertValueEqual(
                    100L,
                    playerInventoryMoney(player),
                    "recovered cash is delivered exactly once");
            helper.assertValueEqual(
                    1,
                    treasuryWithdrawalDeliveryCount(player, marker.withdrawalId()),
                    "one durable Treasury Withdrawal delivery marker");
            TreasuryWithdrawalApprovalRow approval =
                    treasuryWithdrawalApprovalByRequest(databaseFile, marker.requestId());
            helper.assertTrue(approval != null, "restarted Withdrawal approval exists");
            helper.assertValueEqual(
                    "EXECUTED", approval.state(), "restarted Withdrawal approval state");
            helper.assertValueEqual(
                    0L,
                    cumulativeNetIssuance(databaseFile),
                    "Treasury Withdrawal restart preserves Monetary Supply");
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
        var facility = economy.getChild("nation").getChild("facility");
        helper.assertValueEqual(
                Set.of("interface", "register", "baseline", "export", "status"),
                facility.getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "server-authoritative Registered Facility actions");
        helper.assertValueEqual(
                Set.of("capture", "activate"),
                facility.getChild("baseline").getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "server-authoritative Facility Baseline actions");
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
                Set.of("recovery"),
                economy.getChild("admin").getChild("budget-disbursement")
                        .getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "trusted Budget Disbursement recovery inspection actions");
        helper.assertValueEqual(
                Set.of("status"),
                economy.getChild("admin").getChild("budget-disbursement")
                        .getChild("recovery").getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "read-only Budget Disbursement recovery actions");
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
                        "strength",
                        "role",
                        "bill",
                        "budget",
                        "facility",
                        "mint",
                        "territory",
                        "treasury"),
                economy.getChild("nation").getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "server-authoritative Nation Application command actions");
        helper.assertValueEqual(
                Set.of("create", "approve", "cancel", "disbursement", "list", "status"),
                economy.getChild("nation")
                        .getChild("budget")
                        .getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "server-authoritative Budget command actions");
        helper.assertValueEqual(
                Set.of("status"),
                economy.getChild("nation")
                        .getChild("strength")
                        .getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "read-only National Strength inspection actions");
        helper.assertValueEqual(
                Set.of("request", "approve", "approval", "policy"),
                economy.getChild("nation")
                        .getChild("budget")
                        .getChild("disbursement")
                        .getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "server-authoritative Budget Disbursement command actions");
        helper.assertValueEqual(
                Set.of("list", "status", "cancel"),
                economy.getChild("nation")
                        .getChild("budget")
                        .getChild("disbursement")
                        .getChild("approval")
                        .getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "server-authoritative Budget Disbursement approval inspection actions");
        helper.assertValueEqual(
                Set.of("status", "history", "schedule", "schedule-tiered"),
                economy.getChild("nation")
                        .getChild("budget")
                        .getChild("disbursement")
                        .getChild("policy")
                        .getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "server-authoritative Budget Disbursement policy actions");
        helper.assertValueEqual(
                Set.of("issue", "list", "status"),
                economy.getChild("nation")
                        .getChild("bill")
                        .getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "server-authoritative Fiscal Bill command actions");
        helper.assertValueEqual(
                Set.of("list", "status", "fund", "pay", "cancel"),
                economy.getChild("bill").getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "payer-scoped Fiscal Bill inspection actions");
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
                Set.of("schedule", "schedule-tiered", "status", "history"),
                economy.getChild("nation")
                        .getChild("treasury")
                        .getChild("withdraw")
                        .getChild("policy")
                        .getChildren().stream()
                        .map(node -> node.getName())
                        .collect(Collectors.toSet()),
                "future-effective Treasury Withdrawal policy actions");
        helper.assertValueEqual(
                Set.of("list", "status", "cancel"),
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
        helper.succeedWhen(() -> helper.assertTrue(
                CivicServerRuntime.current().nationalStrengthSnapshotForGameTest().isPresent(),
                "startup National Strength snapshot"));
    }

    @GameTest(
            template = "empty",
            timeoutTicks = 6000,
            batch = "facility-player-registration")
    public static void authorizedPlayerRegistersCurrentClaimFacilityExactlyOnce(
            GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-facility-register"),
                ClientInformation.createDefault());
        player.setPos(helper.absolutePos(new BlockPos(8, 1, 8)).getCenter());
        BlockPos expectedCore = player.blockPosition();
        BlockPos expectedInterfaceBlock = expectedCore.below();
        helper.getLevel().setBlockAndUpdate(
                expectedInterfaceBlock,
                CivicContent.FACILITY_ACCOUNTING_INTERFACE.get().defaultBlockState());
        Team team = createHeadOwnedFtbTeamFixture(player);
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        ChunkDimPos position = new ChunkDimPos(
                player.level().dimension(), new ChunkPos(expectedCore));
        TerritoryClaimPosition civicPosition = new TerritoryClaimPosition(
                position.dimension().location().toString(), position.x(), position.z());
        var manager = FTBChunksAPI.api().getManager();
        ClaimedChunk existing = manager.getChunk(position);
        if (existing != null) {
            existing.unclaim(player.createCommandSourceStack(), true);
        }
        var teamData = manager.getOrCreateData(team);
        teamData.setExtraClaimChunks(Math.max(100, teamData.getExtraClaimChunks()));
        ((ChunkTeamDataImpl) teamData).updateLimits();
        helper.assertTrue(
                teamData.claim(
                                player.createCommandSourceStack().withSuppressedOutput(),
                                position,
                                false)
                        .isSuccess(),
                "Registered Facility FTB Claim fixture");

        String requestId = "player-facility-register-" + UUID.randomUUID();
        String reason = "Register current Facility from authoritative player facts";
        Instant now = Instant.now();
        Clock setupClock = Clock.fixed(now, ZoneOffset.UTC);
        Instant productionPolicyEffectiveAt = now.plusMillis(1L);
        String wheatComponents = new ItemStack(Items.WHEAT)
                .saveOptional(helper.getLevel().registryAccess())
                .toString();
        String flourComponents = new ItemStack(BuiltInRegistries.ITEM.get(
                        ResourceLocation.parse("create:wheat_flour")))
                .saveOptional(helper.getLevel().registryAccess())
                .toString();
        String seedComponents = new ItemStack(Items.WHEAT_SEEDS)
                .saveOptional(helper.getLevel().registryAccess())
                .toString();
        CivicServerRuntime runtime = CivicServerRuntime.current();
        AtomicBoolean setupReady = new AtomicBoolean();
        AtomicBoolean commandStarted = new AtomicBoolean();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicReference<StoredRegisteredFacility> persisted = new AtomicReference<>();
        AtomicReference<List<StoredFacilityClaim>> persistedClaims = new AtomicReference<>();
        AtomicReference<StoredFacilityAccountingInterface> persistedInterface =
                new AtomicReference<>();
        AtomicBoolean baselineFinished = new AtomicBoolean();
        AtomicReference<Throwable> baselineFailure = new AtomicReference<>();
        AtomicReference<StoredFacilityAccountingBaseline> persistedBaseline =
                new AtomicReference<>();
        AtomicReference<List<StoredFacilityBaselineMachine>> persistedBaselineMachines =
                new AtomicReference<>(List.of());
        AtomicReference<List<StoredFacilityBaselineInventory>> persistedBaselineInventory =
                new AtomicReference<>(List.of());
        AtomicBoolean replayFinished = new AtomicBoolean();
        AtomicReference<FacilityAccountingBaseline> replayedBaseline =
                new AtomicReference<>();
        AtomicReference<Throwable> replayFailure = new AtomicReference<>();
        AtomicBoolean activationFinished = new AtomicBoolean();
        AtomicReference<Throwable> activationFailure = new AtomicReference<>();
        AtomicReference<StoredFacilityAccountingBaseline> activatedBaseline =
                new AtomicReference<>();
        AtomicReference<StoredRegisteredFacility> activatedFacility =
                new AtomicReference<>();
        AtomicReference<FacilityAccountingBaseline> replayedActivation =
                new AtomicReference<>();
        AtomicReference<FacilityAccountingStatus> inspectedStatus =
                new AtomicReference<>();
        AtomicBoolean receiptFinished = new AtomicBoolean();
        AtomicReference<Throwable> receiptFailure = new AtomicReference<>();
        AtomicReference<StoredFacilityAccountingReceipt> persistedReceipt =
                new AtomicReference<>();
        AtomicReference<StoredFacilityProductionDecision> persistedDecision =
                new AtomicReference<>();
        AtomicReference<List<StoredProductionInventoryChange>> persistedReceiptChanges =
                new AtomicReference<>(List.of());
        AtomicBoolean exportFinished = new AtomicBoolean();
        AtomicReference<Throwable> exportFailure = new AtomicReference<>();
        AtomicReference<ProductionInventoryExport> persistedExport = new AtomicReference<>();
        AtomicReference<StoredProductionInventoryExport> persistedExportRow =
                new AtomicReference<>();
        AtomicReference<StoredProductionMarginalReturnContribution> persistedContribution =
                new AtomicReference<>();
        AtomicReference<NationId> productionNation = new AtomicReference<>();
        AtomicReference<UUID> productionObservationId = new AtomicReference<>();
        AtomicBoolean territoryPauseFinished = new AtomicBoolean();
        AtomicReference<Throwable> territoryPauseFailure = new AtomicReference<>();
        AtomicReference<List<RegisteredFacility>> territoryPaused =
                new AtomicReference<>(List.of());
        AtomicReference<List<StoredRegisteredFacilityStateTransition>> pauseTransitions =
                new AtomicReference<>(List.of());
        AtomicBoolean territoryRestorationFinished = new AtomicBoolean();
        AtomicReference<Throwable> territoryRestorationFailure = new AtomicReference<>();
        AtomicReference<List<RegisteredFacility>> territoryRestored =
                new AtomicReference<>(List.of());
        AtomicReference<List<StoredRegisteredFacilityStateTransition>>
                restorationTransitions = new AtomicReference<>(List.of());
        String baselineRequestId = requestId + "-baseline";
        String baselineReason = "Capture trusted Facility Accounting Baseline";
        String activationRequestId = requestId + "-activation";
        String activationReason = "Activate trusted Facility Accounting Baseline";

        runtime.submitDatabase(database -> {
                    ServiceIdentity setupService =
                            new ServiceIdentity("civiceconomy-gametest");
                    new RegisteredFacilityScopePolicyRegistry(database, setupClock)
                            .schedule(new ScheduleRegisteredFacilityScopePolicy(
                                    setupService,
                                    "facility-command-scope-policy-" + UUID.randomUUID(),
                                    "civic-gametest:facility-registration",
                                    new RegisteredFacilityScopePolicy(16),
                                    productionPolicyEffectiveAt,
                                    "GameTest Registered Facility Scope policy"));
                    NationRegistry nations = new NationRegistry(database, snapshot(teamSnapshot));
                    var nation = nations.register(new RegisterNation(
                            setupService,
                            "facility-command-nation-" + UUID.randomUUID(),
                            team.getId()));
                    CitizenshipRegistry citizenships = new CitizenshipRegistry(
                            database, Duration.ofDays(7L), setupClock);
                    citizenships.join(new JoinCitizenship(
                            setupService,
                            "facility-command-citizenship-" + UUID.randomUUID(),
                            player.getUUID(),
                            nation.nationId()));
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            citizenships,
                            new CitizenshipCorrectionGraceRegistry(database, setupClock),
                            snapshot(teamSnapshot));
                    new NationFiscalAuthorityRegistry(database, provider, setupClock)
                            .grant(new GrantNationFiscalPermission(
                                    setupService,
                                    "facility-command-authority-" + UUID.randomUUID(),
                                    nation.nationId(),
                                    player.getUUID(),
                                    player.getUUID(),
                                    NationFiscalPermission.MANAGE_FACILITY_ACCOUNTING,
                                    "Real player Registered Facility GameTest"));

                    TerritoryMaintenanceRegistry maintenance =
                            new TerritoryMaintenanceRegistry(database, setupClock);
                    var existingCycle = database.territoryMaintenanceCycleAt(
                            now.toEpochMilli());
                    UUID cycleId = existingCycle == null
                            ? maintenance.openCycle(new OpenTerritoryMaintenanceCycle(
                                            setupService,
                                            "facility-command-cycle-" + UUID.randomUUID(),
                                            now.minusSeconds(1L),
                                            now.plus(Duration.ofMinutes(10L))))
                                    .cycleId()
                            : existingCycle.cycleId();
                    maintenance.assess(new AssessTerritoryFiscalValidity(
                            setupService,
                            "facility-command-assessment-" + UUID.randomUUID(),
                            cycleId,
                            nation.nationId(),
                            team.getId(),
                            civicPosition.dimensionId(),
                            civicPosition.chunkX(),
                            civicPosition.chunkZ(),
                            0L,
                            "Effective current Claim for Facility registration"));
                    database.settleZeroCostTerritoryMaintenance(
                            UUID.randomUUID(),
                            setupService.value(),
                            "facility-command-settlement-" + UUID.randomUUID(),
                            cycleId,
                            nation.nationId().value(),
                            "Payment-free Facility registration fixture",
                            now.toEpochMilli());
                    GlobalReferencePriceRegistry prices =
                            new GlobalReferencePriceRegistry(database, setupClock);
                    prices.schedule(new ScheduleGlobalReferencePrice(
                            setupService,
                            "facility-command-wheat-price-" + UUID.randomUUID(),
                            "civic-gametest",
                            "minecraft:wheat",
                            wheatComponents,
                            10L,
                            productionPolicyEffectiveAt,
                            "Real Create wheat input price"));
                    prices.schedule(new ScheduleGlobalReferencePrice(
                            setupService,
                            "facility-command-flour-price-" + UUID.randomUUID(),
                            "civic-gametest",
                            "create:wheat_flour",
                            flourComponents,
                            25L,
                            productionPolicyEffectiveAt,
                            "Real Create flour output price"));
                    prices.schedule(new ScheduleGlobalReferencePrice(
                            setupService,
                            "facility-command-seed-price-" + UUID.randomUUID(),
                            "civic-gametest",
                            "minecraft:wheat_seeds",
                            seedComponents,
                            1L,
                            productionPolicyEffectiveAt,
                            "Real Create optional seed output price"));
                    new ProductionIndustryAssignmentRegistry(database, setupClock)
                            .schedule(new ScheduleProductionIndustryAssignment(
                                    setupService,
                                    "facility-command-industry-" + UUID.randomUUID(),
                                    "civic-gametest",
                                    "6.0.6",
                                    "create:milling/wheat",
                                    new ProductionIndustryId("food-processing"),
                                    productionPolicyEffectiveAt,
                                    "Real Create milling industry"));
                    new ProductionMarginalReturnPolicyRegistry(database, setupClock)
                            .schedule(new ScheduleProductionMarginalReturnPolicy(
                                    setupService,
                                    "facility-command-marginal-policy-" + UUID.randomUUID(),
                                    "civic-gametest",
                                    new ProductionMarginalReturnPolicy(
                                            100_000L, 5_000, 500_000L, 2_500),
                                    productionPolicyEffectiveAt,
                                    "Real Create marginal-return policy"));
                    new ProductionStrengthPolicyRegistry(database, setupClock)
                            .schedule(new ScheduleProductionStrengthPolicy(
                                    setupService,
                                    "facility-command-strength-policy-" + UUID.randomUUID(),
                                    "civic-gametest",
                                    new ProductionStrengthPolicy(
                                            Duration.ofDays(30),
                                            Duration.ofDays(7),
                                            100_000L),
                                    productionPolicyEffectiveAt,
                                    "Real Create Production Strength policy"));
                    return nation.nationId();
                })
                .whenComplete((registeredNation, setupFailure) -> {
                    if (setupFailure != null) {
                        asyncFailure.set(setupFailure);
                    } else {
                        productionNation.set(registeredNation);
                        setupReady.set(true);
                    }
                });

        helper.startSequence()
                .thenWaitUntil(() -> {
                    Throwable failure = asyncFailure.get();
                    helper.assertTrue(
                            failure == null,
                            failure == null
                                    ? "Registered Facility setup state"
                                    : "Registered Facility setup failure: "
                                            + failure.getMessage());
                    helper.assertTrue(setupReady.get(), "Registered Facility setup complete");
                })
                .thenExecute(() -> {
                    try {
                        String command = "civic economy nation facility register "
                                + requestId + " " + reason;
                        var dispatcher = helper.getLevel()
                                .getServer()
                                .getCommands()
                                .getDispatcher();
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        command,
                                        player.createCommandSourceStack()
                                                .withSuppressedOutput()),
                                "Registered Facility command result");
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        command,
                                        player.createCommandSourceStack()
                                                .withSuppressedOutput()),
                                "Registered Facility replay command result");
                        String interfaceCommand =
                                "civic economy nation facility interface bind "
                                        + requestId + "-interface " + reason;
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        interfaceCommand,
                                        player.createCommandSourceStack()
                                                .withSuppressedOutput()),
                                "Facility Accounting Interface command result");
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        interfaceCommand,
                                        player.createCommandSourceStack()
                                                .withSuppressedOutput()),
                                "Facility Accounting Interface replay command result");
                        commandStarted.set(true);
                        helper.runAfterDelay(20L, () -> runtime
                                .submitDatabase(database -> {
                                    StoredRegisteredFacility stored =
                                            database.registeredFacility(
                                                    FacilityAdministration
                                                            .SERVICE_IDENTITY
                                                            .value(),
                                                    requestId);
                                    persisted.set(stored);
                                    persistedClaims.set(stored == null
                                            ? List.of()
                                            : database.registeredFacilityClaims(
                                                    stored.facilityId()));
                                    persistedInterface.set(stored == null
                                            ? null
                                            : database.facilityAccountingInterface(
                                                    stored.facilityId()));
                                    return null;
                                })
                                .whenComplete((ignored, inspectionFailure) -> {
                                    if (inspectionFailure != null) {
                                        asyncFailure.set(inspectionFailure);
                                    }
                                }));
                    } catch (Throwable commandFailure) {
                        asyncFailure.set(commandFailure);
                    }
                })
                .thenWaitUntil(() -> {
                    Throwable failure = asyncFailure.get();
                    helper.assertTrue(
                            failure == null,
                            failure == null
                                    ? "Registered Facility command state"
                                    : "Registered Facility command failure: "
                                            + failure.getMessage());
                    helper.assertTrue(commandStarted.get(), "Registered Facility command started");
                    StoredRegisteredFacility stored = persisted.get();
                    helper.assertTrue(stored != null, "persisted Registered Facility");
                    helper.assertValueEqual(
                            team.getId(), stored.ftbTeamId(), "exact FTB Team binding");
                    helper.assertValueEqual(
                            player.getUUID(), stored.actorPlayerId(), "exact player actor");
                    helper.assertValueEqual(
                            "BASELINING", stored.state(), "safe initial Facility state");
                    helper.assertValueEqual(
                            civicPosition.dimensionId(),
                            stored.dimensionId(),
                            "server-derived Facility dimension");
                    helper.assertValueEqual(
                            expectedCore.getX(), stored.coreBlockX(), "server-derived core X");
                    helper.assertValueEqual(
                            expectedCore.getY(), stored.coreBlockY(), "server-derived core Y");
                    helper.assertValueEqual(
                            expectedCore.getZ(), stored.coreBlockZ(), "server-derived core Z");
                    helper.assertValueEqual(
                            List.of(new StoredFacilityClaim(
                                    civicPosition.dimensionId(),
                                    civicPosition.chunkX(),
                                    civicPosition.chunkZ())),
                            persistedClaims.get(),
                            "single current-Claim Facility scope");
                    StoredFacilityAccountingInterface accountingInterface =
                            persistedInterface.get();
                    helper.assertTrue(
                            accountingInterface != null,
                            "persisted Facility Accounting Interface");
                    helper.assertValueEqual(
                            stored.facilityId(),
                            accountingInterface.facilityId(),
                            "exact Facility Interface binding");
                    helper.assertValueEqual(
                            expectedInterfaceBlock.getX(),
                            accountingInterface.blockX(),
                            "server-targeted interface X");
                    helper.assertValueEqual(
                            expectedInterfaceBlock.getY(),
                            accountingInterface.blockY(),
                            "server-targeted interface Y");
                    helper.assertValueEqual(
                            expectedInterfaceBlock.getZ(),
                            accountingInterface.blockZ(),
                            "server-targeted interface Z");
                })
                .thenExecute(() -> {
                    FacilityAccountingInterfaceBlockEntity accountingInventory =
                            (FacilityAccountingInterfaceBlockEntity) helper.getLevel()
                                    .getBlockEntity(expectedInterfaceBlock);
                    accountingInventory.setItem(4, new ItemStack(Items.DIAMOND, 7));
                    if (CivicEconomy.compatibilityReport().productionScoringEnabled()) {
                        helper.getLevel().setBlockAndUpdate(
                                expectedCore,
                                BuiltInRegistries.BLOCK.get(
                                                ResourceLocation.parse("create:millstone"))
                                        .defaultBlockState());
                        try {
                            int result = helper.getLevel()
                                    .getServer()
                                    .getCommands()
                                    .getDispatcher()
                                    .execute(
                                            "civic economy nation facility baseline capture "
                                                    + baselineRequestId
                                                    + " "
                                                    + baselineReason,
                                            player.createCommandSourceStack()
                                                    .withSuppressedOutput());
                            helper.assertValueEqual(
                                    1, result, "Facility Baseline command result");
                            helper.runAfterDelay(20L, () -> runtime
                                    .submitDatabase(database -> {
                                        StoredFacilityAccountingBaseline stored =
                                                database.facilityAccountingBaseline(
                                                        FacilityAdministration
                                                                .SERVICE_IDENTITY
                                                                .value(),
                                                        baselineRequestId);
                                        persistedBaseline.set(stored);
                                        if (stored != null) {
                                            persistedBaselineMachines.set(
                                                    database.facilityAccountingBaselineMachines(
                                                            stored.baselineId()));
                                            persistedBaselineInventory.set(
                                                    database.facilityAccountingBaselineInventory(
                                                            stored.baselineId()));
                                        }
                                        return null;
                                    })
                                    .whenComplete((ignored, failure) -> {
                                        if (failure != null) {
                                            baselineFailure.set(failure);
                                        }
                                        baselineFinished.set(true);
                                    }));
                        } catch (Throwable failure) {
                            baselineFailure.set(failure);
                            baselineFinished.set(true);
                        }
                    } else {
                        runtime.captureFacilityAccountingBaseline(
                                        player,
                                        baselineRequestId,
                                        baselineReason)
                                .whenComplete((baseline, failure) -> runtime
                                        .submitDatabase(database -> {
                                            persistedBaseline.set(
                                                    database.facilityAccountingBaseline(
                                                            FacilityAdministration
                                                                    .SERVICE_IDENTITY
                                                                    .value(),
                                                            baselineRequestId));
                                            return null;
                                        })
                                        .whenComplete((ignored, inspectionFailure) -> {
                                            baselineFailure.set(failure != null
                                                    ? failure
                                                    : inspectionFailure);
                                            baselineFinished.set(true);
                                        }));
                    }
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(
                            baselineFinished.get(),
                            "Facility Baseline capture completed");
                    if (!CivicEconomy.compatibilityReport().productionScoringEnabled()) {
                        Throwable failure = baselineFailure.get();
                        helper.assertTrue(
                                failure != null
                                        && rootCause(failure).getMessage()
                                                .contains("Create is unavailable"),
                                "missing Create fails Facility Baseline capture closed");
                        helper.assertTrue(
                                persistedBaseline.get() == null,
                                "missing Create writes no Facility Baseline");
                        return;
                    }
                    helper.assertTrue(
                            baselineFailure.get() == null,
                            baselineFailure.get() == null
                                    ? "Facility Baseline command state"
                                    : "Facility Baseline command failure: "
                                            + rootCause(baselineFailure.get()).getMessage());
                    StoredFacilityAccountingBaseline baseline = persistedBaseline.get();
                    helper.assertTrue(baseline != null, "persisted Facility Baseline");
                    helper.assertValueEqual(
                            "CAPTURED", baseline.state(), "safe captured Baseline state");
                    helper.assertValueEqual(
                            "6.0.6", baseline.createVersion(), "pinned Create version");
                    helper.assertValueEqual(
                            1,
                            persistedBaselineMachines.get().size(),
                            "real Create Millstone baseline count");
                    helper.assertValueEqual(
                            "MILLSTONE",
                            persistedBaselineMachines.get().getFirst().machineKind(),
                            "real Create machine kind");
                    helper.assertValueEqual(
                            1,
                            persistedBaselineInventory.get().size(),
                            "real Civic interface inventory count");
                    helper.assertValueEqual(
                            "minecraft:diamond",
                            persistedBaselineInventory.get().getFirst().itemId(),
                            "real Civic interface item identity");
                    helper.assertValueEqual(
                            7,
                            persistedBaselineInventory.get().getFirst().count(),
                            "real Civic interface item count");
                })
                .thenExecute(() -> {
                    if (!CivicEconomy.compatibilityReport().productionScoringEnabled()) {
                        activationFinished.set(true);
                        return;
                    }
                    try {
                        int result = helper.getLevel()
                                .getServer()
                                .getCommands()
                                .getDispatcher()
                                .execute(
                                        "civic economy nation facility baseline activate "
                                                + activationRequestId
                                                + " "
                                                + activationReason,
                                        player.createCommandSourceStack()
                                                .withSuppressedOutput());
                        helper.assertValueEqual(
                                1, result, "Facility Baseline activation command result");
                        waitForFacilityActivation(
                                runtime,
                                helper,
                                persisted.get().facilityId(),
                                activatedBaseline,
                                activatedFacility,
                                activationFailure,
                                activationFinished,
                                100);
                    } catch (Throwable failure) {
                        activationFailure.set(failure);
                        activationFinished.set(true);
                    }
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(
                            activationFinished.get(),
                            "Facility Baseline activation completed");
                    if (!CivicEconomy.compatibilityReport().productionScoringEnabled()) {
                        return;
                    }
                    helper.assertTrue(
                            activationFailure.get() == null,
                            activationFailure.get() == null
                                    ? "Facility Baseline activation state"
                                    : "Facility Baseline activation failure: "
                                            + rootCause(activationFailure.get()).getMessage());
                    helper.assertTrue(
                            activatedBaseline.get() != null,
                            "persisted activated Facility Baseline");
                    helper.assertTrue(
                            activatedFacility.get() != null,
                            "persisted activated Facility");
                    helper.assertValueEqual(
                            "ACTIVE",
                            activatedBaseline.get().state(),
                            "activated Baseline state");
                    helper.assertValueEqual(
                            "ACTIVE",
                            activatedFacility.get().state(),
                            "activated Facility state");
                    helper.assertValueEqual(
                            7,
                            persistedBaselineInventory.get().getFirst().count(),
                            "activation preserves captured starting inventory");
                })
                .thenExecute(() -> {
                    if (!CivicEconomy.compatibilityReport().productionScoringEnabled()) {
                        receiptFinished.set(true);
                        return;
                    }
                    AtomicReference<org.civiceconomy.production.CreateRecipeCompletion>
                            observedCompletion = new AtomicReference<>();
                    CreateMillstoneObservationBridge.install(
                            observedCompletion::set,
                            Clock.systemUTC());
                    try {
                        BlockEntity millstone =
                                helper.getLevel().getBlockEntity(expectedCore);
                        Field inputField =
                                millstone.getClass().getDeclaredField("inputInv");
                        Field outputField =
                                millstone.getClass().getDeclaredField("outputInv");
                        inputField.setAccessible(true);
                        outputField.setAccessible(true);
                        ItemStackHandler input =
                                (ItemStackHandler) inputField.get(millstone);
                        ItemStackHandler output =
                                (ItemStackHandler) outputField.get(millstone);
                        input.setStackInSlot(0, new ItemStack(Items.WHEAT, 1));
                        Method process = millstone.getClass().getDeclaredMethod("process");
                        process.setAccessible(true);
                        process.invoke(millstone);
                        var completion = observedCompletion.get();
                        helper.assertTrue(
                                completion != null,
                                "real Create completion reached the runtime bridge");
                        productionObservationId.set(completion.observationId());
                        FacilityAccountingInterfaceBlockEntity accountingInventory =
                                (FacilityAccountingInterfaceBlockEntity) helper.getLevel()
                                        .getBlockEntity(expectedInterfaceBlock);
                        int receiptSlot = 5;
                        for (int outputSlot = 0;
                                outputSlot < output.getSlots();
                                outputSlot++) {
                            ItemStack produced = output.getStackInSlot(outputSlot).copy();
                            if (!produced.isEmpty()) {
                                accountingInventory.setItem(receiptSlot++, produced);
                                output.setStackInSlot(outputSlot, ItemStack.EMPTY);
                            }
                        }
                        helper.runAfterDelay(60L, () -> runtime
                                .submitDatabase(database -> {
                                    UUID observationId = productionObservationId.get();
                                    StoredFacilityProductionDecision decision =
                                            database.facilityProductionDecision(
                                                    observationId);
                                    persistedDecision.set(decision);
                                    if (decision != null) {
                                        persistedReceipt.set(
                                                database.facilityAccountingReceipt(
                                                        decision.receiptId()));
                                        persistedReceiptChanges.set(
                                                database.facilityAccountingReceiptChanges(
                                                        decision.receiptId()));
                                    }
                                    return null;
                                })
                                .whenComplete((ignored, failure) -> {
                                    receiptFailure.set(failure);
                                    receiptFinished.set(true);
                                }));
                    } catch (Throwable failure) {
                        receiptFailure.set(failure);
                        receiptFinished.set(true);
                    } finally {
                        CreateMillstoneObservationBridge.reset();
                    }
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(
                            receiptFinished.get(),
                            "Facility Accounting Receipt ingestion completed");
                    if (!CivicEconomy.compatibilityReport().productionScoringEnabled()) {
                        return;
                    }
                    helper.assertTrue(
                            receiptFailure.get() == null,
                            receiptFailure.get() == null
                                    ? "Facility Accounting Receipt state"
                                    : "Facility Accounting Receipt failure: "
                                            + rootCause(receiptFailure.get()).getMessage());
                    helper.assertTrue(
                            persistedDecision.get() != null,
                            "persisted Facility Production decision");
                    helper.assertValueEqual(
                            "INCLUDED",
                            persistedDecision.get().decision(),
                            "real Create completion and interface receipt decision");
                    helper.assertTrue(
                            persistedReceipt.get() != null,
                            "persisted server-authoritative Facility Accounting Receipt");
                    helper.assertTrue(
                            !persistedReceiptChanges.get().isEmpty(),
                            "persisted real interface inventory increase");
                })
                .thenExecute(() -> {
                    if (!CivicEconomy.compatibilityReport().productionScoringEnabled()) {
                        exportFinished.set(true);
                        return;
                    }
                    runtime.exportProductionInventory(
                                    player,
                                    requestId + "-export",
                                    5,
                                    1,
                                    ProductionInventoryExportKind.EXPORT,
                                    "Export one server-observed produced item")
                            .thenCompose(first -> runtime.exportProductionInventory(
                                    player,
                                    requestId + "-export",
                                    5,
                                    1,
                                    ProductionInventoryExportKind.EXPORT,
                                    "Export one server-observed produced item")
                                    .thenApply(replay -> {
                                        helper.assertValueEqual(
                                                first,
                                                replay,
                                                "production export replay identity and payload");
                                        return first;
                                    }))
                            .thenCompose(exported -> runtime.submitDatabase(database -> {
                                persistedExportRow.set(database.productionInventoryExport(
                                        FacilityAdministration.SERVICE_IDENTITY.value(),
                                        requestId + "-export"));
                                persistedContribution.set(
                                        database.productionMarginalReturnContribution(
                                                productionObservationId.get()));
                                return exported;
                            }))
                            .whenComplete((exported, failure) -> {
                                persistedExport.set(exported);
                                exportFailure.set(failure);
                                exportFinished.set(true);
                            });
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(exportFinished.get(), "production export completed");
                    if (!CivicEconomy.compatibilityReport().productionScoringEnabled()) {
                        return;
                    }
                    helper.assertTrue(
                            exportFailure.get() == null,
                            exportFailure.get() == null
                                    ? "production export state"
                                    : "production export failure: "
                                            + rootCause(exportFailure.get()).getMessage());
                    helper.assertTrue(
                            persistedExport.get() != null,
                            "server-authoritative production export");
                    helper.assertValueEqual(
                            1,
                            persistedExport.get().exportedStack().count(),
                            "exported server-observed quantity");
                    helper.assertTrue(
                            persistedExportRow.get() != null,
                            "persisted production export audit row");
                    helper.assertTrue(
                            persistedContribution.get() != null,
                            "real export anchored a version-bound production contribution");
                    helper.assertValueEqual(
                            persistedExport.get().exportId(),
                            persistedContribution.get().anchorExportId(),
                            "production contribution anchor Export Event");
                    helper.assertTrue(
                            persistedContribution.get().valueAddedMinorUnits() > 0L,
                            "real Create production contribution is positive");
                    NationalStrengthSnapshot productionSnapshot = runtime
                            .nationalStrengthSnapshotForGameTest()
                            .orElseThrow(() -> new AssertionError(
                                    "production National Strength snapshot is pending"));
                    NationalStrengthRecalculation productionStrength =
                            productionSnapshot.nations().get(productionNation.get());
                    helper.assertTrue(
                            productionStrength != null,
                            "production National Strength contains the exact Nation");
                    helper.assertTrue(
                            productionStrength.productionMarginalReturn()
                                    .finalValueMinorUnits() > 0L,
                            "real Create rolling Production Marginal Return is positive");
                    helper.assertValueEqual(
                            org.civiceconomy.strength.NationalStrengthComponentState.ACTIVE,
                            productionStrength.assessment().componentState(
                                    org.civiceconomy.strength.NationalStrengthComponent
                                            .PRODUCTION_AND_INFRASTRUCTURE),
                            "real Create production National Strength component");
                })
                .thenExecute(() -> {
                    if (!CivicEconomy.compatibilityReport().productionScoringEnabled()) {
                        replayFinished.set(true);
                        return;
                    }
                    helper.getLevel().removeBlock(expectedInterfaceBlock, false);
                    helper.getLevel().removeBlock(expectedCore, false);
                    try {
                        int statusResult = helper.getLevel()
                                .getServer()
                                .getCommands()
                                .getDispatcher()
                                .execute(
                                        "civic economy nation facility status",
                                        player.createCommandSourceStack()
                                                .withSuppressedOutput());
                        helper.assertValueEqual(
                                1, statusResult, "Facility status command result");
                    } catch (Throwable failure) {
                        replayFailure.set(failure);
                        replayFinished.set(true);
                        return;
                    }
                    CompletableFuture<FacilityAccountingBaseline> captureReplay =
                            runtime.captureFacilityAccountingBaseline(
                                    player, baselineRequestId, baselineReason);
                    CompletableFuture<FacilityAccountingBaseline> activationReplay =
                            runtime.activateFacilityAccountingBaseline(
                                    player, activationRequestId, activationReason);
                    CompletableFuture<FacilityAccountingStatus> status =
                            runtime.facilityAccountingStatus(player);
                    captureReplay.thenAccept(replayedBaseline::set);
                    activationReplay.thenAccept(replayedActivation::set);
                    status.thenAccept(inspectedStatus::set);
                    CompletableFuture.allOf(captureReplay, activationReplay, status)
                            .whenComplete((ignored, failure) -> {
                                replayFailure.set(failure);
                                replayFinished.set(true);
                            });
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(
                            replayFinished.get(), "Facility Baseline replay completed");
                    if (CivicEconomy.compatibilityReport().productionScoringEnabled()) {
                        helper.assertTrue(
                                replayFailure.get() == null,
                                replayFailure.get() == null
                                        ? "Facility Baseline replay state"
                                        : "Facility Baseline replay failure: "
                                                + rootCause(replayFailure.get()).getMessage());
                        helper.assertValueEqual(
                                persistedBaseline.get().baselineId(),
                                replayedBaseline.get().baselineId(),
                                "Facility Baseline replay identity");
                        helper.assertValueEqual(
                                activatedBaseline.get().baselineId(),
                                replayedActivation.get().baselineId(),
                                "Facility Baseline activation replay identity");
                        helper.assertValueEqual(
                                persisted.get().facilityId(),
                                inspectedStatus.get().facility().facilityId(),
                                "Facility status exact identity");
                        helper.assertValueEqual(
                                org.civiceconomy.production.RegisteredFacilityState.ACTIVE,
                                inspectedStatus.get().facility().state(),
                                "Facility status active Facility");
                        helper.assertValueEqual(
                                org.civiceconomy.production
                                        .FacilityAccountingBaselineState.ACTIVE,
                                inspectedStatus.get().baseline().state(),
                                "Facility status active Baseline");
                    }
                })
                .thenExecute(() -> {
                    ClaimedChunk claimed = manager.getChunk(position);
                    if (claimed != null) {
                        claimed.unclaim(player.createCommandSourceStack(), true);
                    }
                })
                .thenExecute(() -> {
                    if (!CivicEconomy.compatibilityReport().productionScoringEnabled()) {
                        territoryPauseFinished.set(true);
                        return;
                    }
                    runtime.reconcileRegisteredFacilityTerritoryForGameTest()
                            .thenCompose(changed -> {
                                territoryPaused.set(changed);
                                return runtime.submitDatabase(database ->
                                        database.registeredFacilityStateTransitions(
                                                persisted.get().facilityId()));
                            })
                            .whenComplete((transitions, failure) -> {
                                pauseTransitions.set(
                                        transitions == null ? List.of() : transitions);
                                territoryPauseFailure.set(failure);
                                territoryPauseFinished.set(true);
                            });
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(
                            territoryPauseFinished.get(),
                            "Registered Facility Territory pause completed");
                    if (!CivicEconomy.compatibilityReport().productionScoringEnabled()) {
                        helper.assertValueEqual(
                                List.of(),
                                territoryPaused.get(),
                                "Create-disabled Facility remains conservatively unscored");
                        return;
                    }
                    Throwable failure = territoryPauseFailure.get();
                    helper.assertTrue(
                            failure == null,
                            failure == null
                                    ? "Registered Facility Territory pause state"
                                    : "Registered Facility Territory pause failure: "
                                            + rootCause(failure).getMessage());
                    helper.assertValueEqual(
                            1,
                            territoryPaused.get().size(),
                            "one Territory-paused Registered Facility");
                    helper.assertValueEqual(
                            org.civiceconomy.production.RegisteredFacilityState
                                    .PAUSED_TERRITORY,
                            territoryPaused.get().getFirst().state(),
                            "real FTB Claim loss pauses Facility");
                    helper.assertValueEqual(
                            1,
                            pauseTransitions.get().size(),
                            "one persisted Territory pause audit");
                })
                .thenExecute(() -> {
                    if (!CivicEconomy.compatibilityReport().productionScoringEnabled()) {
                        territoryRestorationFinished.set(true);
                        return;
                    }
                    helper.assertTrue(
                            teamData.claim(
                                            player.createCommandSourceStack()
                                                    .withSuppressedOutput(),
                                            position,
                                            false)
                                    .isSuccess(),
                            "restore Registered Facility FTB Claim fixture");
                    runtime.reconcileRegisteredFacilityTerritoryForGameTest()
                            .thenCompose(changed -> {
                                territoryRestored.set(changed);
                                return runtime.submitDatabase(database ->
                                        database.registeredFacilityStateTransitions(
                                                persisted.get().facilityId()));
                            })
                            .whenComplete((transitions, failure) -> {
                                restorationTransitions.set(
                                        transitions == null ? List.of() : transitions);
                                territoryRestorationFailure.set(failure);
                                territoryRestorationFinished.set(true);
                            });
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(
                            territoryRestorationFinished.get(),
                            "Registered Facility Territory restoration completed");
                    if (!CivicEconomy.compatibilityReport().productionScoringEnabled()) {
                        helper.assertValueEqual(
                                List.of(),
                                territoryRestored.get(),
                                "Create-disabled Facility has no false Territory restoration");
                        return;
                    }
                    Throwable failure = territoryRestorationFailure.get();
                    helper.assertTrue(
                            failure == null,
                            failure == null
                                    ? "Registered Facility Territory restoration state"
                                    : "Registered Facility Territory restoration failure: "
                                            + rootCause(failure).getMessage());
                    helper.assertValueEqual(
                            1,
                            territoryRestored.get().size(),
                            "one Territory-restored Registered Facility");
                    helper.assertValueEqual(
                            org.civiceconomy.production.RegisteredFacilityState.ACTIVE,
                            territoryRestored.get().getFirst().state(),
                            "real FTB Claim restoration reactivates Facility");
                    helper.assertValueEqual(
                            2,
                            restorationTransitions.get().size(),
                            "persisted pause and restoration audits");
                    helper.assertValueEqual(
                            "ACTIVE",
                            restorationTransitions.get().get(1).toState(),
                            "restoration audit target state");
                })
                .thenExecute(() -> {
                    ClaimedChunk claimed = manager.getChunk(position);
                    if (claimed != null) {
                        claimed.unclaim(player.createCommandSourceStack(), true);
                    }
                })
                .thenSucceed();
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
        String referencePriceRequestId = "reference-price-command-" + UUID.randomUUID();
        String unauthorizedPriceRequestId = "reference-price-unauthorized-" + UUID.randomUUID();
        String marginalReturnRequestId = "marginal-return-command-" + UUID.randomUUID();
        String unauthorizedMarginalReturnRequestId =
                "marginal-return-unauthorized-" + UUID.randomUUID();
        String strengthPolicyRequestId = "production-strength-policy-command-" + UUID.randomUUID();
        String unauthorizedStrengthPolicyRequestId =
                "production-strength-policy-unauthorized-" + UUID.randomUUID();
        String effectiveCitizenStrengthPolicyRequestId =
                "effective-citizen-strength-policy-command-" + UUID.randomUUID();
        String unauthorizedEffectiveCitizenStrengthPolicyRequestId =
                "effective-citizen-strength-policy-unauthorized-" + UUID.randomUUID();
        String effectiveTerritoryStrengthPolicyRequestId =
                "effective-territory-strength-policy-command-" + UUID.randomUUID();
        String unauthorizedEffectiveTerritoryStrengthPolicyRequestId =
                "effective-territory-strength-policy-unauthorized-" + UUID.randomUUID();
        String mintCompliancePolicyRequestId =
                "mint-compliance-policy-command-" + UUID.randomUUID();
        String unauthorizedMintCompliancePolicyRequestId =
                "mint-compliance-policy-unauthorized-" + UUID.randomUUID();
        String auditableActivityPolicyRequestId =
                "auditable-activity-policy-command-" + UUID.randomUUID();
        String unauthorizedAuditableActivityPolicyRequestId =
                "auditable-activity-policy-unauthorized-" + UUID.randomUUID();
        String facilityScopePolicyRequestId =
                "facility-scope-policy-command-" + UUID.randomUUID();
        String unauthorizedFacilityScopePolicyRequestId =
                "facility-scope-policy-unauthorized-" + UUID.randomUUID();
        String industryRequestId = "production-industry-command-" + UUID.randomUUID();
        String unauthorizedIndustryRequestId =
                "production-industry-unauthorized-" + UUID.randomUUID();
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
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "civic economy admin reference-price schedule minecraft:iron_ingot \"components:{}\" 25 "
                        + effectiveAt + " " + referencePriceRequestId
                        + " GameTest initial Global Reference Price");
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "civic economy admin production marginal-return schedule 100000 5000 500000 2500 "
                        + effectiveAt + " " + marginalReturnRequestId
                        + " GameTest Production Marginal Return policy");
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "civic economy admin production strength-policy schedule 2592000000 604800000 100000 "
                        + effectiveAt + " " + strengthPolicyRequestId
                        + " GameTest Production Strength policy");
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "civic economy admin strength effective-citizen schedule 10 "
                        + effectiveAt + " " + effectiveCitizenStrengthPolicyRequestId
                        + " GameTest Effective Citizen Strength policy");
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "civic economy admin strength effective-territory schedule 4 "
                        + effectiveAt + " " + effectiveTerritoryStrengthPolicyRequestId
                        + " GameTest Effective Territory Strength policy");
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "civic economy admin strength mint-compliance schedule 2592000000 5000 "
                        + effectiveAt + " " + mintCompliancePolicyRequestId
                        + " GameTest Mint Compliance policy");
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "civic economy admin strength auditable-activity schedule 2592000000 10000 "
                        + effectiveAt + " " + auditableActivityPolicyRequestId
                        + " GameTest Auditable Economic Activity policy");
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "civic economy admin facility scope-policy schedule 16 "
                        + effectiveAt + " " + facilityScopePolicyRequestId
                        + " GameTest Registered Facility Scope policy");
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "civic economy admin production industry schedule 6.0.6 create:milling/wheat food-processing "
                        + effectiveAt + " " + industryRequestId
                        + " GameTest Production Industry assignment");
        ServerPlayer nonOperator = new ServerPlayer(
                server,
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "reference-price-non-op"),
                ClientInformation.createDefault());
        server.getCommands().performPrefixedCommand(
                nonOperator.createCommandSourceStack().withSuppressedOutput(),
                "civic economy admin reference-price schedule minecraft:gold_ingot \"components:{}\" 99 "
                        + effectiveAt + " " + unauthorizedPriceRequestId
                        + " Untrusted reference price");
        server.getCommands().performPrefixedCommand(
                nonOperator.createCommandSourceStack().withSuppressedOutput(),
                "civic economy admin production marginal-return schedule 1 1 1 1 "
                        + effectiveAt + " " + unauthorizedMarginalReturnRequestId
                        + " Untrusted Production Marginal Return policy");
        server.getCommands().performPrefixedCommand(
                nonOperator.createCommandSourceStack().withSuppressedOutput(),
                "civic economy admin production strength-policy schedule 1 1 1 "
                        + effectiveAt + " " + unauthorizedStrengthPolicyRequestId
                        + " Untrusted Production Strength policy");
        server.getCommands().performPrefixedCommand(
                nonOperator.createCommandSourceStack().withSuppressedOutput(),
                "civic economy admin strength effective-citizen schedule 1 "
                        + effectiveAt + " " + unauthorizedEffectiveCitizenStrengthPolicyRequestId
                        + " Untrusted Effective Citizen Strength policy");
        server.getCommands().performPrefixedCommand(
                nonOperator.createCommandSourceStack().withSuppressedOutput(),
                "civic economy admin strength effective-territory schedule 1 "
                        + effectiveAt + " " + unauthorizedEffectiveTerritoryStrengthPolicyRequestId
                        + " Untrusted Effective Territory Strength policy");
        server.getCommands().performPrefixedCommand(
                nonOperator.createCommandSourceStack().withSuppressedOutput(),
                "civic economy admin strength mint-compliance schedule 1 1 "
                        + effectiveAt + " " + unauthorizedMintCompliancePolicyRequestId
                        + " Untrusted Mint Compliance policy");
        server.getCommands().performPrefixedCommand(
                nonOperator.createCommandSourceStack().withSuppressedOutput(),
                "civic economy admin strength auditable-activity schedule 1 1 "
                        + effectiveAt + " " + unauthorizedAuditableActivityPolicyRequestId
                        + " Untrusted Auditable Economic Activity policy");
        server.getCommands().performPrefixedCommand(
                nonOperator.createCommandSourceStack().withSuppressedOutput(),
                "civic economy admin facility scope-policy schedule 1 "
                        + effectiveAt + " " + unauthorizedFacilityScopePolicyRequestId
                        + " Untrusted Registered Facility Scope policy");
        server.getCommands().performPrefixedCommand(
                nonOperator.createCommandSourceStack().withSuppressedOutput(),
                "civic economy admin production industry schedule 6.0.6 create:pressing/iron_ingot metals "
                        + effectiveAt + " " + unauthorizedIndustryRequestId
                        + " Untrusted Production Industry assignment");

        helper.succeedWhen(() -> {
            assertTerritoryPolicyScheduled(helper, databaseFile, requestId, effectiveAt);
            assertTerritoryPricingScheduled(
                    helper, databaseFile, pricingRequestId, effectiveAt);
            assertTerritoryMaintenancePolicyScheduled(
                    helper, databaseFile, maintenanceRequestId, maintenanceEffectiveAt);
            assertGlobalReferencePriceScheduled(
                    helper, databaseFile, referencePriceRequestId, effectiveAt);
            assertGlobalReferencePriceAbsent(
                    helper, databaseFile, unauthorizedPriceRequestId);
            assertProductionMarginalReturnPolicyScheduled(
                    helper, databaseFile, marginalReturnRequestId, effectiveAt);
            assertProductionMarginalReturnPolicyAbsent(
                    helper, databaseFile, unauthorizedMarginalReturnRequestId);
            assertProductionStrengthPolicyScheduled(
                    helper, databaseFile, strengthPolicyRequestId, effectiveAt);
            assertProductionStrengthPolicyAbsent(
                    helper, databaseFile, unauthorizedStrengthPolicyRequestId);
            assertEffectiveCitizenStrengthPolicyScheduled(
                    helper,
                    databaseFile,
                    effectiveCitizenStrengthPolicyRequestId,
                    effectiveAt);
            assertEffectiveCitizenStrengthPolicyAbsent(
                    helper,
                    databaseFile,
                    unauthorizedEffectiveCitizenStrengthPolicyRequestId);
            assertEffectiveTerritoryStrengthPolicyScheduled(
                    helper,
                    databaseFile,
                    effectiveTerritoryStrengthPolicyRequestId,
                    effectiveAt);
            assertEffectiveTerritoryStrengthPolicyAbsent(
                    helper,
                    databaseFile,
                    unauthorizedEffectiveTerritoryStrengthPolicyRequestId);
            assertMintCompliancePolicyScheduled(
                    helper, databaseFile, mintCompliancePolicyRequestId, effectiveAt);
            assertMintCompliancePolicyAbsent(
                    helper, databaseFile, unauthorizedMintCompliancePolicyRequestId);
            assertAuditableEconomicActivityPolicyScheduled(
                    helper, databaseFile, auditableActivityPolicyRequestId, effectiveAt);
            assertAuditableEconomicActivityPolicyAbsent(
                    helper, databaseFile, unauthorizedAuditableActivityPolicyRequestId);
            assertRegisteredFacilityScopePolicyScheduled(
                    helper, databaseFile, facilityScopePolicyRequestId, effectiveAt);
            assertRegisteredFacilityScopePolicyAbsent(
                    helper, databaseFile, unauthorizedFacilityScopePolicyRequestId);
            assertProductionIndustryAssignmentScheduled(
                    helper, databaseFile, industryRequestId, effectiveAt);
            assertProductionIndustryAssignmentAbsent(
                    helper, databaseFile, unauthorizedIndustryRequestId);
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

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void runtimeExpiresDueFiscalObjectsOffThread(GameTestHelper helper) {
        CivicServerRuntime runtime = CivicServerRuntime.current();
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        UUID escrowId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID budgetId = UUID.randomUUID();
        UUID billId = UUID.randomUUID();
        long expiresAt = System.currentTimeMillis() + 250L;
        AtomicBoolean prepared = new AtomicBoolean();
        AtomicBoolean expiryTriggered = new AtomicBoolean();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();

        runtime.submitDatabase(database -> {
                    database.openEscrow(
                            escrowId,
                            reservationId,
                            "civiceconomy-gametest",
                            "runtime-escrow-expiry-" + escrowId,
                            "nation:runtime-escrow-expiry:treasury",
                            300L,
                            "contract:runtime-expiry",
                            "Runtime automatic Escrow expiry",
                            expiresAt);
                    database.createBudget(
                            budgetId,
                            "civiceconomy-gametest",
                            "runtime-budget-expiry-" + budgetId,
                            "nation:runtime-budget-expiry:treasury",
                            200L,
                            "PUBLIC_WORKS:RUNTIME_EXPIRY",
                            "Runtime automatic Budget draft expiry",
                            expiresAt);
                    return database.issueFiscalBill(
                            billId,
                            "civiceconomy-gametest",
                            "runtime-bill-expiry-" + billId,
                            "player:runtime-bill-expiry:payer",
                            "nation:runtime-bill-expiry:treasury",
                            150L,
                            "FEE",
                            "Runtime automatic Fiscal Bill expiry",
                            expiresAt);
                })
                .whenComplete((budget, failure) -> {
                    if (failure == null) {
                        prepared.set(true);
                    } else {
                        asyncFailure.set(failure);
                    }
                });

        helper.succeedWhen(() -> {
            helper.assertTrue(asyncFailure.get() == null, "automatic Escrow expiry setup");
            helper.assertTrue(prepared.get(), "due Escrow persisted on SQLite writer");
            if (System.currentTimeMillis() >= expiresAt
                    && expiryTriggered.compareAndSet(false, true)) {
                runtime.expireFiscalObjectsNowForGameTest();
            }
            helper.assertTrue(expiryTriggered.get(), "automatic fiscal expiry triggered");
            assertEscrowAutomaticallyExpired(helper, databaseFile, escrowId);
            assertBudgetDraftAutomaticallyExpired(helper, databaseFile, budgetId);
            assertFiscalBillAutomaticallyExpired(helper, databaseFile, billId);
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

    @GameTest(template = "empty", timeoutTicks = 12000, batch = "runtime-budget-command")
    public static void authorizedNationPlayerCreatesExactBudgetDraftOffThread(
            GameTestHelper helper) {
        ServerPlayer actor = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-budget-drafter"),
                ClientInformation.createDefault());
        Team team = createHeadOwnedFtbTeamFixture(actor);
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        String unauthorizedRequestId = "unauthorized-budget-" + UUID.randomUUID();
        String requestId = "player-budget-" + UUID.randomUUID();
        String disbursementRequestId = "player-budget-disbursement-" + UUID.randomUUID();
        String cancelledDisbursementRequestId =
                "cancelled-player-budget-disbursement-" + UUID.randomUUID();
        String recoveryDisbursementRequestId =
                "recovery-player-budget-disbursement-" + UUID.randomUUID();
        String preparedRecoveryDisbursementRequestId =
                "prepared-recovery-player-budget-disbursement-" + UUID.randomUUID();
        String tieredPolicyRequestId =
                "tiered-budget-disbursement-policy-" + UUID.randomUUID();
        String unauthorizedTieredPolicyRequestId =
                "unauthorized-tiered-budget-disbursement-policy-" + UUID.randomUUID();
        String tieredPolicyReason = "Tiered GameTest procurement governance";
        UUID recipientPlayerId = UUID.randomUUID();
        UUID recoveryRecipientPlayerId = UUID.randomUUID();
        UUID preparedRecoveryRecipientPlayerId = UUID.randomUUID();
        long expiresAt = java.time.Instant.now()
                .plus(java.time.Duration.ofDays(1L))
                .toEpochMilli();
        AtomicReference<org.civiceconomy.nation.NationId> nationId = new AtomicReference<>();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicReference<Throwable> unauthorizedFailure = new AtomicReference<>();
        AtomicReference<Throwable> unauthorizedTieredPolicyFailure =
                new AtomicReference<>();
        AtomicReference<Throwable> changedReplayFailure = new AtomicReference<>();
        AtomicReference<java.util.List<org.civiceconomy.fiscal.Budget>> inspectedBudgets =
                new AtomicReference<>();
        AtomicReference<org.civiceconomy.fiscal.Budget> inspectedBudget =
                new AtomicReference<>();
        AtomicReference<UUID> cancelledDisbursementApprovalId = new AtomicReference<>();
        AtomicReference<org.civiceconomy.fiscal.BudgetDisbursementApprovalPolicyVersion>
                tieredPolicy = new AtomicReference<>();
        AtomicReference<org.civiceconomy.fiscal.BudgetDisbursementApproval>
                pendingTieredApproval = new AtomicReference<>();
        AtomicBoolean setupReady = new AtomicBoolean();
        AtomicBoolean unauthorizedFinished = new AtomicBoolean();
        AtomicBoolean unauthorizedTieredPolicyFinished = new AtomicBoolean();
        AtomicBoolean permissionReady = new AtomicBoolean();
        AtomicBoolean commandStarted = new AtomicBoolean();
        AtomicBoolean changedReplayFinished = new AtomicBoolean();
        AtomicBoolean inspectionReady = new AtomicBoolean();
        AtomicBoolean inspectionCommandsStarted = new AtomicBoolean();
        AtomicBoolean approvalCommandStarted = new AtomicBoolean();
        AtomicBoolean disbursementCommandStarted = new AtomicBoolean();
        AtomicBoolean disbursementInspectionCommandsStarted = new AtomicBoolean();
        AtomicBoolean pendingDisbursementReady = new AtomicBoolean();
        AtomicBoolean tieredPolicyCommandStarted = new AtomicBoolean();
        AtomicBoolean tieredPolicyReady = new AtomicBoolean();
        AtomicBoolean disbursementCancellationCommandStarted = new AtomicBoolean();
        AtomicBoolean recoveryDisbursementReady = new AtomicBoolean();
        AtomicBoolean preparedRecoveryDisbursementReady = new AtomicBoolean();
        AtomicBoolean cancellationCommandStarted = new AtomicBoolean();
        Path databaseFile = helper.getLevel()
                .getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        CivicServerRuntime runtime = CivicServerRuntime.current();
        java.time.Instant now = java.time.Instant.now();
        java.time.Clock setupClock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC);
        long tieredPolicyEffectiveAt = now.plusSeconds(30L).toEpochMilli();

        runtime.submitDatabase(database -> {
                    NationRegistry nations = new NationRegistry(database, snapshot(teamSnapshot));
                    var nation = nations.register(new RegisterNation(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "budget-nation-" + UUID.randomUUID(),
                            teamSnapshot.teamId()));
                    new CitizenshipRegistry(
                                    database, java.time.Duration.ofDays(7L), setupClock)
                            .join(new JoinCitizenship(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "budget-citizenship-" + UUID.randomUUID(),
                                    actor.getUUID(),
                                    nation.nationId()));
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
                                var treasury = new AccountId(
                                        "nation:" + registeredNationId.value() + ":treasury");
                                LightmansCurrencyNationalTreasuryProvisioner
                                        .forLevel(helper.getLevel())
                                        .ensureExists(registeredNationId, treasury);
                                setupReady.set(true);
                            } catch (Throwable setupFailure) {
                                asyncFailure.set(setupFailure);
                            }
                        }));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Budget setup");
                    helper.assertTrue(setupReady.get(), "Budget setup complete");
                })
                .thenExecute(() -> runtime.createNationalBudgetDraft(
                                actor,
                                unauthorizedRequestId,
                                300L,
                                "PUBLIC_WORKS",
                                expiresAt,
                                "Unauthorized public works Budget")
                        .whenComplete((ignored, failure) -> {
                            if (failure == null) {
                                asyncFailure.set(new AssertionError(
                                        "Unauthorized Budget draft unexpectedly succeeded"));
                            } else {
                                unauthorizedFailure.set(rootCause(failure));
                            }
                            unauthorizedFinished.set(true);
                        }))
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Unauthorized Budget draft");
                    helper.assertTrue(
                            unauthorizedFinished.get(),
                            "unauthorized Budget draft completed");
                    helper.assertTrue(
                            unauthorizedFailure.get() instanceof SecurityException,
                            "unauthorized Budget draft fails at Nation fiscal permission");
                    helper.assertTrue(
                            budgetByRequest(databaseFile, unauthorizedRequestId) == null,
                            "unauthorized Budget draft creates no row");
                    helper.assertTrue(
                            !fiscalServiceExists(databaseFile, "civiceconomy-budget"),
                            "unauthorized Budget draft creates no internal service");
                })
                .thenExecute(() -> runtime.scheduleTieredBudgetDisbursementApprovalPolicy(
                                actor,
                                unauthorizedTieredPolicyRequestId,
                                now.plusSeconds(60L).toEpochMilli(),
                                java.time.Duration.ofDays(7L).toMillis(),
                                java.util.List.of(
                                        new org.civiceconomy.fiscal
                                                .BudgetDisbursementApprovalTier(
                                                MoneyAmount.ZERO, 1),
                                        new org.civiceconomy.fiscal
                                                .BudgetDisbursementApprovalTier(
                                                MoneyAmount.ofMinorUnits(100L), 2)),
                                "Unauthorized tiered Budget Disbursement policy")
                        .whenComplete((ignored, failure) -> {
                            if (failure == null) {
                                asyncFailure.set(new AssertionError(
                                        "Unauthorized tiered policy unexpectedly succeeded"));
                            } else {
                                unauthorizedTieredPolicyFailure.set(rootCause(failure));
                            }
                            unauthorizedTieredPolicyFinished.set(true);
                        }))
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(
                            helper, asyncFailure, "Unauthorized tiered policy");
                    helper.assertTrue(
                            unauthorizedTieredPolicyFinished.get(),
                            "unauthorized tiered policy completed");
                    helper.assertTrue(
                            unauthorizedTieredPolicyFailure.get() instanceof SecurityException,
                            "unauthorized tiered policy fails at exact Nation permission");
                })
                .thenExecute(() -> runtime.submitDatabase(database -> {
                            NationRegistry nations = new NationRegistry(
                                    database, snapshot(teamSnapshot));
                            var provider = new FtbTeamsNationProvider(
                                    nations,
                                    new CitizenshipRegistry(
                                            database,
                                            java.time.Duration.ofDays(7L),
                                            setupClock),
                                    new CitizenshipCorrectionGraceRegistry(database, setupClock),
                                    snapshot(teamSnapshot));
                            NationFiscalAuthorityRegistry authorities =
                                    new NationFiscalAuthorityRegistry(
                                            database, provider, setupClock);
                            authorities.grant(new GrantNationFiscalPermission(
                                            new ServiceIdentity("civiceconomy-gametest"),
                                            "budget-draft-authority-" + UUID.randomUUID(),
                                            nationId.get(),
                                            actor.getUUID(),
                                            actor.getUUID(),
                                            NationFiscalPermission.DRAFT_BUDGET,
                                            "Authorize real player Budget drafting"));
                            authorities.grant(new GrantNationFiscalPermission(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "budget-view-authority-" + UUID.randomUUID(),
                                    nationId.get(),
                                    actor.getUUID(),
                                    actor.getUUID(),
                                    NationFiscalPermission.VIEW_ACCOUNT,
                                    "Authorize real player Budget inspection"));
                            authorities.grant(new GrantNationFiscalPermission(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "budget-approve-authority-" + UUID.randomUUID(),
                                    nationId.get(),
                                    actor.getUUID(),
                                    actor.getUUID(),
                                    NationFiscalPermission.APPROVE_BUDGET,
                                    "Authorize real player Budget approval"));
                            authorities.grant(new GrantNationFiscalPermission(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "budget-disbursement-authority-" + UUID.randomUUID(),
                                    nationId.get(),
                                    actor.getUUID(),
                                    actor.getUUID(),
                                    NationFiscalPermission.INITIATE_PAYMENT,
                                    "Authorize real player Budget Disbursement"));
                            authorities.grant(new GrantNationFiscalPermission(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "budget-disbursement-inspection-authority-" + UUID.randomUUID(),
                                    nationId.get(),
                                    actor.getUUID(),
                                    actor.getUUID(),
                                    NationFiscalPermission.APPROVE_PAYMENT,
                                    "Authorize Budget Disbursement approval inspection"));
                            authorities.grant(new GrantNationFiscalPermission(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "budget-disbursement-policy-authority-" + UUID.randomUUID(),
                                    nationId.get(),
                                    actor.getUUID(),
                                    actor.getUUID(),
                                    NationFiscalPermission.MANAGE_APPROVAL_POLICY,
                                    "Authorize Budget Disbursement policy governance"));
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
                    assertNoAsyncFailure(helper, asyncFailure, "Budget permission");
                    helper.assertTrue(permissionReady.get(), "Budget permission ready");
                })
                .thenExecute(() -> {
                    try {
                        String command = "civic economy nation budget create " + requestId
                                + " 300 PUBLIC_WORKS " + expiresAt
                                + " GameTest public works allocation";
                        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        command,
                                        actor.createCommandSourceStack().withSuppressedOutput()),
                                "Budget draft command result");
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        command,
                                        actor.createCommandSourceStack().withSuppressedOutput()),
                                "Budget draft replay command result");
                        commandStarted.set(true);
                    } catch (Throwable failure) {
                        asyncFailure.set(failure);
                    }
                })
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Budget draft command");
                    helper.assertTrue(commandStarted.get(), "Budget draft command started");
                    BudgetRow budget = budgetByRequest(databaseFile, requestId);
                    helper.assertTrue(budget != null, "durable Budget draft");
                    helper.assertValueEqual(
                            "nation:" + nationId.get().value() + ":treasury",
                            budget.sourceAccount(),
                            "Budget source derived from formal Nation");
                    helper.assertValueEqual(300L, budget.amountMinorUnits(), "Budget amount");
                    helper.assertValueEqual("PUBLIC_WORKS", budget.budgetCode(), "Budget code");
                    helper.assertValueEqual(
                            "GameTest public works allocation",
                            budget.purpose(),
                            "Budget purpose");
                    helper.assertValueEqual(expiresAt, budget.expiresAt(), "Budget expiry");
                    helper.assertValueEqual("DRAFT", budget.state(), "Budget draft state");
                    helper.assertTrue(budget.escrowId() == null, "Budget draft has no Escrow");
                    helper.assertValueEqual(
                            0L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(new AccountId(budget.sourceAccount()))
                                    .minorUnits(),
                            "Budget draft moves no LC");
                })
                .thenExecute(() -> runtime.createNationalBudgetDraft(
                                actor,
                                requestId,
                                301L,
                                "PUBLIC_WORKS",
                                expiresAt,
                                "GameTest public works allocation")
                        .whenComplete((ignored, failure) -> {
                            if (failure == null) {
                                asyncFailure.set(new AssertionError(
                                        "Changed Budget replay unexpectedly succeeded"));
                            } else {
                                changedReplayFailure.set(rootCause(failure));
                            }
                            changedReplayFinished.set(true);
                        }))
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Changed Budget replay");
                    helper.assertTrue(changedReplayFinished.get(), "changed replay completed");
                    helper.assertTrue(
                            changedReplayFailure.get()
                                    instanceof org.civiceconomy.fiscal.IdempotencyConflictException,
                            "changed Budget replay conflicts");
                    helper.assertValueEqual(
                            300L,
                            budgetByRequest(databaseFile, requestId).amountMinorUnits(),
                            "changed replay preserves original Budget");
                })
                .thenExecute(() -> {
                    BudgetRow budget = budgetByRequest(databaseFile, requestId);
                    runtime.nationBudgets(actor)
                            .thenCompose(budgets -> {
                                inspectedBudgets.set(budgets);
                                return runtime.nationBudget(actor, budget.budgetId());
                            })
                            .whenComplete((status, failure) -> {
                                if (failure != null) {
                                    asyncFailure.set(failure);
                                } else {
                                    inspectedBudget.set(status);
                                    inspectionReady.set(true);
                                }
                            });
                })
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Budget inspection");
                    helper.assertTrue(inspectionReady.get(), "Budget inspection complete");
                    helper.assertValueEqual(
                            1, inspectedBudgets.get().size(), "one own-Nation Budget visible");
                    helper.assertValueEqual(
                            inspectedBudgets.get().getFirst(),
                            inspectedBudget.get(),
                            "Budget status matches list entry");
                })
                .thenExecute(() -> {
                    try {
                        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        "civic economy nation budget list",
                                        actor.createCommandSourceStack().withSuppressedOutput()),
                                "Budget list command result");
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        "civic economy nation budget status "
                                                + inspectedBudget.get().budgetId(),
                                        actor.createCommandSourceStack().withSuppressedOutput()),
                                "Budget status command result");
                        inspectionCommandsStarted.set(true);
                    } catch (Throwable failure) {
                        asyncFailure.set(failure);
                    }
                })
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Budget inspection commands");
                    helper.assertTrue(
                            inspectionCommandsStarted.get(),
                            "Budget inspection commands started");
                    BudgetRow budget = budgetByRequest(databaseFile, requestId);
                    helper.assertValueEqual("DRAFT", budget.state(), "inspection is read-only");
                    helper.assertTrue(budget.escrowId() == null, "inspection creates no Escrow");
                    helper.assertValueEqual(
                            0L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(new AccountId(budget.sourceAccount()))
                                    .minorUnits(),
                            "inspection moves no LC");
                })
                .thenExecute(() -> {
                    try {
                        fundTreasury(
                                helper,
                                nationId.get(),
                                "Nation " + nationId.get().value() + " National Treasury");
                        String approvalRequestId = "approve-player-budget-" + UUID.randomUUID();
                        String command = "civic economy nation budget approve "
                                + inspectedBudget.get().budgetId() + " "
                                + approvalRequestId
                                + " Approve GameTest public works allocation";
                        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
                        int result = dispatcher.execute(
                                command,
                                actor.createCommandSourceStack().withSuppressedOutput());
                        helper.assertValueEqual(1, result, "Budget approval command result");
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        command,
                                        actor.createCommandSourceStack().withSuppressedOutput()),
                                "Budget approval replay command result");
                        approvalCommandStarted.set(true);
                    } catch (Throwable failure) {
                        asyncFailure.set(failure);
                    }
                })
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Budget approval command");
                    helper.assertTrue(approvalCommandStarted.get(), "Budget approval started");
                    BudgetRow budget = budgetByRequest(databaseFile, requestId);
                    helper.assertValueEqual("APPROVED", budget.state(), "approved Budget state");
                    helper.assertTrue(budget.escrowId() != null, "approval creates one Escrow");
                    BudgetApprovalRow approval =
                            budgetApprovalByBudget(databaseFile, budget.budgetId());
                    helper.assertTrue(approval != null, "durable Budget approval audit");
                    helper.assertValueEqual(
                            actor.getUUID(), approval.actorPlayerId(), "approval actor audit");
                    helper.assertValueEqual(
                            "Approve GameTest public works allocation",
                            approval.reason(),
                            "approval reason audit");
                    helper.assertValueEqual(
                            1_000L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(new AccountId(budget.sourceAccount()))
                                    .minorUnits(),
                            "Budget approval reserves without moving LC");
                })
                .thenExecute(() -> {
                    try {
                        clearPlayerBank(recipientPlayerId);
                        BudgetRow budget = budgetByRequest(databaseFile, requestId);
                        String command = "civic economy nation budget disbursement request "
                                + budget.budgetId() + " " + disbursementRequestId + " "
                                + recipientPlayerId
                                + " 100 Pay GameTest public works supplier";
                        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        command,
                                        actor.createCommandSourceStack().withSuppressedOutput()),
                                "Budget Disbursement command result");
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        command,
                                        actor.createCommandSourceStack().withSuppressedOutput()),
                                "Budget Disbursement replay command result");
                        disbursementCommandStarted.set(true);
                    } catch (Throwable failure) {
                        asyncFailure.set(failure);
                    }
                })
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Budget Disbursement command");
                    helper.assertTrue(
                            disbursementCommandStarted.get(),
                            "Budget Disbursement command started");
                    BudgetRow budget = budgetByRequest(databaseFile, requestId);
                    helper.assertValueEqual(
                            "PARTIALLY_SPENT", budget.state(), "partially spent Budget state");
                    helper.assertValueEqual(
                            100L, budget.settledMinorUnits(), "Budget settled amount");
                    helper.assertValueEqual(
                            200L,
                            budget.amountMinorUnits() - budget.settledMinorUnits(),
                            "Budget remaining amount");
                    BudgetDisbursementRow disbursement = budgetDisbursementByRequest(
                            databaseFile, disbursementRequestId);
                    helper.assertTrue(disbursement != null, "durable Budget Disbursement");
                    helper.assertValueEqual(
                            budget.budgetId(), disbursement.budgetId(), "Disbursement Budget");
                    helper.assertValueEqual(
                            "player:" + recipientPlayerId,
                            disbursement.recipientAccount(),
                            "Disbursement recipient");
                    helper.assertValueEqual(
                            100L, disbursement.amountMinorUnits(), "Disbursement amount");
                    helper.assertValueEqual(
                            "EXECUTED", disbursement.approvalState(), "Disbursement approval state");
                    helper.assertValueEqual(
                            "CIVIC_COMMITTED",
                            disbursement.paymentState(),
                            "Disbursement Payment state");
                    helper.assertValueEqual(
                            1, disbursement.paymentCount(), "one Disbursement Payment");
                    helper.assertValueEqual(
                            900L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(new AccountId(budget.sourceAccount()))
                                    .minorUnits(),
                            "Budget Disbursement debits real LC Treasury once");
                    helper.assertValueEqual(
                            100L,
                            playerBankBalance(recipientPlayerId),
                            "Budget Disbursement credits real LC recipient once");
                })
                .thenExecute(() -> {
                    try {
                        BudgetDisbursementRow disbursement = budgetDisbursementByRequest(
                                databaseFile, disbursementRequestId);
                        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        "civic economy nation budget disbursement policy status",
                                        actor.createCommandSourceStack().withSuppressedOutput()),
                                "Budget Disbursement policy status command result");
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        "civic economy nation budget disbursement policy history",
                                        actor.createCommandSourceStack().withSuppressedOutput()),
                                "Budget Disbursement policy history command result");
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        "civic economy nation budget disbursement approval list",
                                        actor.createCommandSourceStack().withSuppressedOutput()),
                                "Budget Disbursement approval list command result");
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        "civic economy nation budget disbursement approval status "
                                                + disbursement.approvalRequestId(),
                                        actor.createCommandSourceStack().withSuppressedOutput()),
                                "Budget Disbursement approval status command result");
                        disbursementInspectionCommandsStarted.set(true);
                    } catch (Throwable failure) {
                        asyncFailure.set(failure);
                    }
                })
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(
                            helper, asyncFailure, "Budget Disbursement inspection commands");
                    helper.assertTrue(
                            disbursementInspectionCommandsStarted.get(),
                            "Budget Disbursement inspection commands started");
                    BudgetRow budget = budgetByRequest(databaseFile, requestId);
                    BudgetDisbursementRow disbursement = budgetDisbursementByRequest(
                            databaseFile, disbursementRequestId);
                    helper.assertValueEqual(
                            "PARTIALLY_SPENT", budget.state(), "inspection preserves Budget state");
                    helper.assertValueEqual(
                            100L, budget.settledMinorUnits(), "inspection preserves settled amount");
                    helper.assertValueEqual(
                            "EXECUTED",
                            disbursement.approvalState(),
                            "inspection preserves approval state");
                    helper.assertValueEqual(
                            1, disbursement.paymentCount(), "inspection creates no Payment");
                    helper.assertValueEqual(
                            900L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(new AccountId(budget.sourceAccount()))
                                    .minorUnits(),
                            "inspection does not move Treasury LC");
                    helper.assertValueEqual(
                            100L,
                            playerBankBalance(recipientPlayerId),
                            "inspection does not move recipient LC");
                })
                .thenExecute(() -> {
                    try {
                        String command =
                                "civic economy nation budget disbursement policy schedule-tiered "
                                        + tieredPolicyRequestId + " "
                                        + tieredPolicyEffectiveAt
                                        + " 604800000 0:1,100:2,250:3 "
                                        + tieredPolicyReason;
                        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        command,
                                        actor.createCommandSourceStack().withSuppressedOutput()),
                                "tiered Budget Disbursement policy command result");
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        command,
                                        actor.createCommandSourceStack().withSuppressedOutput()),
                                "tiered Budget Disbursement policy replay result");
                        tieredPolicyCommandStarted.set(true);
                    } catch (Throwable failure) {
                        asyncFailure.set(failure);
                    }
                })
                .thenExecute(() -> runtime.budgetDisbursementApprovalPolicyHistory(actor)
                        .whenComplete((policies, failure) -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                                return;
                            }
                            policies.stream()
                                    .filter(policy -> policy.reason().equals(tieredPolicyReason))
                                    .findFirst()
                                    .ifPresent(tieredPolicy::set);
                            tieredPolicyReady.set(true);
                        }))
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(
                            helper, asyncFailure, "tiered Budget Disbursement policy");
                    helper.assertTrue(
                            tieredPolicyCommandStarted.get(),
                            "tiered Budget Disbursement policy command started");
                    helper.assertTrue(tieredPolicyReady.get(), "tiered policy history ready");
                    var policy = tieredPolicy.get();
                    helper.assertTrue(policy != null, "tiered policy persisted exactly once");
                    helper.assertValueEqual(
                            java.util.List.of(
                                    new org.civiceconomy.fiscal.BudgetDisbursementApprovalTier(
                                            MoneyAmount.ZERO, 1),
                                    new org.civiceconomy.fiscal.BudgetDisbursementApprovalTier(
                                            MoneyAmount.ofMinorUnits(100L), 2),
                                    new org.civiceconomy.fiscal.BudgetDisbursementApprovalTier(
                                            MoneyAmount.ofMinorUnits(250L), 3)),
                            policy.tiers(),
                            "persisted Budget Disbursement policy tiers");
                    helper.assertValueEqual(
                            java.time.Duration.ofDays(7L),
                            policy.approvalLifetime(),
                            "persisted Budget Disbursement policy lifetime");
                })
                .thenExecute(() -> runtime.submitDatabase(database -> {
                            BudgetRow budget = budgetByRequest(databaseFile, requestId);
                            return new BudgetDisbursementApprovalRegistry(
                                            database,
                                            java.time.Clock.fixed(
                                                    java.time.Instant.ofEpochMilli(
                                                            tieredPolicyEffectiveAt + 1L),
                                                    java.time.ZoneOffset.UTC))
                                    .initiate(new InitiateBudgetDisbursementApproval(
                                            NationBudgetDisbursementApprovalCoordinator
                                                    .SERVICE_IDENTITY,
                                            cancelledDisbursementRequestId,
                                            nationId.get(),
                                            budget.budgetId(),
                                            new AccountId(
                                                    "player:88888888-8888-8888-8888-888888888888"),
                                            MoneyAmount.ofMinorUnits(200L),
                                            actor.getUUID(),
                                            "Cancelled GameTest public works authority"));
                        })
                        .whenComplete((approval, failure) -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                            } else {
                                pendingTieredApproval.set(approval);
                                cancelledDisbursementApprovalId.set(
                                        approval.approvalRequestId());
                                pendingDisbursementReady.set(true);
                            }
                        }))
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(
                            helper, asyncFailure, "pending Budget Disbursement cancellation setup");
                    helper.assertTrue(
                            pendingDisbursementReady.get(),
                            "pending Budget Disbursement cancellation setup complete");
                    BudgetDisbursementRow pending = budgetDisbursementByRequest(
                            databaseFile, cancelledDisbursementRequestId);
                    helper.assertValueEqual(
                            "PENDING", pending.approvalState(), "pending cancellation state");
                    helper.assertValueEqual(
                            tieredPolicy.get().policyId(),
                            pendingTieredApproval.get().policyId(),
                            "pending approval pins tiered policy");
                    helper.assertValueEqual(
                            2,
                            pendingTieredApproval.get().requiredApprovals(),
                            "200-unit disbursement selects two-person tier");
                    helper.assertValueEqual(
                            0, pending.paymentCount(), "pending cancellation has no Payment");
                })
                .thenExecute(() -> {
                    try {
                        String cancellationRequestId =
                                "cancel-budget-disbursement-approval-" + UUID.randomUUID();
                        String command =
                                "civic economy nation budget disbursement approval cancel "
                                        + cancelledDisbursementApprovalId.get()
                                        + " " + cancellationRequestId
                                        + " Cancel withdrawn GameTest procurement";
                        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        command,
                                        actor.createCommandSourceStack().withSuppressedOutput()),
                                "Budget Disbursement approval cancellation command result");
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        command,
                                        actor.createCommandSourceStack().withSuppressedOutput()),
                                "Budget Disbursement approval cancellation replay result");
                        disbursementCancellationCommandStarted.set(true);
                    } catch (Throwable failure) {
                        asyncFailure.set(failure);
                    }
                })
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(
                            helper, asyncFailure, "Budget Disbursement approval cancellation");
                    helper.assertTrue(
                            disbursementCancellationCommandStarted.get(),
                            "Budget Disbursement approval cancellation started");
                    BudgetRow budget = budgetByRequest(databaseFile, requestId);
                    BudgetDisbursementRow cancelled = budgetDisbursementByRequest(
                            databaseFile, cancelledDisbursementRequestId);
                    helper.assertValueEqual(
                            "CANCELLED",
                            cancelled.approvalState(),
                            "cancelled Budget Disbursement approval state");
                    helper.assertValueEqual(
                            0, cancelled.paymentCount(), "cancellation creates no Payment");
                    helper.assertValueEqual(
                            "PARTIALLY_SPENT",
                            budget.state(),
                            "approval cancellation preserves Budget state");
                    helper.assertValueEqual(
                            100L,
                            budget.settledMinorUnits(),
                            "approval cancellation preserves settled amount");
                    helper.assertValueEqual(
                            900L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(new AccountId(budget.sourceAccount()))
                                    .minorUnits(),
                            "approval cancellation does not move Treasury LC");
                    helper.assertValueEqual(
                            100L,
                            playerBankBalance(recipientPlayerId),
                            "approval cancellation does not move recipient LC");
                })
                .thenExecute(() -> {
                    clearPlayerBank(recoveryRecipientPlayerId);
                    runtime.submitDatabase(database -> {
                                BudgetRow budget = budgetByRequest(databaseFile, requestId);
                                BudgetDisbursementApprovalRegistry approvals =
                                        new BudgetDisbursementApprovalRegistry(
                                                database,
                                                java.time.Clock.fixed(
                                                        java.time.Instant.ofEpochMilli(
                                                                tieredPolicyEffectiveAt + 1L),
                                                        java.time.ZoneOffset.UTC));
                                var approval = approvals.initiate(
                                        new InitiateBudgetDisbursementApproval(
                                                NationBudgetDisbursementApprovalCoordinator
                                                        .SERVICE_IDENTITY,
                                                recoveryDisbursementRequestId,
                                                nationId.get(),
                                                budget.budgetId(),
                                                new AccountId(
                                                        "player:" + recoveryRecipientPlayerId),
                                                MoneyAmount.ofMinorUnits(100L),
                                                actor.getUUID(),
                                                "Recover approved GameTest procurement"));
                                return approvals.approve(
                                        new org.civiceconomy.fiscal
                                                .ApproveBudgetDisbursementApproval(
                                                NationBudgetDisbursementApprovalCoordinator
                                                        .SERVICE_IDENTITY,
                                                "recovery-second-vote-" + UUID.randomUUID(),
                                                approval.approvalRequestId(),
                                                UUID.randomUUID(),
                                                "Second approval committed before recovery"));
                            })
                            .whenComplete((approval, failure) -> {
                                if (failure != null) {
                                    asyncFailure.set(failure);
                                } else if (!"APPROVED".equals(approval.state())) {
                                    asyncFailure.set(new AssertionError(
                                            "Recovery fixture did not reach APPROVED"));
                                } else {
                                    recoveryDisbursementReady.set(true);
                                }
                            });
                })
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(
                            helper, asyncFailure, "Budget Disbursement recovery setup");
                    helper.assertTrue(
                            recoveryDisbursementReady.get(),
                            "Budget Disbursement recovery fixture ready");
                    BudgetDisbursementRow recoverable = budgetDisbursementByRequest(
                            databaseFile, recoveryDisbursementRequestId);
                    helper.assertValueEqual(
                            "APPROVED",
                            recoverable.approvalState(),
                            "recoverable approval committed before Payment");
                    helper.assertValueEqual(
                            0,
                            recoverable.paymentCount(),
                            "recoverable approval has no Payment before scan");
                })
                .thenExecute(runtime::triggerBudgetDisbursementRecovery)
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(
                            helper, asyncFailure, "Budget Disbursement recovery");
                    BudgetDisbursementRow recovered = budgetDisbursementByRequest(
                            databaseFile, recoveryDisbursementRequestId);
                    helper.assertValueEqual(
                            "EXECUTED",
                            recovered.approvalState(),
                            "recovery executes approved decision");
                    helper.assertValueEqual(
                            "CIVIC_COMMITTED",
                            recovered.paymentState(),
                            "recovery commits stable Payment");
                    helper.assertValueEqual(
                            1, recovered.paymentCount(), "recovery creates one Payment");
                    BudgetRow budget = budgetByRequest(databaseFile, requestId);
                    helper.assertValueEqual(
                            200L,
                            budget.settledMinorUnits(),
                            "recovery settles exact Budget amount");
                    helper.assertValueEqual(
                            800L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(new AccountId(budget.sourceAccount()))
                                    .minorUnits(),
                            "recovery debits real Treasury LC exactly once");
                    helper.assertValueEqual(
                            100L,
                            playerBankBalance(recoveryRecipientPlayerId),
                            "recovery credits exact recipient once");
                })
                .thenExecute(() -> {
                    clearPlayerBank(preparedRecoveryRecipientPlayerId);
                    runtime.submitDatabase(database -> {
                                UUID preparedBudgetId = UUID.randomUUID();
                                String preparedBudgetRequestId =
                                        "prepared-recovery-budget-" + UUID.randomUUID();
                                AccountId treasury = new AccountId(
                                        "nation:" + nationId.get().value() + ":treasury");
                                database.createBudget(
                                        preparedBudgetId,
                                        BudgetFiscalServiceProvisioner
                                                .SERVICE_IDENTITY
                                                .value(),
                                        preparedBudgetRequestId,
                                        treasury.value(),
                                        100L,
                                        "PUBLIC_WORKS",
                                        "Prepared recovery GameTest Budget",
                                        expiresAt);
                                database.approveBudget(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        BudgetFiscalServiceProvisioner
                                                .SERVICE_IDENTITY
                                                .value(),
                                        "approve-" + preparedBudgetRequestId,
                                        preparedBudgetId,
                                        actor.getUUID(),
                                        "Approve prepared recovery Budget",
                                        java.time.Instant.now().toEpochMilli());
                                BudgetDisbursementApprovalRegistry approvals =
                                        new BudgetDisbursementApprovalRegistry(
                                                database,
                                                java.time.Clock.fixed(
                                                        java.time.Instant.ofEpochMilli(
                                                                tieredPolicyEffectiveAt + 1L),
                                                        java.time.ZoneOffset.UTC));
                                var approval = approvals.initiate(
                                        new InitiateBudgetDisbursementApproval(
                                                NationBudgetDisbursementApprovalCoordinator
                                                        .SERVICE_IDENTITY,
                                                preparedRecoveryDisbursementRequestId,
                                                nationId.get(),
                                                preparedBudgetId,
                                                new AccountId(
                                                        "player:"
                                                                + preparedRecoveryRecipientPlayerId),
                                                MoneyAmount.ofMinorUnits(100L),
                                                actor.getUUID(),
                                                "Recover an already prepared payment"));
                                approval = approvals.approve(
                                        new org.civiceconomy.fiscal
                                                .ApproveBudgetDisbursementApproval(
                                                NationBudgetDisbursementApprovalCoordinator
                                                        .SERVICE_IDENTITY,
                                                "prepared-recovery-second-vote-"
                                                        + UUID.randomUUID(),
                                                approval.approvalRequestId(),
                                                UUID.randomUUID(),
                                                "Second approval before Payment preparation"));
                                new BudgetDisbursementPaymentCoordinator(
                                                database, ignored -> {})
                                        .prepare(approval.approvalRequestId());
                                return approval;
                            })
                            .whenComplete((approval, failure) -> {
                                if (failure != null) {
                                    asyncFailure.set(failure);
                                } else {
                                    preparedRecoveryDisbursementReady.set(true);
                                }
                            });
                })
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(
                            helper, asyncFailure, "Prepared Budget Disbursement recovery setup");
                    helper.assertTrue(
                            preparedRecoveryDisbursementReady.get(),
                            "Prepared Budget Disbursement recovery fixture ready");
                    BudgetDisbursementRow recoverable = budgetDisbursementByRequest(
                            databaseFile, preparedRecoveryDisbursementRequestId);
                    helper.assertValueEqual(
                            "APPROVED",
                            recoverable.approvalState(),
                            "prepared recovery approval remains approved");
                    helper.assertValueEqual(
                            "PREPARED",
                            recoverable.paymentState(),
                            "prepared recovery Payment exists before scan");
                })
                .thenExecute(() -> helper.getLevel()
                        .getServer()
                        .getCommands()
                        .performPrefixedCommand(
                                helper.getLevel().getServer().createCommandSourceStack(),
                                "civic economy admin budget-disbursement recovery status"))
                .thenWaitUntil(() -> {
                    BudgetDisbursementRow inspected = budgetDisbursementByRequest(
                            databaseFile, preparedRecoveryDisbursementRequestId);
                    helper.assertValueEqual(
                            "APPROVED",
                            inspected.approvalState(),
                            "recovery inspection preserves approval state");
                    helper.assertValueEqual(
                            "PREPARED",
                            inspected.paymentState(),
                            "recovery inspection preserves Payment state");
                })
                .thenExecute(runtime::triggerBudgetDisbursementRecovery)
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(
                            helper, asyncFailure, "Prepared Budget Disbursement recovery");
                    BudgetDisbursementRow recovered = budgetDisbursementByRequest(
                            databaseFile, preparedRecoveryDisbursementRequestId);
                    helper.assertValueEqual(
                            "EXECUTED",
                            recovered.approvalState(),
                            "prepared recovery executes approved decision");
                    helper.assertValueEqual(
                            "CIVIC_COMMITTED",
                            recovered.paymentState(),
                            "prepared recovery commits stable Payment");
                    helper.assertValueEqual(
                            700L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(new AccountId(
                                            "nation:" + nationId.get().value() + ":treasury"))
                                    .minorUnits(),
                            "prepared recovery debits real Treasury LC once");
                    helper.assertValueEqual(
                            100L,
                            playerBankBalance(preparedRecoveryRecipientPlayerId),
                            "prepared recovery credits exact recipient once");
                })
                .thenExecute(() -> {
                    try {
                        BudgetRow budget = budgetByRequest(databaseFile, requestId);
                        String cancellationRequestId =
                                "cancel-player-budget-" + UUID.randomUUID();
                        String command = "civic economy nation budget cancel "
                                + budget.budgetId() + " " + cancellationRequestId
                                + " Cancel unfinished GameTest public works";
                        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        command,
                                        actor.createCommandSourceStack().withSuppressedOutput()),
                                "Budget cancellation command result");
                        helper.assertValueEqual(
                                1,
                                dispatcher.execute(
                                        command,
                                        actor.createCommandSourceStack().withSuppressedOutput()),
                                "Budget cancellation replay command result");
                        cancellationCommandStarted.set(true);
                    } catch (Throwable failure) {
                        asyncFailure.set(failure);
                    }
                })
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Budget cancellation command");
                    helper.assertTrue(
                            cancellationCommandStarted.get(),
                            "Budget cancellation started");
                    BudgetRow budget = budgetByRequest(databaseFile, requestId);
                    helper.assertValueEqual("RELEASED", budget.state(), "cancelled Budget state");
                    BudgetCancellationRow cancellation =
                            budgetCancellationByBudget(databaseFile, budget.budgetId());
                    helper.assertTrue(cancellation != null, "durable Budget cancellation audit");
                    helper.assertValueEqual(
                            actor.getUUID(),
                            cancellation.actorPlayerId(),
                            "cancellation actor audit");
                    helper.assertValueEqual(
                            "Cancel unfinished GameTest public works",
                            cancellation.reason(),
                            "cancellation reason audit");
                    helper.assertValueEqual(
                            "RELEASED", cancellation.escrowState(), "cancelled Escrow state");
                    helper.assertValueEqual(
                            "RELEASED",
                            cancellation.reservationState(),
                            "cancelled Reservation state");
                    helper.assertValueEqual(
                            1, cancellation.auditCount(), "one Budget cancellation audit");
                    helper.assertValueEqual(
                            1, cancellation.releaseCount(), "one Reservation release audit");
                    helper.assertValueEqual(
                            300L, cancellation.reservationAmount(), "original Reservation amount");
                    helper.assertValueEqual(
                            200L, cancellation.settledAmount(), "preserved settled amount");
                    helper.assertValueEqual(
                            100L,
                            cancellation.reservationAmount() - cancellation.settledAmount(),
                            "only remaining Budget amount released");
                    helper.assertValueEqual(
                            700L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(new AccountId(budget.sourceAccount()))
                                    .minorUnits(),
                            "Budget cancellation releases a logical hold without moving LC");
                    helper.assertValueEqual(
                            100L,
                            playerBankBalance(recipientPlayerId),
                            "Budget cancellation does not reverse executed Disbursement");
                    helper.assertValueEqual(
                            100L,
                            playerBankBalance(recoveryRecipientPlayerId),
                            "Budget cancellation does not reverse recovered Disbursement");
                    helper.assertValueEqual(
                            100L,
                            playerBankBalance(preparedRecoveryRecipientPlayerId),
                            "Budget cancellation does not reverse prepared recovery Disbursement");
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void authorizedNationPlayerIssuesExactFiscalBillOffThread(
            GameTestHelper helper) {
        ServerPlayer issuer = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-fiscal-bill-issuer"),
                ClientInformation.createDefault());
        Team team = createHeadOwnedFtbTeamFixture(issuer);
        NationTeam teamSnapshot = new NationTeam(
                team.getId(), team.getOwner(), team.getMembers());
        ServerPlayer payer = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "civic-fiscal-bill-payer"),
                ClientInformation.createDefault());
        UUID payerId = payer.getUUID();
        String unauthorizedRequestId = "unauthorized-bill-" + UUID.randomUUID();
        String requestId = "player-bill-" + UUID.randomUUID();
        String cancellationIssueRequestId = "player-cancel-bill-" + UUID.randomUUID();
        long dueAt = java.time.Instant.now().plus(java.time.Duration.ofDays(1L)).toEpochMilli();
        AtomicReference<org.civiceconomy.nation.NationId> nationId = new AtomicReference<>();
        AtomicReference<Throwable> asyncFailure = new AtomicReference<>();
        AtomicReference<Throwable> unauthorizedFailure = new AtomicReference<>();
        AtomicReference<Throwable> nationInspectionFailure = new AtomicReference<>();
        AtomicReference<java.util.List<org.civiceconomy.fiscal.FiscalBill>> payerBills =
                new AtomicReference<>();
        AtomicReference<java.util.List<org.civiceconomy.fiscal.FiscalBill>> nationBills =
                new AtomicReference<>();
        AtomicReference<org.civiceconomy.fiscal.FiscalBill> payerBill =
                new AtomicReference<>();
        AtomicReference<org.civiceconomy.fiscal.FiscalBill> nationBill =
                new AtomicReference<>();
        AtomicReference<UUID> billId = new AtomicReference<>();
        AtomicReference<UUID> cancellationBillId = new AtomicReference<>();
        AtomicBoolean setupReady = new AtomicBoolean();
        AtomicBoolean unauthorizedFinished = new AtomicBoolean();
        AtomicBoolean permissionReady = new AtomicBoolean();
        AtomicBoolean commandStarted = new AtomicBoolean();
        AtomicBoolean nationInspectionRejected = new AtomicBoolean();
        AtomicBoolean viewPermissionReady = new AtomicBoolean();
        AtomicBoolean inspectionReady = new AtomicBoolean();
        AtomicBoolean inspectionCommandsStarted = new AtomicBoolean();
        AtomicBoolean fundingCommandsStarted = new AtomicBoolean();
        AtomicBoolean paymentCommandsStarted = new AtomicBoolean();
        AtomicBoolean cancellationBillIssued = new AtomicBoolean();
        AtomicBoolean cancellationFundingCommandsStarted = new AtomicBoolean();
        AtomicBoolean cancellationCommandsStarted = new AtomicBoolean();
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
                            "fiscal-bill-nation-" + UUID.randomUUID(),
                            teamSnapshot.teamId()));
                    new CitizenshipRegistry(
                                    database, java.time.Duration.ofDays(7L), setupClock)
                            .join(new JoinCitizenship(
                                    new ServiceIdentity("civiceconomy-gametest"),
                                    "fiscal-bill-citizenship-" + UUID.randomUUID(),
                                    issuer.getUUID(),
                                    nation.nationId()));
                    return nation.nationId();
                })
                .whenComplete((registeredNationId, failure) -> {
                    if (failure != null) {
                        asyncFailure.set(failure);
                    } else {
                        nationId.set(registeredNationId);
                        setupReady.set(true);
                    }
                });

        helper.startSequence()
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Fiscal Bill setup");
                    helper.assertTrue(setupReady.get(), "Fiscal Bill setup complete");
                })
                .thenExecute(() -> runtime.issueNationalFiscalBill(
                                issuer,
                                unauthorizedRequestId,
                                payerId,
                                300L,
                                org.civiceconomy.fiscal.FiscalBillKind.FEE,
                                dueAt,
                                "Unauthorized GameTest fee")
                        .whenComplete((ignored, failure) -> {
                            if (failure == null) {
                                asyncFailure.set(new AssertionError(
                                        "Unauthorized Fiscal Bill issuance unexpectedly succeeded"));
                            } else {
                                unauthorizedFailure.set(rootCause(failure));
                            }
                            unauthorizedFinished.set(true);
                        }))
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Unauthorized Fiscal Bill");
                    helper.assertTrue(
                            unauthorizedFinished.get(),
                            "unauthorized Fiscal Bill issuance completed");
                    helper.assertTrue(
                            unauthorizedFailure.get() instanceof SecurityException,
                            "unauthorized issuance fails at Nation fiscal permission");
                    helper.assertTrue(
                            fiscalBillIdByRequest(databaseFile, unauthorizedRequestId) == null,
                            "unauthorized issuance creates no Fiscal Bill");
                })
                .thenExecute(() -> runtime.submitDatabase(database -> {
                            NationRegistry nations = new NationRegistry(
                                    database, snapshot(teamSnapshot));
                            var provider = new FtbTeamsNationProvider(
                                    nations,
                                    new CitizenshipRegistry(
                                            database,
                                            java.time.Duration.ofDays(7L),
                                            setupClock),
                                    new CitizenshipCorrectionGraceRegistry(database, setupClock),
                                    snapshot(teamSnapshot));
                            new NationFiscalAuthorityRegistry(database, provider, setupClock)
                                    .grant(new GrantNationFiscalPermission(
                                            new ServiceIdentity("civiceconomy-gametest"),
                                            "fiscal-bill-authority-" + UUID.randomUUID(),
                                            nationId.get(),
                                            issuer.getUUID(),
                                            issuer.getUUID(),
                                            NationFiscalPermission.INITIATE_PAYMENT,
                                            "Authorize real player Fiscal Bill issuance"));
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
                    assertNoAsyncFailure(helper, asyncFailure, "Fiscal Bill permission");
                    helper.assertTrue(permissionReady.get(), "Fiscal Bill permission ready");
                })
                .thenExecute(() -> helper.getLevel().getServer().execute(() -> {
                    try {
                        String command = "civic economy nation bill issue " + requestId
                                + " " + payerId + " 300 FEE " + dueAt
                                + " GameTest building permit fee";
                        int first = helper.getLevel().getServer().getCommands()
                                .getDispatcher()
                                .execute(command, issuer.createCommandSourceStack()
                                        .withSuppressedOutput());
                        int replay = helper.getLevel().getServer().getCommands()
                                .getDispatcher()
                                .execute(command, issuer.createCommandSourceStack()
                                        .withSuppressedOutput());
                        helper.assertValueEqual(1, first, "Fiscal Bill issue command result");
                        helper.assertValueEqual(1, replay, "Fiscal Bill issue replay result");
                        commandStarted.set(true);
                    } catch (Throwable commandFailure) {
                        asyncFailure.set(commandFailure);
                    }
                }))
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Fiscal Bill command");
                    helper.assertTrue(commandStarted.get(), "Fiscal Bill command started");
                    assertIssuedFiscalBill(
                            helper,
                            databaseFile,
                            requestId,
                            nationId.get(),
                            payerId,
                            dueAt);
                })
                .thenExecute(() -> {
                    String persistedBillId = fiscalBillIdByRequest(databaseFile, requestId);
                    billId.set(UUID.fromString(persistedBillId));
                    runtime.nationFiscalBills(issuer)
                            .whenComplete((ignored, failure) -> {
                                if (failure == null) {
                                    asyncFailure.set(new AssertionError(
                                            "Nation Fiscal Bill inspection without VIEW_ACCOUNT succeeded"));
                                } else {
                                    nationInspectionFailure.set(rootCause(failure));
                                }
                                nationInspectionRejected.set(true);
                            });
                })
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Nation Fiscal Bill inspection denial");
                    helper.assertTrue(
                            nationInspectionRejected.get(),
                            "Nation Fiscal Bill inspection denial completed");
                    helper.assertTrue(
                            nationInspectionFailure.get() instanceof SecurityException,
                            "Nation Fiscal Bill inspection requires VIEW_ACCOUNT");
                })
                .thenExecute(() -> runtime.submitDatabase(database -> {
                            NationRegistry nations = new NationRegistry(
                                    database, snapshot(teamSnapshot));
                            var provider = new FtbTeamsNationProvider(
                                    nations,
                                    new CitizenshipRegistry(
                                            database,
                                            java.time.Duration.ofDays(7L),
                                            setupClock),
                                    new CitizenshipCorrectionGraceRegistry(database, setupClock),
                                    snapshot(teamSnapshot));
                            new NationFiscalAuthorityRegistry(database, provider, setupClock)
                                    .grant(new GrantNationFiscalPermission(
                                            new ServiceIdentity("civiceconomy-gametest"),
                                            "fiscal-bill-view-authority-" + UUID.randomUUID(),
                                            nationId.get(),
                                            issuer.getUUID(),
                                            issuer.getUUID(),
                                            NationFiscalPermission.VIEW_ACCOUNT,
                                            "Authorize National Treasury receivable inspection"));
                            return null;
                        })
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                            } else {
                                viewPermissionReady.set(true);
                            }
                        }))
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Fiscal Bill view permission");
                    helper.assertTrue(viewPermissionReady.get(), "Fiscal Bill view permission ready");
                })
                .thenExecute(() -> {
                    var payerList = runtime.payerFiscalBills(payer)
                            .thenAccept(payerBills::set);
                    var payerStatus = runtime.payerFiscalBill(payer, billId.get())
                            .thenAccept(payerBill::set);
                    var nationList = runtime.nationFiscalBills(issuer)
                            .thenAccept(nationBills::set);
                    var nationStatus = runtime.nationFiscalBill(issuer, billId.get())
                            .thenAccept(nationBill::set);
                    CompletableFuture.allOf(
                                    payerList, payerStatus, nationList, nationStatus)
                            .whenComplete((ignored, failure) -> {
                                if (failure != null) {
                                    asyncFailure.set(failure);
                                } else {
                                    inspectionReady.set(true);
                                }
                            });
                })
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Fiscal Bill inspection");
                    helper.assertTrue(inspectionReady.get(), "Fiscal Bill inspection ready");
                    helper.assertValueEqual(1, payerBills.get().size(), "payer Bill count");
                    helper.assertValueEqual(1, nationBills.get().size(), "Nation Bill count");
                    helper.assertValueEqual(
                            billId.get(), payerBill.get().billId(), "payer exact Bill status");
                    helper.assertValueEqual(
                            billId.get(), nationBill.get().billId(), "Nation exact Bill status");
                    helper.assertValueEqual(
                            payerBill.get(), payerBills.get().getFirst(), "payer list exact Bill");
                    helper.assertValueEqual(
                            nationBill.get(), nationBills.get().getFirst(), "Nation list exact Bill");
                })
                .thenExecute(() -> helper.getLevel().getServer().execute(() -> {
                    try {
                        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
                        int payerList = dispatcher.execute(
                                "civic economy bill list",
                                payer.createCommandSourceStack().withSuppressedOutput());
                        int payerStatus = dispatcher.execute(
                                "civic economy bill status " + billId.get(),
                                payer.createCommandSourceStack().withSuppressedOutput());
                        int nationList = dispatcher.execute(
                                "civic economy nation bill list",
                                issuer.createCommandSourceStack().withSuppressedOutput());
                        int nationStatus = dispatcher.execute(
                                "civic economy nation bill status " + billId.get(),
                                issuer.createCommandSourceStack().withSuppressedOutput());
                        helper.assertValueEqual(1, payerList, "payer Bill list command result");
                        helper.assertValueEqual(1, payerStatus, "payer Bill status command result");
                        helper.assertValueEqual(1, nationList, "Nation Bill list command result");
                        helper.assertValueEqual(1, nationStatus, "Nation Bill status command result");
                        inspectionCommandsStarted.set(true);
                    } catch (Throwable commandFailure) {
                        asyncFailure.set(commandFailure);
                    }
                }))
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Fiscal Bill inspection commands");
                    helper.assertTrue(
                            inspectionCommandsStarted.get(),
                            "Fiscal Bill inspection commands started");
                    assertIssuedFiscalBill(
                            helper,
                            databaseFile,
                            requestId,
                            nationId.get(),
                            payerId,
                            dueAt);
                })
                .thenExecute(() -> {
                    LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                            .create(
                                    new AccountId(
                                            "nation:" + nationId.get().value() + ":treasury"),
                                    FiscalAccountKind.NATIONAL_TREASURY,
                                    "Fiscal Bill GameTest Treasury");
                    seedPlayerBank(payerId, 1_000L);
                })
                .thenExecute(() -> helper.getLevel().getServer().execute(() -> {
                    try {
                        String command = "civic economy bill fund " + billId.get()
                                + " fund-player-bill-" + billId.get();
                        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
                        int first = dispatcher.execute(
                                command, payer.createCommandSourceStack().withSuppressedOutput());
                        int replay = dispatcher.execute(
                                command, payer.createCommandSourceStack().withSuppressedOutput());
                        helper.assertValueEqual(1, first, "Fiscal Bill funding command result");
                        helper.assertValueEqual(1, replay, "Fiscal Bill funding replay result");
                        fundingCommandsStarted.set(true);
                    } catch (Throwable commandFailure) {
                        asyncFailure.set(commandFailure);
                    }
                }))
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Fiscal Bill funding command");
                    helper.assertTrue(
                            fundingCommandsStarted.get(),
                            "Fiscal Bill funding commands started");
                    assertFundedFiscalBill(
                            helper,
                            databaseFile,
                            billId.get(),
                            payerId,
                            "fund-player-bill-" + billId.get(),
                            "nation:" + nationId.get().value() + ":treasury",
                            300L);
                    helper.assertValueEqual(
                            1_000L,
                            playerBankBalance(payerId),
                            "Fiscal Bill funding does not move LC");
                })
                .thenExecute(() -> helper.getLevel().getServer().execute(() -> {
                    try {
                        String command = "civic economy bill pay " + billId.get()
                                + " pay-player-bill-" + billId.get();
                        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
                        int first = dispatcher.execute(
                                command, payer.createCommandSourceStack().withSuppressedOutput());
                        int replay = dispatcher.execute(
                                command, payer.createCommandSourceStack().withSuppressedOutput());
                        helper.assertValueEqual(1, first, "Fiscal Bill payment command result");
                        helper.assertValueEqual(1, replay, "Fiscal Bill payment replay result");
                        paymentCommandsStarted.set(true);
                    } catch (Throwable commandFailure) {
                        asyncFailure.set(commandFailure);
                    }
                }))
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Fiscal Bill payment command");
                    helper.assertTrue(
                            paymentCommandsStarted.get(),
                            "Fiscal Bill payment commands started");
                    String beneficiary = "nation:" + nationId.get().value() + ":treasury";
                    assertPaidFiscalBill(
                            helper,
                            databaseFile,
                            billId.get(),
                            payerId,
                            "pay-player-bill-" + billId.get(),
                            beneficiary);
                    helper.assertValueEqual(
                            700L,
                            playerBankBalance(payerId),
                            "Fiscal Bill payment debits real LC payer once");
                    helper.assertValueEqual(
                            300L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(new AccountId(beneficiary))
                                    .minorUnits(),
                            "Fiscal Bill payment credits persisted beneficiary once");
                })
                .thenExecute(() -> runtime.submitDatabase(database -> {
                            var stored = database.issueFiscalBill(
                                    UUID.randomUUID(),
                                    FiscalBillFiscalServiceProvisioner
                                            .SERVICE_IDENTITY
                                            .value(),
                                    cancellationIssueRequestId,
                                    "player:" + payerId,
                                    "nation:" + nationId.get().value() + ":treasury",
                                    100L,
                                    FiscalBillKind.FEE.name(),
                                    "Cancelled player fee",
                                    java.time.Instant.now()
                                            .plus(java.time.Duration.ofDays(1L))
                                            .toEpochMilli());
                            cancellationBillId.set(stored.billId());
                            cancellationBillIssued.set(true);
                            return null;
                        })
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) {
                                asyncFailure.set(failure);
                            }
                        }))
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "cancellation Bill issuance");
                    helper.assertTrue(cancellationBillIssued.get(), "cancellation Bill issued");
                    helper.assertTrue(cancellationBillId.get() != null, "cancellation Bill ID");
                })
                .thenExecute(() -> helper.getLevel().getServer().execute(() -> {
                    try {
                        String command = "civic economy bill fund " + cancellationBillId.get()
                                + " fund-cancel-bill-" + cancellationBillId.get();
                        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
                        int first = dispatcher.execute(
                                command, payer.createCommandSourceStack().withSuppressedOutput());
                        int replay = dispatcher.execute(
                                command, payer.createCommandSourceStack().withSuppressedOutput());
                        helper.assertValueEqual(
                                1, first, "cancellation Bill funding command result");
                        helper.assertValueEqual(
                                1, replay, "cancellation Bill funding replay result");
                        cancellationFundingCommandsStarted.set(true);
                    } catch (Throwable commandFailure) {
                        asyncFailure.set(commandFailure);
                    }
                }))
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(
                            helper, asyncFailure, "cancellation Bill funding command");
                    helper.assertTrue(
                            cancellationFundingCommandsStarted.get(),
                            "cancellation Bill funding commands started");
                    assertFundedFiscalBill(
                            helper,
                            databaseFile,
                            cancellationBillId.get(),
                            payerId,
                            "fund-cancel-bill-" + cancellationBillId.get(),
                            "nation:" + nationId.get().value() + ":treasury",
                            100L);
                })
                .thenExecute(() -> helper.getLevel().getServer().execute(() -> {
                    try {
                        String command = "civic economy bill cancel "
                                + cancellationBillId.get()
                                + " cancel-player-bill-" + cancellationBillId.get()
                                + " Payer withdrew the fee";
                        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
                        int first = dispatcher.execute(
                                command, payer.createCommandSourceStack().withSuppressedOutput());
                        int replay = dispatcher.execute(
                                command, payer.createCommandSourceStack().withSuppressedOutput());
                        helper.assertValueEqual(1, first, "Fiscal Bill cancellation result");
                        helper.assertValueEqual(1, replay, "Fiscal Bill cancellation replay");
                        cancellationCommandsStarted.set(true);
                    } catch (Throwable commandFailure) {
                        asyncFailure.set(commandFailure);
                    }
                }))
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Fiscal Bill cancellation command");
                    helper.assertTrue(
                            cancellationCommandsStarted.get(),
                            "Fiscal Bill cancellation commands started");
                    assertCancelledFiscalBill(
                            helper,
                            databaseFile,
                            cancellationBillId.get(),
                            "cancel-player-bill-" + cancellationBillId.get(),
                            "Payer withdrew the fee");
                    helper.assertValueEqual(
                            700L,
                            playerBankBalance(payerId),
                            "Fiscal Bill cancellation does not move payer LC");
                    helper.assertValueEqual(
                            300L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(new AccountId(
                                            "nation:" + nationId.get().value() + ":treasury"))
                                    .minorUnits(),
                            "Fiscal Bill cancellation does not move beneficiary LC");
                })
                .thenSucceed();
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
                .thenIdle(2)
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
        AtomicReference<UUID> activePolicyId = new AtomicReference<>();
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
                    var activePolicy = new org.civiceconomy.fiscal.WithdrawalApprovalPolicyRegistry(
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
                                    java.time.Duration.ofDays(7L),
                                    now.minus(java.time.Duration.ofDays(1)),
                                    "Require two distinct Citizens"));
                    activePolicyId.set(activePolicy.policyId());
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
                        String command = "civic economy nation treasury withdraw policy schedule-tiered "
                                + policyRequestId + " " + effectiveAt
                                + " 259200000 0:1,500:2,2000:3"
                                + " Future governed Withdrawal policy";
                        helper.assertValueEqual(
                                1,
                                helper.getLevel().getServer().getCommands().getDispatcher()
                                        .execute(
                                                command,
                                                initiator.createCommandSourceStack()
                                                        .withSuppressedOutput()),
                                "Withdrawal policy schedule command result");
                        helper.assertValueEqual(
                                1,
                                helper.getLevel().getServer().getCommands().getDispatcher()
                                        .execute(
                                                command,
                                                initiator.createCommandSourceStack()
                                                        .withSuppressedOutput()),
                                "Withdrawal tiered policy replay command result");
                        policyCommandStarted.set(true);
                    } catch (Throwable failure) {
                        asyncFailure.set(failure);
                    }
                })
                .thenWaitUntil(() -> {
                    assertNoAsyncFailure(helper, asyncFailure, "Withdrawal policy command");
                    helper.assertTrue(policyCommandStarted.get(), "policy command started");
                    Long policyLifetime = withdrawalApprovalPolicyLifetime(
                            databaseFile, policyRequestId);
                    helper.assertTrue(
                            policyLifetime != null,
                            "future-effective Withdrawal policy is persisted");
                    helper.assertValueEqual(
                            259_200_000L,
                            policyLifetime.longValue(),
                            "future-effective Withdrawal policy pins three-day lifetime");
                    helper.assertValueEqual(
                            "0:1,500:2,2000:3",
                            withdrawalApprovalPolicyTiers(databaseFile, policyRequestId),
                            "future-effective Withdrawal policy persists ordered tiers");
                    helper.assertValueEqual(
                            1,
                            withdrawalApprovalPolicyCount(databaseFile, policyRequestId),
                            "tiered policy replay persists one policy version");
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
                    helper.assertValueEqual(
                            activePolicyId.get(),
                            approval.policyId(),
                            "existing active policy remains pinned after future schedule");
                    helper.assertValueEqual(
                            604_800_000L,
                            treasuryWithdrawalApprovalLifetime(databaseFile, withdrawalRequestId),
                            "existing approval keeps original seven-day expiry");
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
                                        "civic economy nation treasury withdraw policy history",
                                        initiator.createCommandSourceStack()
                                                .withSuppressedOutput()),
                                "Withdrawal policy history command result");
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
                .thenExecute(runtime::recoverTreasuryWithdrawalsNowForGameTest)
                .thenWaitUntil(() -> {
                    TreasuryWithdrawalApprovalRow approval =
                            treasuryWithdrawalApprovalByRequest(
                                    databaseFile, withdrawalRequestId);
                    TreasuryWithdrawalRow operation =
                            treasuryWithdrawalByRequest(databaseFile, withdrawalRequestId);
                    helper.assertValueEqual(
                            "APPROVED", approval.state(),
                            "automatic recovery preserves the approved decision");
                    helper.assertTrue(
                            operation != null,
                            "automatic recovery prepares the approved Withdrawal");
                    helper.assertValueEqual(
                            "PREPARED", operation.state(),
                            "offline actor leaves the recovered Withdrawal prepared");
                    helper.assertValueEqual(
                            1_000L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(new AccountId(operation.sourceAccount()))
                                    .minorUnits(),
                            "preparation alone does not debit real LC");
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
                    TreasuryWithdrawalRow operation =
                            treasuryWithdrawalByRequest(databaseFile, withdrawalRequestId);
                    helper.assertValueEqual(
                            "PREPARED", operation.state(),
                            "read-only OP inspection does not advance the Withdrawal operation");
                    helper.assertValueEqual(
                            1_000L,
                            LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel())
                                    .balance(new AccountId(operation.sourceAccount()))
                                    .minorUnits(),
                            "read-only OP inspection does not debit real LC");
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
            timeoutTicks = 5000,
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

    private static void waitForFacilityActivation(
            CivicServerRuntime runtime,
            GameTestHelper helper,
            UUID facilityId,
            AtomicReference<StoredFacilityAccountingBaseline> activatedBaseline,
            AtomicReference<StoredRegisteredFacility> activatedFacility,
            AtomicReference<Throwable> activationFailure,
            AtomicBoolean activationFinished,
            int attemptsRemaining) {
        runtime.submitDatabase(database -> {
                    StoredFacilityAccountingBaseline baseline =
                            database.facilityAccountingBaseline(facilityId);
                    StoredRegisteredFacility facility = database.registeredFacility(facilityId);
                    activatedBaseline.set(baseline);
                    activatedFacility.set(facility);
                    return baseline != null
                            && facility != null
                            && "ACTIVE".equals(baseline.state())
                            && "ACTIVE".equals(facility.state());
                })
                .whenComplete((active, failure) -> {
                    if (failure != null) {
                        activationFailure.set(failure);
                        activationFinished.set(true);
                        return;
                    }
                    if (Boolean.TRUE.equals(active)) {
                        activationFinished.set(true);
                        return;
                    }
                    if (attemptsRemaining <= 1) {
                        activationFailure.set(new IllegalStateException(
                                "Facility activation did not reach ACTIVE state"));
                        activationFinished.set(true);
                        return;
                    }
                    helper.getLevel().getServer().execute(() -> helper.runAfterDelay(
                            1L,
                            () -> waitForFacilityActivation(
                                    runtime,
                                    helper,
                                    facilityId,
                                    activatedBaseline,
                                    activatedFacility,
                                    activationFailure,
                                    activationFinished,
                                    attemptsRemaining - 1)));
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

    private static long permanentDestructionEventCount(
            Path databaseFile, UUID operationId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT COUNT(*)
                        FROM monetary_supply_event
                        WHERE change_kind = 'PERMANENT_DESTRUCTION'
                          AND external_reference = ?
                        """)) {
            query.setString(1, "permanent-destruction:" + operationId);
            try (var result = query.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Permanent Destruction event count", failure);
        }
    }

    private static long permanentDestructionEventCountByRequest(
            Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT COUNT(*)
                        FROM monetary_supply_event
                        WHERE change_kind = 'PERMANENT_DESTRUCTION'
                          AND request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Permanent Destruction event request count",
                    failure);
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

    private static ServerPlayer connectMockServerPlayer(
            GameTestHelper helper, UUID playerId, String playerName) {
        var server = helper.getLevel().getServer();
        GameProfile profile = new GameProfile(playerId, playerName);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(
                server,
                helper.getLevel(),
                profile,
                cookie.clientInformation()) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return true;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(new ChannelHandler[] {connection});
        net.neoforged.neoforge.network.registration.NetworkRegistry
                .configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    private static int treasuryWithdrawalDeliveryCount(
            ServerPlayer player, UUID withdrawalId) {
        var civic = player.getPersistentData().getCompound("civiceconomy");
        var deliveries = civic.getList(
                "TreasuryWithdrawalCashDeliveries",
                net.minecraft.nbt.Tag.TAG_STRING);
        int count = 0;
        for (int index = 0; index < deliveries.size(); index++) {
            if (withdrawalId.toString().equals(deliveries.getString(index))) {
                count++;
            }
        }
        return count;
    }

    private static NationActivationRow nationActivationByRequest(
            Path databaseFile,
            String serviceIdentity,
            String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT activation.application_id,
                               activation.nation_id,
                               activation.ftb_team_id,
                               activation.treasury_account_id,
                               activation.capital_dimension_id,
                               activation.capital_chunk_x,
                               activation.capital_chunk_z,
                               activation.state,
                               application.state,
                               (SELECT COUNT(*)
                                  FROM nation_registry nation
                                 WHERE nation.nation_id = activation.nation_id),
                               (SELECT COUNT(*)
                                  FROM citizenship_period citizenship
                                 WHERE citizenship.nation_id = activation.nation_id
                                   AND citizenship.ended_at_epoch_millis IS NULL),
                               (SELECT COUNT(*)
                                  FROM nation_capital capital
                                 WHERE capital.nation_id = activation.nation_id)
                        FROM nation_application_activation activation
                        JOIN nation_application application
                          ON application.application_id = activation.application_id
                        WHERE activation.service_identity = ?
                          AND activation.request_id = ?
                        """)) {
            query.setString(1, serviceIdentity);
            query.setString(2, requestId);
            try (var result = query.executeQuery()) {
                return result.next()
                        ? new NationActivationRow(
                                UUID.fromString(result.getString(1)),
                                UUID.fromString(result.getString(2)),
                                UUID.fromString(result.getString(3)),
                                result.getString(4),
                                new Capital(
                                        result.getString(5),
                                        result.getInt(6),
                                        result.getInt(7)),
                                result.getString(8),
                                result.getString(9),
                                result.getInt(10),
                                result.getInt(11),
                                result.getInt(12))
                        : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Nation Activation restart state", failure);
        }
    }

    private static TreasuryWithdrawalRow treasuryWithdrawalByRequest(
            Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT withdrawal_id,
                               nation_id,
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
                                UUID.fromString(result.getString(1)),
                                result.getString(2),
                                result.getString(3),
                                result.getString(4),
                                result.getLong(5),
                                result.getString(6),
                                result.getString(7),
                                result.getString(8))
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
                               COUNT(vote.vote_id),
                               approval.policy_id
                        FROM treasury_withdrawal_approval_request approval
                        LEFT JOIN treasury_withdrawal_approval_vote vote
                          ON vote.approval_request_id = approval.approval_request_id
                        WHERE approval.service_identity = ?
                          AND approval.request_id = ?
                        GROUP BY approval.approval_request_id,
                                 approval.state,
                                 approval.required_approvals,
                                 approval.policy_id
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
                                result.getInt(4),
                                UUID.fromString(result.getString(5)))
                        : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Treasury Withdrawal approval", failure);
        }
    }

    private static Long withdrawalApprovalPolicyLifetime(
            Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT approval_lifetime_millis FROM withdrawal_approval_policy
                        WHERE service_identity = ? AND request_id = ?
                        """)) {
            query.setString(1, "civiceconomy-withdrawal-governance");
            query.setString(2, requestId);
            try (var result = query.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return result.getLong(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Withdrawal Approval Policy", failure);
        }
    }

    private static String withdrawalApprovalPolicyTiers(
            Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT tier.minimum_amount_minor_units,
                               tier.required_approvals
                        FROM withdrawal_approval_policy policy
                        JOIN withdrawal_approval_policy_tier tier
                          ON tier.policy_id = policy.policy_id
                        WHERE policy.service_identity = ? AND policy.request_id = ?
                        ORDER BY tier.minimum_amount_minor_units
                        """)) {
            query.setString(1, "civiceconomy-withdrawal-governance");
            query.setString(2, requestId);
            try (var result = query.executeQuery()) {
                var tiers = new java.util.ArrayList<String>();
                while (result.next()) {
                    tiers.add(result.getLong(1) + ":" + result.getInt(2));
                }
                return String.join(",", tiers);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Withdrawal Approval Policy tiers", failure);
        }
    }

    private static int withdrawalApprovalPolicyCount(
            Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT COUNT(*) FROM withdrawal_approval_policy
                        WHERE service_identity = ? AND request_id = ?
                        """)) {
            query.setString(1, "civiceconomy-withdrawal-governance");
            query.setString(2, requestId);
            try (var result = query.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Withdrawal Approval Policy replay", failure);
        }
    }

    private static long treasuryWithdrawalApprovalLifetime(
            Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT expiry.expires_at_epoch_millis
                                   - approval.initiated_at_epoch_millis
                        FROM treasury_withdrawal_approval_request approval
                        JOIN treasury_withdrawal_approval_expiry expiry
                          ON expiry.approval_request_id = approval.approval_request_id
                        WHERE approval.service_identity = ? AND approval.request_id = ?
                        """)) {
            query.setString(
                    1,
                    org.civiceconomy.fiscal.TreasuryWithdrawalFiscalServiceProvisioner
                            .SERVICE_IDENTITY
                            .value());
            query.setString(2, requestId);
            try (var result = query.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException(
                            "Unknown Treasury Withdrawal approval request " + requestId);
                }
                return result.getLong(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Treasury Withdrawal approval lifetime", failure);
        }
    }

    private static PermanentDestructionRow permanentDestructionByRequest(
            Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT operation_id,
                               source_account,
                               amount_minor_units,
                               operator_identity,
                               reason,
                               state,
                               external_applied_at_epoch_millis
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
                if (!result.next()) {
                    return null;
                }
                long externalAppliedAt = result.getLong(7);
                boolean externalAppliedAtMissing = result.wasNull();
                return new PermanentDestructionRow(
                        UUID.fromString(result.getString(1)),
                        result.getString(2),
                        result.getLong(3),
                        result.getString(4),
                        result.getString(5),
                        result.getString(6),
                        externalAppliedAtMissing ? null : externalAppliedAt);
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
                helper.assertValueEqual(87, version.getInt(1), "backup schema version");
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

    private static void assertGlobalReferencePriceScheduled(
            GameTestHelper helper,
            Path databaseFile,
            String requestId,
            long effectiveAtEpochMillis) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT actor_identity, item_id, component_fingerprint,
                               unit_price_minor_units,
                               effective_at_epoch_millis, reason
                        FROM global_reference_price
                        WHERE service_identity = 'civiceconomy-reference-price'
                          AND request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "persistent Global Reference Price");
                helper.assertTrue(
                        result.getString(1).startsWith("civic-admin-console:"),
                        "server-derived Global Reference Price administrator");
                helper.assertValueEqual(
                        "minecraft:iron_ingot", result.getString(2), "reference-price item");
                helper.assertValueEqual(
                        "components:{}", result.getString(3), "reference-price components");
                helper.assertValueEqual(25L, result.getLong(4), "reference-price minor units");
                helper.assertValueEqual(
                        effectiveAtEpochMillis,
                        result.getLong(5),
                        "reference-price effective time");
                helper.assertValueEqual(
                        "GameTest initial Global Reference Price",
                        result.getString(6),
                        "reference-price reason");
                helper.assertFalse(result.next(), "duplicate Global Reference Price");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Global Reference Price command result", failure);
        }
    }

    private static void assertGlobalReferencePriceAbsent(
            GameTestHelper helper, Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT COUNT(*) FROM global_reference_price
                        WHERE request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "unauthorized reference-price count");
                helper.assertValueEqual(
                        0L,
                        result.getLong(1),
                        "non-OP cannot schedule Global Reference Price");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect unauthorized Global Reference Price", failure);
        }
    }

    private static void assertProductionMarginalReturnPolicyScheduled(
            GameTestHelper helper,
            Path databaseFile,
            String requestId,
            long effectiveAtEpochMillis) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT actor_identity,
                               facility_soft_cap_minor_units,
                               facility_excess_weight_basis_points,
                               industry_soft_cap_minor_units,
                               industry_excess_weight_basis_points,
                               effective_at_epoch_millis, reason
                        FROM production_marginal_return_policy
                        WHERE service_identity = 'civiceconomy-production-policy'
                          AND request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(
                        result.next(), "persistent Production Marginal Return policy");
                helper.assertTrue(
                        result.getString(1).startsWith("civic-admin-console:"),
                        "server-derived Production Marginal Return administrator");
                helper.assertValueEqual(
                        100_000L, result.getLong(2), "facility marginal-return soft cap");
                helper.assertValueEqual(
                        5_000, result.getInt(3), "facility marginal-return excess weight");
                helper.assertValueEqual(
                        500_000L, result.getLong(4), "industry marginal-return soft cap");
                helper.assertValueEqual(
                        2_500, result.getInt(5), "industry marginal-return excess weight");
                helper.assertValueEqual(
                        effectiveAtEpochMillis,
                        result.getLong(6),
                        "Production Marginal Return policy effective time");
                helper.assertValueEqual(
                        "GameTest Production Marginal Return policy",
                        result.getString(7),
                        "Production Marginal Return policy reason");
                helper.assertFalse(
                        result.next(), "duplicate Production Marginal Return policy");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Production Marginal Return policy command result",
                    failure);
        }
    }

    private static void assertProductionMarginalReturnPolicyAbsent(
            GameTestHelper helper, Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT COUNT(*) FROM production_marginal_return_policy
                        WHERE request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "unauthorized marginal-return policy count");
                helper.assertValueEqual(
                        0L,
                        result.getLong(1),
                        "non-OP cannot schedule Production Marginal Return policy");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect unauthorized Production Marginal Return policy",
                    failure);
        }
    }

    private static void assertProductionStrengthPolicyScheduled(
            GameTestHelper helper,
            Path databaseFile,
            String requestId,
            long effectiveAtEpochMillis) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT actor_identity, observation_window_millis,
                               full_weight_window_millis,
                               full_strength_scale_minor_units,
                               effective_at_epoch_millis, reason
                        FROM production_strength_policy
                        WHERE service_identity = 'civiceconomy-production-strength-policy'
                          AND request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "persistent Production Strength policy");
                helper.assertTrue(
                        result.getString(1).startsWith("civic-admin-console:"),
                        "server-derived Production Strength administrator");
                helper.assertValueEqual(
                        2_592_000_000L, result.getLong(2), "production observation window");
                helper.assertValueEqual(
                        604_800_000L, result.getLong(3), "production full-weight window");
                helper.assertValueEqual(
                        100_000L, result.getLong(4), "production full-strength scale");
                helper.assertValueEqual(
                        effectiveAtEpochMillis,
                        result.getLong(5),
                        "Production Strength policy effective time");
                helper.assertValueEqual(
                        "GameTest Production Strength policy",
                        result.getString(6),
                        "Production Strength policy reason");
                helper.assertFalse(result.next(), "duplicate Production Strength policy");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Production Strength policy command result", failure);
        }
    }

    private static void assertProductionStrengthPolicyAbsent(
            GameTestHelper helper, Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT COUNT(*) FROM production_strength_policy
                        WHERE request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "unauthorized strength policy count");
                helper.assertValueEqual(
                        0L,
                        result.getLong(1),
                        "non-OP cannot schedule Production Strength policy");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect unauthorized Production Strength policy", failure);
        }
    }

    private static void assertEffectiveCitizenStrengthPolicyScheduled(
            GameTestHelper helper,
            Path databaseFile,
            String requestId,
            long effectiveAtEpochMillis) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT actor_identity,
                               full_strength_scale_citizen_equivalents,
                               effective_at_epoch_millis, reason
                        FROM effective_citizen_strength_policy
                        WHERE service_identity =
                                'civiceconomy-effective-citizen-strength-policy'
                          AND request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "persistent Effective Citizen Strength policy");
                helper.assertTrue(
                        result.getString(1).startsWith("civic-admin-console:"),
                        "server-derived Effective Citizen Strength administrator");
                helper.assertValueEqual(
                        10, result.getInt(2), "Effective Citizen full-strength scale");
                helper.assertValueEqual(
                        effectiveAtEpochMillis,
                        result.getLong(3),
                        "Effective Citizen Strength policy effective time");
                helper.assertValueEqual(
                        "GameTest Effective Citizen Strength policy",
                        result.getString(4),
                        "Effective Citizen Strength policy reason");
                helper.assertFalse(result.next(), "duplicate Effective Citizen Strength policy");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Effective Citizen Strength policy command result", failure);
        }
    }

    private static void assertEffectiveCitizenStrengthPolicyAbsent(
            GameTestHelper helper, Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT COUNT(*) FROM effective_citizen_strength_policy
                        WHERE request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "unauthorized citizen strength policy count");
                helper.assertValueEqual(
                        0L,
                        result.getLong(1),
                        "non-OP cannot schedule Effective Citizen Strength policy");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect unauthorized Effective Citizen Strength policy", failure);
        }
    }

    private static void assertEffectiveTerritoryStrengthPolicyScheduled(
            GameTestHelper helper,
            Path databaseFile,
            String requestId,
            long effectiveAtEpochMillis) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT actor_identity,
                               full_strength_scale_effective_claims,
                               effective_at_epoch_millis, reason
                        FROM effective_territory_strength_policy
                        WHERE service_identity =
                                'civiceconomy-effective-territory-strength-policy'
                          AND request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "persistent Effective Territory Strength policy");
                helper.assertTrue(
                        result.getString(1).startsWith("civic-admin-console:"),
                        "server-derived Effective Territory Strength administrator");
                helper.assertValueEqual(
                        4, result.getInt(2), "Effective Territory full-strength scale");
                helper.assertValueEqual(
                        effectiveAtEpochMillis,
                        result.getLong(3),
                        "Effective Territory Strength policy effective time");
                helper.assertValueEqual(
                        "GameTest Effective Territory Strength policy",
                        result.getString(4),
                        "Effective Territory Strength policy reason");
                helper.assertFalse(result.next(), "duplicate Effective Territory Strength policy");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Effective Territory Strength policy command result",
                    failure);
        }
    }

    private static void assertEffectiveTerritoryStrengthPolicyAbsent(
            GameTestHelper helper, Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT COUNT(*) FROM effective_territory_strength_policy
                        WHERE request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "unauthorized territory strength policy count");
                helper.assertValueEqual(
                        0L,
                        result.getLong(1),
                        "non-OP cannot schedule Effective Territory Strength policy");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect unauthorized Effective Territory Strength policy",
                    failure);
        }
    }

    private static void assertMintCompliancePolicyScheduled(
            GameTestHelper helper,
            Path databaseFile,
            String requestId,
            long effectiveAtEpochMillis) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT actor_identity, observation_window_millis,
                               recovered_commit_basis_points,
                               effective_at_epoch_millis, reason
                        FROM mint_compliance_policy
                        WHERE service_identity = 'civiceconomy-mint-compliance-policy'
                          AND request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "persistent Mint Compliance policy");
                helper.assertTrue(
                        result.getString(1).startsWith("civic-admin-console:"),
                        "server-derived Mint Compliance administrator");
                helper.assertValueEqual(
                        2_592_000_000L,
                        result.getLong(2),
                        "Mint Compliance observation window");
                helper.assertValueEqual(
                        5_000, result.getInt(3), "recovered Mint commit weight");
                helper.assertValueEqual(
                        effectiveAtEpochMillis,
                        result.getLong(4),
                        "Mint Compliance policy effective time");
                helper.assertValueEqual(
                        "GameTest Mint Compliance policy",
                        result.getString(5),
                        "Mint Compliance policy reason");
                helper.assertFalse(result.next(), "duplicate Mint Compliance policy");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Mint Compliance policy command result", failure);
        }
    }

    private static void assertMintCompliancePolicyAbsent(
            GameTestHelper helper, Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT COUNT(*) FROM mint_compliance_policy
                        WHERE request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "unauthorized Mint Compliance policy count");
                helper.assertValueEqual(
                        0L,
                        result.getLong(1),
                        "non-OP cannot schedule Mint Compliance policy");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect unauthorized Mint Compliance policy", failure);
        }
    }

    private static void assertAuditableEconomicActivityPolicyScheduled(
            GameTestHelper helper,
            Path databaseFile,
            String requestId,
            long effectiveAtEpochMillis) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT actor_identity, observation_window_millis,
                               full_strength_scale_minor_units,
                               effective_at_epoch_millis, reason
                        FROM auditable_economic_activity_policy
                        WHERE service_identity =
                                'civiceconomy-auditable-economic-activity-policy'
                          AND request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(
                        result.next(), "persistent Auditable Economic Activity policy");
                helper.assertTrue(
                        result.getString(1).startsWith("civic-admin-console:"),
                        "server-derived Auditable Economic Activity administrator");
                helper.assertValueEqual(
                        2_592_000_000L,
                        result.getLong(2),
                        "Auditable Economic Activity observation window");
                helper.assertValueEqual(
                        10_000L,
                        result.getLong(3),
                        "Auditable Economic Activity full-strength scale");
                helper.assertValueEqual(
                        effectiveAtEpochMillis,
                        result.getLong(4),
                        "Auditable Economic Activity policy effective time");
                helper.assertValueEqual(
                        "GameTest Auditable Economic Activity policy",
                        result.getString(5),
                        "Auditable Economic Activity policy reason");
                helper.assertFalse(
                        result.next(), "duplicate Auditable Economic Activity policy");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Auditable Economic Activity policy command result",
                    failure);
        }
    }

    private static void assertAuditableEconomicActivityPolicyAbsent(
            GameTestHelper helper, Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT COUNT(*) FROM auditable_economic_activity_policy
                        WHERE request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(
                        result.next(), "unauthorized Auditable Economic Activity policy count");
                helper.assertValueEqual(
                        0L,
                        result.getLong(1),
                        "non-OP cannot schedule Auditable Economic Activity policy");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect unauthorized Auditable Economic Activity policy",
                    failure);
        }
    }

    private static void assertRegisteredFacilityScopePolicyScheduled(
            GameTestHelper helper,
            Path databaseFile,
            String requestId,
            long effectiveAtEpochMillis) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT actor_identity, max_scope_chunks,
                               effective_at_epoch_millis, reason
                        FROM registered_facility_scope_policy
                        WHERE service_identity =
                                'civiceconomy-registered-facility-scope-policy'
                          AND request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "persistent Facility Scope policy");
                helper.assertTrue(
                        result.getString(1).startsWith("civic-admin-console:"),
                        "server-derived Facility Scope administrator");
                helper.assertValueEqual(16, result.getInt(2), "Facility maximum scope");
                helper.assertValueEqual(
                        effectiveAtEpochMillis,
                        result.getLong(3),
                        "Facility Scope policy effective time");
                helper.assertValueEqual(
                        "GameTest Registered Facility Scope policy",
                        result.getString(4),
                        "Facility Scope policy reason");
                helper.assertFalse(result.next(), "duplicate Facility Scope policy");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Registered Facility Scope policy", failure);
        }
    }

    private static void assertRegisteredFacilityScopePolicyAbsent(
            GameTestHelper helper, Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT COUNT(*) FROM registered_facility_scope_policy
                        WHERE request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "unauthorized Facility Scope policy count");
                helper.assertValueEqual(
                        0L, result.getLong(1), "non-OP cannot schedule Facility Scope policy");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect unauthorized Facility Scope policy", failure);
        }
    }

    private static void assertProductionIndustryAssignmentScheduled(
            GameTestHelper helper,
            Path databaseFile,
            String requestId,
            long effectiveAtEpochMillis) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT actor_identity, create_version, recipe_id, industry_id,
                               effective_at_epoch_millis, reason
                        FROM production_industry_assignment
                        WHERE service_identity = 'civiceconomy-production-industry'
                          AND request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "persistent Production Industry assignment");
                helper.assertTrue(
                        result.getString(1).startsWith("civic-admin-console:"),
                        "server-derived Production Industry administrator");
                helper.assertValueEqual("6.0.6", result.getString(2), "exact Create version");
                helper.assertValueEqual(
                        "create:milling/wheat", result.getString(3), "exact recipe identity");
                helper.assertValueEqual(
                        "food-processing", result.getString(4), "trusted Production Industry");
                helper.assertValueEqual(
                        effectiveAtEpochMillis,
                        result.getLong(5),
                        "Production Industry assignment effective time");
                helper.assertValueEqual(
                        "GameTest Production Industry assignment",
                        result.getString(6),
                        "Production Industry assignment reason");
                helper.assertFalse(result.next(), "duplicate Production Industry assignment");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect Production Industry assignment command result",
                    failure);
        }
    }

    private static void assertProductionIndustryAssignmentAbsent(
            GameTestHelper helper, Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var query = connection.prepareStatement("""
                        SELECT COUNT(*) FROM production_industry_assignment
                        WHERE request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "unauthorized industry assignment count");
                helper.assertValueEqual(
                        0L,
                        result.getLong(1),
                        "non-OP cannot schedule Production Industry assignment");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect unauthorized Production Industry assignment",
                    failure);
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

    private static void assertEscrowAutomaticallyExpired(
            GameTestHelper helper, Path databaseFile, UUID escrowId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
                var query = connection.prepareStatement("""
                        SELECT escrow.state, reservation.state,
                               expiry.service_identity, expiry.request_id,
                               COUNT(*) OVER ()
                        FROM fiscal_escrow escrow
                        JOIN fiscal_reservation reservation
                          ON reservation.reservation_id = escrow.reservation_id
                        JOIN escrow_expiry expiry
                          ON expiry.escrow_id = escrow.escrow_id
                        WHERE escrow.escrow_id = ?
                        """)) {
            query.setString(1, escrowId.toString());
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "automatic Escrow expiry audit");
                helper.assertValueEqual("EXPIRED", result.getString(1), "Escrow state");
                helper.assertValueEqual("RELEASED", result.getString(2), "Reservation state");
                helper.assertValueEqual(
                        "civiceconomy-server", result.getString(3), "expiry service identity");
                helper.assertValueEqual(
                        "automatic-expiry:" + escrowId,
                        result.getString(4),
                        "expiry request identity");
                helper.assertValueEqual(1, result.getInt(5), "single expiry audit");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect automatic Escrow expiry", failure);
        }
    }

    private static void assertBudgetDraftAutomaticallyExpired(
            GameTestHelper helper, Path databaseFile, UUID budgetId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
                var query = connection.prepareStatement("""
                        SELECT budget.state, budget.escrow_id,
                               expiry.service_identity, expiry.request_id
                        FROM fiscal_budget budget
                        JOIN budget_draft_expiry expiry
                          ON expiry.budget_id = budget.budget_id
                        WHERE budget.budget_id = ?
                        """)) {
            query.setString(1, budgetId.toString());
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "automatic Budget draft expiry audit");
                helper.assertValueEqual("EXPIRED", result.getString(1), "Budget state");
                helper.assertTrue(result.getString(2) == null, "expired draft has no Escrow");
                helper.assertValueEqual(
                        "civiceconomy-server", result.getString(3), "expiry service identity");
                helper.assertValueEqual(
                        "automatic-expiry:" + budgetId,
                        result.getString(4),
                        "expiry request identity");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect automatic Budget draft expiry", failure);
        }
    }

    private static void assertFiscalBillAutomaticallyExpired(
            GameTestHelper helper, Path databaseFile, UUID billId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
                var query = connection.prepareStatement("""
                        SELECT bill.state, bill.escrow_id,
                               expiry.service_identity, expiry.request_id
                        FROM fiscal_bill bill
                        JOIN fiscal_bill_expiry expiry
                          ON expiry.bill_id = bill.bill_id
                        WHERE bill.bill_id = ?
                        """)) {
            query.setString(1, billId.toString());
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "automatic Fiscal Bill expiry audit");
                helper.assertValueEqual("EXPIRED", result.getString(1), "Fiscal Bill state");
                helper.assertTrue(result.getString(2) == null, "expired Bill has no Escrow");
                helper.assertValueEqual(
                        "civiceconomy-server", result.getString(3), "expiry service identity");
                helper.assertValueEqual(
                        "automatic-expiry:" + billId,
                        result.getString(4),
                        "expiry request identity");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect automatic Fiscal Bill expiry", failure);
        }
    }

    private static String fiscalBillIdByRequest(Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
                var query = connection.prepareStatement("""
                        SELECT bill_id FROM fiscal_bill WHERE request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect Fiscal Bill request", failure);
        }
    }

    private static void assertFundedFiscalBill(
            GameTestHelper helper,
            Path databaseFile,
            UUID billId,
            UUID payerId,
            String fundingRequestId,
            String beneficiaryAccount,
            long amountMinorUnits) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
                var query = connection.prepareStatement("""
                        SELECT b.state, b.funding_service_identity, b.funding_request_id,
                               b.beneficiary_account, e.required_recipient_account,
                               e.state, r.source_account, r.amount_minor_units, r.state,
                               (SELECT COUNT(*) FROM fiscal_reservation
                                WHERE service_identity = ? AND request_id = ?),
                               (SELECT COUNT(*) FROM fiscal_escrow
                                WHERE service_identity = ? AND request_id = ?)
                        FROM fiscal_bill b
                        JOIN fiscal_escrow e ON e.escrow_id = b.escrow_id
                        JOIN fiscal_reservation r ON r.reservation_id = e.reservation_id
                        WHERE b.bill_id = ?
                        """)) {
            String serviceIdentity = "civiceconomy-fiscal-bill-funding";
            query.setString(1, serviceIdentity);
            query.setString(2, fundingRequestId);
            query.setString(3, serviceIdentity);
            query.setString(4, fundingRequestId);
            query.setString(5, billId.toString());
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "funded Fiscal Bill row");
                helper.assertValueEqual("RESERVED", result.getString(1), "funded Bill state");
                helper.assertValueEqual(
                        serviceIdentity, result.getString(2), "funding service identity");
                helper.assertValueEqual(
                        fundingRequestId, result.getString(3), "funding request ID");
                helper.assertValueEqual(
                        beneficiaryAccount, result.getString(4), "persisted beneficiary");
                helper.assertValueEqual(
                        beneficiaryAccount, result.getString(5), "Escrow required recipient");
                helper.assertValueEqual("RESERVED", result.getString(6), "Escrow state");
                helper.assertValueEqual(
                        "player:" + payerId, result.getString(7), "Reservation payer");
                helper.assertValueEqual(
                        amountMinorUnits, result.getLong(8), "Reservation amount");
                helper.assertValueEqual("ACTIVE", result.getString(9), "Reservation state");
                helper.assertValueEqual(1, result.getInt(10), "one funding Reservation");
                helper.assertValueEqual(1, result.getInt(11), "one funding Escrow");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect funded Fiscal Bill", failure);
        }
    }

    private static void assertCancelledFiscalBill(
            GameTestHelper helper,
            Path databaseFile,
            UUID billId,
            String cancellationRequestId,
            String reason) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
                var query = connection.prepareStatement("""
                        SELECT b.state, e.state, r.state, r.settled_minor_units,
                               rr.reason,
                               (SELECT COUNT(*) FROM reservation_release
                                WHERE service_identity = ? AND request_id = ?),
                               (SELECT COUNT(*) FROM payment_transaction
                                WHERE reservation_id = r.reservation_id)
                        FROM fiscal_bill b
                        JOIN fiscal_escrow e ON e.escrow_id = b.escrow_id
                        JOIN fiscal_reservation r ON r.reservation_id = e.reservation_id
                        JOIN reservation_release rr
                          ON rr.reservation_id = r.reservation_id
                        WHERE b.bill_id = ?
                          AND rr.service_identity = ?
                          AND rr.request_id = ?
                        """)) {
            String serviceIdentity = "civiceconomy-fiscal-bill-cancellation";
            query.setString(1, serviceIdentity);
            query.setString(2, cancellationRequestId);
            query.setString(3, billId.toString());
            query.setString(4, serviceIdentity);
            query.setString(5, cancellationRequestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "cancelled Fiscal Bill row");
                helper.assertValueEqual("CANCELLED", result.getString(1), "cancelled Bill state");
                helper.assertValueEqual("RELEASED", result.getString(2), "cancelled Escrow state");
                helper.assertValueEqual(
                        "RELEASED", result.getString(3), "cancelled Reservation state");
                helper.assertValueEqual(0L, result.getLong(4), "cancelled settled amount");
                helper.assertValueEqual(reason, result.getString(5), "cancellation reason");
                helper.assertValueEqual(1, result.getInt(6), "one cancellation release");
                helper.assertValueEqual(0, result.getInt(7), "cancellation creates no payment");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect cancelled Fiscal Bill", failure);
        }
    }

    private static void assertPaidFiscalBill(
            GameTestHelper helper,
            Path databaseFile,
            UUID billId,
            UUID payerId,
            String paymentRequestId,
            String beneficiaryAccount) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
                var query = connection.prepareStatement("""
                        SELECT b.state, e.state, r.state, r.settled_minor_units,
                               p.state, p.source_account, p.recipient_account,
                               p.amount_minor_units,
                               (SELECT COUNT(*) FROM payment_transaction
                                WHERE service_identity = ? AND request_id = ?),
                               (SELECT COUNT(*) FROM fiscal_ledger_entry
                                WHERE transaction_id = p.transaction_id)
                        FROM fiscal_bill b
                        JOIN fiscal_escrow e ON e.escrow_id = b.escrow_id
                        JOIN fiscal_reservation r ON r.reservation_id = e.reservation_id
                        JOIN payment_transaction p ON p.reservation_id = r.reservation_id
                        WHERE b.bill_id = ?
                          AND p.service_identity = ?
                          AND p.request_id = ?
                        """)) {
            String serviceIdentity = "civiceconomy-fiscal-bill-payment";
            query.setString(1, serviceIdentity);
            query.setString(2, paymentRequestId);
            query.setString(3, billId.toString());
            query.setString(4, serviceIdentity);
            query.setString(5, paymentRequestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "paid Fiscal Bill row");
                helper.assertValueEqual("PAID", result.getString(1), "paid Bill state");
                helper.assertValueEqual("SETTLED", result.getString(2), "paid Escrow state");
                helper.assertValueEqual("SETTLED", result.getString(3), "paid Reservation state");
                helper.assertValueEqual(300L, result.getLong(4), "settled Reservation amount");
                helper.assertValueEqual(
                        "CIVIC_COMMITTED", result.getString(5), "payment transaction state");
                helper.assertValueEqual(
                        "player:" + payerId, result.getString(6), "payment payer account");
                helper.assertValueEqual(
                        beneficiaryAccount, result.getString(7), "payment beneficiary account");
                helper.assertValueEqual(300L, result.getLong(8), "payment amount");
                helper.assertValueEqual(1, result.getInt(9), "one payment transaction");
                helper.assertValueEqual(2, result.getInt(10), "paired payment ledger entries");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect paid Fiscal Bill", failure);
        }
    }

    private static void seedPlayerBank(UUID playerId, long amountMinorUnits) {
        BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
        var account = bankData.getAccount(playerId);
        account.getMoneyStorage().clear();
        if (!BankAPI.getApi().BankDepositFromServer(
                account, CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, amountMinorUnits))) {
            throw new IllegalStateException("Unable to seed Fiscal Bill payer LC account");
        }
    }

    private static void clearPlayerBank(UUID playerId) {
        CustomSaveData.getData(BankDataCache.TYPE)
                .getAccount(playerId)
                .getMoneyStorage()
                .clear();
    }

    private static long playerBankBalance(UUID playerId) {
        BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
        var unit = CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, 1L);
        return bankData.getAccount(playerId)
                .getMoneyStorage()
                .valueOf(unit.getUniqueName())
                .getCoreValue();
    }

    private static void assertIssuedFiscalBill(
            GameTestHelper helper,
            Path databaseFile,
            String requestId,
            org.civiceconomy.nation.NationId nationId,
            UUID payerId,
            long dueAtEpochMillis) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
                var query = connection.prepareStatement("""
                        SELECT service_identity, payer_account, beneficiary_account,
                               amount_minor_units, kind, purpose, due_at_epoch_millis,
                               state, escrow_id
                        FROM fiscal_bill
                        WHERE request_id = ?
                        """)) {
            query.setString(1, requestId);
            try (var result = query.executeQuery()) {
                helper.assertTrue(result.next(), "issued Fiscal Bill row");
                helper.assertValueEqual(
                        "civiceconomy-fiscal-bill",
                        result.getString(1),
                        "internal Fiscal Bill service identity");
                helper.assertValueEqual(
                        "player:" + payerId,
                        result.getString(2),
                        "payer derived from target player UUID");
                helper.assertValueEqual(
                        "nation:" + nationId.value() + ":treasury",
                        result.getString(3),
                        "beneficiary derived from issuer Nation");
                helper.assertValueEqual(300L, result.getLong(4), "Fiscal Bill amount");
                helper.assertValueEqual("FEE", result.getString(5), "Fiscal Bill kind");
                helper.assertValueEqual(
                        "GameTest building permit fee",
                        result.getString(6),
                        "Fiscal Bill purpose");
                helper.assertValueEqual(
                        dueAtEpochMillis, result.getLong(7), "Fiscal Bill due time");
                helper.assertValueEqual("ISSUED", result.getString(8), "Fiscal Bill state");
                helper.assertTrue(result.getString(9) == null, "issued Bill has no Escrow");
                helper.assertTrue(!result.next(), "request replay creates one Fiscal Bill");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect issued Fiscal Bill", failure);
        }
    }

    private static BudgetRow budgetByRequest(Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
                var query = connection.prepareStatement("""
                        SELECT budget.budget_id, budget.source_account,
                               budget.amount_minor_units, budget.budget_code, budget.purpose,
                               budget.expires_at_epoch_millis, budget.state, budget.escrow_id,
                               COALESCE(reservation.settled_minor_units, 0)
                        FROM fiscal_budget budget
                        LEFT JOIN fiscal_escrow escrow ON escrow.escrow_id = budget.escrow_id
                        LEFT JOIN fiscal_reservation reservation
                          ON reservation.reservation_id = escrow.reservation_id
                        WHERE budget.service_identity = ? AND budget.request_id = ?
                        """)) {
            query.setString(1, "civiceconomy-budget");
            query.setString(2, requestId);
            try (var result = query.executeQuery()) {
                return result.next()
                        ? new BudgetRow(
                                UUID.fromString(result.getString(1)),
                                result.getString(2),
                                result.getLong(3),
                                result.getString(4),
                                result.getString(5),
                                result.getLong(6),
                                result.getString(7),
                                result.getString(8),
                                result.getLong(9))
                        : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect Budget draft", failure);
        }
    }

    private static boolean fiscalServiceExists(Path databaseFile, String serviceIdentity) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
                var query = connection.prepareStatement("""
                        SELECT 1 FROM fiscal_service WHERE service_identity = ?
                        """)) {
            query.setString(1, serviceIdentity);
            try (var result = query.executeQuery()) {
                return result.next();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect Fiscal Service", failure);
        }
    }

    private static BudgetApprovalRow budgetApprovalByBudget(
            Path databaseFile, UUID budgetId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
                var query = connection.prepareStatement("""
                        SELECT actor_player_id, reason
                        FROM budget_approval_audit
                        WHERE budget_id = ?
                        """)) {
            query.setString(1, budgetId.toString());
            try (var result = query.executeQuery()) {
                return result.next()
                        ? new BudgetApprovalRow(
                                UUID.fromString(result.getString(1)), result.getString(2))
                        : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect Budget approval", failure);
        }
    }

    private static BudgetDisbursementRow budgetDisbursementByRequest(
            Path databaseFile, String requestId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
                var query = connection.prepareStatement("""
                        SELECT approval.approval_request_id, approval.budget_id,
                                approval.recipient_account, approval.amount_minor_units,
                                CASE WHEN cancellation.approval_request_id IS NULL
                                     THEN approval.state ELSE 'CANCELLED' END,
                                payment.transaction_id, payment.state,
                                (SELECT COUNT(*) FROM payment_transaction counted
                                 WHERE counted.service_identity = approval.service_identity
                                   AND counted.request_id = approval.request_id)
                        FROM budget_disbursement_approval_request approval
                        LEFT JOIN payment_transaction payment
                          ON payment.service_identity = approval.service_identity
                         AND payment.request_id = approval.request_id
                        LEFT JOIN budget_disbursement_approval_cancellation cancellation
                          ON cancellation.approval_request_id =
                             approval.approval_request_id
                        WHERE approval.service_identity = ? AND approval.request_id = ?
                        """)) {
            query.setString(1, "civiceconomy-budget-disbursement");
            query.setString(2, requestId);
            try (var result = query.executeQuery()) {
                return result.next()
                        ? new BudgetDisbursementRow(
                                UUID.fromString(result.getString(1)),
                                UUID.fromString(result.getString(2)),
                                result.getString(3),
                                result.getLong(4),
                                result.getString(5),
                                result.getString(6) == null
                                        ? null
                                        : UUID.fromString(result.getString(6)),
                                result.getString(7),
                                result.getInt(8))
                        : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect Budget Disbursement", failure);
        }
    }

    private static void assertRestartedBudgetDisbursementAccounting(
            GameTestHelper helper,
            Path databaseFile,
            BudgetDisbursementProcessRestartDrill.Marker marker) {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile);
                var query = connection.prepareStatement("""
                        SELECT budget.state, escrow.state, reservation.state,
                               reservation.settled_minor_units,
                               payment.state, payment.transaction_id,
                               (SELECT COUNT(*) FROM fiscal_ledger_entry ledger
                                WHERE ledger.transaction_id = payment.transaction_id),
                               (SELECT COUNT(*) FROM payment_recovery_audit audit
                                WHERE audit.transaction_id = payment.transaction_id
                                  AND audit.action = 'RECOVERY_EXTERNAL_APPLIED'),
                               (SELECT COUNT(*) FROM payment_recovery_audit audit
                                WHERE audit.transaction_id = payment.transaction_id
                                  AND audit.action = 'RECOVERY_CIVIC_COMMITTED')
                        FROM budget_disbursement_approval_request approval
                        JOIN fiscal_budget budget ON budget.budget_id = approval.budget_id
                        JOIN fiscal_escrow escrow ON escrow.escrow_id = budget.escrow_id
                        JOIN fiscal_reservation reservation
                          ON reservation.reservation_id = escrow.reservation_id
                        JOIN payment_transaction payment
                          ON payment.service_identity = approval.service_identity
                         AND payment.request_id = approval.request_id
                        WHERE approval.service_identity = ? AND approval.request_id = ?
                        """)) {
            query.setString(1, "civiceconomy-budget-disbursement");
            query.setString(2, marker.requestId());
            try (var result = query.executeQuery()) {
                helper.assertTrue(
                        result.next(),
                        "restarted Budget Disbursement accounting row");
                helper.assertValueEqual(
                        "SPENT", result.getString(1), "restarted Budget state");
                helper.assertValueEqual(
                        "SETTLED", result.getString(2), "restarted Escrow state");
                helper.assertValueEqual(
                        "SETTLED", result.getString(3), "restarted Reservation state");
                helper.assertValueEqual(
                        marker.amountMinorUnits(),
                        result.getLong(4),
                        "restarted settled Reservation amount");
                helper.assertValueEqual(
                        "CIVIC_COMMITTED",
                        result.getString(5),
                        "restarted Payment state");
                helper.assertValueEqual(
                        marker.transactionId().toString(),
                        result.getString(6),
                        "restarted persisted transaction UUID");
                helper.assertValueEqual(
                        2, result.getInt(7), "restarted paired ledger entries");
                helper.assertValueEqual(
                        1,
                        result.getInt(8),
                        "one recovered-external audit entry");
                helper.assertValueEqual(
                        1,
                        result.getInt(9),
                        "one recovered-Civic-commit audit entry");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "Unable to inspect restarted Budget Disbursement accounting",
                    failure);
        }
    }

    private static BudgetCancellationRow budgetCancellationByBudget(
            Path databaseFile, UUID budgetId) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
                var query = connection.prepareStatement("""
                        SELECT cancellation.actor_player_id, cancellation.reason,
                               escrow.state, reservation.state,
                               (SELECT COUNT(*) FROM budget_cancellation_audit counted
                                WHERE counted.budget_id = budget.budget_id),
                               (SELECT COUNT(*) FROM reservation_release release
                                WHERE release.reservation_id = reservation.reservation_id),
                               reservation.amount_minor_units,
                               reservation.settled_minor_units
                        FROM fiscal_budget budget
                        JOIN budget_cancellation_audit cancellation
                          ON cancellation.budget_id = budget.budget_id
                        JOIN fiscal_escrow escrow ON escrow.escrow_id = budget.escrow_id
                        JOIN fiscal_reservation reservation
                          ON reservation.reservation_id = escrow.reservation_id
                        WHERE budget.budget_id = ?
                        """)) {
            query.setString(1, budgetId.toString());
            try (var result = query.executeQuery()) {
                return result.next()
                        ? new BudgetCancellationRow(
                                UUID.fromString(result.getString(1)),
                                result.getString(2),
                                result.getString(3),
                                result.getString(4),
                                result.getInt(5),
                                result.getInt(6),
                                result.getLong(7),
                                result.getLong(8))
                        : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect Budget cancellation", failure);
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
            TeamManagerImpl manager = (TeamManagerImpl) FTBTeamsAPI.api().getManager();
            var method = TeamManagerImpl.class.getDeclaredMethod(
                    "createPartyTeamInternal", UUID.class, ServerPlayer.class, String.class);
            method.setAccessible(true);
            Team team = (Team) method.invoke(
                    manager,
                    player.getUUID(),
                    null,
                    "Civic Founding " + UUID.randomUUID());
            ((AbstractTeamBase) team).addMember(player.getUUID(), TeamRank.OWNER);
            manager.getPlayerTeamForPlayerID(player.getUUID())
                    .filter(PlayerTeam.class::isInstance)
                    .map(PlayerTeam.class::cast)
                    .ifPresent(personalTeam -> {
                        personalTeam.setEffectiveTeam((AbstractTeam) team);
                        personalTeam.markDirty();
                    });
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

    private record NationActivationRow(
            UUID applicationId,
            UUID nationId,
            UUID ftbTeamId,
            String treasuryAccount,
            Capital capital,
            String activationState,
            String applicationState,
            int nationCount,
            int citizenshipCount,
            int capitalCount) {}

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
            UUID operationId,
            String sourceAccount,
            long amountMinorUnits,
            String operatorIdentity,
            String reason,
            String state,
            Long externalAppliedAtEpochMillis) {}

    private record PermanentDestructionRestartPreparation(
            org.civiceconomy.nation.NationId nationId,
            long issuanceBeforeMinorUnits) {}

    private record TreasuryWithdrawalRow(
            UUID withdrawalId,
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
            int approvalCount,
            UUID policyId) {}

    private record BudgetRow(
            UUID budgetId,
            String sourceAccount,
            long amountMinorUnits,
            String budgetCode,
            String purpose,
            long expiresAt,
            String state,
            String escrowId,
            long settledMinorUnits) {}

    private record BudgetApprovalRow(UUID actorPlayerId, String reason) {}

    private record BudgetDisbursementRow(
            UUID approvalRequestId,
            UUID budgetId,
            String recipientAccount,
            long amountMinorUnits,
            String approvalState,
            UUID transactionId,
            String paymentState,
            int paymentCount) {}

    private record BudgetDisbursementRestartPreparation(
            org.civiceconomy.nation.NationId nationId,
            UUID recipientPlayerId) {}

    private record BudgetCancellationRow(
            UUID actorPlayerId,
            String reason,
            String escrowState,
            String reservationState,
            int auditCount,
            int releaseCount,
            long reservationAmount,
            long settledAmount) {}
}
