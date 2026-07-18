package org.civiceconomy.platform.neoforge;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.civiceconomy.CivicEconomy;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.Budget;
import org.civiceconomy.fiscal.BudgetDraftExpiryProcessor;
import org.civiceconomy.fiscal.BudgetFiscalServiceProvisioner;
import org.civiceconomy.fiscal.BudgetDisbursementApproval;
import org.civiceconomy.fiscal.BudgetDisbursementApprovalOutcome;
import org.civiceconomy.fiscal.BudgetDisbursementApprovalStatus;
import org.civiceconomy.fiscal.BudgetDisbursementRecoveryInspection;
import org.civiceconomy.fiscal.BudgetDisbursementRecoveryStatus;
import org.civiceconomy.fiscal.BudgetDisbursementInspection;
import org.civiceconomy.fiscal.BudgetDisbursementApprovalPolicyRegistry;
import org.civiceconomy.fiscal.BudgetDisbursementApprovalPolicyVersion;
import org.civiceconomy.fiscal.BudgetDisbursementApprovalRegistry;
import org.civiceconomy.fiscal.BudgetDisbursementApprovalTier;
import org.civiceconomy.fiscal.BudgetDisbursementPaymentCoordinator;
import org.civiceconomy.fiscal.PreparedBudgetDisbursementPayment;
import org.civiceconomy.fiscal.ScheduleBudgetDisbursementApprovalPolicy;
import org.civiceconomy.fiscal.CreateBudget;
import org.civiceconomy.fiscal.EscrowExpiryProcessor;
import org.civiceconomy.fiscal.FiscalBillExpiryProcessor;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalBill;
import org.civiceconomy.fiscal.FiscalBillCancellationCoordinator;
import org.civiceconomy.fiscal.FiscalBillFiscalServiceProvisioner;
import org.civiceconomy.fiscal.FiscalBillFundingCoordinator;
import org.civiceconomy.fiscal.FiscalBillInspection;
import org.civiceconomy.fiscal.FiscalBillKind;
import org.civiceconomy.fiscal.FiscalBillPaymentCoordinator;
import org.civiceconomy.fiscal.FiscalLedger;
import org.civiceconomy.fiscal.IssueFiscalBill;
import org.civiceconomy.fiscal.NationBudgetInspection;
import org.civiceconomy.fiscal.NationBudgetApprovalCoordinator;
import org.civiceconomy.fiscal.NationBudgetCancellationCoordinator;
import org.civiceconomy.fiscal.NationBudgetDisbursementApprovalCoordinator;
import org.civiceconomy.fiscal.NationFiscalBillInspection;
import org.civiceconomy.fiscal.PaymentCoordinator;
import org.civiceconomy.fiscal.PreparedFiscalBillPayment;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyPayments;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyMintIssuances;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyAccountBalances;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyTerritoryClearingAccountProvisioner;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyPublicMaintenanceFundProvisioner;
import org.civiceconomy.integration.lightmanscurrency.PermanentDestructionCoordinator;
import org.civiceconomy.integration.lightmanscurrency.TreasuryWithdrawalCoordinator;
import org.civiceconomy.fiscal.ConfirmTreasuryWithdrawal;
import org.civiceconomy.fiscal.ApproveTreasuryWithdrawal;
import org.civiceconomy.fiscal.ScheduleWithdrawalApprovalPolicy;
import org.civiceconomy.fiscal.TreasuryWithdrawal;
import org.civiceconomy.fiscal.TreasuryWithdrawalApproval;
import org.civiceconomy.fiscal.TreasuryWithdrawalApprovalOutcome;
import org.civiceconomy.fiscal.TreasuryWithdrawalApprovalRegistry;
import org.civiceconomy.fiscal.TreasuryWithdrawalApprovalStatus;
import org.civiceconomy.fiscal.TreasuryWithdrawalFiscalServiceProvisioner;
import org.civiceconomy.fiscal.TreasuryWithdrawalInspection;
import org.civiceconomy.fiscal.TreasuryWithdrawalRecoveryInspection;
import org.civiceconomy.fiscal.TreasuryWithdrawalRecoveryStatus;
import org.civiceconomy.fiscal.WithdrawalApprovalPolicyRegistry;
import org.civiceconomy.fiscal.WithdrawalApprovalPolicyVersion;
import org.civiceconomy.fiscal.WithdrawalApprovalTier;
import org.civiceconomy.integration.lightmanscurrency.TerritoryMaintenancePaymentCoordinator;
import org.civiceconomy.nation.OnlineSessionAccumulator;
import org.civiceconomy.nation.RecordOnlineTime;
import org.civiceconomy.nation.NationApplicationExpiryProcessor;
import org.civiceconomy.integration.ftb.FtbNationTeamDirectory;
import org.civiceconomy.integration.ftb.FtbChunksAdapter;
import org.civiceconomy.nation.Capital;
import org.civiceconomy.nation.CitizenshipReconciler;
import org.civiceconomy.nation.CitizenshipReconciliationResult;
import org.civiceconomy.nation.NationRegistry;
import org.civiceconomy.nation.NationTeam;
import org.civiceconomy.nation.NationTeamDirectory;
import org.civiceconomy.nation.RegisteredNation;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.civiceconomy.persistence.StoredDatabaseBackupOperation;
import org.civiceconomy.persistence.StoredDatabaseRestoreOperation;
import org.civiceconomy.production.EffectiveTerritoryFacilityAuthority;
import org.civiceconomy.production.CreateMachineKind;
import org.civiceconomy.production.CreateRecipeCompletion;
import org.civiceconomy.production.FacilityAdministration;
import org.civiceconomy.production.FacilityAccountingBaseline;
import org.civiceconomy.production.FacilityAccountingBaselineSnapshot;
import org.civiceconomy.production.FacilityAccountingStatus;
import org.civiceconomy.production.FacilityAccountingReceipt;
import org.civiceconomy.production.FacilityAccountingInterfaceRegistry;
import org.civiceconomy.production.FacilityAccountingInterface;
import org.civiceconomy.production.FacilityAccountingInterfacePosition;
import org.civiceconomy.production.FacilityBaselineAdministration;
import org.civiceconomy.production.FacilityBaselineActivationReplay;
import org.civiceconomy.production.FacilityBaselineActivationWork;
import org.civiceconomy.production.FacilityBaselineCaptureReplay;
import org.civiceconomy.production.FacilityBaselineCaptureWork;
import org.civiceconomy.production.FacilityCorePosition;
import org.civiceconomy.production.FacilityProductionMatcher;
import org.civiceconomy.production.FacilityProductionObservation;
import org.civiceconomy.production.FacilityProductionObservationRegistry;
import org.civiceconomy.production.GlobalReferencePriceRegistry;
import org.civiceconomy.production.ProductionIndustryAssignmentRegistry;
import org.civiceconomy.production.ProductionInventoryAgeLedger;
import org.civiceconomy.production.ProductionInventoryExport;
import org.civiceconomy.production.ProductionInventoryExportCoordinator;
import org.civiceconomy.production.ProductionInventoryExportKind;
import org.civiceconomy.production.ProductionInventoryExportRequest;
import org.civiceconomy.production.ProductionInventoryExportHandoff;
import org.civiceconomy.production.ProductionInventoryExportHandoffRegistry;
import org.civiceconomy.production.ProductionMarginalReturnContributionRegistry;
import org.civiceconomy.production.ProductionMarginalReturnPolicyRegistry;
import org.civiceconomy.production.ProductionValueAddedCalculator;
import org.civiceconomy.production.RecordProductionInventoryExportHandoff;
import org.civiceconomy.production.ProductionStack;
import org.civiceconomy.production.RegisteredFacility;
import org.civiceconomy.production.RegisteredFacilityRegistry;
import org.civiceconomy.production.RegisteredFacilityTerritoryReconciler;
import org.civiceconomy.territory.CommittedTerritoryPrepaymentVerifier;
import org.civiceconomy.territory.TerritoryClaimPermitCompensationCoordinator;
import org.civiceconomy.territory.ConsumeTerritoryClaimPermit;
import org.civiceconomy.territory.TerritoryClaimPermitEventBridge;
import org.civiceconomy.territory.TerritoryClaimPermitMirror;
import org.civiceconomy.territory.FreeClaimAuthorization;
import org.civiceconomy.territory.FreeClaimAuthorizationMirror;
import org.civiceconomy.territory.TerritoryClaimTarget;
import org.civiceconomy.territory.TerritoryClaimPermitRegistry;
import org.civiceconomy.territory.TerritoryFiscalServiceProvisioner;
import org.civiceconomy.territory.TerritoryForceLoadEnforcement;
import org.civiceconomy.territory.TerritoryForceLoadEnforcementRegistry;
import org.civiceconomy.territory.TerritoryForceLoadEnforcementState;
import org.civiceconomy.territory.TerritoryForceLoadRestrictionMirror;
import org.civiceconomy.territory.TerritoryForceLoadRestrictionRegistry;
import org.civiceconomy.territory.PrepareTerritoryClaimPrepayment;
import org.civiceconomy.territory.CancelTerritoryClaimPermit;
import org.civiceconomy.territory.TerritoryClaimPermit;
import org.civiceconomy.territory.TerritoryClaimPrepaymentCoordinator;
import org.civiceconomy.territory.TerritoryExpansionPricingPolicyRegistry;
import org.civiceconomy.territory.TerritoryExpansionPricingPolicyVersion;
import org.civiceconomy.territory.TerritoryFreeAllocationPolicyRegistry;
import org.civiceconomy.territory.TerritoryFreeAllocationPolicyVersion;
import org.civiceconomy.territory.AssessTerritoryMaintenanceCycle;
import org.civiceconomy.territory.TerritoryClaimPosition;
import org.civiceconomy.territory.TerritoryFreeAllocation;
import org.civiceconomy.territory.TerritoryMaintenanceAssessmentBatch;
import org.civiceconomy.territory.TerritoryMaintenanceAssessmentPlanner;
import org.civiceconomy.territory.TerritoryMaintenanceAssessmentProcessor;
import org.civiceconomy.territory.TerritoryMaintenanceCycleSchedule;
import org.civiceconomy.territory.TerritoryMaintenanceCycleWindow;
import org.civiceconomy.territory.TerritoryMaintenanceObservedClaim;
import org.civiceconomy.territory.TerritoryMaintenancePolicyRegistry;
import org.civiceconomy.territory.TerritoryMaintenancePolicyVersion;
import org.civiceconomy.territory.TerritoryMaintenanceRegistry;
import org.civiceconomy.territory.TerritoryMaintenanceRestorationHistory;
import org.civiceconomy.territory.SettleAvailableTerritoryMaintenance;
import org.civiceconomy.territory.TerritoryFiscalValidity;
import org.civiceconomy.territory.TerritoryMaintenanceSettlementOutcome;
import org.civiceconomy.strength.NationalStrengthRecalculation;
import org.civiceconomy.strength.NationalStrengthSnapshot;
import org.civiceconomy.strength.NationalStrengthSnapshotBuilder;
import org.civiceconomy.strength.NationalStrengthSnapshotConfiguration;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.FiscalLedger;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.mint.CancelMintBatch;
import org.civiceconomy.mint.EffectiveTerritoryMintAuthority;
import org.civiceconomy.mint.MintBatch;
import org.civiceconomy.mint.MintBatchCoordinator;
import org.civiceconomy.mint.MintBatchRecovery;
import org.civiceconomy.mint.MintIssuanceRecovery;
import org.civiceconomy.mint.MintFiscalServiceProvisioner;
import org.civiceconomy.mint.PendingMintMaterialOperation;
import org.civiceconomy.mint.PendingMintMaterialReturn;
import org.civiceconomy.mint.PendingMintMaterialTake;
import org.civiceconomy.mint.PendingMintIssuanceStep;
import org.civiceconomy.mint.PrepareMintBatch;
import org.civiceconomy.monetary.CorrectMonetaryStock;
import org.civiceconomy.monetary.ConfirmPermanentDestruction;
import org.civiceconomy.monetary.MonetarySupplyEvent;
import org.civiceconomy.monetary.PermanentDestructionOperation;
import org.civiceconomy.monetary.MonetaryStockCorrection;
import org.civiceconomy.monetary.MonetaryStockCorrectionRegistry;
import org.civiceconomy.monetary.PermanentDestructionFiscalServiceProvisioner;
import org.civiceconomy.nation.CitizenshipCorrectionGraceRegistry;
import org.civiceconomy.nation.CitizenshipRegistry;
import org.civiceconomy.nation.FtbTeamsNationProvider;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.territory.EffectiveTerritoryQuery;
import org.civiceconomy.territory.TerritoryOwnershipSource;
import org.slf4j.Logger;

public final class CivicServerRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHECKPOINT_INTERVAL_TICKS = 20 * 60;
    private static final int FISCAL_EXPIRY_INTERVAL_TICKS = 20 * 60;
    private static final int NATION_APPLICATION_EXPIRY_INTERVAL_TICKS = 20 * 60;
    private static final int CITIZENSHIP_RECONCILIATION_INTERVAL_TICKS = 20 * 60;
    private static final int TERRITORY_PERMIT_COMPENSATION_INTERVAL_TICKS = 20 * 60;
    private static final int PERMANENT_DESTRUCTION_RECOVERY_INTERVAL_TICKS = 20 * 60;
    private static final int TREASURY_WITHDRAWAL_RECOVERY_INTERVAL_TICKS = 20 * 60;
    private static final int MINT_BATCH_RECOVERY_INTERVAL_TICKS = 20 * 60;
    private static final int TERRITORY_MAINTENANCE_ASSESSMENT_INTERVAL_TICKS = 20 * 60;
    private static final int REGISTERED_FACILITY_TERRITORY_INTERVAL_TICKS = 20 * 60;
    private static final int NATIONAL_STRENGTH_RECALCULATION_INTERVAL_TICKS = 20 * 60;
    private static final int DATABASE_BACKUP_INTERVAL_TICKS = 20 * 60 * 30;
    private static final int DATABASE_BACKUP_RETENTION = 8;
    private static final Duration NATION_APPLICATION_EVIDENCE_WINDOW = Duration.ofDays(60);
    private static final Duration CITIZENSHIP_CORRECTION_GRACE = Duration.ofDays(2);
    private static final Duration CITIZENSHIP_TRANSFER_COOLDOWN = Duration.ofDays(7);
    private static final Duration NATIONAL_STRENGTH_ACTIVITY_WINDOW = Duration.ofDays(30);
    private static final Duration NATIONAL_STRENGTH_COMPLIANCE_WINDOW = Duration.ofDays(30);
    private static final Duration NATIONAL_STRENGTH_FULL_CITIZEN_TIME = Duration.ofHours(8);
    private static final int NATIONAL_STRENGTH_EFFECTIVE_TERRITORY_FULL_SCALE = 100;
    private static final long NATIONAL_STRENGTH_ACTIVITY_FULL_SCALE = 10_000L;
    private static final int REGISTERED_FACILITY_MAX_SCOPE_CHUNKS = 16;
    private static final Duration FACILITY_ACCOUNTING_RECEIPT_MATCH_WINDOW =
            Duration.ofSeconds(5);
    private static final NationTeamDirectory NO_TEAM_LOOKUPS = new NationTeamDirectory() {
        @Override
        public Optional<NationTeam> find(UUID teamId) {
            return Optional.empty();
        }

        @Override
        public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
            return Optional.empty();
        }
    };
    private static volatile CivicServerRuntime current;

    private final Clock clock;
    private final TerritoryClaimPermitMirror territoryClaimPermitMirror =
            new TerritoryClaimPermitMirror();
    private final FreeClaimAuthorizationMirror freeClaimAuthorizationMirror =
            new FreeClaimAuthorizationMirror();
    private final TerritoryForceLoadRestrictionMirror territoryForceLoadRestrictionMirror =
            new TerritoryForceLoadRestrictionMirror();
    private final Map<UUID, org.civiceconomy.nation.NationId> nationByFtbTeam =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean territoryClaimAuthorizationReady = new AtomicBoolean();
    private final AtomicBoolean territoryForceLoadRestrictionsReady = new AtomicBoolean();
    private RuntimeState state;

    public CivicServerRuntime() {
        this(Clock.systemUTC());
    }

    CivicServerRuntime(Clock clock) {
        this.clock = clock;
        current = this;
        new FtbTerritoryClaimPermitEvents(
                        this,
                        new TerritoryClaimPermitEventBridge(
                                territoryClaimPermitMirror,
                                freeClaimAuthorizationMirror,
                                this::queueTerritoryClaimPermitConsumption,
                                clock),
                        territoryForceLoadRestrictionMirror)
                .register();
    }

    static CivicServerRuntime current() {
        CivicServerRuntime runtime = current;
        if (runtime == null) {
            throw new IllegalStateException("Civic server runtime has not been constructed");
        }
        return runtime;
    }

    Optional<NationalStrengthSnapshot> nationalStrengthSnapshotForGameTest() {
        RuntimeState current = state;
        return current == null
                ? Optional.empty()
                : Optional.ofNullable(current.nationalStrengthSnapshot);
    }

    public void onServerStarted(ServerStartedEvent event) {
        if (state != null) {
            throw new IllegalStateException("Civic server runtime is already active");
        }
        MinecraftServer server = event.getServer();
        Path databaseDirectory = server.getWorldPath(LevelResource.ROOT).resolve("civiceconomy");
        try {
            Files.createDirectories(databaseDirectory);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to create Civic database directory " + databaseDirectory, failure);
        }
        NeoForgeModCatalog mods = new NeoForgeModCatalog();
        DatabaseIdentity identity = new DatabaseIdentity(
                CivicWorldIdentityData.getOrCreate(server),
                requireVersion(mods, CivicEconomy.MOD_ID),
                requireVersion(mods, "lightmanscurrency"),
                requireVersion(mods, "ftbteams"),
                requireVersion(mods, "ftbchunks"));
        DatabaseRestoreStartup.activate(databaseDirectory, identity, clock).ifPresent(activation ->
                LOGGER.warn(
                        "Activated staged Civic database restore {}; rollback snapshot is {}",
                        activation.operationId(),
                        activation.rollbackFileName()));
        CivicDatabase database = CivicDatabase.open(databaseDirectory.resolve("civic.sqlite3"), identity);
        LightmansCurrencyPublicMaintenanceFundProvisioner.forLevel(server.overworld())
                .ensureExists();
        AsyncOnlineTimeWriter writer = new AsyncOnlineTimeWriter(database);
        OnlineSessionAccumulator sessions =
                new OnlineSessionAccumulator(new ServiceIdentity("civiceconomy-server"));
        long now = clock.millis();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sessions.login(player.getUUID(), now);
        }
        OnlineDatabaseBackupManager backups = new OnlineDatabaseBackupManager(
                database,
                databaseDirectory.resolve("backups"),
                clock,
                DATABASE_BACKUP_RETENTION);
        OnlineDatabaseRestoreManager restores =
                new OnlineDatabaseRestoreManager(database, databaseDirectory, clock);
        state = new RuntimeState(
                server,
                sessions,
                writer,
                backups,
                restores,
                new ServerPlayerMintMaterialCustody(server),
                now);
        if (CivicEconomy.compatibilityReport().productionScoringEnabled()) {
            RuntimeState active = state;
            CreateMillstoneObservationBridge.installRuntime(
                    completion -> observeCreateRecipeCompletion(active, completion),
                    clock);
            FacilityAccountingInterfaceObservationBridge.installRuntime(
                    increase -> observeFacilityAccountingInventoryIncrease(active, increase),
                    clock);
        }
        scheduleDatabaseBackupRecovery(state);
        scheduleFiscalExpiry(state);
        scheduleBudgetDisbursementRecovery(state);
        scheduleTerritoryPermitMirrorRefresh(state);
        scheduleTerritoryForceLoadRestrictionRefresh(state);
        scheduleNationApplicationExpiry(state);
        scheduleCitizenshipReconciliation(state);
        scheduleTerritoryPermitCompensation(state);
        if (!PermanentDestructionProcessRestartDrill.verifying()) {
            schedulePermanentDestructionRecovery(state);
        }
        scheduleTreasuryWithdrawalRecovery(state);
        scheduleMintBatchRecovery(state);
        scheduleTerritoryMaintenanceAssessment(state);
        scheduleRegisteredFacilityTerritoryReconciliation(state);
        scheduleNationalStrengthRecalculation(state);
        scheduleTerritoryForceLoadEnforcementRecovery(state);
        LOGGER.info("Civic server runtime opened world-bound SQLite and enabled buffered online-time observation");
        if (CivicDebugWorldData.get(server).enabled()) {
            LOGGER.warn(
                    "DEBUG WORLD is active for {}; Civic debug data must not be represented as formal economy data",
                    server.getWorldData().getLevelName());
        }
    }

    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            observeLogin(player);
        }
    }

    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            observeLogout(player);
        }
    }

    void observeLogin(ServerPlayer player) {
        RuntimeState current = state;
        if (current != null) {
            current.sessions.login(player.getUUID(), clock.millis());
            scheduleTreasuryWithdrawalRecovery(current);
        }
    }

    void observeLogout(ServerPlayer player) {
        RuntimeState current = state;
        if (current != null) {
            current.writer.submit(current.sessions.logout(player.getUUID(), clock.millis()));
        }
    }

    public void onServerTick(ServerTickEvent.Post event) {
        RuntimeState current = state;
        if (current == null || current.server != event.getServer()) {
            return;
        }
        current.ticksSinceCheckpoint++;
        if (current.ticksSinceCheckpoint >= CHECKPOINT_INTERVAL_TICKS) {
            current.ticksSinceCheckpoint = 0;
            current.writer.submit(current.sessions.checkpoint(clock.millis()));
        }
        current.ticksSinceFiscalExpiry++;
        if (current.ticksSinceFiscalExpiry >= FISCAL_EXPIRY_INTERVAL_TICKS) {
            current.ticksSinceFiscalExpiry = 0;
            scheduleFiscalExpiry(current);
            scheduleBudgetDisbursementRecovery(current);
        }
        current.ticksSinceNationApplicationExpiry++;
        if (current.ticksSinceNationApplicationExpiry
                >= NATION_APPLICATION_EXPIRY_INTERVAL_TICKS) {
            current.ticksSinceNationApplicationExpiry = 0;
            scheduleNationApplicationExpiry(current);
        }
        current.ticksSinceCitizenshipReconciliation++;
        if (current.ticksSinceCitizenshipReconciliation
                >= CITIZENSHIP_RECONCILIATION_INTERVAL_TICKS) {
            current.ticksSinceCitizenshipReconciliation = 0;
            scheduleCitizenshipReconciliation(current);
        }
        current.ticksSinceTerritoryPermitCompensation++;
        if (current.ticksSinceTerritoryPermitCompensation
                >= TERRITORY_PERMIT_COMPENSATION_INTERVAL_TICKS) {
            current.ticksSinceTerritoryPermitCompensation = 0;
            scheduleTerritoryPermitCompensation(current);
        }
        current.ticksSincePermanentDestructionRecovery++;
        if (current.ticksSincePermanentDestructionRecovery
                >= PERMANENT_DESTRUCTION_RECOVERY_INTERVAL_TICKS) {
            current.ticksSincePermanentDestructionRecovery = 0;
            schedulePermanentDestructionRecovery(current);
        }
        current.ticksSinceTreasuryWithdrawalRecovery++;
        if (current.ticksSinceTreasuryWithdrawalRecovery
                >= TREASURY_WITHDRAWAL_RECOVERY_INTERVAL_TICKS) {
            current.ticksSinceTreasuryWithdrawalRecovery = 0;
            scheduleTreasuryWithdrawalRecovery(current);
        }
        current.ticksSinceMintBatchRecovery++;
        if (current.ticksSinceMintBatchRecovery >= MINT_BATCH_RECOVERY_INTERVAL_TICKS) {
            current.ticksSinceMintBatchRecovery = 0;
            scheduleMintBatchRecovery(current);
        }
        current.ticksSinceTerritoryMaintenanceAssessment++;
        if (current.ticksSinceTerritoryMaintenanceAssessment
                >= TERRITORY_MAINTENANCE_ASSESSMENT_INTERVAL_TICKS) {
            current.ticksSinceTerritoryMaintenanceAssessment = 0;
            scheduleTerritoryMaintenanceAssessment(current);
            scheduleTerritoryForceLoadEnforcementRecovery(current);
            scheduleTerritoryForceLoadRestrictionRefresh(current);
        }
        current.ticksSinceRegisteredFacilityTerritory++;
        if (current.ticksSinceRegisteredFacilityTerritory
                >= REGISTERED_FACILITY_TERRITORY_INTERVAL_TICKS) {
            current.ticksSinceRegisteredFacilityTerritory = 0;
            scheduleRegisteredFacilityTerritoryReconciliation(current);
        }
        current.ticksSinceDatabaseBackup++;
        if (current.ticksSinceDatabaseBackup >= DATABASE_BACKUP_INTERVAL_TICKS) {
            current.ticksSinceDatabaseBackup = 0;
            scheduleLifecycleDatabaseBackup(current, "scheduled");
        }
        current.ticksSinceNationalStrengthRecalculation++;
        if (current.ticksSinceNationalStrengthRecalculation
                >= NATIONAL_STRENGTH_RECALCULATION_INTERVAL_TICKS) {
            current.ticksSinceNationalStrengthRecalculation = 0;
            scheduleNationalStrengthRecalculation(current);
        }
        Throwable failure = current.writer.failure();
        if (failure != null && !current.failureLogged) {
            current.failureLogged = true;
            LOGGER.error("Civic online-time persistence failed closed; new intervals are paused", failure);
        }
    }

    public void onServerStopping(ServerStoppingEvent event) {
        RuntimeState current = state;
        if (current != null && current.server == event.getServer()) {
            List<RecordOnlineTime> finalIntervals = current.sessions.checkpoint(clock.millis());
            current.writer.submit(finalIntervals);
            queueShutdownDatabaseBackup(current);
        }
    }

    public void onServerStopped(ServerStoppedEvent event) {
        RuntimeState current = state;
        if (current == null || current.server != event.getServer()) {
            return;
        }
        try {
            current.writer.close();
        } finally {
            CreateMillstoneObservationBridge.resetRuntime();
            FacilityAccountingInterfaceObservationBridge.resetRuntime();
            state = null;
            territoryClaimAuthorizationReady.set(false);
            territoryForceLoadRestrictionsReady.set(false);
            territoryClaimPermitMirror.replaceAll(List.of());
            freeClaimAuthorizationMirror.clear();
            territoryForceLoadRestrictionMirror.replaceAll(List.of());
            nationByFtbTeam.clear();
        }
        LOGGER.info("Civic server runtime closed SQLite after draining buffered online-time intervals");
    }

    private void observeCreateRecipeCompletion(
            RuntimeState current,
            CreateRecipeCompletion completion) {
        if (state != current || completion == null) {
            return;
        }
        long earliest = Math.max(
                0L,
                completion.observedAtEpochMillis()
                        - FACILITY_ACCOUNTING_RECEIPT_MATCH_WINDOW.toMillis());
        current.pendingFacilityCompletions.removeIf(candidate ->
                candidate.observedAtEpochMillis() < earliest);
        current.pendingFacilityCompletions.addLast(completion);
    }

    private void observeFacilityAccountingInventoryIncrease(
            RuntimeState current,
            FacilityAccountingInterfaceInventoryIncrease increase) {
        if (state != current || increase == null) {
            return;
        }
        long earliest = Math.max(
                0L,
                increase.observedAtEpochMillis()
                        - FACILITY_ACCOUNTING_RECEIPT_MATCH_WINDOW.toMillis());
        current.pendingFacilityCompletions.removeIf(candidate ->
                candidate.observedAtEpochMillis() < earliest);
        List<CreateRecipeCompletion> candidates = current.pendingFacilityCompletions.stream()
                .filter(candidate ->
                        candidate.observedAtEpochMillis() <= increase.observedAtEpochMillis())
                .toList();
        if (candidates.isEmpty()) {
            return;
        }
        current.writer.submitDatabase(database ->
                        prepareFacilityAccountingReceipt(
                                database, increase, candidates))
                .thenCompose(preparation -> preparation == null
                        ? CompletableFuture.completedFuture(null)
                        : onServer(current, () ->
                                snapshotFacilityAccountingReceiptOwnership(preparation)))
                .thenCompose(snapshot -> snapshot == null
                        ? CompletableFuture.completedFuture(null)
                        : current.writer.submitDatabase(database ->
                                recordFacilityAccountingReceipt(database, snapshot)))
                .whenComplete((observation, failure) -> {
                    if (failure != null) {
                        LOGGER.error(
                                "Facility Accounting Receipt ingestion failed closed",
                                failure);
                    } else if (observation != null) {
                        onServer(current, () -> {
                            current.pendingFacilityCompletions.removeIf(candidate ->
                                    candidate.observationId().equals(
                                            observation.completion().observationId()));
                            return null;
                        });
                    }
                });
    }

    private static FacilityReceiptPreparation prepareFacilityAccountingReceipt(
            CivicDatabase database,
            FacilityAccountingInterfaceInventoryIncrease increase,
            List<CreateRecipeCompletion> candidates) {
        var facility = database.registeredFacilityAt(
                increase.position().dimensionId(),
                increase.position().blockX(),
                increase.position().blockZ());
        if (facility == null) {
            return null;
        }
        var accountingInterface =
                database.facilityAccountingInterface(facility.facilityId());
        if (accountingInterface == null
                || !accountingInterface.dimensionId().equals(
                        increase.position().dimensionId())
                || accountingInterface.blockX() != increase.position().blockX()
                || accountingInterface.blockY() != increase.position().blockY()
                || accountingInterface.blockZ() != increase.position().blockZ()) {
            return null;
        }
        return new FacilityReceiptPreparation(
                accountingInterface.interfaceId(),
                facility.ftbTeamId(),
                increase,
                candidates);
    }

    private static FacilityReceiptSnapshot snapshotFacilityAccountingReceiptOwnership(
            FacilityReceiptPreparation preparation) {
        Map<TerritoryClaimPosition, UUID> currentClaims =
                FtbChunksAdapter.live().claimsForTeam(preparation.ftbTeamId()).stream()
                        .collect(Collectors.toUnmodifiableMap(
                                claim -> new TerritoryClaimPosition(
                                        claim.dimension().location().toString(),
                                        claim.chunkPos().x,
                                        claim.chunkPos().z),
                                claim -> preparation.ftbTeamId()));
        TerritoryOwnershipSource ownership =
                (dimensionId, chunkX, chunkZ) -> Optional.ofNullable(currentClaims.get(
                        new TerritoryClaimPosition(dimensionId, chunkX, chunkZ)));
        return new FacilityReceiptSnapshot(preparation, ownership);
    }

    private FacilityProductionObservation recordFacilityAccountingReceipt(
            CivicDatabase database,
            FacilityReceiptSnapshot snapshot) {
        FacilityReceiptPreparation preparation = snapshot.preparation();
        FacilityAccountingInterfaceInventoryIncrease increase = preparation.increase();
        Clock operationClock = Clock.fixed(
                Instant.ofEpochMilli(increase.observedAtEpochMillis()),
                ZoneOffset.UTC);
        FacilityAccountingReceipt receipt = new FacilityAccountingReceipt(
                UUID.randomUUID(),
                preparation.interfaceId(),
                increase.position(),
                increase.observedAtEpochMillis(),
                increase.receivedOutputs());
        new ProductionInventoryAgeLedger(database, operationClock).recordReceipt(receipt);
        EffectiveTerritoryFacilityAuthority territory =
                new EffectiveTerritoryFacilityAuthority(
                        database,
                        new EffectiveTerritoryQuery(
                                new TerritoryMaintenanceRegistry(database, operationClock),
                                snapshot.ownership()),
                        operationClock);
        return new FacilityProductionObservationRegistry(
                        database,
                        new FacilityProductionMatcher(
                                database,
                                territory,
                                Set.of(CreateMachineKind.MILLSTONE),
                                FACILITY_ACCOUNTING_RECEIPT_MATCH_WINDOW),
                        operationClock)
                .recordFirstMatching(receipt, preparation.candidates());
    }

    <T> CompletableFuture<T> submitAdministration(
            Function<FiscalAuthorization, T> operation) {
        return submitDatabase(database -> operation.apply(new FiscalAuthorization(database)));
    }

    <T> CompletableFuture<T> submitDatabase(Function<CivicDatabase, T> operation) {
        RuntimeState current = state;
        if (current == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Civic server runtime is not active"));
        }
        return current.writer.submitDatabase(operation);
    }

    CompletableFuture<StoredDatabaseBackupOperation> createDatabaseBackup(
            String administratorIdentity, String requestId, String reason) {
        RuntimeState current = state;
        if (current == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Civic server runtime is not active"));
        }
        return current.writer.submitDatabase(ignored ->
                current.backups.create(administratorIdentity, requestId, reason));
    }

    CompletableFuture<List<StoredDatabaseBackupOperation>> databaseBackups() {
        return submitDatabase(CivicDatabase::databaseBackupOperations);
    }

    CompletableFuture<StoredDatabaseRestoreOperation> stageDatabaseRestore(
            String administratorIdentity,
            String requestId,
            UUID backupOperationId,
            String reason) {
        RuntimeState current = state;
        if (current == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Civic server runtime is not active"));
        }
        return current.writer.submitDatabase(ignored -> current.restores.stage(
                administratorIdentity, requestId, backupOperationId, reason));
    }

    CompletableFuture<StoredDatabaseRestoreOperation> cancelDatabaseRestore(
            String administratorIdentity,
            String requestId,
            UUID restoreOperationId,
            String reason) {
        RuntimeState current = state;
        if (current == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Civic server runtime is not active"));
        }
        return current.writer.submitDatabase(ignored -> current.restores.cancel(
                administratorIdentity, requestId, restoreOperationId, reason));
    }

    CompletableFuture<List<StoredDatabaseRestoreOperation>> databaseRestores() {
        return submitDatabase(CivicDatabase::databaseRestoreOperations);
    }

    CompletableFuture<MonetaryStockCorrection> correctMonetaryStock(
            String administratorIdentity,
            String requestId,
            UUID incidentId,
            String evidenceReference,
            String reason) {
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return submitDatabase(database -> new MonetaryStockCorrectionRegistry(database, commandClock)
                .correct(new CorrectMonetaryStock(
                        administratorIdentity,
                        requestId,
                        incidentId,
                        evidenceReference,
                        reason)));
    }

    CompletableFuture<MonetaryStockCorrection> monetaryStockCorrection(UUID incidentId) {
        return submitDatabase(database ->
                new MonetaryStockCorrectionRegistry(database, clock).correction(incidentId));
    }

    CompletableFuture<MonetarySupplyEvent> destroyNationalTreasury(
            ServerPlayer actor,
            String requestId,
            long amountMinorUnits,
            String reason) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> {
                    NationTeam team = requireActorTeam(actorPlayerId);
                    return new NationalStrengthCommandContext(
                            team, snapshotNationalStrengthClaims(List.of(team.teamId())));
                })
                .thenCompose(context -> current.writer.submitDatabase(database -> {
                    NationTeam team = context.team();
                    NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
                    NationRegistry nations = new NationRegistry(database, teams);
                    var nation = nations.findByFtbTeam(team.teamId())
                            .orElseThrow(() -> new SecurityException(
                                    "Your FTB Team is not bound to a formal Nation"));
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            new CitizenshipRegistry(
                                    database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock),
                            new CitizenshipCorrectionGraceRegistry(database, commandClock),
                            teams);
                    new NationFiscalAuthorityRegistry(database, provider, commandClock)
                            .require(
                                    nation.nationId(),
                                    actorPlayerId,
                                    NationFiscalPermission.MANAGE_ISSUANCE);
                    AccountId treasury = nationalTreasury(nation.nationId());
                    FiscalAuthorization authorization = new FiscalAuthorization(database);
                    new PermanentDestructionFiscalServiceProvisioner(authorization)
                            .ensureAuthorized(treasury);
                    var session = authorization.openSession(
                            PermanentDestructionFiscalServiceProvisioner.SERVICE_IDENTITY);
                    PermanentDestructionCoordinator coordinator =
                            PermanentDestructionCoordinator.live(
                                    database,
                                    session,
                                    commandClock,
                                    current.server.overworld(),
                                    PermanentDestructionProcessRestartDrill.observer(
                                            current.server));
                    return new PreparedPermanentDestruction(
                            coordinator,
                            coordinator.prepare(new ConfirmPermanentDestruction(
                                    PermanentDestructionFiscalServiceProvisioner.SERVICE_IDENTITY,
                                    requestId,
                                    treasury,
                                    MoneyAmount.ofMinorUnits(amountMinorUnits),
                                    "player:" + actorPlayerId,
                                    reason)));
                }))
                .thenCompose(prepared -> onServer(current, () -> {
                    prepared.coordinator().applyExternal(prepared.operation());
                    return prepared;
                }))
                .thenCompose(prepared -> current.writer.submitDatabase(database -> {
                    prepared.coordinator().recordExternalApplied(prepared.operation());
                    return prepared.coordinator().commit(prepared.operation());
                }));
    }

    CompletableFuture<NationalStrengthRecalculation> nationalStrengthStatus(ServerPlayer actor) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        return onServer(current, () -> {
                    NationTeam team = requireActorTeam(actorPlayerId);
                    return new NationalStrengthCommandContext(
                            team, snapshotNationalStrengthClaims(List.of(team.teamId())));
                })
                .thenCompose(context -> current.writer.submitDatabase(database -> {
                    NationTeam team = context.team();
                    NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
                    NationRegistry nations = new NationRegistry(database, teams);
                    var nation = nations.findByFtbTeam(team.teamId())
                            .orElseThrow(() -> new SecurityException(
                                    "Your FTB Team is not bound to a formal Nation"));
                    NationalStrengthSnapshot snapshot = current.nationalStrengthSnapshot;
                    NationalStrengthRecalculation cached = snapshot == null
                            ? null
                            : snapshot.nations().get(nation.nationId());
                    if (cached != null) {
                        return cached;
                    }
                    NationalStrengthSnapshot refreshed = new NationalStrengthSnapshotBuilder(
                                    database,
                                    nationalStrengthConfiguration(),
                                    context.currentClaimsByTeam(),
                                    CivicEconomy.compatibilityReport().productionScoringEnabled())
                            .recalculateAll(clock.millis());
                    current.nationalStrengthSnapshot = refreshed;
                    NationalStrengthRecalculation recalculation =
                            refreshed.nations().get(nation.nationId());
                    if (recalculation == null) {
                        throw new IllegalStateException(
                                "National Strength snapshot does not contain the formal Nation");
                    }
                    return recalculation;
                }));
    }

    CompletableFuture<FiscalBill> issueNationalFiscalBill(
            ServerPlayer actor,
            String requestId,
            UUID payerPlayerId,
            long amountMinorUnits,
            FiscalBillKind kind,
            long dueAtEpochMillis,
            String purpose) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database -> {
                    NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
                    NationRegistry nations = new NationRegistry(database, teams);
                    var nation = nations.findByFtbTeam(team.teamId())
                            .orElseThrow(() -> new SecurityException(
                                    "Your FTB Team is not bound to a formal Nation"));
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            new CitizenshipRegistry(
                                    database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock),
                            new CitizenshipCorrectionGraceRegistry(database, commandClock),
                            teams);
                    new NationFiscalAuthorityRegistry(database, provider, commandClock)
                            .require(
                                    nation.nationId(),
                                    actorPlayerId,
                                    NationFiscalPermission.INITIATE_PAYMENT);
                    AccountId treasury = nationalTreasury(nation.nationId());
                    FiscalAuthorization authorization = new FiscalAuthorization(database);
                    new FiscalBillFiscalServiceProvisioner(authorization)
                            .ensureIssueAuthorized(treasury);
                    var session = authorization.openSession(
                            FiscalBillFiscalServiceProvisioner.SERVICE_IDENTITY);
                    return FiscalLedger.authorized(
                                    database,
                                    ignored -> MoneyAmount.ZERO,
                                    commandClock,
                                    session)
                            .issueBill(new IssueFiscalBill(
                                    FiscalBillFiscalServiceProvisioner.SERVICE_IDENTITY,
                                    requestId,
                                    new AccountId("player:" + payerPlayerId),
                                    treasury,
                                    MoneyAmount.ofMinorUnits(amountMinorUnits),
                                    kind,
                                    purpose,
                                    Instant.ofEpochMilli(dueAtEpochMillis)));
                }));
    }

    CompletableFuture<Budget> createNationalBudgetDraft(
            ServerPlayer actor,
            String requestId,
            long amountMinorUnits,
            String budgetCode,
            long expiresAtEpochMillis,
            String purpose) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database -> {
                    NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
                    NationRegistry nations = new NationRegistry(database, teams);
                    var nation = nations.findByFtbTeam(team.teamId())
                            .orElseThrow(() -> new SecurityException(
                                    "Your FTB Team is not bound to a formal Nation"));
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            new CitizenshipRegistry(
                                    database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock),
                            new CitizenshipCorrectionGraceRegistry(database, commandClock),
                            teams);
                    new NationFiscalAuthorityRegistry(database, provider, commandClock)
                            .require(
                                    nation.nationId(),
                                    actorPlayerId,
                                    NationFiscalPermission.DRAFT_BUDGET);
                    AccountId treasury = nationalTreasury(nation.nationId());
                    FiscalAuthorization authorization = new FiscalAuthorization(database);
                    new BudgetFiscalServiceProvisioner(authorization)
                            .ensureAuthorized(treasury);
                    var session = authorization.openSession(
                            BudgetFiscalServiceProvisioner.SERVICE_IDENTITY);
                    return FiscalLedger.authorized(
                                    database,
                                    ignored -> MoneyAmount.ZERO,
                                    commandClock,
                                    session)
                            .createBudget(new CreateBudget(
                                    BudgetFiscalServiceProvisioner.SERVICE_IDENTITY,
                                    requestId,
                                    treasury,
                                    MoneyAmount.ofMinorUnits(amountMinorUnits),
                                    budgetCode,
                                    purpose,
                                    Instant.ofEpochMilli(expiresAtEpochMillis)));
                }));
    }

    CompletableFuture<List<Budget>> nationBudgets(ServerPlayer actor) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database ->
                        nationBudgetInspection(database, team, commandClock)
                                .list(actorPlayerId)));
    }

    CompletableFuture<Budget> nationBudget(ServerPlayer actor, UUID budgetId) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database ->
                        nationBudgetInspection(database, team, commandClock)
                                .status(actorPlayerId, budgetId)));
    }

    CompletableFuture<Budget> approveNationalBudget(
            ServerPlayer actor, UUID budgetId, String requestId, String reason) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database -> {
                    NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
                    NationRegistry nations = new NationRegistry(database, teams);
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            new CitizenshipRegistry(
                                    database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock),
                            new CitizenshipCorrectionGraceRegistry(database, commandClock),
                            teams);
                    var nation = provider.findForCitizen(actorPlayerId)
                            .orElseThrow(() -> new SecurityException(
                                    "Budget approval requires effective Citizenship"));
                    new NationFiscalAuthorityRegistry(database, provider, commandClock)
                            .require(
                                    nation.nationId(),
                                    actorPlayerId,
                                    NationFiscalPermission.APPROVE_BUDGET);
                    AccountId treasury = nationalTreasury(nation.nationId());
                    if (database.budgetForSourceAccount(budgetId, treasury.value()) == null) {
                        throw new SecurityException("Budget is not approvable by this Nation");
                    }
                    return new PendingBudgetApproval(team, treasury);
                }))
                .thenCompose(pending -> onServer(current, () ->
                        new PreparedBudgetApproval(
                                pending.team(),
                                pending.treasury(),
                                LightmansCurrencyAccountBalances.live(
                                                current.server.overworld())
                                        .balance(pending.treasury()))))
                .thenCompose(prepared -> current.writer.submitDatabase(database -> {
                    NationTeamDirectory teams = snapshotDirectory(
                            Map.of(prepared.team().teamId(), prepared.team()));
                    NationRegistry nations = new NationRegistry(database, teams);
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            new CitizenshipRegistry(
                                    database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock),
                            new CitizenshipCorrectionGraceRegistry(database, commandClock),
                            teams);
                    return new NationBudgetApprovalCoordinator(
                                    database,
                                    provider,
                                    new NationFiscalAuthorityRegistry(
                                            database, provider, commandClock),
                                    commandClock)
                            .approve(
                                    actorPlayerId,
                                    budgetId,
                                    requestId,
                                    reason,
                                    accountId -> {
                                        if (!prepared.treasury().equals(accountId)) {
                                            throw new IllegalArgumentException(
                                                    "Unexpected Budget balance account "
                                                            + accountId.value());
                                        }
                                        return prepared.balance();
                                    });
                }));
    }

    CompletableFuture<Budget> cancelNationalBudget(
            ServerPlayer actor, UUID budgetId, String requestId, String reason) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database -> {
                    NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
                    NationRegistry nations = new NationRegistry(database, teams);
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            new CitizenshipRegistry(
                                    database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock),
                            new CitizenshipCorrectionGraceRegistry(database, commandClock),
                            teams);
                    return new NationBudgetCancellationCoordinator(
                                    database,
                                    provider,
                                    new NationFiscalAuthorityRegistry(
                                            database, provider, commandClock),
                                    commandClock)
                            .cancel(actorPlayerId, budgetId, requestId, reason);
                }));
    }

    CompletableFuture<BudgetDisbursementApprovalOutcome> requestNationalBudgetDisbursement(
            ServerPlayer actor,
            UUID budgetId,
            UUID recipientPlayerId,
            long amountMinorUnits,
            String requestId,
            String reason) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database -> {
                    NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
                    NationRegistry nations = new NationRegistry(database, teams);
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            new CitizenshipRegistry(
                                    database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock),
                            new CitizenshipCorrectionGraceRegistry(database, commandClock),
                            teams);
                    var authorities = new NationFiscalAuthorityRegistry(
                            database, provider, commandClock);
                    BudgetDisbursementApproval approval =
                            new NationBudgetDisbursementApprovalCoordinator(
                                            database, provider, authorities, commandClock)
                                    .initiate(
                                            actorPlayerId,
                                            budgetId,
                                            new AccountId("player:" + recipientPlayerId),
                                            MoneyAmount.ofMinorUnits(amountMinorUnits),
                                            requestId,
                                            reason);
                    return prepareBudgetDisbursement(current, database, approval);
                }))
                .thenCompose(prepared -> completeBudgetDisbursement(
                        current, commandClock, prepared));
    }

    CompletableFuture<BudgetDisbursementApprovalOutcome> approveNationalBudgetDisbursement(
            ServerPlayer actor,
            UUID approvalRequestId,
            String requestId,
            String reason) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database -> {
                    NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
                    NationRegistry nations = new NationRegistry(database, teams);
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            new CitizenshipRegistry(
                                    database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock),
                            new CitizenshipCorrectionGraceRegistry(database, commandClock),
                            teams);
                    var authorities = new NationFiscalAuthorityRegistry(
                            database, provider, commandClock);
                    BudgetDisbursementApproval approval =
                            new NationBudgetDisbursementApprovalCoordinator(
                                            database, provider, authorities, commandClock)
                                    .approve(
                                            actorPlayerId,
                                            approvalRequestId,
                                            requestId,
                                            reason);
                    return prepareBudgetDisbursement(current, database, approval);
                }))
                .thenCompose(prepared -> completeBudgetDisbursement(
                        current, commandClock, prepared));
    }

    CompletableFuture<BudgetDisbursementApproval> cancelNationalBudgetDisbursementApproval(
            ServerPlayer actor,
            UUID approvalRequestId,
            String requestId,
            String reason) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database -> {
                    NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
                    NationRegistry nations = new NationRegistry(database, teams);
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            new CitizenshipRegistry(
                                    database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock),
                            new CitizenshipCorrectionGraceRegistry(database, commandClock),
                            teams);
                    var authorities = new NationFiscalAuthorityRegistry(
                            database, provider, commandClock);
                    return new NationBudgetDisbursementApprovalCoordinator(
                                    database, provider, authorities, commandClock)
                            .cancel(
                                    actorPlayerId,
                                    approvalRequestId,
                                    requestId,
                                    reason);
                }));
    }

    CompletableFuture<BudgetDisbursementApprovalPolicyVersion>
            scheduleBudgetDisbursementApprovalPolicy(
                    ServerPlayer actor,
                    String requestId,
                    long effectiveAtEpochMillis,
                    long approvalLifetimeMillis,
                    long thresholdMinorUnits,
                    int requiredApprovals,
                    String reason) {
        List<BudgetDisbursementApprovalTier> tiers = thresholdMinorUnits == 0L
                ? List.of(new BudgetDisbursementApprovalTier(
                        MoneyAmount.ZERO, requiredApprovals))
                : List.of(
                        new BudgetDisbursementApprovalTier(MoneyAmount.ZERO, 1),
                        new BudgetDisbursementApprovalTier(
                                MoneyAmount.ofMinorUnits(thresholdMinorUnits),
                                requiredApprovals));
        return scheduleTieredBudgetDisbursementApprovalPolicy(
                actor,
                requestId,
                effectiveAtEpochMillis,
                approvalLifetimeMillis,
                tiers,
                reason);
    }

    CompletableFuture<BudgetDisbursementApprovalPolicyVersion>
            scheduleTieredBudgetDisbursementApprovalPolicy(
                    ServerPlayer actor,
                    String requestId,
                    long effectiveAtEpochMillis,
                    long approvalLifetimeMillis,
                    List<BudgetDisbursementApprovalTier> tiers,
                    String reason) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        List<BudgetDisbursementApprovalTier> requestedTiers = List.copyOf(tiers);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database -> {
                    NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
                    NationRegistry nations = new NationRegistry(database, teams);
                    var nation = nations.findByFtbTeam(team.teamId())
                            .orElseThrow(() -> new SecurityException(
                                    "Your FTB Team is not bound to a formal Nation"));
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            new CitizenshipRegistry(
                                    database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock),
                            new CitizenshipCorrectionGraceRegistry(database, commandClock),
                            teams);
                    new NationFiscalAuthorityRegistry(database, provider, commandClock)
                            .require(
                                    nation.nationId(),
                                    actorPlayerId,
                                    NationFiscalPermission.MANAGE_APPROVAL_POLICY);
                    return new BudgetDisbursementApprovalPolicyRegistry(database, commandClock)
                            .schedule(new ScheduleBudgetDisbursementApprovalPolicy(
                                    new ServiceIdentity(
                                            "civiceconomy-budget-disbursement-governance"),
                                    requestId,
                                    nation.nationId(),
                                    actorPlayerId,
                                    requestedTiers,
                                    Duration.ofMillis(approvalLifetimeMillis),
                                    Instant.ofEpochMilli(effectiveAtEpochMillis),
                                    reason));
                }));
    }

    CompletableFuture<BudgetDisbursementApprovalPolicyVersion>
            budgetDisbursementApprovalPolicy(ServerPlayer actor) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database ->
                        budgetDisbursementInspection(database, team, commandClock)
                                .currentPolicy(actorPlayerId)));
    }

    CompletableFuture<List<BudgetDisbursementApprovalPolicyVersion>>
            budgetDisbursementApprovalPolicyHistory(ServerPlayer actor) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database ->
                        budgetDisbursementInspection(database, team, commandClock)
                                .policyHistory(actorPlayerId)));
    }

    CompletableFuture<List<BudgetDisbursementApprovalStatus>>
            budgetDisbursementApprovalStatuses(ServerPlayer actor) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database ->
                        budgetDisbursementInspection(database, team, commandClock)
                                .approvalStatuses(actorPlayerId)));
    }

    CompletableFuture<BudgetDisbursementApprovalStatus> budgetDisbursementApprovalStatus(
            ServerPlayer actor, UUID approvalRequestId) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database ->
                        budgetDisbursementInspection(database, team, commandClock)
                                .approvalStatus(actorPlayerId, approvalRequestId)));
    }

    private static PreparedNationalBudgetDisbursement prepareBudgetDisbursement(
            RuntimeState current,
            CivicDatabase database,
            BudgetDisbursementApproval approval) {
        if (!"APPROVED".equals(approval.state())
                && !"EXECUTED".equals(approval.state())) {
            return new PreparedNationalBudgetDisbursement(approval, null, null);
        }
        BudgetDisbursementPaymentCoordinator payments =
                new BudgetDisbursementPaymentCoordinator(
                        database,
                        LightmansCurrencyPayments.live(current.server.overworld()));
        return new PreparedNationalBudgetDisbursement(
                approval, payments, payments.prepare(approval.approvalRequestId()));
    }

    private static CompletableFuture<BudgetDisbursementApprovalOutcome>
            completeBudgetDisbursement(
                    RuntimeState current,
                    Clock commandClock,
                    PreparedNationalBudgetDisbursement prepared) {
        if (prepared.payment() == null) {
            return CompletableFuture.completedFuture(
                    new BudgetDisbursementApprovalOutcome(prepared.approval(), null));
        }
        return onServer(current, () -> {
                    prepared.coordinator().applyExternal(prepared.payment());
                    return prepared;
                })
                .thenCompose(applied -> current.writer.submitDatabase(database -> {
                    var transaction = applied.coordinator().commit(applied.payment());
                    var approval = new org.civiceconomy.fiscal.BudgetDisbursementApprovalRegistry(
                                    database, commandClock)
                            .find(applied.approval().approvalRequestId());
                    return new BudgetDisbursementApprovalOutcome(approval, transaction);
                }));
    }

    CompletableFuture<List<FiscalBill>> payerFiscalBills(ServerPlayer actor) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        return current.writer.submitDatabase(database ->
                new FiscalBillInspection(database).listForPayer(actorPlayerId));
    }

    CompletableFuture<FiscalBill> payerFiscalBill(ServerPlayer actor, UUID billId) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        return current.writer.submitDatabase(database ->
                new FiscalBillInspection(database).statusForPayer(actorPlayerId, billId));
    }

    CompletableFuture<FiscalBill> fundPlayerFiscalBill(
            ServerPlayer actor, UUID billId, String requestId) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        AccountId payerAccount = new AccountId("player:" + actorPlayerId);
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return current.writer.submitDatabase(database ->
                        new FiscalBillInspection(database)
                                .statusForPayer(actorPlayerId, billId))
                .thenCompose(ignored -> onServer(current, () ->
                        LightmansCurrencyAccountBalances.live(current.server.overworld())
                                .balance(payerAccount)))
                .thenCompose(balance -> current.writer.submitDatabase(database ->
                        new FiscalBillFundingCoordinator(
                                        database,
                                        ignored -> balance,
                                        commandClock)
                                .fund(actorPlayerId, billId, requestId)));
    }

    CompletableFuture<FiscalBill> payPlayerFiscalBill(
            ServerPlayer actor, UUID billId, String requestId) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        return onServer(current, () ->
                        LightmansCurrencyPayments.live(current.server.overworld()))
                .thenCompose(payments -> current.writer.submitDatabase(database -> {
                    FiscalBillPaymentCoordinator coordinator =
                            new FiscalBillPaymentCoordinator(database, payments);
                    return new PreparedPlayerFiscalBillPayment(
                            coordinator,
                            coordinator.prepare(actorPlayerId, billId, requestId));
                }))
                .thenCompose(prepared -> onServer(current, () -> {
                    prepared.coordinator().applyExternal(prepared.payment());
                    return prepared;
                }))
                .thenCompose(prepared -> current.writer.submitDatabase(database -> {
                    prepared.coordinator().commit(prepared.payment());
                    return new FiscalBillInspection(database)
                            .statusForPayer(actorPlayerId, billId);
                }));
    }

    CompletableFuture<FiscalBill> cancelPlayerFiscalBill(
            ServerPlayer actor, UUID billId, String requestId, String reason) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return current.writer.submitDatabase(database ->
                new FiscalBillCancellationCoordinator(database, commandClock)
                        .cancel(actorPlayerId, billId, requestId, reason));
    }

    CompletableFuture<List<FiscalBill>> nationFiscalBills(ServerPlayer actor) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database ->
                        nationFiscalBillInspection(database, team, commandClock)
                                .list(actorPlayerId)));
    }

    CompletableFuture<FiscalBill> nationFiscalBill(ServerPlayer actor, UUID billId) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database ->
                        nationFiscalBillInspection(database, team, commandClock)
                                .status(actorPlayerId, billId)));
    }

    CompletableFuture<TreasuryWithdrawal> withdrawNationalTreasury(
            ServerPlayer actor,
            String requestId,
            long amountMinorUnits,
            String reason) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database -> {
                    NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
                    NationRegistry nations = new NationRegistry(database, teams);
                    var nation = nations.findByFtbTeam(team.teamId())
                            .orElseThrow(() -> new SecurityException(
                                    "Your FTB Team is not bound to a formal Nation"));
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            new CitizenshipRegistry(
                                    database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock),
                            new CitizenshipCorrectionGraceRegistry(database, commandClock),
                            teams);
                    new NationFiscalAuthorityRegistry(database, provider, commandClock)
                            .require(
                                    nation.nationId(),
                                    actorPlayerId,
                                    NationFiscalPermission.MANAGE_WITHDRAWAL);
                    AccountId treasury = nationalTreasury(nation.nationId());
                    FiscalAuthorization authorization = new FiscalAuthorization(database);
                    new TreasuryWithdrawalFiscalServiceProvisioner(authorization)
                            .ensureAuthorized(treasury);
                    var session = authorization.openSession(
                            TreasuryWithdrawalFiscalServiceProvisioner.SERVICE_IDENTITY);
                    TreasuryWithdrawalCoordinator coordinator =
                            TreasuryWithdrawalCoordinator.live(
                                    database,
                                    session,
                                    commandClock,
                                    current.server.overworld(),
                                    playerId -> actorPlayerId.equals(playerId)
                                            ? actor
                                            : current.server.getPlayerList().getPlayer(playerId),
                                    TreasuryWithdrawalProcessRestartDrill.observer(
                                            current.server));
                    TreasuryWithdrawal prepared = coordinator.prepare(
                            new ConfirmTreasuryWithdrawal(
                                    TreasuryWithdrawalFiscalServiceProvisioner.SERVICE_IDENTITY,
                                    requestId,
                                    nation.nationId(),
                                    treasury,
                                    actorPlayerId,
                                    MoneyAmount.ofMinorUnits(amountMinorUnits),
                                    reason));
                    return new PreparedTreasuryWithdrawal(coordinator, prepared);
                }))
                .thenCompose(prepared -> onServer(current, () -> {
                    prepared.coordinator().applyExternal(prepared.withdrawal());
                    return prepared;
                }))
                .thenCompose(prepared -> current.writer.submitDatabase(database ->
                        prepared.coordinator().commit(prepared.withdrawal())));
    }

    CompletableFuture<TreasuryWithdrawalApprovalOutcome> approveNationalTreasuryWithdrawal(
            ServerPlayer actor,
            UUID approvalRequestId,
            String requestId,
            String reason) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database -> {
                    NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
                    NationRegistry nations = new NationRegistry(database, teams);
                    var nation = nations.findByFtbTeam(team.teamId())
                            .orElseThrow(() -> new SecurityException(
                                    "Your FTB Team is not bound to a formal Nation"));
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            new CitizenshipRegistry(
                                    database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock),
                            new CitizenshipCorrectionGraceRegistry(database, commandClock),
                            teams);
                    new NationFiscalAuthorityRegistry(database, provider, commandClock)
                            .require(
                                    nation.nationId(),
                                    actorPlayerId,
                                    NationFiscalPermission.MANAGE_WITHDRAWAL);
                    TreasuryWithdrawalApprovalRegistry approvals =
                            new TreasuryWithdrawalApprovalRegistry(database, commandClock);
                    TreasuryWithdrawalApproval existing = approvals.find(approvalRequestId);
                    if (!existing.nationId().equals(nation.nationId())) {
                        throw new SecurityException(
                                "Treasury Withdrawal approval belongs to another Nation");
                    }
                    TreasuryWithdrawalApproval approved = approvals.approve(
                            new ApproveTreasuryWithdrawal(
                                    TreasuryWithdrawalFiscalServiceProvisioner.SERVICE_IDENTITY,
                                    requestId,
                                    approvalRequestId,
                                    actorPlayerId,
                                    reason));
                    if (!approved.state().equals("APPROVED")) {
                        return new PreparedApprovedTreasuryWithdrawal(
                                null, approved, null);
                    }
                    AccountId treasury = nationalTreasury(nation.nationId());
                    if (!approved.sourceAccount().equals(treasury)) {
                        throw new SecurityException(
                                "Treasury Withdrawal approval source is not the current National Treasury");
                    }
                    FiscalAuthorization authorization = new FiscalAuthorization(database);
                    new TreasuryWithdrawalFiscalServiceProvisioner(authorization)
                            .ensureAuthorized(treasury);
                    var session = authorization.openSession(
                            TreasuryWithdrawalFiscalServiceProvisioner.SERVICE_IDENTITY);
                    TreasuryWithdrawalCoordinator coordinator =
                            TreasuryWithdrawalCoordinator.live(
                                    database,
                                    session,
                                    commandClock,
                                    current.server.overworld(),
                                    playerId -> current.server.getPlayerList()
                                            .getPlayer(playerId),
                                    TreasuryWithdrawalProcessRestartDrill.observer(
                                            current.server));
                    TreasuryWithdrawal prepared =
                            coordinator.prepareApproved(approvalRequestId);
                    return new PreparedApprovedTreasuryWithdrawal(
                            coordinator, approved, prepared);
                }))
                .thenCompose(prepared -> {
                    if (prepared.withdrawal() == null) {
                        return CompletableFuture.completedFuture(prepared);
                    }
                    return onServer(current, () -> {
                        prepared.coordinator().applyExternal(prepared.withdrawal());
                        return prepared;
                    });
                })
                .thenCompose(prepared -> {
                    if (prepared.withdrawal() == null) {
                        return CompletableFuture.completedFuture(
                                new TreasuryWithdrawalApprovalOutcome(
                                        prepared.approval(), null));
                    }
                    return current.writer.submitDatabase(database -> {
                        TreasuryWithdrawal committed = prepared.coordinator()
                                .commit(prepared.withdrawal());
                        TreasuryWithdrawalApproval executed =
                                new TreasuryWithdrawalApprovalRegistry(database, commandClock)
                                        .find(approvalRequestId);
                        return new TreasuryWithdrawalApprovalOutcome(executed, committed);
                    });
                });
    }

    CompletableFuture<WithdrawalApprovalPolicyVersion> scheduleWithdrawalApprovalPolicy(
            ServerPlayer actor,
            String requestId,
            long approvalLifetimeMillis,
            long thresholdMinorUnits,
            int requiredApprovals,
            long effectiveAtEpochMillis,
            String reason) {
        List<WithdrawalApprovalTier> tiers = thresholdMinorUnits == 0L
                ? List.of(new WithdrawalApprovalTier(
                        MoneyAmount.ZERO, requiredApprovals))
                : List.of(
                        new WithdrawalApprovalTier(MoneyAmount.ZERO, 1),
                        new WithdrawalApprovalTier(
                                MoneyAmount.ofMinorUnits(thresholdMinorUnits),
                                requiredApprovals));
        return scheduleTieredWithdrawalApprovalPolicy(
                actor,
                requestId,
                approvalLifetimeMillis,
                tiers,
                effectiveAtEpochMillis,
                reason);
    }

    CompletableFuture<WithdrawalApprovalPolicyVersion> scheduleTieredWithdrawalApprovalPolicy(
            ServerPlayer actor,
            String requestId,
            long approvalLifetimeMillis,
            List<WithdrawalApprovalTier> tiers,
            long effectiveAtEpochMillis,
            String reason) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        List<WithdrawalApprovalTier> requestedTiers = List.copyOf(tiers);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database -> {
                    NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
                    NationRegistry nations = new NationRegistry(database, teams);
                    var nation = nations.findByFtbTeam(team.teamId())
                            .orElseThrow(() -> new SecurityException(
                                    "Your FTB Team is not bound to a formal Nation"));
                    var provider = new FtbTeamsNationProvider(
                            nations,
                            new CitizenshipRegistry(
                                    database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock),
                            new CitizenshipCorrectionGraceRegistry(database, commandClock),
                            teams);
                    new NationFiscalAuthorityRegistry(database, provider, commandClock)
                            .require(
                                    nation.nationId(),
                                    actorPlayerId,
                                    NationFiscalPermission.MANAGE_APPROVAL_POLICY);
                    return new WithdrawalApprovalPolicyRegistry(database, commandClock)
                            .schedule(new ScheduleWithdrawalApprovalPolicy(
                                    new ServiceIdentity(
                                            "civiceconomy-withdrawal-governance"),
                                    requestId,
                                    nation.nationId(),
                                    actorPlayerId,
                                    requestedTiers,
                                    java.time.Duration.ofMillis(approvalLifetimeMillis),
                                    Instant.ofEpochMilli(effectiveAtEpochMillis),
                                    reason));
                }));
    }

    CompletableFuture<WithdrawalApprovalPolicyVersion> withdrawalApprovalPolicy(
            ServerPlayer actor) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database ->
                        withdrawalInspection(database, team, commandClock)
                                .currentPolicy(actorPlayerId)));
    }

    CompletableFuture<List<WithdrawalApprovalPolicyVersion>> withdrawalApprovalPolicyHistory(
            ServerPlayer actor) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database ->
                        withdrawalInspection(database, team, commandClock)
                                .policyHistory(actorPlayerId)));
    }

    CompletableFuture<List<TreasuryWithdrawalApprovalStatus>>
            withdrawalApprovalStatuses(ServerPlayer actor) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database ->
                        withdrawalInspection(database, team, commandClock)
                                .approvalStatuses(actorPlayerId)));
    }

    CompletableFuture<TreasuryWithdrawalApprovalStatus> withdrawalApprovalStatus(
            ServerPlayer actor, UUID approvalRequestId) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database ->
                        withdrawalInspection(database, team, commandClock)
                                .approvalStatus(actorPlayerId, approvalRequestId)));
    }

    CompletableFuture<TreasuryWithdrawalApproval> cancelWithdrawalApproval(
            ServerPlayer actor,
            UUID approvalRequestId,
            String requestId,
            String reason) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database ->
                        withdrawalInspection(database, team, commandClock)
                                .cancel(
                                        actorPlayerId,
                                        approvalRequestId,
                                        requestId,
                                        reason)));
    }

    CompletableFuture<TreasuryWithdrawalRecoveryStatus> withdrawalRecoveryStatus() {
        RuntimeState current = requireState();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return current.writer.submitDatabase(database ->
                new TreasuryWithdrawalRecoveryInspection(database, commandClock)
                        .status(TreasuryWithdrawalFiscalServiceProvisioner.SERVICE_IDENTITY));
    }

    CompletableFuture<BudgetDisbursementRecoveryStatus>
            budgetDisbursementRecoveryStatus() {
        RuntimeState current = requireState();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return current.writer.submitDatabase(database ->
                new BudgetDisbursementRecoveryInspection(database, commandClock)
                        .status(NationBudgetDisbursementApprovalCoordinator
                                .SERVICE_IDENTITY));
    }

    CompletableFuture<MintBatch> startMintBatch(
            ServerPlayer actor,
            String requestId,
            UUID mintId,
            UUID periodId,
            long amountMinorUnits) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Instant commandTime = clock.instant();
        Clock commandClock = Clock.fixed(commandTime, ZoneOffset.UTC);
        CompletableFuture<MintBatch> result = new CompletableFuture<>();
        onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database -> {
                    var existing = mintCoordinator(
                                    database,
                                    team,
                                    NO_TERRITORY_OWNERSHIP,
                                    current,
                                    commandClock)
                            .findByRequest(
                                    MintFiscalServiceProvisioner.SERVICE_IDENTITY,
                                    requestId,
                                    mintId,
                                    periodId,
                                    actorPlayerId,
                                    MoneyAmount.ofMinorUnits(amountMinorUnits));
                    return existing.<MintStartPreparation>map(MintStartReplay::new)
                            .orElseGet(() -> new NewMintStart(
                                    mintStartFacts(database, mintId, amountMinorUnits), team));
                }))
                .thenCompose(preparation -> preparation instanceof MintStartReplay replay
                        ? CompletableFuture.completedFuture(replay.batch())
                        : startNewMintBatch(
                                current,
                                actor,
                                actorPlayerId,
                                requestId,
                                mintId,
                                periodId,
                                amountMinorUnits,
                                ((NewMintStart) preparation).facts(),
                                ((NewMintStart) preparation).team(),
                                commandClock))
                .whenComplete((batch, failure) -> {
                    if (failure == null) {
                        result.complete(batch);
                    } else {
                        result.completeExceptionally(failure);
                    }
                });
        return result;
    }

    CompletableFuture<RegisteredFacility> registerFacility(
            ServerPlayer actor, String requestId, String reason) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> snapshotFacilityRegistration(actor))
                .thenCompose(snapshot -> current.writer.submitDatabase(database -> {
                    NationTeamDirectory teams = snapshotDirectory(
                            Map.of(snapshot.team().teamId(), snapshot.team()));
                    NationRegistry nations = new NationRegistry(database, teams);
                    CitizenshipRegistry citizenships = new CitizenshipRegistry(
                            database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock);
                    CitizenshipCorrectionGraceRegistry corrections =
                            new CitizenshipCorrectionGraceRegistry(database, commandClock);
                    FtbTeamsNationProvider provider = new FtbTeamsNationProvider(
                            nations, citizenships, corrections, teams);
                    EffectiveTerritoryFacilityAuthority territory =
                            new EffectiveTerritoryFacilityAuthority(
                                    database,
                                    new EffectiveTerritoryQuery(
                                            new TerritoryMaintenanceRegistry(
                                                    database, commandClock),
                                            snapshot.ownership()),
                                    commandClock);
                    return new FacilityAdministration(
                                    nations,
                                    provider,
                                    new NationFiscalAuthorityRegistry(
                                            database, provider, commandClock),
                                    new RegisteredFacilityRegistry(
                                            database,
                                            territory,
                                            commandClock,
                                            REGISTERED_FACILITY_MAX_SCOPE_CHUNKS),
                                    new FacilityAccountingInterfaceRegistry(
                                            database, commandClock))
                            .register(
                                    actorPlayerId,
                                    snapshot.team().teamId(),
                                    requestId,
                                    snapshot.core(),
                                    List.of(snapshot.claim()),
                                    reason);
                }));
    }

    CompletableFuture<FacilityAccountingInterface> bindFacilityAccountingInterface(
            ServerPlayer actor, String requestId, String reason) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> snapshotFacilityAccountingInterface(actor))
                .thenCompose(snapshot -> current.writer.submitDatabase(database -> {
                    NationTeamDirectory teams = snapshotDirectory(
                            Map.of(snapshot.team().teamId(), snapshot.team()));
                    NationRegistry nations = new NationRegistry(database, teams);
                    CitizenshipRegistry citizenships = new CitizenshipRegistry(
                            database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock);
                    CitizenshipCorrectionGraceRegistry corrections =
                            new CitizenshipCorrectionGraceRegistry(database, commandClock);
                    FtbTeamsNationProvider provider = new FtbTeamsNationProvider(
                            nations, citizenships, corrections, teams);
                    return new FacilityAdministration(
                                    nations,
                                    provider,
                                    new NationFiscalAuthorityRegistry(
                                            database, provider, commandClock),
                                    new RegisteredFacilityRegistry(
                                            database,
                                            (nationId, teamId, claim) -> false,
                                            commandClock,
                                            REGISTERED_FACILITY_MAX_SCOPE_CHUNKS),
                                    new FacilityAccountingInterfaceRegistry(
                                            database, commandClock))
                            .bindInterface(
                                    actorPlayerId,
                                    snapshot.team().teamId(),
                                    requestId,
                                    snapshot.position(),
                                    reason);
                }));
    }

    CompletableFuture<FacilityAccountingBaseline> captureFacilityAccountingBaseline(
            ServerPlayer actor, String requestId, String reason) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> snapshotFacilityBaselineActor(actor))
                .thenCompose(actorSnapshot -> current.writer.submitDatabase(database ->
                        facilityBaselineAdministration(
                                        database,
                                        actorSnapshot.team(),
                                        NO_TERRITORY_OWNERSHIP,
                                        commandClock)
                                .prepareCapture(
                                        actorPlayerId,
                                        actorSnapshot.team().teamId(),
                                        requestId,
                                        actorSnapshot.position(),
                                        reason)))
                .thenCompose(preparation -> {
                    if (preparation instanceof FacilityBaselineCaptureReplay replay) {
                        return CompletableFuture.completedFuture(replay.baseline());
                    }
                    FacilityBaselineCaptureWork work =
                            (FacilityBaselineCaptureWork) preparation;
                    return onServer(current, () ->
                                    snapshotFacilityBaselineServerState(
                                            actor,
                                            work.facility(),
                                            work.accountingInterface()))
                            .thenCompose(captured -> current.writer.submitDatabase(database ->
                                    facilityBaselineAdministration(
                                                    database,
                                                    captured.team(),
                                                    captured.ownership(),
                                                    commandClock)
                                            .completeCapture(
                                                    work,
                                                    captured.snapshot())));
                });
    }

    CompletableFuture<FacilityAccountingBaseline> activateFacilityAccountingBaseline(
            ServerPlayer actor, String requestId, String reason) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> snapshotFacilityBaselineActor(actor))
                .thenCompose(actorSnapshot -> current.writer.submitDatabase(database ->
                        facilityBaselineAdministration(
                                        database,
                                        actorSnapshot.team(),
                                        NO_TERRITORY_OWNERSHIP,
                                        commandClock)
                                .prepareActivation(
                                        actorPlayerId,
                                        actorSnapshot.team().teamId(),
                                        requestId,
                                        actorSnapshot.position(),
                                        reason)))
                .thenCompose(preparation -> {
                    if (preparation instanceof FacilityBaselineActivationReplay replay) {
                        return CompletableFuture.completedFuture(replay.baseline());
                    }
                    FacilityBaselineActivationWork work =
                            (FacilityBaselineActivationWork) preparation;
                    return onServer(current, () ->
                                    snapshotFacilityBaselineServerState(
                                            actor,
                                            work.facility(),
                                            work.accountingInterface()))
                            .thenCompose(captured -> current.writer.submitDatabase(database ->
                                    facilityBaselineAdministration(
                                                    database,
                                                    captured.team(),
                                                    captured.ownership(),
                                                    commandClock)
                                            .completeActivation(
                                                    work,
                                                    captured.snapshot())));
                });
    }

    CompletableFuture<FacilityAccountingStatus> facilityAccountingStatus(
            ServerPlayer actor) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> snapshotFacilityBaselineActor(actor))
                .thenCompose(actorSnapshot -> current.writer.submitDatabase(database ->
                        facilityBaselineAdministration(
                                        database,
                                        actorSnapshot.team(),
                                        NO_TERRITORY_OWNERSHIP,
                                        commandClock)
                                .status(
                                        actorPlayerId,
                                        actorSnapshot.team().teamId(),
                                        actorSnapshot.position())));
    }

    CompletableFuture<ProductionInventoryExport> exportProductionInventory(
            ServerPlayer actor,
            String requestId,
            int slot,
            int count,
            ProductionInventoryExportKind kind,
            String reason) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> snapshotFacilityAccountingInterface(actor))
                .thenCompose(snapshot -> current.writer.submitDatabase(database -> {
                    FacilityAdministration administration = facilityAdministrationForExport(
                            database, snapshot.team(), commandClock);
                    administration.requireAuthority(
                            actorPlayerId, snapshot.team().teamId());
                    var facility = database.registeredFacilityAt(
                            snapshot.position().dimensionId(),
                            snapshot.position().blockX(),
                            snapshot.position().blockZ());
                    if (facility == null
                            || !facility.ftbTeamId().equals(snapshot.team().teamId())) {
                        throw new SecurityException(
                                "Export target is not inside the actor's exact Facility");
                    }
                    var accountingInterface = database.facilityAccountingInterface(
                            facility.facilityId());
                    if (accountingInterface == null
                            || accountingInterface.blockX() != snapshot.position().blockX()
                            || accountingInterface.blockY() != snapshot.position().blockY()
                            || accountingInterface.blockZ() != snapshot.position().blockZ()
                            || !accountingInterface.dimensionId().equals(
                                    snapshot.position().dimensionId())) {
                        throw new SecurityException(
                                "Export target is not the exact registered Facility Accounting Interface");
                    }
                    var replay = database.productionInventoryExport(
                            FacilityAdministration.SERVICE_IDENTITY.value(), requestId);
                    ProductionInventoryExportRequest request =
                            new ProductionInventoryExportRequest(
                                    FacilityAdministration.SERVICE_IDENTITY,
                                    requestId,
                                    accountingInterface.interfaceId(),
                                    actorPlayerId,
                                    slot,
                                    count,
                                    kind,
                                    "facility-interface:" + accountingInterface.interfaceId(),
                                    replay == null
                                            ? commandClock.millis()
                                            : replay.exportedAtEpochMillis(),
                                    reason);
                    return new ProductionExportPreparation(
                            request,
                            snapshot.position(),
                            replay);
                }))
                .thenCompose(preparation -> {
                    if (preparation.replay() != null) {
                        return current.writer.submitDatabase(database ->
                                new ProductionInventoryExportCoordinator(
                                                database,
                                                commandClock,
                                                ignored -> {
                                                    throw new IllegalStateException(
                                                            "Replay must not invoke the export source");
                                                })
                                        .export(preparation.request()));
                    }
                    return onServer(current, () -> extractProductionExportStack(
                                    actor, preparation.position(), slot, count))
                            .thenCompose(actual -> current.writer.submitDatabase(database ->
                                    new ProductionInventoryExportCoordinator(
                                                    database,
                                                    commandClock,
                                                    ignored -> actual)
                                            .export(preparation.request())));
                })
                .thenCompose(export -> current.writer.submitDatabase(database -> {
                    new ProductionMarginalReturnContributionRegistry(
                                    database,
                                    new ProductionValueAddedCalculator(
                                            new GlobalReferencePriceRegistry(
                                                    database, commandClock)),
                                    new ProductionIndustryAssignmentRegistry(
                                            database, commandClock),
                                    new ProductionMarginalReturnPolicyRegistry(
                                            database, commandClock),
                                    commandClock)
                            .bindExport(export.exportId());
                    scheduleNationalStrengthRecalculation(current);
                    return export;
                }));
    }

    /**
     * Trusted server-side seam for an adapter that has just observed a real destination Receipt.
     * The destination interface is derived from the actor's real targeted Civic block; only the
     * already-persisted Receipt ID crosses into the writer, and no item or timestamp matching is
     * attempted.
     */
    CompletableFuture<ProductionInventoryExportHandoff> recordProductionInventoryExportHandoff(
            ServerPlayer actor,
            String requestId,
            UUID exportId,
            UUID destinationReceiptId,
            String reason) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Clock commandClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return onServer(current, () -> snapshotFacilityAccountingInterface(actor))
                .thenCompose(snapshot -> current.writer.submitDatabase(database -> {
                    FacilityAdministration administration = facilityAdministrationForExport(
                            database, snapshot.team(), commandClock);
                    administration.requireAuthority(
                            actorPlayerId, snapshot.team().teamId());
                    var facility = database.registeredFacilityAt(
                            snapshot.position().dimensionId(),
                            snapshot.position().blockX(),
                            snapshot.position().blockZ());
                    if (facility == null || !facility.ftbTeamId().equals(snapshot.team().teamId())) {
                        throw new SecurityException(
                                "Handoff target is not inside the actor's exact Facility");
                    }
                    var accountingInterface = database.facilityAccountingInterface(
                            facility.facilityId());
                    if (accountingInterface == null
                            || accountingInterface.blockX() != snapshot.position().blockX()
                            || accountingInterface.blockY() != snapshot.position().blockY()
                            || accountingInterface.blockZ() != snapshot.position().blockZ()
                            || !accountingInterface.dimensionId().equals(
                                    snapshot.position().dimensionId())) {
                        throw new SecurityException(
                                "Handoff target is not the exact registered Facility Accounting Interface");
                    }
                    return new ProductionInventoryExportHandoffRegistry(
                            database, commandClock)
                            .record(new RecordProductionInventoryExportHandoff(
                                    FacilityAdministration.SERVICE_IDENTITY,
                                    requestId,
                                    exportId,
                                    accountingInterface.interfaceId(),
                                    destinationReceiptId,
                                    actorPlayerId,
                                    reason));
                }));
    }

    private static ProductionStack extractProductionExportStack(
            ServerPlayer actor,
            FacilityAccountingInterfacePosition position,
            int slot,
            int count) {
        BlockEntity blockEntity = actor.level().getBlockEntity(new BlockPos(
                position.blockX(), position.blockY(), position.blockZ()));
        if (!(blockEntity instanceof FacilityAccountingInterfaceBlockEntity accountingInterface)
                || !actor.level().dimension().location().toString()
                        .equals(position.dimensionId())) {
            throw new SecurityException(
                    "Export target is not a real server Facility Accounting Interface");
        }
        ItemStack removed = accountingInterface.removeItem(slot, count);
        if (removed.isEmpty() || removed.getCount() != count) {
            throw new IllegalStateException(
                    "Facility Accounting Interface does not contain the requested export quantity");
        }
        ItemStack identity = removed.copyWithCount(1);
        return new ProductionStack(
                BuiltInRegistries.ITEM.getKey(removed.getItem()).toString(),
                identity.saveOptional(actor.level().registryAccess()).toString(),
                removed.getCount());
    }

    private static FacilityAdministration facilityAdministrationForExport(
            CivicDatabase database, NationTeam team, Clock operationClock) {
        NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
        NationRegistry nations = new NationRegistry(database, teams);
        CitizenshipRegistry citizenships = new CitizenshipRegistry(
                database, CITIZENSHIP_TRANSFER_COOLDOWN, operationClock);
        CitizenshipCorrectionGraceRegistry corrections =
                new CitizenshipCorrectionGraceRegistry(database, operationClock);
        FtbTeamsNationProvider provider = new FtbTeamsNationProvider(
                nations, citizenships, corrections, teams);
        EffectiveTerritoryFacilityAuthority territory =
                new EffectiveTerritoryFacilityAuthority(
                        database,
                        new EffectiveTerritoryQuery(
                                new TerritoryMaintenanceRegistry(database, operationClock),
                                NO_TERRITORY_OWNERSHIP),
                        operationClock);
        return new FacilityAdministration(
                nations,
                provider,
                new NationFiscalAuthorityRegistry(database, provider, operationClock),
                new RegisteredFacilityRegistry(
                        database,
                        territory,
                        operationClock,
                        REGISTERED_FACILITY_MAX_SCOPE_CHUNKS),
                new FacilityAccountingInterfaceRegistry(database, operationClock));
    }

    private FacilityBaselineActorSnapshot snapshotFacilityBaselineActor(ServerPlayer actor) {
        NationTeam team = requireActorTeam(actor.getUUID());
        BlockPos position = actor.blockPosition();
        return new FacilityBaselineActorSnapshot(
                team,
                new FacilityAccountingInterfacePosition(
                        actor.level().dimension().location().toString(),
                        position.getX(),
                        position.getY(),
                        position.getZ()));
    }

    private FacilityBaselineServerSnapshot snapshotFacilityBaselineServerState(
            ServerPlayer actor,
            RegisteredFacility facility,
            FacilityAccountingInterface accountingInterface) {
        NationTeam team = requireActorTeam(actor.getUUID());
        if (!team.teamId().equals(facility.ftbTeamId())) {
            throw new SecurityException(
                    "Facility actor changed FTB Team before Baseline capture");
        }
        Map<TerritoryClaimPosition, UUID> currentClaims =
                FtbChunksAdapter.live().claimsForTeam(team.teamId()).stream()
                        .collect(Collectors.toUnmodifiableMap(
                                claim -> new TerritoryClaimPosition(
                                        claim.dimension().location().toString(),
                                        claim.chunkPos().x,
                                        claim.chunkPos().z),
                                claim -> team.teamId()));
        TerritoryOwnershipSource ownership =
                (dimensionId, chunkX, chunkZ) -> Optional.ofNullable(currentClaims.get(
                        new TerritoryClaimPosition(dimensionId, chunkX, chunkZ)));
        FacilityAccountingBaselineSnapshot snapshot =
                new ServerFacilityAccountingBaselineSnapshotSource(actor.getServer())
                        .capture(facility, accountingInterface);
        return new FacilityBaselineServerSnapshot(snapshot, team, ownership);
    }

    private FacilityBaselineAdministration facilityBaselineAdministration(
            CivicDatabase database,
            NationTeam team,
            TerritoryOwnershipSource ownership,
            Clock operationClock) {
        NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
        NationRegistry nations = new NationRegistry(database, teams);
        CitizenshipRegistry citizenships = new CitizenshipRegistry(
                database, CITIZENSHIP_TRANSFER_COOLDOWN, operationClock);
        CitizenshipCorrectionGraceRegistry corrections =
                new CitizenshipCorrectionGraceRegistry(database, operationClock);
        FtbTeamsNationProvider provider = new FtbTeamsNationProvider(
                nations, citizenships, corrections, teams);
        EffectiveTerritoryFacilityAuthority territory =
                new EffectiveTerritoryFacilityAuthority(
                        database,
                        new EffectiveTerritoryQuery(
                                new TerritoryMaintenanceRegistry(database, operationClock),
                                ownership),
                        operationClock);
        return new FacilityBaselineAdministration(
                database,
                nations,
                provider,
                new NationFiscalAuthorityRegistry(database, provider, operationClock),
                new RegisteredFacilityRegistry(
                        database,
                        territory,
                        operationClock,
                        REGISTERED_FACILITY_MAX_SCOPE_CHUNKS),
                new FacilityAccountingInterfaceRegistry(database, operationClock),
                territory,
                operationClock);
    }

    private FacilityInterfaceSnapshot snapshotFacilityAccountingInterface(
            ServerPlayer actor) {
        NationTeam team = requireActorTeam(actor.getUUID());
        HitResult hit = actor.pick(5.0D, 0.0F, false);
        List<BlockPos> candidates = new ArrayList<>();
        if (hit instanceof BlockHitResult blockHit
                && hit.getType() == HitResult.Type.BLOCK) {
            candidates.add(blockHit.getBlockPos());
        }
        candidates.add(actor.blockPosition());
        candidates.add(actor.blockPosition().below());
        for (BlockPos candidate : candidates.stream().distinct().toList()) {
            BlockEntity blockEntity = actor.level().getBlockEntity(candidate);
            if (blockEntity instanceof FacilityAccountingInterfaceBlockEntity
                    && blockEntity.getType()
                            == CivicContent.FACILITY_ACCOUNTING_INTERFACE_BLOCK_ENTITY.get()
                    && actor.level().getBlockState(candidate).getBlock()
                            == CivicContent.FACILITY_ACCOUNTING_INTERFACE.get()) {
                return new FacilityInterfaceSnapshot(
                        team,
                        new FacilityAccountingInterfacePosition(
                                actor.level().dimension().location().toString(),
                                candidate.getX(),
                                candidate.getY(),
                                candidate.getZ()));
            }
        }
        throw new SecurityException(
                "Look at or stand on a real Civic Facility Accounting Interface");
    }

    private FacilityRegistrationSnapshot snapshotFacilityRegistration(ServerPlayer actor) {
        NationTeam team = requireActorTeam(actor.getUUID());
        BlockPos position = actor.blockPosition();
        String dimensionId = actor.level().dimension().location().toString();
        TerritoryClaimPosition claim = new TerritoryClaimPosition(
                dimensionId,
                position.getX() >> 4,
                position.getZ() >> 4);
        boolean owned = FtbChunksAdapter.live().claimsForTeam(team.teamId()).stream()
                .anyMatch(candidate -> candidate.dimension().location().toString()
                                .equals(dimensionId)
                        && candidate.chunkPos().x == claim.chunkX()
                        && candidate.chunkPos().z == claim.chunkZ());
        if (!owned) {
            throw new SecurityException(
                    "Your FTB Team does not own the current Facility Claim");
        }
        TerritoryOwnershipSource ownership =
                (requestedDimension, requestedX, requestedZ) ->
                        requestedDimension.equals(claim.dimensionId())
                                        && requestedX == claim.chunkX()
                                        && requestedZ == claim.chunkZ()
                                ? Optional.of(team.teamId())
                                : Optional.empty();
        return new FacilityRegistrationSnapshot(
                team,
                new FacilityCorePosition(
                        dimensionId,
                        position.getX(),
                        position.getY(),
                        position.getZ()),
                claim,
                ownership);
    }

    CompletableFuture<MintBatchStatus> mintBatchStatusForActor(
            UUID actorPlayerId, UUID requestedBatchId) {
        return submitDatabase(database -> {
            var batch = database.mintBatchesForActor(actorPlayerId).stream()
                    .filter(candidate -> requestedBatchId == null
                            || candidate.batchId().equals(requestedBatchId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            requestedBatchId == null
                                    ? "No Mint Batches are recorded for your player identity"
                                    : "Unknown Mint Batch for your player identity"));
            return new MintBatchStatus(
                    batch,
                    database.mintBatchIssuanceOperationByBatch(batch.batchId()),
                    database.latestMintRecoveryIncidentByBatch(batch.batchId()));
        });
    }

    CompletableFuture<MintBatchStatus> mintBatchStatus(UUID batchId) {
        return submitDatabase(database -> {
            var batch = database.mintBatch(batchId);
            if (batch == null) {
                throw new IllegalArgumentException("Unknown Mint Batch " + batchId);
            }
            return new MintBatchStatus(
                    batch,
                    database.mintBatchIssuanceOperationByBatch(batch.batchId()),
                    database.latestMintRecoveryIncidentByBatch(batch.batchId()));
        });
    }

    CompletableFuture<Integer> pendingMintRecoveryCount() {
        return submitDatabase(database ->
                database.recoverableMintBatches().size()
                        + database.recoverableMintIssuanceOperations().size());
    }

    void triggerMintRecovery() {
        RuntimeState current = state;
        if (current == null) {
            throw new IllegalStateException("Civic server runtime is not active");
        }
        scheduleMintBatchRecovery(current);
    }

    private CompletableFuture<MintBatch> startNewMintBatch(
            RuntimeState current,
            ServerPlayer actor,
            UUID actorPlayerId,
            String requestId,
            UUID mintId,
            UUID periodId,
            long amountMinorUnits,
            MintStartFacts facts,
            NationTeam team,
            Clock commandClock) {
        return onServer(current, () -> snapshotMintStart(actor, facts, team))
                .thenCompose(snapshot -> current.writer.submitDatabase(database -> {
                    MintBatchCoordinator coordinator = mintCoordinator(
                            database, snapshot.team(), snapshot.ownership(), current, commandClock);
                    return new PreparedMintTake(
                            coordinator.prepareExternal(new PrepareMintBatch(
                                    MintFiscalServiceProvisioner.SERVICE_IDENTITY,
                                    requestId,
                                    mintId,
                                    periodId,
                                    actorPlayerId,
                                    MoneyAmount.ofMinorUnits(amountMinorUnits),
                                    snapshot.materials(),
                                    "Player starts authorized Mint Batch")),
                            snapshot.team(),
                            snapshot.ownership());
                }))
                .thenCompose(prepared -> onServer(current, () -> {
                    prepared.pending().apply(custodyFor(current, actor));
                    return prepared;
                }))
                .thenCompose(prepared -> current.writer.submitDatabase(database ->
                        mintCoordinator(
                                        database,
                                        prepared.team(),
                                        prepared.ownership(),
                                        current,
                                        commandClock)
                                .confirmExternal(prepared.pending())));
    }

    CompletableFuture<MintBatch> cancelMintBatch(
            ServerPlayer actor, UUID batchId, String requestId, String reason) {
        RuntimeState current = requireState();
        UUID actorPlayerId = actor.getUUID();
        Instant commandTime = clock.instant();
        Clock commandClock = Clock.fixed(commandTime, ZoneOffset.UTC);
        return onServer(current, () -> requireActorTeam(actorPlayerId))
                .thenCompose(team -> current.writer.submitDatabase(database -> {
                    MintBatchCoordinator coordinator = mintCoordinator(
                            database, team, NO_TERRITORY_OWNERSHIP, current, commandClock);
                    CancelMintBatch request = new CancelMintBatch(
                            MintFiscalServiceProvisioner.SERVICE_IDENTITY,
                            requestId,
                            batchId,
                            actorPlayerId,
                            reason);
                    var replay = coordinator.findCompletedCancellation(request);
                    if (replay.isPresent()) {
                        return new MintCancellationReplay(replay.get());
                    }
                    return new PreparedMintReturn(
                            coordinator.prepareCancellationExternal(request),
                            team);
                }))
                .thenCompose(preparation -> preparation instanceof MintCancellationReplay replay
                        ? CompletableFuture.completedFuture(replay.batch())
                        : cancelPreparedMintBatch(
                                current, actor, (PreparedMintReturn) preparation, commandClock));
    }

    void recoverMintBatchesNowForGameTest() {
        RuntimeState current = state;
        if (current != null) {
            scheduleMintBatchRecovery(current);
        }
    }

    void recoverTreasuryWithdrawalsNowForGameTest() {
        RuntimeState current = state;
        if (current != null) {
            scheduleTreasuryWithdrawalRecovery(current);
        }
    }

    void recoverPermanentDestructionsNowForGameTest() {
        RuntimeState current = state;
        if (current != null) {
            schedulePermanentDestructionRecovery(current);
        }
    }

    void expireFiscalObjectsNowForGameTest() {
        RuntimeState current = state;
        if (current != null) {
            scheduleFiscalExpiry(current);
        }
    }

    private CompletableFuture<MintBatch> cancelPreparedMintBatch(
            RuntimeState current,
            ServerPlayer actor,
            PreparedMintReturn prepared,
            Clock commandClock) {
        return onServer(current, () -> {
                    prepared.pending().apply(custodyFor(current, actor));
                    return prepared;
                })
                .thenCompose(confirmedPreparation -> current.writer.submitDatabase(database ->
                        mintCoordinator(
                                        database,
                                        confirmedPreparation.team(),
                                        NO_TERRITORY_OWNERSHIP,
                                        current,
                                        commandClock)
                                .confirmExternal(confirmedPreparation.pending())));
    }

    private RuntimeState requireState() {
        RuntimeState current = state;
        if (current == null) {
            throw new IllegalStateException("Civic server runtime is not active");
        }
        return current;
    }

    private MintStartFacts mintStartFacts(
            CivicDatabase database, UUID mintId, long amountMinorUnits) {
        if (mintId == null || amountMinorUnits <= 0L) {
            throw new IllegalArgumentException("Mint Batch start values are invalid");
        }
        var mint = database.registeredMint(mintId);
        if (mint == null) {
            throw new IllegalArgumentException("Unknown Registered Mint " + mintId);
        }
        return new MintStartFacts(
                mint,
                database.mintRecipeIngredients(mint.recipeVersionId()),
                amountMinorUnits);
    }

    private MintStartSnapshot snapshotMintStart(
            ServerPlayer player, MintStartFacts facts, NationTeam team) {
        ResourceLocation dimensionId = ResourceLocation.tryParse(facts.mint().dimensionId());
        if (dimensionId == null) {
            throw new IllegalStateException("Registered Mint dimension is invalid");
        }
        int chunkX = Math.floorDiv(facts.mint().blockX(), 16);
        int chunkZ = Math.floorDiv(facts.mint().blockZ(), 16);
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
        var claim = FtbChunksAdapter.live().find(dimension, new ChunkPos(chunkX, chunkZ))
                .filter(found -> found.teamId().equals(team.teamId()))
                .orElseThrow(() -> new SecurityException(
                        "Your exact Nation FTB Team does not own the Registered Mint Claim"));
        TerritoryOwnershipSource ownership = (requestedDimension, requestedX, requestedZ) ->
                requestedDimension.equals(claim.dimension().location().toString())
                                && requestedX == claim.chunkPos().x
                                && requestedZ == claim.chunkPos().z
                        ? Optional.of(claim.teamId())
                        : Optional.empty();
        return new MintStartSnapshot(
                team,
                ownership,
                MintMaterialManifestSelector.select(
                        player.getInventory(), facts.ingredients(), facts.amountMinorUnits()));
    }

    private static ServerPlayerMintMaterialCustody custodyFor(
            RuntimeState current, ServerPlayer actor) {
        return new ServerPlayerMintMaterialCustody(
                current.server,
                playerId -> playerId.equals(actor.getUUID()) ? actor : null,
                () -> {});
    }

    private NationTeam requireActorTeam(UUID actorPlayerId) {
        FtbNationTeamDirectory teams = FtbNationTeamDirectory.live();
        return teams.findEffectiveTeamForPlayer(actorPlayerId)
                .or(() -> teams.findOwnedTeamForPlayer(actorPlayerId))
                .orElseThrow(() -> new SecurityException(
                        "Mint Batch actor has no current non-player FTB Team"));
    }

    private MintBatchCoordinator mintCoordinator(
            CivicDatabase database,
            NationTeam team,
            TerritoryOwnershipSource ownership,
            RuntimeState current,
            Clock operationClock) {
        NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
        NationRegistry nations = new NationRegistry(database, teams);
        CitizenshipRegistry citizenships = new CitizenshipRegistry(
                database, CITIZENSHIP_TRANSFER_COOLDOWN, operationClock);
        CitizenshipCorrectionGraceRegistry corrections =
                new CitizenshipCorrectionGraceRegistry(database, operationClock);
        FtbTeamsNationProvider provider = new FtbTeamsNationProvider(
                nations, citizenships, corrections, teams);
        FiscalAuthorization authorization = new FiscalAuthorization(database);
        org.civiceconomy.nation.NationId nationId = nations.findByFtbTeam(team.teamId())
                .orElseThrow(() -> new SecurityException(
                        "Your FTB Team is not bound to a formal Nation"))
                .nationId();
        new MintFiscalServiceProvisioner(authorization).ensureAuthorized(nationId);
        return new MintBatchCoordinator(
                database,
                authorization.openSession(MintFiscalServiceProvisioner.SERVICE_IDENTITY),
                new NationFiscalAuthorityRegistry(database, provider, operationClock),
                new EffectiveTerritoryMintAuthority(
                        database,
                        new EffectiveTerritoryQuery(
                                new TerritoryMaintenanceRegistry(database, operationClock),
                                ownership),
                        operationClock),
                current.mintCustody,
                operationClock);
    }

    private static <T> CompletableFuture<T> onServer(
            RuntimeState current, java.util.concurrent.Callable<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        current.server.execute(() -> {
            try {
                result.complete(operation.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private void scheduleDatabaseBackupRecovery(RuntimeState current) {
        if (!current.databaseBackupQueued.compareAndSet(false, true)) {
            return;
        }
        current.writer
                .submitDatabase(ignored -> {
                    int recovered = current.backups.recoverPending().size();
                    StoredDatabaseBackupOperation startup = current.backups.create(
                            "civic-backup-lifecycle",
                            "startup-" + current.startedAtEpochMillis,
                            "Lifecycle startup database backup");
                    return new DatabaseBackupLifecycleResult(recovered, startup);
                })
                .whenComplete((result, failure) -> {
                    current.databaseBackupQueued.set(false);
                    if (failure == null) {
                        LOGGER.info(
                                "Civic database backup lifecycle recovered {} operation(s) and published {}",
                                result.recovered(),
                                result.backup().fileName());
                    } else {
                        LOGGER.error(
                                "Civic database backup startup recovery failed; the durable operation remains recoverable",
                                failure);
                    }
                });
    }

    private void scheduleLifecycleDatabaseBackup(RuntimeState current, String kind) {
        if (!current.databaseBackupQueued.compareAndSet(false, true)) {
            return;
        }
        long interval = Math.floorDiv(clock.millis(), Duration.ofMinutes(30).toMillis());
        current.writer
                .submitDatabase(ignored -> current.backups.create(
                        "civic-backup-lifecycle",
                        kind + "-" + interval,
                        "Lifecycle scheduled database backup"))
                .whenComplete((backup, failure) -> {
                    current.databaseBackupQueued.set(false);
                    if (failure == null) {
                        LOGGER.info("Civic lifecycle database backup published {}", backup.fileName());
                    } else {
                        LOGGER.error(
                                "Civic lifecycle database backup failed; the durable operation remains recoverable",
                                failure);
                    }
                });
    }

    private void queueShutdownDatabaseBackup(RuntimeState current) {
        current.writer
                .submitDatabase(ignored -> current.backups.create(
                        "civic-backup-lifecycle",
                        "shutdown-" + current.startedAtEpochMillis,
                        "Lifecycle shutdown database backup"))
                .whenComplete((backup, failure) -> {
                    if (failure == null) {
                        LOGGER.info("Civic shutdown database backup published {}", backup.fileName());
                    } else {
                        LOGGER.error(
                                "Civic shutdown database backup failed; the durable operation remains recoverable",
                                failure);
                    }
                });
    }

    CompletableFuture<PreparedTerritoryClaim> prepareTerritoryClaim(
            NationTeam team,
            UUID actorPlayerId,
            String requestId,
            String dimensionId,
            int chunkX,
            int chunkZ,
            int currentClaimedChunks) {
        Instant commandTime = clock.instant();
        Clock commandClock = Clock.fixed(commandTime, ZoneOffset.UTC);
        NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
        RuntimeState current = state;
        if (current == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Civic server runtime is not active"));
        }
        var freeReplay = freeClaimAuthorizationMirror.requireActiveReplay(
                requestId, commandTime);
        if (freeReplay.isPresent()) {
            FreeClaimAuthorization authorization = freeReplay.orElseThrow();
            TerritoryClaimTarget target = authorization.target();
            if (!target.nationId().equals(nationForFtbTeam(team.teamId()))
                    || !target.ftbTeamId().equals(team.teamId())
                    || !target.actorPlayerId().equals(actorPlayerId)
                    || !target.dimensionId().equals(dimensionId)
                    || target.chunkX() != chunkX
                    || target.chunkZ() != chunkZ) {
                return CompletableFuture.failedFuture(
                        new org.civiceconomy.fiscal.IdempotencyConflictException(
                                TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                                requestId));
            }
            return CompletableFuture.completedFuture(
                    new PreparedTerritoryClaim(null, authorization));
        }
        return current.writer.submitDatabase(database -> {
                    NationRegistry nations = new NationRegistry(database, teams);
                    var nation = nations.findByFtbTeam(team.teamId())
                            .orElseThrow(() -> new SecurityException(
                                    "Your FTB Team is not bound to a formal Nation"));
                    var citizenships = new org.civiceconomy.nation.CitizenshipRegistry(
                            database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock);
                    var provider = new org.civiceconomy.nation.FtbTeamsNationProvider(
                            nations,
                            citizenships,
                            new org.civiceconomy.nation.CitizenshipCorrectionGraceRegistry(
                                    database, commandClock),
                            teams);
                    var actorNation = provider.findForCitizen(actorPlayerId)
                            .orElseThrow(() -> new SecurityException(
                                    "Free Claim actor has no effective formal Citizenship"));
                    if (!actorNation.nationId().equals(nation.nationId())) {
                        throw new SecurityException(
                                "Free Claim actor does not belong to the exact Nation");
                    }
                    var population = new org.civiceconomy.nation.NationPopulationCalculator(
                                    citizenships,
                                    new org.civiceconomy.nation.CitizenshipCorrectionGraceRegistry(
                                            database, commandClock),
                                    new org.civiceconomy.nation.OnlineTimeLedger(database),
                                    NATION_APPLICATION_EVIDENCE_WINDOW,
                                    Duration.ofHours(8))
                            .calculate(nation.nationId(), commandTime);
                    var allocation = new TerritoryFreeAllocationPolicyRegistry(
                                    database,
                                    commandClock,
                                    TerritoryFreeAllocationPolicyVersion.defaultPolicy(0, 0))
                            .current(commandTime)
                            .policy()
                            .calculate(population);
                    TerritoryClaimPermitRegistry permits = new TerritoryClaimPermitRegistry(
                            database,
                            new CommittedTerritoryPrepaymentVerifier(
                                    database, TerritoryFiscalServiceProvisioner.CLEARING_ACCOUNT_ID),
                            commandClock);
                    var replay = permits.findByRequest(
                            TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                            requestId + ":permit");
                    if (replay.isPresent()) {
                        TerritoryClaimPermit permit = replay.orElseThrow();
                        if (!permit.nationId().equals(nation.nationId())
                                || !permit.ftbTeamId().equals(team.teamId())
                                || !permit.actorPlayerId().equals(actorPlayerId)
                                || !permit.dimensionId().equals(dimensionId)
                                || permit.chunkX() != chunkX
                                || permit.chunkZ() != chunkZ) {
                            throw new org.civiceconomy.fiscal.IdempotencyConflictException(
                                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                                    requestId);
                        }
                        return new PreparedTerritoryClaim(permit, null);
                    }
                    int pendingClaims = Math.toIntExact(permits.readyPermits().stream()
                            .filter(permit -> permit.nationId().equals(nation.nationId()))
                            .count());
                    pendingClaims = Math.addExact(
                            pendingClaims,
                            freeClaimAuthorizationMirror.pendingCount(
                                    nation.nationId(), commandTime));
                    int quotedClaimedChunks = Math.addExact(
                            currentClaimedChunks, pendingClaims);
                    var quote = new TerritoryExpansionPricingPolicyRegistry(
                                    database,
                                    commandClock,
                                    TerritoryExpansionPricingPolicyVersion.defaultPolicy(0, 0))
                            .current(commandTime)
                            .policy()
                            .quote(allocation, quotedClaimedChunks, 1, commandTime);
                    if (quote.prepayment().minorUnits() <= 0L) {
                        TerritoryClaimTarget target = new TerritoryClaimTarget(
                                nation.nationId(),
                                team.teamId(),
                                actorPlayerId,
                                dimensionId,
                                chunkX,
                                chunkZ);
                        FreeClaimAuthorization freeClaim = new FreeClaimAuthorization(
                                UUID.randomUUID(),
                                requestId,
                                target,
                                commandTime.plus(Duration.ofMinutes(2)));
                        return new PreparedTerritoryClaim(null, freeClaim);
                    }
                    var authorization = new FiscalAuthorization(database);
                    var treasury = new org.civiceconomy.fiscal.AccountId(
                            "nation:" + nation.nationId().value() + ":treasury");
                    new TerritoryFiscalServiceProvisioner(authorization)
                            .ensureAuthorized(treasury);
                    var session = authorization.openSession(
                            TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY);
                    TerritoryClaimPermit permit = new TerritoryClaimPrepaymentCoordinator(
                                    nations,
                                    new org.civiceconomy.nation.NationFiscalAuthorityRegistry(
                                            database, provider, commandClock),
                                    LightmansCurrencyTerritoryClearingAccountProvisioner.forLevel(
                                            current.server.overworld()),
                                    FiscalLedger.authorized(
                                            database,
                                            LightmansCurrencyAccountBalances.live(
                                                    current.server.overworld()),
                                            session),
                                    PaymentCoordinator.authorized(
                                            database,
                                            LightmansCurrencyPayments.live(
                                                    current.server.overworld()),
                                            session),
                                    permits,
                                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                                    TerritoryFiscalServiceProvisioner.CLEARING_ACCOUNT_ID,
                                    commandClock)
                            .prepare(new PrepareTerritoryClaimPrepayment(
                                    requestId,
                                    nation.nationId(),
                                    team.teamId(),
                                    actorPlayerId,
                                    dimensionId,
                                    chunkX,
                                    chunkZ,
                                    allocation.totalFreeChunks(),
                                    quote,
                                    commandTime.plus(Duration.ofMinutes(2))));
                    return new PreparedTerritoryClaim(permit, null);
                })
                .thenApply(prepared -> {
                    TerritoryClaimPermit permit = prepared.permit();
                    if (permit != null && permit.state()
                            == org.civiceconomy.territory.TerritoryClaimPermitState.READY) {
                        publishTerritoryClaimPermit(permit);
                    }
                    if (prepared.freeClaim() != null) {
                        nationByFtbTeam.put(
                                prepared.freeClaim().target().ftbTeamId(),
                                prepared.freeClaim().target().nationId());
                        freeClaimAuthorizationMirror.publish(prepared.freeClaim());
                    }
                    return prepared;
                });
    }

    CompletableFuture<TerritoryClaimPermit> cancelTerritoryClaim(
            UUID actorPlayerId, UUID permitId, String requestId, String reason) {
        Instant commandTime = clock.instant();
        Clock commandClock = Clock.fixed(commandTime, ZoneOffset.UTC);
        RuntimeState current = state;
        if (current == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Civic server runtime is not active"));
        }
        return current.writer.submitDatabase(database -> {
                    var authorization = new FiscalAuthorization(database);
                    var session = authorization.openSession(
                            TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY);
                    TerritoryClaimPermitRegistry permits = new TerritoryClaimPermitRegistry(
                            database,
                            new CommittedTerritoryPrepaymentVerifier(
                                    database, TerritoryFiscalServiceProvisioner.CLEARING_ACCOUNT_ID),
                            commandClock);
                    return new TerritoryClaimPermitCompensationCoordinator(
                                    database,
                                    PaymentCoordinator.authorized(
                                            database,
                                            LightmansCurrencyPayments.live(
                                                    current.server.overworld()),
                                            session),
                                    permits,
                                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                                    commandClock)
                            .cancel(new CancelTerritoryClaimPermit(
                                    requestId, permitId, actorPlayerId, reason));
                })
                .thenApply(permit -> {
                    removeTerritoryClaimPermit(permit);
                    return permit;
                });
    }

    CompletableFuture<org.civiceconomy.territory.TerritoryMaintenanceRestorationPayment>
            restoreTerritory(
                    NationTeam team,
                    UUID actorPlayerId,
                    String requestId,
                    String dimensionId,
                    int chunkX,
                    int chunkZ) {
        Instant commandTime = clock.instant();
        Clock commandClock = Clock.fixed(commandTime, ZoneOffset.UTC);
        RuntimeState current = state;
        if (current == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Civic server runtime is not active"));
        }
        var observedClaims = FtbChunksAdapter.live().claimsForTeam(team.teamId()).stream()
                .map(claim -> new TerritoryMaintenanceObservedClaim(
                        new TerritoryClaimPosition(
                                claim.dimension().location().toString(),
                                claim.chunkPos().x,
                                claim.chunkPos().z),
                        claim.forceLoadRequested()))
                .toList();
        TerritoryClaimPosition target =
                new TerritoryClaimPosition(dimensionId, chunkX, chunkZ);
        if (observedClaims.stream().noneMatch(claim -> claim.position().equals(target))) {
            return CompletableFuture.failedFuture(new SecurityException(
                    "Your FTB Team does not own the exact Restoration Claim"));
        }
        NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
        return current.writer.submitDatabase(database -> {
                    NationRegistry nations = new NationRegistry(database, teams);
                    var nation = nations.findByFtbTeam(team.teamId())
                            .orElseThrow(() -> new SecurityException(
                                    "Your FTB Team is not bound to a formal Nation"));
                    var citizenships = new org.civiceconomy.nation.CitizenshipRegistry(
                            database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock);
                    var corrections =
                            new org.civiceconomy.nation.CitizenshipCorrectionGraceRegistry(
                                    database, commandClock);
                    var provider = new org.civiceconomy.nation.FtbTeamsNationProvider(
                            nations, citizenships, corrections, teams);
                    var restorations = new org.civiceconomy.territory
                            .TerritoryMaintenanceRestorationRegistry(database, commandClock);
                    var replay = restorations.find(
                            TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY, requestId);
                    org.civiceconomy.territory.PrepareTerritoryMaintenanceRestoration
                            restorationRequest;
                    if (replay.isPresent()) {
                        var existingRestoration = replay.orElseThrow();
                        restorationRequest = new org.civiceconomy.territory
                                .PrepareTerritoryMaintenanceRestoration(
                                        TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                                        requestId,
                                        nation.nationId(),
                                        team.teamId(),
                                        actorPlayerId,
                                        dimensionId,
                                        chunkX,
                                        chunkZ,
                                        existingRestoration.policyId(),
                                        existingRestoration.nextFullCycleStartsAt(),
                                        new org.civiceconomy.territory
                                                .TerritoryMaintenanceRestorationQuote(
                                                        existingRestoration.totalDue(),
                                                        existingRestoration.cooldownEndsAt()),
                                        existingRestoration.reason());
                    } else {
                        var policies = new TerritoryMaintenancePolicyRegistry(
                                database, commandClock);
                        TerritoryMaintenancePolicyVersion currentPolicy = policies
                                .current(commandTime)
                                .orElseThrow(() -> new IllegalStateException(
                                        "Territory Maintenance policy is not configured"));
                        TerritoryMaintenanceCycleWindow currentWindow =
                                new TerritoryMaintenanceCycleSchedule()
                                        .current(currentPolicy, commandTime)
                                        .orElseThrow(() -> new IllegalStateException(
                                                "Territory Maintenance Cycle is not active"));
                        Instant nextCycleStartsAt = currentWindow.endsAt();
                        TerritoryMaintenancePolicyVersion nextPolicy = policies
                                .current(nextCycleStartsAt)
                                .orElseThrow(() -> new IllegalStateException(
                                        "Next Territory Maintenance policy is unavailable"));
                        var storedCapital = database.nationCapital(nation.nationId().value());
                        if (storedCapital == null) {
                            throw new IllegalStateException(
                                    "Territory Restoration requires a persistent Capital");
                        }
                        Capital capital = new Capital(
                                storedCapital.dimensionId(),
                                storedCapital.chunkX(),
                                storedCapital.chunkZ());
                        var population = new org.civiceconomy.nation.NationPopulationCalculator(
                                        citizenships,
                                        corrections,
                                        new org.civiceconomy.nation.OnlineTimeLedger(database),
                                        NATION_APPLICATION_EVIDENCE_WINDOW,
                                        Duration.ofHours(8))
                                .calculate(nation.nationId(), commandTime);
                        TerritoryFreeAllocation allocation =
                                new TerritoryFreeAllocationPolicyRegistry(
                                                database,
                                                commandClock,
                                                TerritoryFreeAllocationPolicyVersion.defaultPolicy(
                                                        0, 0))
                                        .current(nextCycleStartsAt)
                                        .policy()
                                        .calculate(population);
                        var maintenance = new TerritoryMaintenanceRegistry(database, commandClock);
                        var history = maintenance.restorationHistory(
                                nation.nationId(), team.teamId(), commandTime.plusMillis(1L));
                        var targetHistory = history.get(target);
                        if (targetHistory == null || !targetHistory.previouslySuspended()) {
                            throw new IllegalStateException(
                                    "Territory Restoration requires the exact latest suspended Claim");
                        }
                        targetHistory.cooldownEndsAt()
                                .filter(commandTime::isBefore)
                                .ifPresent(eligibleAt -> {
                                    throw new org.civiceconomy.territory
                                            .TerritoryMaintenanceRestorationCooldownException(
                                                    eligibleAt);
                                });
                        var targetSnapshot = new TerritoryMaintenanceAssessmentPlanner()
                                .plan(
                                        nation.nationId(),
                                        team.teamId(),
                                        capital,
                                        observedClaims,
                                        allocation,
                                        nextPolicy,
                                        nextCycleStartsAt,
                                        Map.of())
                                .stream()
                                .filter(claim -> claim.dimensionId().equals(dimensionId)
                                        && claim.chunkX() == chunkX
                                        && claim.chunkZ() == chunkZ)
                                .findFirst()
                                .orElseThrow();
                        var quote = new org.civiceconomy.territory
                                .TerritoryMaintenanceRestorationQuote(
                                        org.civiceconomy.fiscal.MoneyAmount.ofMinorUnits(
                                                Math.addExact(
                                                        targetSnapshot
                                                                .maintenanceDueMinorUnits(),
                                                        nextPolicy
                                                                .restorationFeeMinorUnits())),
                                        commandTime.plus(nextPolicy.restorationCooldown()));
                        restorationRequest = new org.civiceconomy.territory
                                .PrepareTerritoryMaintenanceRestoration(
                                        TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                                        requestId,
                                        nation.nationId(),
                                        team.teamId(),
                                        actorPlayerId,
                                        dimensionId,
                                        chunkX,
                                        chunkZ,
                                        nextPolicy.policyId(),
                                        nextCycleStartsAt,
                                        quote,
                                        "Player-authorized out-of-Cycle Restoration");
                    }
                    var treasury = new org.civiceconomy.fiscal.AccountId(
                            "nation:" + nation.nationId().value() + ":treasury");
                    var authorization = new FiscalAuthorization(database);
                    new TerritoryFiscalServiceProvisioner(authorization)
                            .ensureAuthorized(treasury);
                    var session = authorization.openSession(
                            TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY);
                    var payment = new org.civiceconomy.integration.lightmanscurrency
                                    .TerritoryMaintenanceRestorationPaymentCoordinator(
                                            nations,
                                            new org.civiceconomy.nation
                                                    .NationFiscalAuthorityRegistry(
                                                            database, provider, commandClock),
                                            FiscalLedger.authorized(
                                                    database,
                                                    LightmansCurrencyAccountBalances.live(
                                                            current.server.overworld()),
                                                    session),
                                            PaymentCoordinator.authorized(
                                                    database,
                                                    LightmansCurrencyPayments.live(
                                                            current.server.overworld()),
                                                    session),
                                            PermanentDestructionCoordinator.live(
                                                    database,
                                                    session,
                                                    commandClock,
                                                    current.server.overworld()),
                                            restorations)
                            .restore(restorationRequest);
                    return payment;
                })
                .thenApply(payment -> {
                    scheduleTerritoryForceLoadRestrictionRefresh(current);
                    return payment;
                });
    }

    org.civiceconomy.nation.NationId nationForFtbTeam(UUID ftbTeamId) {
        return nationByFtbTeam.get(ftbTeamId);
    }

    boolean territoryClaimAuthorizationReady() {
        return territoryClaimAuthorizationReady.get();
    }

    boolean territoryForceLoadRestrictionsReady() {
        return territoryForceLoadRestrictionsReady.get();
    }

    boolean territoryForceLoadBlocked(UUID ftbTeamId, TerritoryClaimPosition position) {
        return territoryForceLoadRestrictionMirror.blocks(ftbTeamId, position);
    }

    void refreshTerritoryForceLoadRestrictions() {
        RuntimeState current = state;
        if (current != null) {
            scheduleTerritoryForceLoadRestrictionRefresh(current);
        }
    }

    private void scheduleTerritoryForceLoadRestrictionRefresh(RuntimeState current) {
        if (state != current
                || !current.territoryForceLoadRestrictionRefreshQueued.compareAndSet(false, true)) {
            return;
        }
        Clock refreshClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        current.writer.submitDatabase(database ->
                        new TerritoryForceLoadRestrictionRegistry(database, refreshClock).active())
                .whenComplete((restrictions, failure) -> {
                    current.territoryForceLoadRestrictionRefreshQueued.set(false);
                    if (state != current) {
                        return;
                    }
                    if (failure != null) {
                        LOGGER.error(
                                "Territory Force-load Restriction mirror refresh failed closed",
                                failure);
                        territoryForceLoadRestrictionsReady.set(false);
                        return;
                    }
                    territoryForceLoadRestrictionMirror.replaceAll(restrictions);
                    territoryForceLoadRestrictionsReady.set(true);
                });
    }

    private void scheduleTerritoryPermitMirrorRefresh(RuntimeState current) {
        Clock refreshClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        current.writer.submitDatabase(database -> {
                    TerritoryClaimPermitRegistry permits = new TerritoryClaimPermitRegistry(
                            database,
                            new CommittedTerritoryPrepaymentVerifier(
                                    database,
                                    TerritoryFiscalServiceProvisioner.CLEARING_ACCOUNT_ID),
                            refreshClock);
                    Map<UUID, org.civiceconomy.nation.NationId> bindings =
                            new HashMap<>();
                    for (RegisteredNation nation
                            : new NationRegistry(database, NO_TEAM_LOOKUPS).registeredNations()) {
                        bindings.put(nation.ftbTeamId(), nation.nationId());
                    }
                    return new TerritoryPermitMirrorSnapshot(
                            permits.readyPermits(), Map.copyOf(bindings));
                })
                .whenComplete((snapshot, failure) -> {
                    if (failure != null) {
                        LOGGER.error(
                                "Territory Claim Permit mirror refresh failed closed", failure);
                        territoryClaimPermitMirror.replaceAll(List.of());
                        nationByFtbTeam.clear();
                        territoryClaimAuthorizationReady.set(false);
                        return;
                    }
                    territoryClaimPermitMirror.replaceAll(snapshot.permits());
                    nationByFtbTeam.clear();
                    nationByFtbTeam.putAll(snapshot.nationByFtbTeam());
                    territoryClaimAuthorizationReady.set(true);
                });
    }

    void publishTerritoryClaimPermit(
            org.civiceconomy.territory.TerritoryClaimPermit permit) {
        nationByFtbTeam.compute(permit.ftbTeamId(), (ignored, existing) -> {
            if (existing != null && !existing.equals(permit.nationId())) {
                throw new IllegalStateException(
                        "FTB Team is already mirrored for a different Nation");
            }
            return permit.nationId();
        });
        territoryClaimPermitMirror.publish(permit);
    }

    void removeTerritoryClaimPermit(
            org.civiceconomy.territory.TerritoryClaimPermit permit) {
        territoryClaimPermitMirror.remove(permit);
    }

    private void queueTerritoryClaimPermitConsumption(
            org.civiceconomy.territory.TerritoryClaimPermitConsumptionIntent intent) {
        RuntimeState current = state;
        if (current == null) {
            LOGGER.error(
                    "Territory Claim Permit consumption was acquired without an active runtime: {}",
                    intent.permitId());
            return;
        }
        current.writer.submitDatabase(database -> {
                    TerritoryClaimPermitRegistry permits = new TerritoryClaimPermitRegistry(
                            database,
                            new CommittedTerritoryPrepaymentVerifier(
                                    database,
                                    TerritoryFiscalServiceProvisioner.CLEARING_ACCOUNT_ID),
                            Clock.fixed(intent.claimedAt(), ZoneOffset.UTC));
                    return permits.consume(new ConsumeTerritoryClaimPermit(
                            TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                            "ftb-after-claim:" + intent.permitId(),
                            intent.permitId(),
                            intent.target().nationId(),
                            intent.target().ftbTeamId(),
                            intent.target().actorPlayerId(),
                            intent.target().dimensionId(),
                            intent.target().chunkX(),
                            intent.target().chunkZ()));
                })
                .whenComplete((consumed, failure) -> {
                    if (failure != null) {
                        LOGGER.error(
                                "Territory Claim Permit durable consumption failed closed after FTB claim",
                                failure);
                    }
                });
    }

    private void scheduleNationApplicationExpiry(RuntimeState current) {
        if (!current.nationApplicationExpiryQueued.compareAndSet(false, true)) {
            return;
        }
        Clock scanClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        current.writer.submitDatabase(database -> new NationApplicationExpiryProcessor(
                        database, scanClock, NATION_APPLICATION_EVIDENCE_WINDOW)
                .expireDue())
                .whenComplete((expired, failure) -> {
                    current.nationApplicationExpiryQueued.set(false);
                    if (failure != null) {
                        LOGGER.error("Automatic Nation Application expiry failed closed", failure);
                    } else if (!expired.isEmpty()) {
                        LOGGER.info("Automatically expired {} Nation Application(s)", expired.size());
                    }
                });
    }

    private void scheduleFiscalExpiry(RuntimeState current) {
        if (state != current || !current.fiscalExpiryQueued.compareAndSet(false, true)) {
            return;
        }
        Clock scanClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
                current.writer.submitDatabase(database -> new FiscalExpiryResult(
                        new EscrowExpiryProcessor(database, scanClock).expireDue().size(),
                        new BudgetDraftExpiryProcessor(database, scanClock).expireDue().size(),
                        new FiscalBillExpiryProcessor(database, scanClock).expireDue().size(),
                        new BudgetDisbursementApprovalRegistry(database, scanClock)
                                .expirePending().size()))
                .whenComplete((result, failure) -> {
                    current.fiscalExpiryQueued.set(false);
                    if (failure != null) {
                        LOGGER.error("Automatic fiscal expiry failed closed", failure);
                    } else if (result.escrows() > 0
                            || result.budgetDrafts() > 0
                            || result.fiscalBills() > 0
                            || result.budgetDisbursements() > 0) {
                        LOGGER.info(
                                "Automatically expired {} Escrow(s), {} Budget draft(s), {} Fiscal Bill(s), and {} Budget Disbursement approval(s)",
                                result.escrows(), result.budgetDrafts(), result.fiscalBills(),
                                result.budgetDisbursements());
                    }
                });
    }

    void triggerBudgetDisbursementRecovery() {
        scheduleBudgetDisbursementRecovery(requireState());
    }

    private void scheduleBudgetDisbursementRecovery(RuntimeState current) {
        if (state != current
                || !current.budgetDisbursementRecoveryQueued.compareAndSet(false, true)) {
            return;
        }
        Clock recoveryClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        current.writer.submitDatabase(database ->
                        new BudgetDisbursementApprovalRegistry(database, recoveryClock)
                                .approvedWithoutPayment(
                                        NationBudgetDisbursementApprovalCoordinator
                                                .SERVICE_IDENTITY)
                                .stream()
                                .map(BudgetDisbursementApproval::approvalRequestId)
                                .toList())
                .whenComplete((approvalRequestIds, failure) -> {
                    if (failure != null) {
                        current.budgetDisbursementRecoveryQueued.set(false);
                        LOGGER.error(
                                "Budget Disbursement approval recovery scan failed closed",
                                failure);
                    } else {
                        recoverBudgetDisbursements(
                                current, approvalRequestIds, 0, 0);
                    }
                });
    }

    private void recoverBudgetDisbursements(
            RuntimeState current,
            List<UUID> approvalRequestIds,
            int index,
            int recovered) {
        if (state != current) {
            current.budgetDisbursementRecoveryQueued.set(false);
            return;
        }
        if (index >= approvalRequestIds.size()) {
            scanIncompleteBudgetDisbursements(current, recovered);
            return;
        }
        UUID approvalRequestId = approvalRequestIds.get(index);
        current.writer.submitDatabase(database -> {
                    BudgetDisbursementPaymentCoordinator coordinator =
                            new BudgetDisbursementPaymentCoordinator(
                                    database,
                                    LightmansCurrencyPayments.live(
                                            current.server.overworld()));
                    return new PreparedBudgetDisbursementRecovery(
                            coordinator,
                            coordinator.prepare(approvalRequestId));
                })
                .thenCompose(prepared -> onServer(current, () -> {
                    prepared.coordinator().applyExternal(prepared.payment());
                    return prepared;
                }))
                .thenCompose(prepared -> current.writer.submitDatabase(database ->
                        prepared.coordinator().commit(prepared.payment())))
                .whenComplete((transaction, failure) -> {
                    if (failure != null) {
                        LOGGER.warn(
                                "Budget Disbursement approval {} remains pending recovery",
                                approvalRequestId,
                                failure);
                    }
                    recoverBudgetDisbursements(
                            current,
                            approvalRequestIds,
                            index + 1,
                            recovered + (failure == null ? 1 : 0));
                });
    }

    private void scanIncompleteBudgetDisbursements(
            RuntimeState current, int recoveredApproved) {
        current.writer.submitDatabase(database ->
                        database.incompleteBudgetDisbursementPayments(
                                        NationBudgetDisbursementApprovalCoordinator
                                                .SERVICE_IDENTITY
                                                .value())
                                .stream()
                                .map(org.civiceconomy.persistence.StoredPaymentTransaction
                                        ::transactionId)
                                .toList())
                .whenComplete((transactionIds, failure) -> {
                    if (failure != null) {
                        current.budgetDisbursementRecoveryQueued.set(false);
                        LOGGER.error(
                                "Incomplete Budget Disbursement payment recovery scan failed closed",
                                failure);
                    } else {
                        recoverIncompleteBudgetDisbursements(
                                current,
                                transactionIds,
                                0,
                                recoveredApproved,
                                0);
                    }
                });
    }

    private void recoverIncompleteBudgetDisbursements(
            RuntimeState current,
            List<UUID> transactionIds,
            int index,
            int recoveredApproved,
            int recoveredIncomplete) {
        if (state != current) {
            current.budgetDisbursementRecoveryQueued.set(false);
            return;
        }
        if (index >= transactionIds.size()) {
            current.budgetDisbursementRecoveryQueued.set(false);
            if (recoveredApproved > 0 || recoveredIncomplete > 0) {
                LOGGER.info(
                        "Recovered {} approved-without-Payment and {} incomplete Budget Disbursement payment(s)",
                        recoveredApproved,
                        recoveredIncomplete);
            }
            return;
        }
        UUID transactionId = transactionIds.get(index);
        current.writer.submitDatabase(database -> {
                    BudgetDisbursementPaymentCoordinator coordinator =
                            new BudgetDisbursementPaymentCoordinator(
                                    database,
                                    LightmansCurrencyPayments.live(
                                            current.server.overworld()));
                    PreparedBudgetDisbursementPayment payment =
                            coordinator.recoverableIncomplete().stream()
                                    .filter(candidate -> candidate.transaction()
                                            .transactionId()
                                            .equals(transactionId))
                                    .findFirst()
                                    .orElseThrow(() -> new IllegalStateException(
                                            "Budget Disbursement payment is no longer recoverable "
                                                    + transactionId));
                    return new PreparedBudgetDisbursementRecovery(
                            coordinator, payment);
                })
                .thenCompose(prepared -> onServer(current, () -> {
                    prepared.coordinator().applyExternal(prepared.payment());
                    BudgetDisbursementProcessRestartDrill.crashAfterExternalApplied(
                            current.server, prepared.payment());
                    return prepared;
                }))
                .thenCompose(prepared -> current.writer.submitDatabase(database -> {
                    prepared.coordinator()
                            .recordExternalAppliedRecovery(prepared.payment());
                    BudgetDisbursementProcessRestartDrill.crashAfterExternalRecorded(
                            current.server, prepared.payment());
                    return prepared.coordinator().commitRecovery(prepared.payment());
                }))
                .whenComplete((transaction, failure) -> {
                    if (failure != null) {
                        LOGGER.warn(
                                "Incomplete Budget Disbursement payment {} remains pending recovery",
                                transactionId,
                                failure);
                    }
                    recoverIncompleteBudgetDisbursements(
                            current,
                            transactionIds,
                            index + 1,
                            recoveredApproved,
                            recoveredIncomplete + (failure == null ? 1 : 0));
                });
    }

    private void scheduleCitizenshipReconciliation(RuntimeState current) {
        if (!current.citizenshipReconciliationQueued.compareAndSet(false, true)) {
            return;
        }
        Clock scanClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        current.writer.submitDatabase(database ->
                        new NationRegistry(database, NO_TEAM_LOOKUPS).registeredNations())
                .whenComplete((nations, listFailure) -> {
                    if (listFailure != null) {
                        finishCitizenshipReconciliation(current, null, listFailure);
                        return;
                    }
                    current.server.execute(() -> {
                        try {
                            snapshotAndReconcile(current, nations, scanClock);
                        } catch (RuntimeException failure) {
                            finishCitizenshipReconciliation(current, null, failure);
                        }
                    });
                });
    }

    private void snapshotAndReconcile(
            RuntimeState current, List<RegisteredNation> nations, Clock scanClock) {
        if (state != current) {
            current.citizenshipReconciliationQueued.set(false);
            return;
        }
        Map<UUID, NationTeam> snapshots = new HashMap<>();
        FtbNationTeamDirectory liveTeams = FtbNationTeamDirectory.live();
        int unavailable = 0;
        for (RegisteredNation nation : nations) {
            Optional<NationTeam> team = liveTeams.find(nation.ftbTeamId());
            if (team.isPresent()) {
                snapshots.put(nation.ftbTeamId(), team.orElseThrow());
            } else {
                unavailable++;
            }
        }
        if (unavailable > 0) {
            LOGGER.warn(
                    "Skipped Citizenship reconciliation for {} Nation(s) with unavailable FTB Team facts",
                    unavailable);
        }
        NationTeamDirectory snapshotDirectory = snapshotDirectory(snapshots);
        current.writer.submitDatabase(database -> {
                    int started = 0;
                    int restored = 0;
                    int ended = 0;
                    CitizenshipReconciler reconciler = new CitizenshipReconciler(
                            database,
                            snapshotDirectory,
                            CITIZENSHIP_CORRECTION_GRACE,
                            CITIZENSHIP_TRANSFER_COOLDOWN,
                            scanClock);
                    for (RegisteredNation nation : nations) {
                        if (!snapshots.containsKey(nation.ftbTeamId())) {
                            continue;
                        }
                        CitizenshipReconciliationResult result =
                                reconciler.reconcile(nation.nationId());
                        started += result.startedGraceCount();
                        restored += result.restoredCount();
                        ended += result.endedCitizenshipCount();
                    }
                    return new CitizenshipReconciliationResult(started, restored, ended);
                })
                .whenComplete((result, failure) ->
                        finishCitizenshipReconciliation(current, result, failure));
    }

    private static NationTeamDirectory snapshotDirectory(Map<UUID, NationTeam> snapshots) {
        Map<UUID, NationTeam> immutable = Map.copyOf(snapshots);
        return new NationTeamDirectory() {
            @Override
            public Optional<NationTeam> find(UUID teamId) {
                return Optional.ofNullable(immutable.get(teamId));
            }

            @Override
            public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
                return immutable.values().stream()
                        .filter(team -> team.citizens().contains(playerId))
                        .findFirst();
            }
        };
    }

    private static void finishCitizenshipReconciliation(
            RuntimeState current,
            CitizenshipReconciliationResult result,
            Throwable failure) {
        current.citizenshipReconciliationQueued.set(false);
        if (failure != null) {
            LOGGER.error("Automatic Citizenship reconciliation failed closed", failure);
        } else if (result != null
                && (result.startedGraceCount() > 0
                        || result.restoredCount() > 0
                        || result.endedCitizenshipCount() > 0)) {
            LOGGER.info(
                    "Citizenship reconciliation started {} grace(s), restored {}, ended {}",
                    result.startedGraceCount(),
                    result.restoredCount(),
                    result.endedCitizenshipCount());
        }
    }

    private void scheduleTerritoryPermitCompensation(RuntimeState current) {
        if (!current.territoryPermitCompensationQueued.compareAndSet(false, true)) {
            return;
        }
        Clock scanClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        current.writer.submitDatabase(database -> {
                    boolean hasWork = !database.incompleteTerritoryClaimPermitCompensations()
                                    .isEmpty()
                            || !database.dueTerritoryClaimPermits(scanClock.millis()).isEmpty();
                    if (!hasWork) {
                        return new TerritoryPermitCompensationResult(0, 0);
                    }
                    var session = new FiscalAuthorization(database)
                            .openSession(TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY);
                    TerritoryClaimPermitRegistry permits = new TerritoryClaimPermitRegistry(
                            database,
                            new CommittedTerritoryPrepaymentVerifier(
                                    database,
                                    TerritoryFiscalServiceProvisioner.CLEARING_ACCOUNT_ID),
                            scanClock);
                    TerritoryClaimPermitCompensationCoordinator coordinator =
                            new TerritoryClaimPermitCompensationCoordinator(
                                    database,
                                    PaymentCoordinator.authorized(
                                            database,
                                            LightmansCurrencyPayments.live(current.server.overworld()),
                                            session),
                                    permits,
                                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                                    scanClock);
                    int recovered = coordinator.recoverIncomplete().size();
                    int expired = coordinator.expireDue().size();
                    return new TerritoryPermitCompensationResult(recovered, expired);
                })
                .whenComplete((result, failure) -> {
                    current.territoryPermitCompensationQueued.set(false);
                    if (failure != null) {
                        LOGGER.error(
                                "Automatic Territory Claim Permit compensation failed closed",
                                failure);
                    } else if (result.recovered() > 0 || result.expired() > 0) {
                        LOGGER.info(
                                "Recovered {} and expired {} Territory Claim Permit compensation(s)",
                                result.recovered(),
                                result.expired());
                        scheduleTerritoryPermitMirrorRefresh(current);
                    }
                });
    }

    private void schedulePermanentDestructionRecovery(RuntimeState current) {
        if (state != current
                || !current.permanentDestructionRecoveryQueued.compareAndSet(false, true)) {
            return;
        }
        Clock recoveryClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        current.writer.submitDatabase(database -> {
                    List<PreparedPermanentDestruction> pending = new ArrayList<>();
                    appendPermanentDestructionRecoveries(
                            pending,
                            database,
                            current,
                            recoveryClock,
                            TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY);
                    appendPermanentDestructionRecoveries(
                            pending,
                            database,
                            current,
                            recoveryClock,
                            PermanentDestructionFiscalServiceProvisioner.SERVICE_IDENTITY);
                    return List.copyOf(pending);
                })
                .whenComplete((pending, failure) -> {
                    if (failure != null) {
                        current.permanentDestructionRecoveryQueued.set(false);
                        LOGGER.error(
                                "Automatic Permanent Destruction recovery scan failed closed",
                                failure);
                    } else if (pending == null || pending.isEmpty()) {
                        current.permanentDestructionRecoveryQueued.set(false);
                    } else {
                        recoverPermanentDestructions(current, pending, 0, 0);
                    }
                });
    }

    private static void appendPermanentDestructionRecoveries(
            List<PreparedPermanentDestruction> destination,
            CivicDatabase database,
            RuntimeState current,
            Clock recoveryClock,
            ServiceIdentity serviceIdentity) {
        if (database.pendingPermanentDestructionOperations(serviceIdentity.value())
                .isEmpty()) {
            return;
        }
        var session = new FiscalAuthorization(database).openSession(serviceIdentity);
        PermanentDestructionCoordinator coordinator = PermanentDestructionCoordinator.live(
                database,
                session,
                recoveryClock,
                current.server.overworld(),
                PermanentDestructionProcessRestartDrill.observer(current.server));
        coordinator.pending().stream()
                .map(operation -> new PreparedPermanentDestruction(coordinator, operation))
                .forEach(destination::add);
    }

    private void recoverPermanentDestructions(
            RuntimeState current,
            List<PreparedPermanentDestruction> pending,
            int index,
            int recovered) {
        if (state != current || index >= pending.size()) {
            current.permanentDestructionRecoveryQueued.set(false);
            if (state == current && recovered > 0) {
                LOGGER.info("Replayed {} Permanent Destruction operation(s)", recovered);
            }
            return;
        }
        PreparedPermanentDestruction prepared = pending.get(index);
        onServer(current, () -> {
                    prepared.coordinator().applyExternal(prepared.operation());
                    return prepared;
                })
                .thenCompose(applied -> current.writer.submitDatabase(database -> {
                    applied.coordinator().recordExternalApplied(applied.operation());
                    return applied.coordinator().commit(applied.operation());
                }))
                .whenComplete((committed, failure) -> {
                    if (failure != null) {
                        LOGGER.warn(
                                "Permanent Destruction {} remains pending",
                                prepared.operation().operationId(),
                                failure);
                        recoverPermanentDestructions(
                                current, pending, index + 1, recovered);
                    } else {
                        recoverPermanentDestructions(
                                current, pending, index + 1, recovered + 1);
                    }
                });
    }

    private void scheduleTreasuryWithdrawalRecovery(RuntimeState current) {
        if (state != current
                || !current.treasuryWithdrawalRecoveryQueued.compareAndSet(false, true)) {
            return;
        }
        Clock recoveryClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        current.writer.submitDatabase(database -> {
                    new TreasuryWithdrawalApprovalRegistry(database, recoveryClock)
                            .expirePending();
                    List<org.civiceconomy.persistence.StoredTreasuryWithdrawalOperation>
                            pending = database.pendingTreasuryWithdrawalOperations(
                                    TreasuryWithdrawalFiscalServiceProvisioner
                                            .SERVICE_IDENTITY
                                            .value());
                    boolean hasApproved = !database
                            .approvedTreasuryWithdrawalApprovalsWithoutOperation(
                                    TreasuryWithdrawalFiscalServiceProvisioner
                                            .SERVICE_IDENTITY
                                            .value())
                            .isEmpty();
                    if (pending.isEmpty() && !hasApproved) {
                        return null;
                    }
                    FiscalAuthorization authorization = new FiscalAuthorization(database);
                    var session = authorization.openSession(
                            TreasuryWithdrawalFiscalServiceProvisioner.SERVICE_IDENTITY);
                    TreasuryWithdrawalCoordinator coordinator =
                            TreasuryWithdrawalCoordinator.live(
                                    database,
                                    session,
                                    recoveryClock,
                                    current.server.overworld(),
                                    current.server.getPlayerList()::getPlayer,
                                    TreasuryWithdrawalProcessRestartDrill.observer(
                                            current.server));
                    List<TreasuryWithdrawal> withdrawals = new ArrayList<>(
                            coordinator.prepareApprovedPending());
                    withdrawals.addAll(
                            pending.stream()
                                    .map(operation -> new TreasuryWithdrawal(
                                            operation.withdrawalId(),
                                            new ServiceIdentity(operation.serviceIdentity()),
                                            operation.requestId(),
                                            operation.approvalRequestId(),
                                            new org.civiceconomy.nation.NationId(
                                                    operation.nationId()),
                                            new AccountId(operation.sourceAccount()),
                                            operation.actorPlayerId(),
                                            MoneyAmount.ofMinorUnits(
                                                    operation.amountMinorUnits()),
                                            operation.reason(),
                                            operation.state(),
                                            Instant.ofEpochMilli(
                                                    operation.preparedAtEpochMillis()),
                                            null))
                                    .toList());
                    return new TreasuryWithdrawalRecovery(
                            coordinator,
                            List.copyOf(withdrawals));
                })
                .whenComplete((recovery, failure) -> {
                    if (failure != null) {
                        current.treasuryWithdrawalRecoveryQueued.set(false);
                        LOGGER.error(
                                "Treasury Withdrawal recovery scan failed closed", failure);
                    } else if (recovery == null) {
                        current.treasuryWithdrawalRecoveryQueued.set(false);
                    } else {
                        recoverTreasuryWithdrawals(current, recovery, 0);
                    }
                });
    }

    private void recoverTreasuryWithdrawals(
            RuntimeState current, TreasuryWithdrawalRecovery recovery, int index) {
        if (state != current || index >= recovery.withdrawals().size()) {
            current.treasuryWithdrawalRecoveryQueued.set(false);
            if (state == current && index > 0) {
                LOGGER.info("Replayed {} Treasury Withdrawal operation(s)", index);
            }
            return;
        }
        TreasuryWithdrawal withdrawal = recovery.withdrawals().get(index);
        onServer(current, () -> {
                    recovery.coordinator().applyExternal(withdrawal);
                    return withdrawal;
                })
                .thenCompose(applied -> current.writer.submitDatabase(database ->
                        recovery.coordinator().commit(applied)))
                .whenComplete((committed, failure) -> {
                    if (failure != null) {
                        current.treasuryWithdrawalRecoveryQueued.set(false);
                        LOGGER.warn(
                                "Treasury Withdrawal {} remains pending for player {}",
                                withdrawal.withdrawalId(),
                                withdrawal.actorPlayerId(),
                                failure);
                    } else {
                        recoverTreasuryWithdrawals(current, recovery, index + 1);
                    }
                });
    }

    private void scheduleMintBatchRecovery(RuntimeState current) {
        if (state != current || !current.mintBatchRecoveryQueued.compareAndSet(false, true)) {
            return;
        }
        Clock recoveryClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        current.writer.submitDatabase(database -> new PendingMintRecoveryScan(
                        new MintBatchRecovery(database, recoveryClock).pendingExternal(),
                        new MintIssuanceRecovery(database, recoveryClock).pendingExternal()))
                .whenComplete((scan, failure) -> {
                    if (failure != null) {
                        current.mintBatchRecoveryQueued.set(false);
                        LOGGER.error("Mint Batch recovery scan failed closed", failure);
                        return;
                    }
                    recoverMintBatchOperations(
                            current, scan.materials(), recoveryClock, 0, 0, () ->
                                    recoverMintIssuanceOperations(
                                            current, scan.issuances(), recoveryClock, 0, 0));
                });
    }

    private void recoverMintBatchOperations(
            RuntimeState current,
            List<PendingMintMaterialOperation> pending,
            Clock recoveryClock,
            int index,
            int recovered,
            Runnable next) {
        if (state != current || index >= pending.size()) {
            if (recovered > 0) {
                LOGGER.info("Recovered {} Mint Batch material operation(s)", recovered);
            }
            next.run();
            return;
        }
        PendingMintMaterialOperation operation = pending.get(index);
        onServer(current, () -> {
                    operation.apply(current.mintCustody);
                    return operation;
                })
                .thenCompose(applied -> current.writer.submitDatabase(database ->
                        new MintBatchRecovery(database, recoveryClock).confirmExternal(applied)))
                .whenComplete((batch, failure) -> {
                    if (failure != null) {
                        LOGGER.warn(
                                "Mint Batch {} recovery remains pending; the material owner may be offline",
                                operation.batchId(),
                                failure);
                    }
                    recoverMintBatchOperations(
                            current,
                            pending,
                            recoveryClock,
                            index + 1,
                            failure == null ? recovered + 1 : recovered,
                            next);
                });
    }

    private void recoverMintIssuanceOperations(
            RuntimeState current,
            List<PendingMintIssuanceStep> pending,
            Clock recoveryClock,
            int index,
            int recovered) {
        if (state != current || index >= pending.size()) {
            current.mintBatchRecoveryQueued.set(false);
            if (recovered > 0) {
                LOGGER.info("Recovered {} Mint issuance step(s)", recovered);
            }
            return;
        }
        PendingMintIssuanceStep operation = pending.get(index);
        onServer(current, () -> {
                    operation.apply(
                            LightmansCurrencyMintIssuances.live(current.server.overworld()),
                            current.mintCustody);
                    MintProcessRestartDrill.crashAfterAppliedStep(
                            current.server, operation);
                    return operation;
                })
                .thenCompose(applied -> current.writer.submitDatabase(database ->
                        new MintIssuanceRecovery(database, recoveryClock)
                                .confirmExternal(applied)))
                .whenComplete((confirmed, failure) -> {
                    if (failure != null) {
                        LOGGER.warn(
                                "Mint issuance {} recovery remains pending; material consumption "
                                        + "may require the source player online",
                                operation.operationId(),
                                failure);
                        current.writer.submitDatabase(database ->
                                        new MintIssuanceRecovery(database, recoveryClock)
                                                .recordFailure(operation, failure))
                                .whenComplete((incident, incidentFailure) -> {
                                    if (incidentFailure != null) {
                                        LOGGER.error(
                                                "Mint issuance {} recovery failure evidence could not be persisted",
                                                operation.operationId(),
                                                incidentFailure);
                                    }
                                    recoverMintIssuanceOperations(
                                            current,
                                            pending,
                                            recoveryClock,
                                            index + 1,
                                            recovered);
                                });
                        return;
                    }
                    recoverMintIssuanceOperations(
                            current,
                            pending,
                            recoveryClock,
                            index + 1,
                            failure == null ? recovered + 1 : recovered);
                });
    }

    void scheduleTerritoryMaintenanceAssessment() {
        RuntimeState current = state;
        if (current != null) {
            scheduleTerritoryMaintenanceAssessment(current);
        }
    }

    private void scheduleTerritoryMaintenanceAssessment(RuntimeState current) {
        if (!current.territoryMaintenanceAssessmentQueued.compareAndSet(false, true)) {
            return;
        }
        Instant scanTime = clock.instant();
        Clock scanClock = Clock.fixed(scanTime, ZoneOffset.UTC);
        current.writer.submitDatabase(database -> prepareTerritoryMaintenanceAssessment(
                        database, scanTime, scanClock))
                .whenComplete((preparation, failure) -> {
                    if (failure != null) {
                        finishTerritoryMaintenanceAssessment(current, null, failure);
                    } else if (preparation == null) {
                        current.territoryMaintenanceAssessmentQueued.set(false);
                    } else if (preparation.recover()) {
                        recoverTerritoryMaintenanceAssessment(current, preparation, scanClock);
                    } else {
                        current.server.execute(() -> snapshotTerritoryMaintenanceClaims(
                                current, preparation, scanClock));
                    }
                });
    }

    private AutomaticMaintenancePreparation prepareTerritoryMaintenanceAssessment(
            CivicDatabase database, Instant scanTime, Clock scanClock) {
        TerritoryMaintenancePolicyRegistry policies =
                new TerritoryMaintenancePolicyRegistry(database, scanClock);
        TerritoryMaintenancePolicyVersion effectivePolicy;
        TerritoryMaintenanceCycleWindow window;
        var activeCycle = database.territoryMaintenanceCycleAt(scanTime.toEpochMilli());
        if (activeCycle != null) {
            if (!activeCycle.requestId().startsWith("automatic-maintenance:")
                    || !activeCycle.requestId().endsWith(":cycle")) {
                LOGGER.warn(
                        "Automatic Territory Maintenance is waiting for non-automatic Cycle {} to end",
                        activeCycle.cycleId());
                return null;
            }
            String requestId = activeCycle.requestId().substring(
                    0, activeCycle.requestId().length() - ":cycle".length());
            window = new TerritoryMaintenanceCycleWindow(
                    requestId,
                    Instant.ofEpochMilli(activeCycle.startsAtEpochMillis()),
                    Instant.ofEpochMilli(activeCycle.endsAtEpochMillis()));
            var batch = database.territoryMaintenanceAssessmentBatch(activeCycle.cycleId());
            if (batch != null) {
                return new AutomaticMaintenancePreparation(null, window, List.of(), true);
            }
            effectivePolicy = policies.find(policyIdFromAutomaticRequest(requestId))
                    .orElseThrow(() -> new IllegalStateException(
                            "Automatic Territory Maintenance Cycle policy is missing"));
        } else {
            Optional<TerritoryMaintenancePolicyVersion> policy = policies.current(scanTime);
            if (policy.isEmpty()) {
                return null;
            }
            effectivePolicy = policy.orElseThrow();
            window = new TerritoryMaintenanceCycleSchedule()
                    .current(effectivePolicy, scanTime)
                    .orElse(null);
            if (window == null) {
                return null;
            }
        }
        var citizenships = new org.civiceconomy.nation.CitizenshipRegistry(
                database, CITIZENSHIP_TRANSFER_COOLDOWN, scanClock);
        var grace = new org.civiceconomy.nation.CitizenshipCorrectionGraceRegistry(
                database, scanClock);
        var populations = new org.civiceconomy.nation.NationPopulationCalculator(
                citizenships,
                grace,
                new org.civiceconomy.nation.OnlineTimeLedger(database),
                NATION_APPLICATION_EVIDENCE_WINDOW,
                Duration.ofHours(8));
        var allocationPolicy = new TerritoryFreeAllocationPolicyRegistry(
                        database,
                        scanClock,
                        TerritoryFreeAllocationPolicyVersion.defaultPolicy(0, 0))
                .current(scanTime)
                .policy();
        var maintenance = new TerritoryMaintenanceRegistry(database, scanClock);
        List<AutomaticNationMaintenanceContext> nations = new java.util.ArrayList<>();
        int missingCapitals = 0;
        for (RegisteredNation nation
                : new NationRegistry(database, NO_TEAM_LOOKUPS).registeredNations()) {
            var storedCapital = database.nationCapital(nation.nationId().value());
            if (storedCapital == null) {
                missingCapitals++;
                continue;
            }
            Capital capital = new Capital(
                    storedCapital.dimensionId(),
                    storedCapital.chunkX(),
                    storedCapital.chunkZ());
            TerritoryFreeAllocation allocation = allocationPolicy.calculate(
                    populations.calculate(nation.nationId(), scanTime));
            nations.add(new AutomaticNationMaintenanceContext(
                    nation.nationId(),
                    nation.ftbTeamId(),
                    capital,
                    allocation,
                    maintenance.restorationHistory(
                            nation.nationId(), nation.ftbTeamId(), window.startsAt())));
        }
        if (missingCapitals > 0) {
            LOGGER.warn(
                    "Skipped automatic Territory Maintenance for {} Nation(s) without a persistent Capital",
                    missingCapitals);
        }
        return new AutomaticMaintenancePreparation(
                effectivePolicy, window, List.copyOf(nations), false);
    }

    private static UUID policyIdFromAutomaticRequest(String requestId) {
        String prefix = "automatic-maintenance:";
        int finalSeparator = requestId.lastIndexOf(':');
        if (!requestId.startsWith(prefix) || finalSeparator <= prefix.length()) {
            throw new IllegalStateException(
                    "Automatic Territory Maintenance request ID is invalid");
        }
        return UUID.fromString(requestId.substring(prefix.length(), finalSeparator));
    }

    private void snapshotTerritoryMaintenanceClaims(
            RuntimeState current,
            AutomaticMaintenancePreparation preparation,
            Clock scanClock) {
        if (state != current) {
            current.territoryMaintenanceAssessmentQueued.set(false);
            return;
        }
        try {
            FtbNationTeamDirectory teams = FtbNationTeamDirectory.live();
            FtbChunksAdapter chunks = FtbChunksAdapter.live();
            TerritoryMaintenanceAssessmentPlanner planner =
                    new TerritoryMaintenanceAssessmentPlanner();
            List<org.civiceconomy.territory.TerritoryMaintenanceClaimSnapshot> claims =
                    preparation.nations().stream()
                            .flatMap(nation -> {
                                if (teams.find(nation.ftbTeamId()).isEmpty()) {
                                    throw new IllegalStateException(
                                            "FTB Team facts are unavailable for Nation "
                                                    + nation.nationId().value());
                                }
                                List<TerritoryMaintenanceObservedClaim> observed = chunks
                                        .claimsForTeam(nation.ftbTeamId()).stream()
                                        .map(claim -> new TerritoryMaintenanceObservedClaim(
                                                new TerritoryClaimPosition(
                                                        claim.dimension().location().toString(),
                                                        claim.chunkPos().x,
                                                        claim.chunkPos().z),
                                                claim.forceLoadRequested()))
                                        .toList();
                                return planner.plan(
                                                nation.nationId(),
                                                nation.ftbTeamId(),
                                                nation.capital(),
                                                 observed,
                                                 nation.freeAllocation(),
                                                 preparation.policy(),
                                                 preparation.window().startsAt(),
                                                 nation.restorationHistory())
                                         .stream();
                            })
                            .toList();
            persistTerritoryMaintenanceAssessment(
                    current, preparation, claims, scanClock);
        } catch (RuntimeException failure) {
            finishTerritoryMaintenanceAssessment(current, null, failure);
        }
    }

    private void persistTerritoryMaintenanceAssessment(
            RuntimeState current,
            AutomaticMaintenancePreparation preparation,
            List<org.civiceconomy.territory.TerritoryMaintenanceClaimSnapshot> claims,
            Clock scanClock) {
        current.writer.submitDatabase(database -> new TerritoryMaintenanceAssessmentProcessor(
                        new TerritoryMaintenanceRegistry(database, scanClock))
                .assess(new AssessTerritoryMaintenanceCycle(
                        TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                        preparation.window().requestId(),
                        preparation.window().startsAt(),
                        preparation.window().endsAt(),
                        claims,
                        "Automatic Territory Maintenance assessment")))
                .whenComplete((batch, failure) ->
                        finishTerritoryMaintenanceAssessment(current, batch, failure));
    }

    private void recoverTerritoryMaintenanceAssessment(
            RuntimeState current,
            AutomaticMaintenancePreparation preparation,
            Clock scanClock) {
        current.writer.submitDatabase(database -> new TerritoryMaintenanceAssessmentProcessor(
                        new TerritoryMaintenanceRegistry(database, scanClock))
                .recover(
                        TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                        preparation.window().requestId(),
                        "Automatic Territory Maintenance assessment")
                .orElseThrow(() -> new IllegalStateException(
                        "Territory Maintenance Assessment Batch disappeared during recovery")))
                .whenComplete((batch, failure) ->
                        finishTerritoryMaintenanceAssessment(current, batch, failure));
    }

    private void finishTerritoryMaintenanceAssessment(
            RuntimeState current,
            TerritoryMaintenanceAssessmentBatch batch,
            Throwable failure) {
        current.territoryMaintenanceAssessmentQueued.set(false);
        if (failure != null) {
            LOGGER.error("Automatic Territory Maintenance assessment failed closed", failure);
        } else if (batch != null) {
            LOGGER.info(
                    "Automatic Territory Maintenance Cycle {} has {} Assessment(s)",
                    batch.cycle().cycleId(),
                    batch.assessments().size());
            scheduleTerritoryMaintenanceSettlement(current, batch);
        }
    }

    private void scheduleTerritoryMaintenanceSettlement(
            RuntimeState current, TerritoryMaintenanceAssessmentBatch batch) {
        if (!current.territoryMaintenanceSettlementQueued.compareAndSet(false, true)) {
            return;
        }
        Clock settlementClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        current.writer.submitDatabase(database -> settleTerritoryMaintenance(
                        current, database, batch, settlementClock))
                .whenComplete((result, failure) -> {
                    current.territoryMaintenanceSettlementQueued.set(false);
                    scheduleTerritoryForceLoadEnforcementRecovery(current);
                    scheduleTerritoryForceLoadRestrictionRefresh(current);
                    if (failure != null) {
                        LOGGER.error(
                                "Automatic Territory Maintenance settlement failed closed",
                                failure);
                    } else if (result != null && result.nationCount() > 0) {
                        LOGGER.info(
                                "Automatic Territory Maintenance settled {} Nation(s): "
                                        + "{} fully funded, {} partially funded, {} unfunded",
                                result.nationCount(),
                                result.fullyFunded(),
                                result.partiallyFunded(),
                                result.unfunded());
                    }
                });
    }

    private static AutomaticMaintenanceSettlementResult settleTerritoryMaintenance(
            RuntimeState current,
            CivicDatabase database,
            TerritoryMaintenanceAssessmentBatch batch,
            Clock settlementClock) {
        String cycleRequestId = batch.cycle().requestId();
        if (!cycleRequestId.endsWith(":cycle")) {
            throw new IllegalStateException(
                    "Automatic Territory Maintenance Cycle request ID is invalid");
        }
        String requestPrefix = cycleRequestId.substring(
                0, cycleRequestId.length() - ":cycle".length());
        TerritoryMaintenancePolicyVersion policy =
                new TerritoryMaintenancePolicyRegistry(database, settlementClock)
                        .find(policyIdFromAutomaticRequest(requestPrefix))
                        .orElseThrow(() -> new IllegalStateException(
                                "Automatic Territory Maintenance Settlement policy is missing"));
        TerritoryForceLoadEnforcementRegistry enforcements =
                new TerritoryForceLoadEnforcementRegistry(database, settlementClock);
        batch.assessments().stream()
                .filter(assessment ->
                        assessment.validity() == TerritoryFiscalValidity.SUSPENDED)
                .forEach(assessment -> prepareTerritoryForceLoadEnforcement(
                        enforcements, requestPrefix, assessment.assessmentId()));
        List<org.civiceconomy.nation.NationId> nations = batch.assessments().stream()
                .filter(assessment -> assessment.validity() == TerritoryFiscalValidity.PENDING)
                .map(org.civiceconomy.territory.TerritoryFiscalAssessment::nationId)
                .distinct()
                .sorted(java.util.Comparator.comparing(org.civiceconomy.nation.NationId::value))
                .toList();
        if (nations.isEmpty()) {
            return new AutomaticMaintenanceSettlementResult(0, 0, 0, 0);
        }
        FiscalAuthorization authorization = new FiscalAuthorization(database);
        TerritoryFiscalServiceProvisioner provisioner =
                new TerritoryFiscalServiceProvisioner(authorization);
        for (org.civiceconomy.nation.NationId nationId : nations) {
            provisioner.ensureAuthorized(nationalTreasury(nationId));
        }
        var session = authorization.openSession(TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY);
        TerritoryMaintenancePaymentCoordinator coordinator =
                TerritoryMaintenancePaymentCoordinator.live(
                        database, session, settlementClock, current.server.overworld());
        int fullyFunded = 0;
        int partiallyFunded = 0;
        int unfunded = 0;
        RuntimeException aggregateFailure = null;
        for (org.civiceconomy.nation.NationId nationId : nations) {
            try {
                var settlement = coordinator.settleAvailable(
                                new SettleAvailableTerritoryMaintenance(
                                        TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                                        requestPrefix + ":nation:" + nationId.value(),
                                        batch.cycle().cycleId(),
                                        nationId,
                                        nationalTreasury(nationId),
                                        policy.destructionBasisPoints(),
                                        "Automatic Territory Maintenance settlement"))
                        .settlement();
                if (settlement.outcome() == TerritoryMaintenanceSettlementOutcome.FULLY_FUNDED) {
                    fullyFunded++;
                } else if (settlement.outcome()
                        == TerritoryMaintenanceSettlementOutcome.PARTIALLY_FUNDED) {
                    partiallyFunded++;
                } else {
                    unfunded++;
                }
                settlement.suspendedAssessmentIds().forEach(assessmentId ->
                        prepareTerritoryForceLoadEnforcement(
                                enforcements, requestPrefix, assessmentId));
            } catch (RuntimeException failure) {
                LOGGER.error(
                        "Automatic Territory Maintenance settlement failed closed for Nation {}",
                        nationId.value(),
                        failure);
                if (aggregateFailure == null) {
                    aggregateFailure = new IllegalStateException(
                            "One or more automatic Territory Maintenance Settlements failed");
                }
                aggregateFailure.addSuppressed(failure);
            }
        }
        if (aggregateFailure != null) {
            throw aggregateFailure;
        }
        return new AutomaticMaintenanceSettlementResult(
                nations.size(), fullyFunded, partiallyFunded, unfunded);
    }

    private static TerritoryForceLoadEnforcement prepareTerritoryForceLoadEnforcement(
            TerritoryForceLoadEnforcementRegistry enforcements,
            String requestPrefix,
            UUID assessmentId) {
        return enforcements.prepare(
                TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY,
                requestPrefix + ":force-load:" + assessmentId,
                assessmentId,
                "Disable FTB force-load for suspended Territory");
    }

    private void scheduleNationalStrengthRecalculation(RuntimeState current) {
        if (state != current
                || !current.nationalStrengthRecalculationQueued.compareAndSet(false, true)) {
            return;
        }
        long recalculatedAt = clock.millis();
        current.writer
                .submitDatabase(database -> database.registeredNations().stream()
                        .map(org.civiceconomy.persistence.StoredNation::ftbTeamId)
                        .distinct()
                        .toList())
                .thenCompose(teamIds -> onServer(
                        current, () -> snapshotNationalStrengthClaims(teamIds)))
                .thenCompose(currentClaims -> current.writer.submitDatabase(database ->
                        new NationalStrengthSnapshotBuilder(
                                        database,
                                        nationalStrengthConfiguration(),
                                        currentClaims,
                                        CivicEconomy.compatibilityReport()
                                                .productionScoringEnabled())
                                .recalculateAll(recalculatedAt)))
                .whenComplete((snapshot, failure) -> {
                    current.nationalStrengthRecalculationQueued.set(false);
                    if (failure != null) {
                        LOGGER.error("Automatic National Strength recalculation failed closed", failure);
                    } else if (state == current) {
                        current.nationalStrengthSnapshot = snapshot;
                    }
                });
    }

    void scheduleRegisteredFacilityTerritoryReconciliation() {
        RuntimeState current = state;
        if (current != null) {
            scheduleRegisteredFacilityTerritoryReconciliation(current);
        }
    }

    CompletableFuture<List<RegisteredFacility>>
            reconcileRegisteredFacilityTerritoryForGameTest() {
        RuntimeState current = requireState();
        return reconcileRegisteredFacilityTerritory(
                current, Clock.fixed(clock.instant(), ZoneOffset.UTC));
    }

    private void scheduleRegisteredFacilityTerritoryReconciliation(
            RuntimeState current) {
        if (state != current
                || !current.registeredFacilityTerritoryReconciliationQueued
                        .compareAndSet(false, true)) {
            return;
        }
        Clock reconciliationClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        reconcileRegisteredFacilityTerritory(current, reconciliationClock)
                .whenComplete((changed, failure) -> {
                    current.registeredFacilityTerritoryReconciliationQueued.set(false);
                    if (failure != null) {
                        LOGGER.error(
                                "Automatic Registered Facility Territory reconciliation failed closed",
                                failure);
                    } else if (!changed.isEmpty()) {
                        LOGGER.info(
                                "Reconciled Territory state for {} Registered Facility(s)",
                                changed.size());
                        scheduleNationalStrengthRecalculation(current);
                    }
                });
    }

    private CompletableFuture<List<RegisteredFacility>>
            reconcileRegisteredFacilityTerritory(
                    RuntimeState current, Clock reconciliationClock) {
        return current.writer
                .submitDatabase(database -> database.registeredFacilities().stream()
                        .filter(facility -> "ACTIVE".equals(facility.state())
                                || "PAUSED_TERRITORY".equals(facility.state()))
                        .map(org.civiceconomy.persistence.StoredRegisteredFacility::ftbTeamId)
                        .distinct()
                        .toList())
                .thenCompose(teamIds -> onServer(
                        current, () -> snapshotRegisteredFacilityClaims(teamIds)))
                .thenCompose(claims -> current.writer.submitDatabase(database -> {
                    TerritoryOwnershipSource ownership =
                            (dimensionId, chunkX, chunkZ) -> Optional.ofNullable(claims.get(
                                    new TerritoryClaimPosition(
                                            dimensionId, chunkX, chunkZ)));
                    EffectiveTerritoryFacilityAuthority territory =
                            new EffectiveTerritoryFacilityAuthority(
                                    database,
                                    new EffectiveTerritoryQuery(
                                            new TerritoryMaintenanceRegistry(
                                                    database, reconciliationClock),
                                            ownership),
                                    reconciliationClock);
                    return new RegisteredFacilityTerritoryReconciler(
                                    database,
                                    territory,
                                    FacilityAdministration.SERVICE_IDENTITY,
                                    reconciliationClock)
                            .reconcile();
                }));
    }

    private static Map<TerritoryClaimPosition, UUID> snapshotRegisteredFacilityClaims(
            List<UUID> teamIds) {
        FtbNationTeamDirectory teams = FtbNationTeamDirectory.live();
        FtbChunksAdapter chunks = FtbChunksAdapter.live();
        Map<TerritoryClaimPosition, UUID> ownership = new HashMap<>();
        for (UUID teamId : teamIds) {
            if (teams.find(teamId).isEmpty()) {
                continue;
            }
            for (var claim : chunks.claimsForTeam(teamId)) {
                TerritoryClaimPosition position = new TerritoryClaimPosition(
                        claim.dimension().location().toString(),
                        claim.chunkPos().x,
                        claim.chunkPos().z);
                UUID existing = ownership.put(position, teamId);
                if (existing != null && !existing.equals(teamId)) {
                    throw new IllegalStateException(
                            "FTB Claim snapshot contains conflicting Team ownership");
                }
            }
        }
        return Map.copyOf(ownership);
    }

    private static NationalStrengthSnapshotConfiguration nationalStrengthConfiguration() {
        return new NationalStrengthSnapshotConfiguration(
                CITIZENSHIP_TRANSFER_COOLDOWN,
                NATION_APPLICATION_EVIDENCE_WINDOW,
                NATIONAL_STRENGTH_FULL_CITIZEN_TIME,
                NATIONAL_STRENGTH_EFFECTIVE_TERRITORY_FULL_SCALE,
                NATIONAL_STRENGTH_COMPLIANCE_WINDOW,
                NATIONAL_STRENGTH_ACTIVITY_WINDOW,
                NATIONAL_STRENGTH_ACTIVITY_FULL_SCALE);
    }

    private static Map<UUID, List<TerritoryClaimPosition>> snapshotNationalStrengthClaims(
            List<UUID> teamIds) {
        FtbNationTeamDirectory teams = FtbNationTeamDirectory.live();
        FtbChunksAdapter chunks = FtbChunksAdapter.live();
        Map<UUID, List<TerritoryClaimPosition>> claimsByTeam = new HashMap<>();
        for (UUID teamId : teamIds) {
            if (teams.find(teamId).isEmpty()) {
                continue;
            }
            claimsByTeam.put(
                    teamId,
                    chunks.claimsForTeam(teamId).stream()
                            .map(claim -> new TerritoryClaimPosition(
                                    claim.dimension().location().toString(),
                                    claim.chunkPos().x,
                                    claim.chunkPos().z))
                            .toList());
        }
        return Map.copyOf(claimsByTeam);
    }

    private void scheduleTerritoryForceLoadEnforcementRecovery(RuntimeState current) {
        if (state != current
                || !current.territoryForceLoadEnforcementQueued.compareAndSet(false, true)) {
            return;
        }
        current.writer.submitDatabase(database ->
                        new TerritoryForceLoadEnforcementRegistry(database).incomplete())
                .whenComplete((enforcements, failure) -> {
                    current.territoryForceLoadEnforcementQueued.set(false);
                    if (failure != null) {
                        LOGGER.error(
                                "Territory force-load Enforcement recovery failed closed",
                                failure);
                        return;
                    }
                    for (TerritoryForceLoadEnforcement enforcement : enforcements) {
                        current.server.execute(() -> applyTerritoryForceLoadEnforcement(
                                current, enforcement));
                    }
                });
    }

    private void applyTerritoryForceLoadEnforcement(
            RuntimeState current, TerritoryForceLoadEnforcement enforcement) {
        if (state != current
                || enforcement.state()
                        == TerritoryForceLoadEnforcementState.CIVIC_COMMITTED) {
            return;
        }
        try {
            ResourceLocation dimensionId =
                    ResourceLocation.tryParse(enforcement.position().dimensionId());
            if (dimensionId == null) {
                throw new IllegalStateException(
                        "Territory force-load Enforcement dimension is invalid");
            }
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
            FtbChunksAdapter.live().disableForceLoadIfOwned(
                    enforcement.ftbTeamId(),
                    dimension,
                    new ChunkPos(
                            enforcement.position().chunkX(),
                            enforcement.position().chunkZ()),
                    current.server.createCommandSourceStack());
            Clock enforcementClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
            current.writer.submitDatabase(database -> {
                        TerritoryForceLoadEnforcementRegistry registry =
                                new TerritoryForceLoadEnforcementRegistry(
                                        database, enforcementClock);
                        TerritoryForceLoadEnforcement latest = registry
                                .find(enforcement.enforcementId())
                                .orElseThrow(() -> new IllegalStateException(
                                        "Territory force-load Enforcement disappeared"));
                        if (latest.state() == TerritoryForceLoadEnforcementState.PREPARED) {
                            latest = registry.markExternalApplied(latest.enforcementId());
                        }
                        return registry.commit(latest.enforcementId());
                    })
                    .whenComplete((committed, failure) -> {
                        if (failure != null) {
                            LOGGER.error(
                                    "Territory force-load Enforcement Civic commit failed; "
                                            + "recovery will verify the exact FTB Claim again",
                                    failure);
                        } else {
                            LOGGER.info(
                                    "Territory force-load Enforcement {} committed for Assessment {}",
                                    committed.enforcementId(),
                                    committed.assessmentId());
                        }
                    });
        } catch (RuntimeException failure) {
            LOGGER.error(
                    "Territory force-load Enforcement failed closed for Assessment {}; "
                            + "the durable state remains recoverable",
                    enforcement.assessmentId(),
                    failure);
        }
    }

    private static AccountId nationalTreasury(org.civiceconomy.nation.NationId nationId) {
        return new AccountId("nation:" + nationId.value() + ":treasury");
    }

    private static TreasuryWithdrawalInspection withdrawalInspection(
            CivicDatabase database, NationTeam team, Clock commandClock) {
        NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
        NationRegistry nations = new NationRegistry(database, teams);
        var provider = new FtbTeamsNationProvider(
                nations,
                new CitizenshipRegistry(
                        database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock),
                new CitizenshipCorrectionGraceRegistry(database, commandClock),
                teams);
        return new TreasuryWithdrawalInspection(
                provider,
                new NationFiscalAuthorityRegistry(database, provider, commandClock),
                new WithdrawalApprovalPolicyRegistry(database, commandClock),
                new TreasuryWithdrawalApprovalRegistry(database, commandClock),
                commandClock);
    }

    private static BudgetDisbursementInspection budgetDisbursementInspection(
            CivicDatabase database, NationTeam team, Clock commandClock) {
        NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
        NationRegistry nations = new NationRegistry(database, teams);
        var provider = new FtbTeamsNationProvider(
                nations,
                new CitizenshipRegistry(
                        database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock),
                new CitizenshipCorrectionGraceRegistry(database, commandClock),
                teams);
        return new BudgetDisbursementInspection(
                provider,
                new NationFiscalAuthorityRegistry(database, provider, commandClock),
                new BudgetDisbursementApprovalPolicyRegistry(database, commandClock),
                new BudgetDisbursementApprovalRegistry(database, commandClock),
                commandClock);
    }

    private static NationFiscalBillInspection nationFiscalBillInspection(
            CivicDatabase database, NationTeam team, Clock commandClock) {
        NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
        NationRegistry nations = new NationRegistry(database, teams);
        var provider = new FtbTeamsNationProvider(
                nations,
                new CitizenshipRegistry(
                        database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock),
                new CitizenshipCorrectionGraceRegistry(database, commandClock),
                teams);
        return new NationFiscalBillInspection(
                database,
                provider,
                new NationFiscalAuthorityRegistry(database, provider, commandClock));
    }

    private static NationBudgetInspection nationBudgetInspection(
            CivicDatabase database, NationTeam team, Clock commandClock) {
        NationTeamDirectory teams = snapshotDirectory(Map.of(team.teamId(), team));
        NationRegistry nations = new NationRegistry(database, teams);
        var provider = new FtbTeamsNationProvider(
                nations,
                new CitizenshipRegistry(
                        database, CITIZENSHIP_TRANSFER_COOLDOWN, commandClock),
                new CitizenshipCorrectionGraceRegistry(database, commandClock),
                teams);
        return new NationBudgetInspection(
                database,
                provider,
                new NationFiscalAuthorityRegistry(database, provider, commandClock));
    }

    private static String requireVersion(NeoForgeModCatalog mods, String modId) {
        return mods.version(modId)
                .orElseThrow(() -> new IllegalStateException("Loaded mod version is unavailable: " + modId));
    }

    private static final class RuntimeState {
        private final MinecraftServer server;
        private final OnlineSessionAccumulator sessions;
        private final AsyncOnlineTimeWriter writer;
        private final OnlineDatabaseBackupManager backups;
        private final OnlineDatabaseRestoreManager restores;
        private final ServerPlayerMintMaterialCustody mintCustody;
        private final ArrayDeque<CreateRecipeCompletion> pendingFacilityCompletions =
                new ArrayDeque<>();
        private final long startedAtEpochMillis;
        private final AtomicBoolean fiscalExpiryQueued = new AtomicBoolean();
        private final AtomicBoolean budgetDisbursementRecoveryQueued =
                new AtomicBoolean();
        private final AtomicBoolean nationApplicationExpiryQueued = new AtomicBoolean();
        private final AtomicBoolean citizenshipReconciliationQueued = new AtomicBoolean();
        private final AtomicBoolean territoryPermitCompensationQueued = new AtomicBoolean();
        private final AtomicBoolean permanentDestructionRecoveryQueued = new AtomicBoolean();
        private final AtomicBoolean treasuryWithdrawalRecoveryQueued = new AtomicBoolean();
        private final AtomicBoolean mintBatchRecoveryQueued = new AtomicBoolean();
        private final AtomicBoolean territoryMaintenanceAssessmentQueued = new AtomicBoolean();
        private final AtomicBoolean territoryMaintenanceSettlementQueued = new AtomicBoolean();
        private final AtomicBoolean registeredFacilityTerritoryReconciliationQueued =
                new AtomicBoolean();
        private final AtomicBoolean territoryForceLoadEnforcementQueued = new AtomicBoolean();
        private final AtomicBoolean territoryForceLoadRestrictionRefreshQueued =
                new AtomicBoolean();
        private final AtomicBoolean nationalStrengthRecalculationQueued = new AtomicBoolean();
        private final AtomicBoolean databaseBackupQueued = new AtomicBoolean();
        private volatile NationalStrengthSnapshot nationalStrengthSnapshot;
        private int ticksSinceCheckpoint;
        private int ticksSinceFiscalExpiry;
        private int ticksSinceNationApplicationExpiry;
        private int ticksSinceCitizenshipReconciliation;
        private int ticksSinceTerritoryPermitCompensation;
        private int ticksSincePermanentDestructionRecovery;
        private int ticksSinceTreasuryWithdrawalRecovery;
        private int ticksSinceMintBatchRecovery;
        private int ticksSinceTerritoryMaintenanceAssessment;
        private int ticksSinceRegisteredFacilityTerritory;
        private int ticksSinceNationalStrengthRecalculation;
        private int ticksSinceDatabaseBackup;
        private boolean failureLogged;

        private RuntimeState(
                MinecraftServer server,
                OnlineSessionAccumulator sessions,
                AsyncOnlineTimeWriter writer,
                OnlineDatabaseBackupManager backups,
                OnlineDatabaseRestoreManager restores,
                ServerPlayerMintMaterialCustody mintCustody,
                long startedAtEpochMillis) {
            this.server = server;
            this.sessions = sessions;
            this.writer = writer;
            this.backups = backups;
            this.restores = restores;
            this.mintCustody = mintCustody;
            this.startedAtEpochMillis = startedAtEpochMillis;
        }
    }

    private static final TerritoryOwnershipSource NO_TERRITORY_OWNERSHIP =
            (dimension, chunkX, chunkZ) -> Optional.empty();

    private record MintStartFacts(
            org.civiceconomy.persistence.StoredRegisteredMint mint,
            List<org.civiceconomy.persistence.StoredMintRecipeIngredient> ingredients,
            long amountMinorUnits) {}

    private sealed interface MintStartPreparation permits MintStartReplay, NewMintStart {}

    private record MintStartReplay(MintBatch batch) implements MintStartPreparation {}

    private record NewMintStart(MintStartFacts facts, NationTeam team)
            implements MintStartPreparation {}

    private record MintStartSnapshot(
            NationTeam team,
            TerritoryOwnershipSource ownership,
            List<org.civiceconomy.mint.MintMaterialStack> materials) {}

    private record NationalStrengthCommandContext(
            NationTeam team,
            Map<UUID, List<TerritoryClaimPosition>> currentClaimsByTeam) {}

    private record FacilityRegistrationSnapshot(
            NationTeam team,
            FacilityCorePosition core,
            TerritoryClaimPosition claim,
            TerritoryOwnershipSource ownership) {}

    private record FacilityInterfaceSnapshot(
            NationTeam team, FacilityAccountingInterfacePosition position) {}

    private record ProductionExportPreparation(
            ProductionInventoryExportRequest request,
            FacilityAccountingInterfacePosition position,
            org.civiceconomy.persistence.StoredProductionInventoryExport replay) {}

    private record FacilityBaselineActorSnapshot(
            NationTeam team, FacilityAccountingInterfacePosition position) {}

    private record FacilityBaselineServerSnapshot(
            FacilityAccountingBaselineSnapshot snapshot,
            NationTeam team,
            TerritoryOwnershipSource ownership) {}

    private record FacilityReceiptPreparation(
            UUID interfaceId,
            UUID ftbTeamId,
            FacilityAccountingInterfaceInventoryIncrease increase,
            List<CreateRecipeCompletion> candidates) {
        private FacilityReceiptPreparation {
            candidates = List.copyOf(candidates);
        }
    }

    private record FacilityReceiptSnapshot(
            FacilityReceiptPreparation preparation,
            TerritoryOwnershipSource ownership) {}

    private record PreparedMintTake(
            PendingMintMaterialTake pending,
            NationTeam team,
            TerritoryOwnershipSource ownership) {}

    private record PreparedTreasuryWithdrawal(
            TreasuryWithdrawalCoordinator coordinator,
            TreasuryWithdrawal withdrawal) {}

    private record PreparedPermanentDestruction(
            PermanentDestructionCoordinator coordinator,
            PermanentDestructionOperation operation) {}

    private record PreparedPlayerFiscalBillPayment(
            FiscalBillPaymentCoordinator coordinator,
            PreparedFiscalBillPayment payment) {}

    private record PendingBudgetApproval(NationTeam team, AccountId treasury) {}

    private record PreparedBudgetApproval(
            NationTeam team, AccountId treasury, MoneyAmount balance) {}

    private record PreparedNationalBudgetDisbursement(
            BudgetDisbursementApproval approval,
            BudgetDisbursementPaymentCoordinator coordinator,
            PreparedBudgetDisbursementPayment payment) {}

    private record PreparedBudgetDisbursementRecovery(
            BudgetDisbursementPaymentCoordinator coordinator,
            PreparedBudgetDisbursementPayment payment) {}

    private record PreparedApprovedTreasuryWithdrawal(
            TreasuryWithdrawalCoordinator coordinator,
            TreasuryWithdrawalApproval approval,
            TreasuryWithdrawal withdrawal) {}

    private record TreasuryWithdrawalRecovery(
            TreasuryWithdrawalCoordinator coordinator,
            List<TreasuryWithdrawal> withdrawals) {}

    private record FiscalExpiryResult(
            int escrows,
            int budgetDrafts,
            int fiscalBills,
            int budgetDisbursements) {}

    private sealed interface MintCancellationPreparation
            permits MintCancellationReplay, PreparedMintReturn {}

    private record MintCancellationReplay(MintBatch batch)
            implements MintCancellationPreparation {}

    private record PreparedMintReturn(PendingMintMaterialReturn pending, NationTeam team)
            implements MintCancellationPreparation {}

    private record PendingMintRecoveryScan(
            List<PendingMintMaterialOperation> materials,
            List<PendingMintIssuanceStep> issuances) {}

    private record TerritoryPermitCompensationResult(int recovered, int expired) {}

    private record TerritoryPermitMirrorSnapshot(
            List<org.civiceconomy.territory.TerritoryClaimPermit> permits,
            Map<UUID, org.civiceconomy.nation.NationId> nationByFtbTeam) {}

    private record AutomaticNationMaintenanceContext(
            org.civiceconomy.nation.NationId nationId,
            UUID ftbTeamId,
            Capital capital,
            TerritoryFreeAllocation freeAllocation,
            Map<TerritoryClaimPosition, TerritoryMaintenanceRestorationHistory>
                    restorationHistory) {}

    private record AutomaticMaintenancePreparation(
            TerritoryMaintenancePolicyVersion policy,
            TerritoryMaintenanceCycleWindow window,
            List<AutomaticNationMaintenanceContext> nations,
            boolean recover) {
        private AutomaticMaintenancePreparation {
            nations = List.copyOf(nations);
        }
    }

    record PreparedTerritoryClaim(
            TerritoryClaimPermit permit, FreeClaimAuthorization freeClaim) {
        PreparedTerritoryClaim {
            if ((permit == null) == (freeClaim == null)) {
                throw new IllegalArgumentException(
                        "Prepared Territory Claim must contain exactly one authorization");
            }
        }
    }

    private record AutomaticMaintenanceSettlementResult(
            int nationCount, int fullyFunded, int partiallyFunded, int unfunded) {}

    private record DatabaseBackupLifecycleResult(
            int recovered, StoredDatabaseBackupOperation backup) {}
}
