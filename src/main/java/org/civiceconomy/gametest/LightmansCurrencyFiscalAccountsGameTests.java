package org.civiceconomy.gametest;

import io.github.lightman314.lightmanscurrency.api.money.bank.BankAPI;
import io.github.lightman314.lightmanscurrency.api.money.bank.IBankAccount;
import io.github.lightman314.lightmanscurrency.api.money.bank.reference.BankReference;
import io.github.lightman314.lightmanscurrency.api.money.coins.CoinAPI;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue;
import io.github.lightman314.lightmanscurrency.api.money.value.builtin.CoinValue;
import io.github.lightman314.lightmanscurrency.common.data.CustomSaveData;
import io.github.lightman314.lightmanscurrency.common.data.types.BankDataCache;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.civiceconomy.CivicEconomy;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.ApproveBudget;
import org.civiceconomy.fiscal.Budget;
import org.civiceconomy.fiscal.BudgetState;
import org.civiceconomy.fiscal.CompensatePayment;
import org.civiceconomy.fiscal.CreateBudget;
import org.civiceconomy.fiscal.ExternalPayment;
import org.civiceconomy.fiscal.Escrow;
import org.civiceconomy.fiscal.EscrowState;
import org.civiceconomy.fiscal.FailurePoint;
import org.civiceconomy.fiscal.FiscalBill;
import org.civiceconomy.fiscal.FiscalBillKind;
import org.civiceconomy.fiscal.FiscalBillState;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalCapability;
import org.civiceconomy.fiscal.FiscalLedger;
import org.civiceconomy.fiscal.FiscalServiceSession;
import org.civiceconomy.fiscal.FundFiscalBill;
import org.civiceconomy.fiscal.GrantFiscalCapability;
import org.civiceconomy.fiscal.IssueFiscalBill;
import org.civiceconomy.fiscal.LedgerDirection;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.PaymentCoordinator;
import org.civiceconomy.fiscal.PaymentKind;
import org.civiceconomy.fiscal.PaymentTransaction;
import org.civiceconomy.fiscal.RefundPayment;
import org.civiceconomy.fiscal.RecoveryAction;
import org.civiceconomy.fiscal.ReleaseReservation;
import org.civiceconomy.fiscal.RegisterFiscalService;
import org.civiceconomy.fiscal.Reservation;
import org.civiceconomy.fiscal.ReserveFunds;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.SettleReservation;
import org.civiceconomy.fiscal.SimulatedCrash;
import org.civiceconomy.fiscal.TransactionState;
import org.civiceconomy.integration.lightmanscurrency.FiscalAccountKind;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyAccountBalances;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyFiscalAccounts;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyNationalTreasuryProvisioner;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyPayments;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyTerritoryClearingAccountProvisioner;
import org.civiceconomy.nation.ActivateNationApplication;
import org.civiceconomy.nation.ActivatedNation;
import org.civiceconomy.nation.Capital;
import org.civiceconomy.nation.CreateNationApplication;
import org.civiceconomy.nation.GrantNationFiscalPermission;
import org.civiceconomy.nation.NationActivationCoordinator;
import org.civiceconomy.nation.NationApplication;
import org.civiceconomy.nation.NationApplicationRegistry;
import org.civiceconomy.nation.NationFoundingPolicy;
import org.civiceconomy.nation.NationFacts;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.NationProvider;
import org.civiceconomy.nation.NationRegistry;
import org.civiceconomy.nation.NationTeam;
import org.civiceconomy.nation.NationTeamDirectory;
import org.civiceconomy.nation.OnlineTimeLedger;
import org.civiceconomy.nation.RecordOnlineTime;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.civiceconomy.territory.CommittedTerritoryPrepaymentVerifier;
import org.civiceconomy.territory.CancelTerritoryClaimPermit;
import org.civiceconomy.territory.PrepareTerritoryClaimPrepayment;
import org.civiceconomy.territory.TerritoryClaimPermit;
import org.civiceconomy.territory.TerritoryClaimPermitRegistry;
import org.civiceconomy.territory.TerritoryClaimPermitCompensationCoordinator;
import org.civiceconomy.territory.TerritoryClaimPermitState;
import org.civiceconomy.territory.TerritoryClaimPrepaymentCoordinator;
import org.civiceconomy.territory.TerritoryExpansionQuote;
import org.civiceconomy.territory.TerritoryFiscalServiceProvisioner;

@GameTestHolder(CivicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LightmansCurrencyFiscalAccountsGameTests {
    private LightmansCurrencyFiscalAccountsGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void territoryPrepaymentClearingUsesRealLcFiscalAccount(GameTestHelper helper) {
        AccountId clearing = new AccountId("system:territory:prepayment-clearing");
        LightmansCurrencyFiscalAccounts accounts =
                LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
        LightmansCurrencyTerritoryClearingAccountProvisioner provisioner =
                new LightmansCurrencyTerritoryClearingAccountProvisioner(accounts);

        provisioner.ensureExists(clearing);
        provisioner.ensureExists(clearing);

        helper.assertValueEqual(
                0L,
                accounts.balance(clearing).minorUnits(),
                "new real LC Territory prepayment clearing balance");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void authorizedTerritoryPrepaymentMovesRealLcBeforeReadyPermit(
            GameTestHelper helper) {
        Instant now = Instant.parse("2026-07-14T13:30:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        NationId nationId = NationId.create();
        UUID teamId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        NationFacts facts = new NationFacts(nationId, actorId, Set.of(actorId));
        NationProvider provider = new NationProvider() {
            @Override
            public Optional<NationFacts> find(NationId requestedNationId) {
                return nationId.equals(requestedNationId) ? Optional.of(facts) : Optional.empty();
            }

            @Override
            public Optional<NationFacts> findForCitizen(UUID playerId) {
                return actorId.equals(playerId) ? Optional.of(facts) : Optional.empty();
            }
        };
        NationTeamDirectory noTeams = new NationTeamDirectory() {
            @Override
            public Optional<NationTeam> find(UUID requestedTeamId) {
                return Optional.empty();
            }

            @Override
            public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
                return Optional.empty();
            }
        };
        AccountId treasury = new AccountId("nation:" + nationId.value() + ":treasury");
        AccountId clearing = LightmansCurrencyTerritoryClearingAccountProvisioner.ACCOUNT_ID;
        UUID fundingPlayerId = UUID.randomUUID();
        AccountId fundingAccount = new AccountId("player:" + fundingPlayerId);
        BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
        LightmansCurrencyFiscalAccounts accounts =
                LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
        LightmansCurrencyPayments lcPayments = LightmansCurrencyPayments.live(helper.getLevel());
        accounts.create(treasury, FiscalAccountKind.NATIONAL_TREASURY, "Territory GameTest Treasury");
        LightmansCurrencyTerritoryClearingAccountProvisioner clearingProvisioner =
                new LightmansCurrencyTerritoryClearingAccountProvisioner(accounts);
        clearingProvisioner.ensureExists(clearing);
        clearFiscalAccount(bankData, accounts, lcPayments, treasury);
        clearFiscalAccount(bankData, accounts, lcPayments, clearing);
        reset(bankData, fundingPlayerId, 1_000L);
        lcPayments.apply(new ExternalPayment(
                UUID.randomUUID(), fundingAccount, treasury, MoneyAmount.ofMinorUnits(1_000L)));

        Path temporaryDirectory = createTemporaryDirectory();
        try (CivicDatabase database = CivicDatabase.open(
                temporaryDirectory.resolve("territory-real-lc.sqlite3"),
                new DatabaseIdentity(
                        UUID.randomUUID(),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"))) {
            database.registerNation(
                    nationId.value(), "territory-gametest", "register", teamId, now.toEpochMilli());
            NationFiscalAuthorityRegistry nationAuthorities =
                    new NationFiscalAuthorityRegistry(database, provider, clock);
            nationAuthorities.grant(new GrantNationFiscalPermission(
                    new ServiceIdentity("territory-gametest-governance"),
                    "grant-territory-finance",
                    nationId,
                    actorId,
                    actorId,
                    NationFiscalPermission.MANAGE_TERRITORY_FINANCE,
                    "Real LC Territory prepayment GameTest"));
            ServiceIdentity territoryService =
                    TerritoryFiscalServiceProvisioner.SERVICE_IDENTITY;
            new TerritoryFiscalServiceProvisioner(new FiscalAuthorization(database))
                    .ensureAuthorized(treasury);
            FiscalServiceSession session = session(database, territoryService);
            TerritoryClaimPermitRegistry permits = new TerritoryClaimPermitRegistry(
                    database,
                    new CommittedTerritoryPrepaymentVerifier(database, clearing),
                    clock);
            PaymentCoordinator paymentCoordinator =
                    PaymentCoordinator.authorized(database, lcPayments, session);
            TerritoryClaimPrepaymentCoordinator coordinator =
                    new TerritoryClaimPrepaymentCoordinator(
                            new NationRegistry(database, noTeams),
                            nationAuthorities,
                            clearingProvisioner,
                            FiscalLedger.authorized(
                                    database,
                                    LightmansCurrencyAccountBalances.live(helper.getLevel()),
                                    session),
                            paymentCoordinator,
                            permits,
                            territoryService,
                            clearing,
                            clock);

            TerritoryClaimPermit permit = coordinator.prepare(
                    new PrepareTerritoryClaimPrepayment(
                            "real-lc-territory-prepayment",
                            nationId,
                            teamId,
                            actorId,
                            "minecraft:overworld",
                            8,
                            12,
                            17,
                            new TerritoryExpansionQuote(
                                    nationId,
                                    17,
                                    1,
                                    0,
                                    1,
                                    MoneyAmount.ofMinorUnits(250L),
                                    now),
                            now.plusSeconds(120L)));

            helper.assertValueEqual(
                    TerritoryClaimPermitState.READY,
                    permit.state(),
                    "real LC Territory Claim Permit state");
            helper.assertValueEqual(
                    750L,
                    accounts.balance(treasury).minorUnits(),
                    "real LC National Treasury after prepayment");
            helper.assertValueEqual(
                    250L,
                    accounts.balance(clearing).minorUnits(),
                    "real LC Territory clearing after prepayment");

            TerritoryClaimPermit cancelled =
                    new TerritoryClaimPermitCompensationCoordinator(
                                    database,
                                    paymentCoordinator,
                                    permits,
                                    territoryService,
                                    clock)
                            .cancel(new CancelTerritoryClaimPermit(
                                    "real-lc-territory-cancellation",
                                    permit.permitId(),
                                    actorId,
                                    "Real LC Territory cancellation GameTest"));
            helper.assertValueEqual(
                    TerritoryClaimPermitState.CANCELLED,
                    cancelled.state(),
                    "real LC cancelled Territory Claim Permit state");
            helper.assertValueEqual(
                    1_000L,
                    accounts.balance(treasury).minorUnits(),
                    "real LC National Treasury after Permit cancellation");
            helper.assertValueEqual(
                    0L,
                    accounts.balance(clearing).minorUnits(),
                    "real LC Territory clearing after Permit cancellation");
        } finally {
            clearFiscalAccount(bankData, accounts, lcPayments, treasury);
            clearFiscalAccount(bankData, accounts, lcPayments, clearing);
            bankData.deleteAccount(fundingPlayerId);
            deleteTemporaryDirectory(temporaryDirectory);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void civicFiscalAccountsDenyAllNativePlayerAccess(GameTestHelper helper) {
        LightmansCurrencyFiscalAccounts accounts = LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
        AccountId treasury =
                new AccountId("nation:601379d8-9553-4fe0-9250-f99ca42d95e2:treasury");
        AccountId organization =
                new AccountId("organization:1db32200-bfee-4211-8c79-f6a1349744f5:fiscal");
        accounts.create(treasury, FiscalAccountKind.NATIONAL_TREASURY, "GameTest National Treasury");
        accounts.create(
                organization,
                FiscalAccountKind.ORGANIZATION_FISCAL_ACCOUNT,
                "GameTest Organization Fiscal Account");

        var player = helper.makeMockPlayer(GameType.CREATIVE);
        Set<String> expectedAccountIds = Set.of(treasury.value(), organization.value());
        var references = BankAPI.getApi().GetAllBankReferences(false).stream()
                .filter(reference -> expectedAccountIds.contains(reference.save().getString("AccountId")))
                .toList();
        helper.assertValueEqual(2, references.size(), "registered Civic LC fiscal account references");
        for (BankReference reference : references) {
            helper.assertTrue(reference.isValid(), "registered Civic LC fiscal account must resolve");
            helper.assertFalse(reference.allowedAccess(player), "LC native player access must be denied");
            helper.assertFalse(reference.isSalaryTarget(player), "LC native salary targeting must be denied");
            helper.assertValueEqual(0, reference.salaryPermission(player), "LC native salary permission");
            helper.assertFalse(reference.canPersist(player), "LC native reference persistence must be denied");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void civicFiscalAccountsRejectLcInterestIssuance(GameTestHelper helper) {
        UUID fundingPlayerId = UUID.randomUUID();
        BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
        AccountId fundingAccount = new AccountId("player:" + fundingPlayerId);
        AccountId treasury =
                new AccountId("nation:4e7ba39f-4418-48cb-82c1-c5f981959fd7:treasury");
        LightmansCurrencyFiscalAccounts accounts = LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
        accounts.create(treasury, FiscalAccountKind.NATIONAL_TREASURY, "No Interest GameTest Treasury");
        LightmansCurrencyPayments payments = LightmansCurrencyPayments.live(helper.getLevel());
        clearFiscalAccount(bankData, accounts, payments, treasury);
        reset(bankData, fundingPlayerId, 500);
        payments.apply(new ExternalPayment(
                UUID.randomUUID(), fundingAccount, treasury, MoneyAmount.ofMinorUnits(500)));
        IBankAccount treasuryBankAccount = BankAPI.getApi().GetAllBankReferences(false).stream()
                .filter(reference -> treasury.value().equals(reference.save().getString("AccountId")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing registered National Treasury reference"))
                .get();

        treasuryBankAccount.applyInterest(1.0, List.of(), List.of(), true, false);

        helper.assertValueEqual(500L, accounts.balance(treasury).minorUnits(), "interest-free National Treasury");
        payments.apply(new ExternalPayment(
                UUID.randomUUID(), treasury, fundingAccount, MoneyAmount.ofMinorUnits(500)));
        bankData.deleteAccount(fundingPlayerId);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void treasuryReservationRecoversAndPaysAPlayerExactlyOnce(GameTestHelper helper) {
        UUID fundingPlayerId = UUID.randomUUID();
        UUID recipientPlayerId = UUID.randomUUID();
        BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
        AccountId treasury =
                new AccountId("nation:a5f803e5-0d35-42b7-8e5a-c94fd15d4589:treasury");
        AccountId fundingAccount = new AccountId("player:" + fundingPlayerId);
        AccountId recipientAccount = new AccountId("player:" + recipientPlayerId);
        LightmansCurrencyFiscalAccounts accounts = LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
        accounts.create(treasury, FiscalAccountKind.NATIONAL_TREASURY, "Settlement GameTest Treasury");
        LightmansCurrencyPayments payments = LightmansCurrencyPayments.live(helper.getLevel());
        clearFiscalAccount(bankData, accounts, payments, treasury);
        IBankAccount fundingPlayer = reset(bankData, fundingPlayerId, 1_000);
        IBankAccount recipientPlayer = reset(bankData, recipientPlayerId, 25);
        payments.apply(new ExternalPayment(
                UUID.randomUUID(), fundingAccount, treasury, MoneyAmount.ofMinorUnits(700)));

        Path temporaryDirectory = createTemporaryDirectory();
        try (CivicDatabase database = CivicDatabase.open(
                temporaryDirectory.resolve("civic.sqlite3"),
                new DatabaseIdentity(
                        UUID.randomUUID(), "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20"))) {
            ServiceIdentity fiscalService = new ServiceIdentity("civiceconomy-gametest");
            ServiceIdentity approver = new ServiceIdentity("civiceconomy-gametest-approver");
            grant(
                    database,
                    fiscalService,
                    treasury,
                    FiscalCapability.MANAGE_BUDGET,
                    FiscalCapability.SETTLE_PAYMENT,
                    FiscalCapability.READ_ACCOUNT);
            grant(database, approver, treasury, FiscalCapability.MANAGE_BUDGET);
            FiscalLedger ledger = FiscalLedger.authorized(
                    database, accounts, session(database, fiscalService));
            FiscalLedger approvalLedger = FiscalLedger.authorized(
                    database, accounts, session(database, approver));
            CreateBudget budgetRequest = new CreateBudget(
                    fiscalService,
                    "treasury-budget-" + UUID.randomUUID(),
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "GAMETEST:TREASURY_SETTLEMENT",
                    "Real LC treasury settlement GameTest",
                    Instant.now().plusSeconds(3_600));
            Budget draft = ledger.createBudget(budgetRequest);
            Budget approved = approvalLedger.approveBudget(new ApproveBudget(
                    approver,
                    "approve-treasury-budget-" + UUID.randomUUID(),
                    draft.budgetId()));
            Escrow escrow = ledger.escrow(fiscalService, approved.escrowId().orElseThrow());
            String paymentRequestId = "treasury-payment-" + UUID.randomUUID();
            PaymentCoordinator coordinator = PaymentCoordinator.authorized(
                    database, payments, session(database, fiscalService));
            try {
                coordinator.settle(
                        new SettleReservation(
                                fiscalService,
                                paymentRequestId,
                                escrow.reservationId(),
                                recipientAccount,
                                MoneyAmount.ofMinorUnits(300)),
                        FailurePoint.AFTER_EXTERNAL_BEFORE_RECORD);
                helper.fail("Expected the controlled ambiguous payment crash");
            } catch (SimulatedCrash expected) {
                // Recovery must replay the same external transaction ID.
            }

            coordinator.recoverIncomplete();

            helper.assertValueEqual(300L, mainChainBalance(fundingPlayer), "funding LC player-bank balance");
            helper.assertValueEqual(400L, accounts.balance(treasury).minorUnits(), "National Treasury LC balance");
            helper.assertValueEqual(325L, mainChainBalance(recipientPlayer), "recipient LC player-bank balance");
            helper.assertValueEqual(
                    TransactionState.CIVIC_COMMITTED,
                    coordinator.transaction(fiscalService, paymentRequestId).state(),
                    "recovered Civic payment state");
            helper.assertValueEqual(
                    MoneyAmount.ZERO,
                    ledger.reservedBalance(fiscalService, treasury),
                    "remaining National Treasury Reservation");
            helper.assertValueEqual(
                    EscrowState.SETTLED,
                    ledger.escrow(fiscalService, escrow.escrowId()).state(),
                    "settled National Treasury Escrow state");
            helper.assertValueEqual(
                    BudgetState.SPENT,
                    ledger.createBudget(budgetRequest).state(),
                    "spent National Treasury Budget state");
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
        payments.apply(new ExternalPayment(
                UUID.randomUUID(), treasury, fundingAccount, accounts.balance(treasury)));
        bankData.deleteAccount(fundingPlayerId);
        bankData.deleteAccount(recipientPlayerId);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void committedTreasuryPaymentRefundsThroughRealLcExactlyOnce(GameTestHelper helper) {
        UUID fundingPlayerId = UUID.randomUUID();
        UUID recipientPlayerId = UUID.randomUUID();
        BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
        AccountId treasury =
                new AccountId("nation:8a5affd3-8095-4ad5-ae6d-0740c0f3cc46:treasury");
        AccountId fundingAccount = new AccountId("player:" + fundingPlayerId);
        AccountId recipientAccount = new AccountId("player:" + recipientPlayerId);
        LightmansCurrencyFiscalAccounts accounts = LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
        accounts.create(treasury, FiscalAccountKind.NATIONAL_TREASURY, "Refund GameTest Treasury");
        LightmansCurrencyPayments payments = LightmansCurrencyPayments.live(helper.getLevel());
        clearFiscalAccount(bankData, accounts, payments, treasury);
        IBankAccount fundingPlayer = reset(bankData, fundingPlayerId, 1_000);
        IBankAccount recipientPlayer = reset(bankData, recipientPlayerId, 25);
        payments.apply(new ExternalPayment(
                UUID.randomUUID(), fundingAccount, treasury, MoneyAmount.ofMinorUnits(700)));

        Path temporaryDirectory = createTemporaryDirectory();
        try (CivicDatabase database = CivicDatabase.open(
                temporaryDirectory.resolve("civic.sqlite3"),
                new DatabaseIdentity(
                        UUID.randomUUID(), "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20"))) {
            ServiceIdentity fiscalService = new ServiceIdentity("civiceconomy-gametest");
            grant(
                    database,
                    fiscalService,
                    treasury,
                    FiscalCapability.RESERVE_FUNDS,
                    FiscalCapability.SETTLE_PAYMENT,
                    FiscalCapability.READ_ACCOUNT);
            grant(database, fiscalService, recipientAccount, FiscalCapability.REFUND_PAYMENT);
            FiscalLedger ledger = FiscalLedger.authorized(
                    database, accounts, session(database, fiscalService));
            Reservation reservation = ledger.reserve(new ReserveFunds(
                    fiscalService,
                    "refund-hold-" + UUID.randomUUID(),
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Real LC treasury refund GameTest"));
            PaymentCoordinator coordinator = PaymentCoordinator.authorized(
                    database, payments, session(database, fiscalService));
            PaymentTransaction original = coordinator.settle(
                    new SettleReservation(
                            fiscalService,
                            "refund-payment-" + UUID.randomUUID(),
                            reservation.reservationId(),
                            recipientAccount,
                            MoneyAmount.ofMinorUnits(300)),
                    FailurePoint.NONE);
            RefundPayment request = new RefundPayment(
                    fiscalService,
                    "refund-reversal-" + UUID.randomUUID(),
                    original.transactionId(),
                    MoneyAmount.ofMinorUnits(300),
                    "Real LC exact-once refund GameTest");

            PaymentTransaction refund = coordinator.refund(request, FailurePoint.NONE);
            PaymentTransaction replay = coordinator.refund(request, FailurePoint.NONE);

            helper.assertValueEqual(300L, mainChainBalance(fundingPlayer), "funding LC player-bank balance");
            helper.assertValueEqual(700L, accounts.balance(treasury).minorUnits(), "refunded National Treasury balance");
            helper.assertValueEqual(25L, mainChainBalance(recipientPlayer), "refunded recipient player-bank balance");
            helper.assertValueEqual(refund, replay, "idempotent LC refund replay");
            helper.assertValueEqual(PaymentKind.REFUND, refund.kind(), "refund transaction kind");
            helper.assertValueEqual(TransactionState.CIVIC_COMMITTED, refund.state(), "refund transaction state");
            helper.assertValueEqual(
                    original.transactionId(),
                    refund.parentTransactionId().orElseThrow(),
                    "refund parent transaction");
            helper.assertValueEqual(
                    MoneyAmount.ofMinorUnits(300),
                    coordinator.transaction(fiscalService, original.requestId()).refundedAmount(),
                    "original transaction refunded amount");
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
        payments.apply(new ExternalPayment(
                UUID.randomUUID(), treasury, fundingAccount, accounts.balance(treasury)));
        bankData.deleteAccount(fundingPlayerId);
        bankData.deleteAccount(recipientPlayerId);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void externallyAppliedTreasuryPaymentCompensatesThroughRealLcExactlyOnce(
            GameTestHelper helper) {
        UUID fundingPlayerId = UUID.randomUUID();
        UUID recipientPlayerId = UUID.randomUUID();
        BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
        AccountId treasury =
                new AccountId("nation:2c93ef6b-8f2d-48b6-af3d-55b4ef715c9f:treasury");
        AccountId fundingAccount = new AccountId("player:" + fundingPlayerId);
        AccountId recipientAccount = new AccountId("player:" + recipientPlayerId);
        LightmansCurrencyFiscalAccounts accounts = LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
        accounts.create(treasury, FiscalAccountKind.NATIONAL_TREASURY, "Compensation GameTest Treasury");
        LightmansCurrencyPayments payments = LightmansCurrencyPayments.live(helper.getLevel());
        clearFiscalAccount(bankData, accounts, payments, treasury);
        IBankAccount fundingPlayer = reset(bankData, fundingPlayerId, 1_000);
        IBankAccount recipientPlayer = reset(bankData, recipientPlayerId, 25);
        payments.apply(new ExternalPayment(
                UUID.randomUUID(), fundingAccount, treasury, MoneyAmount.ofMinorUnits(700)));

        Path temporaryDirectory = createTemporaryDirectory();
        Path databaseFile = temporaryDirectory.resolve("civic.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.randomUUID(), "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        String paymentRequestId = "compensation-payment-" + UUID.randomUUID();
        String compensationRequestId = "compensation-reversal-" + UUID.randomUUID();
        UUID transactionId;
        UUID reservationId;
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            ServiceIdentity fiscalService = new ServiceIdentity("civiceconomy-gametest");
            ServiceIdentity compensationService = new ServiceIdentity("civiceconomy-gametest-admin");
            grant(
                    database,
                    fiscalService,
                    treasury,
                    FiscalCapability.RESERVE_FUNDS,
                    FiscalCapability.SETTLE_PAYMENT,
                    FiscalCapability.READ_ACCOUNT);
            grant(database, compensationService, treasury, FiscalCapability.COMPENSATE_PAYMENT);
            FiscalLedger ledger = FiscalLedger.authorized(
                    database, accounts, session(database, fiscalService));
            Reservation reservation = ledger.reserve(new ReserveFunds(
                    fiscalService,
                    "compensation-hold-" + UUID.randomUUID(),
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Real LC compensation GameTest"));
            reservationId = reservation.reservationId();
            PaymentCoordinator coordinator = PaymentCoordinator.authorized(
                    database, payments, session(database, fiscalService));
            try {
                coordinator.settle(
                        new SettleReservation(
                                fiscalService,
                                paymentRequestId,
                                reservationId,
                                recipientAccount,
                                MoneyAmount.ofMinorUnits(300)),
                        FailurePoint.AFTER_EXTERNAL_APPLIED);
                helper.fail("Expected the controlled post-external payment crash");
            } catch (SimulatedCrash expected) {
                // The recorded external payment is now eligible for controlled compensation.
            }
            transactionId = coordinator.transaction(fiscalService, paymentRequestId).transactionId();
            try {
                PaymentCoordinator compensationCoordinator = PaymentCoordinator.authorized(
                        database, payments, session(database, compensationService));
                compensationCoordinator.compensate(
                        new CompensatePayment(
                                compensationService,
                                compensationRequestId,
                                transactionId,
                                "Controlled real LC compensation"),
                        FailurePoint.AFTER_COMPENSATION_BEFORE_RECORD);
                helper.fail("Expected the controlled post-compensation crash");
            } catch (SimulatedCrash expected) {
                // Recovery must replay the persisted reverse-transfer UUID.
            }
        }

        try (CivicDatabase reopened = CivicDatabase.open(databaseFile, identity)) {
            ServiceIdentity fiscalService = new ServiceIdentity("civiceconomy-gametest");
            ServiceIdentity compensationService = new ServiceIdentity("civiceconomy-gametest-admin");
            FiscalLedger ledger = FiscalLedger.authorized(
                    reopened, accounts, session(reopened, fiscalService));
            PaymentCoordinator recovery = PaymentCoordinator.authorized(
                    reopened, payments, session(reopened, compensationService));
            PaymentCoordinator fiscalReader = PaymentCoordinator.authorized(
                    reopened, payments, session(reopened, fiscalService));
            recovery.recoverIncomplete();
            PaymentTransaction compensated = recovery.compensate(
                    new CompensatePayment(
                            compensationService,
                            compensationRequestId,
                            transactionId,
                            "Controlled real LC compensation"),
                    FailurePoint.NONE);

            helper.assertValueEqual(300L, mainChainBalance(fundingPlayer), "funding LC player-bank balance");
            helper.assertValueEqual(700L, accounts.balance(treasury).minorUnits(), "compensated Treasury balance");
            helper.assertValueEqual(25L, mainChainBalance(recipientPlayer), "compensated recipient balance");
            helper.assertValueEqual(TransactionState.COMPENSATED, compensated.state(), "compensated payment state");
            helper.assertValueEqual(
                    MoneyAmount.ofMinorUnits(300),
                    ledger.reservedBalance(fiscalService, treasury),
                    "Reservation preserved after compensation");
            helper.assertValueEqual(
                    List.of(RecoveryAction.COMPENSATION_STARTED, RecoveryAction.COMPENSATION_COMPLETED),
                    fiscalReader.recoveryAudit(fiscalService, transactionId).stream()
                            .map(entry -> entry.action())
                            .toList(),
                    "durable compensation audit actions");
            ledger.release(new ReleaseReservation(
                    fiscalService,
                    "compensation-release-" + UUID.randomUUID(),
                    reservationId,
                    "Compensated GameTest payment no longer needs its hold"));
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
        payments.apply(new ExternalPayment(
                UUID.randomUUID(), treasury, fundingAccount, accounts.balance(treasury)));
        bankData.deleteAccount(fundingPlayerId);
        bankData.deleteAccount(recipientPlayerId);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void playerFiscalBillPaysNationalTreasuryThroughRealLcExactlyOnce(
            GameTestHelper helper) {
        UUID payerPlayerId = UUID.randomUUID();
        BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
        AccountId payerAccount = new AccountId("player:" + payerPlayerId);
        AccountId treasury =
                new AccountId("nation:4a1ae536-f6aa-4a92-a3a4-5ea8e22fbeea:treasury");
        LightmansCurrencyFiscalAccounts accounts = LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
        accounts.create(treasury, FiscalAccountKind.NATIONAL_TREASURY, "Fiscal Bill GameTest Treasury");
        LightmansCurrencyPayments payments = LightmansCurrencyPayments.live(helper.getLevel());
        clearFiscalAccount(bankData, accounts, payments, treasury);
        IBankAccount payer = reset(bankData, payerPlayerId, 500);
        LightmansCurrencyAccountBalances accountBalances =
                LightmansCurrencyAccountBalances.live(helper.getLevel());

        helper.assertValueEqual(
                500L,
                accountBalances.balance(payerAccount).minorUnits(),
                "production LC player balance adapter");
        try {
            accountBalances.balance(new AccountId("player:" + UUID.randomUUID()));
            helper.fail("Expected an unknown LC player account to fail closed");
        } catch (IllegalArgumentException expected) {
            // Expected: the balance adapter must not create unknown LC player accounts.
        }

        Path temporaryDirectory = createTemporaryDirectory();
        try (CivicDatabase database = CivicDatabase.open(
                temporaryDirectory.resolve("civic.sqlite3"),
                new DatabaseIdentity(
                        UUID.randomUUID(), "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20"))) {
            ServiceIdentity issuer = new ServiceIdentity("civiceconomy-gametest-revenue");
            ServiceIdentity payerService = new ServiceIdentity("civiceconomy-gametest-player");
            grant(database, issuer, treasury, FiscalCapability.ISSUE_BILL);
            grant(
                    database,
                    payerService,
                    payerAccount,
                    FiscalCapability.FUND_BILL,
                    FiscalCapability.SETTLE_PAYMENT,
                    FiscalCapability.READ_ACCOUNT);
            grant(database, payerService, treasury, FiscalCapability.READ_ACCOUNT);
            FiscalLedger issuerLedger = FiscalLedger.authorized(
                    database, accountBalances, session(database, issuer));
            FiscalLedger payerLedger = FiscalLedger.authorized(
                    database, accountBalances, session(database, payerService));
            IssueFiscalBill issue = new IssueFiscalBill(
                    issuer,
                    "issue-real-lc-fee-" + UUID.randomUUID(),
                    payerAccount,
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    FiscalBillKind.FEE,
                    "Real LC permit fee GameTest",
                    Instant.now().plusSeconds(3_600));
            FiscalBill issued = issuerLedger.issueBill(issue);
            FiscalBill funded = payerLedger.fundBill(new FundFiscalBill(
                    payerService,
                    "fund-real-lc-fee-" + UUID.randomUUID(),
                    issued.billId()));
            Escrow escrow = payerLedger.escrow(payerService, funded.escrowId().orElseThrow());
            SettleReservation settle = new SettleReservation(
                    payerService,
                    "pay-real-lc-fee-" + UUID.randomUUID(),
                    escrow.reservationId(),
                    treasury,
                    MoneyAmount.ofMinorUnits(300));
            PaymentCoordinator coordinator = PaymentCoordinator.authorized(
                    database, payments, session(database, payerService));

            PaymentTransaction paid = coordinator.settle(settle, FailurePoint.NONE);
            PaymentTransaction replay = coordinator.settle(settle, FailurePoint.NONE);

            helper.assertValueEqual(200L, mainChainBalance(payer), "Fiscal Bill payer LC balance");
            helper.assertValueEqual(300L, accounts.balance(treasury).minorUnits(), "Fiscal Bill Treasury balance");
            helper.assertValueEqual(paid, replay, "idempotent Fiscal Bill payment replay");
            helper.assertValueEqual(
                    FiscalBillState.PAID,
                    issuerLedger.issueBill(issue).state(),
                    "paid Fiscal Bill state");
            helper.assertValueEqual(
                    EscrowState.SETTLED,
                    payerLedger.escrow(payerService, escrow.escrowId()).state(),
                    "settled Fiscal Bill Escrow state");
            helper.assertValueEqual(
                    MoneyAmount.ZERO,
                    payerLedger.reservedBalance(payerService, payerAccount),
                    "Fiscal Bill hold");
            helper.assertValueEqual(
                    1,
                    payerLedger.ledgerEntries(payerService, payerAccount).size(),
                    "payer ledger entry count");
            helper.assertValueEqual(
                    LedgerDirection.OUTFLOW,
                    payerLedger.ledgerEntries(payerService, payerAccount).getFirst().direction(),
                    "payer ledger direction");
            helper.assertValueEqual(
                    1,
                    payerLedger.ledgerEntries(payerService, treasury).size(),
                    "Treasury ledger entry count");
            helper.assertValueEqual(
                    LedgerDirection.INFLOW,
                    payerLedger.ledgerEntries(payerService, treasury).getFirst().direction(),
                    "Treasury ledger direction");
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
        payments.apply(new ExternalPayment(
                UUID.randomUUID(), treasury, payerAccount, accounts.balance(treasury)));
        bankData.deleteAccount(payerPlayerId);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nationActivationCreatesRealLcNationalTreasury(GameTestHelper helper) {
        UUID teamId = UUID.randomUUID();
        UUID headId = UUID.randomUUID();
        NationTeam team = new NationTeam(teamId, headId, Set.of(headId));
        NationTeamDirectory teams = new NationTeamDirectory() {
            @Override
            public Optional<NationTeam> find(UUID requestedTeamId) {
                return teamId.equals(requestedTeamId) ? Optional.of(team) : Optional.empty();
            }

            @Override
            public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
                return headId.equals(playerId) ? Optional.of(team) : Optional.empty();
            }
        };
        Instant appliedAt = Instant.parse("2026-07-14T08:00:00Z");
        Path temporaryDirectory = createTemporaryDirectory();
        LightmansCurrencyFiscalAccounts accounts =
                LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());

        try (CivicDatabase database = CivicDatabase.open(
                temporaryDirectory.resolve("nation-activation.sqlite3"),
                new DatabaseIdentity(
                        UUID.randomUUID(),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"))) {
            NationApplication application = new NationApplicationRegistry(
                            database,
                            teams,
                            Clock.fixed(appliedAt, ZoneOffset.UTC))
                    .create(new CreateNationApplication(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "apply-real-lc-treasury-" + UUID.randomUUID(),
                            teamId,
                            headId,
                            appliedAt.plus(Duration.ofDays(7))));
            new OnlineTimeLedger(database).record(new RecordOnlineTime(
                    new ServiceIdentity("civiceconomy-server"),
                    "real-lc-treasury-candidate-" + UUID.randomUUID(),
                    headId,
                    appliedAt.toEpochMilli(),
                    appliedAt.plus(Duration.ofHours(1)).toEpochMilli()));
            NationActivationCoordinator coordinator = new NationActivationCoordinator(
                    database,
                    new LightmansCurrencyNationalTreasuryProvisioner(accounts),
                    NationFoundingPolicy.debugWorld(
                            2, Duration.ofDays(60), Duration.ZERO),
                    Clock.fixed(appliedAt.plus(Duration.ofHours(2)), ZoneOffset.UTC));

            ActivatedNation activated = coordinator.activate(new ActivateNationApplication(
                    new ServiceIdentity("civiceconomy-gametest"),
                    "activate-real-lc-treasury-" + UUID.randomUUID(),
                    application.applicationId(),
                    new Capital("minecraft:overworld", 0, 0),
                    "Real LC National Treasury GameTest"));

            helper.assertValueEqual(
                    0L,
                    accounts.balance(activated.treasuryAccountId()).minorUnits(),
                    "new real LC National Treasury balance");
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
        helper.succeed();
    }

    private static void clearFiscalAccount(
            BankDataCache bankData,
            LightmansCurrencyFiscalAccounts accounts,
            LightmansCurrencyPayments payments,
            AccountId accountId) {
        MoneyAmount balance = accounts.balance(accountId);
        if (balance.equals(MoneyAmount.ZERO)) {
            return;
        }
        UUID sinkPlayerId = UUID.randomUUID();
        reset(bankData, sinkPlayerId, 0);
        payments.apply(new ExternalPayment(
                UUID.randomUUID(), accountId, new AccountId("player:" + sinkPlayerId), balance));
        bankData.deleteAccount(sinkPlayerId);
    }

    private static IBankAccount reset(BankDataCache bankData, UUID playerId, long balance) {
        IBankAccount account = bankData.getAccount(playerId);
        account.getMoneyStorage().clear();
        if (balance > 0) {
            MoneyValue value = CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, balance);
            if (!BankAPI.getApi().BankDepositFromServer(account, value)) {
                throw new IllegalStateException("Unable to seed LC GameTest account " + playerId);
            }
        }
        return account;
    }

    private static long mainChainBalance(IBankAccount account) {
        MoneyValue unit = CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, 1);
        return account.getMoneyStorage().valueOf(unit.getUniqueName()).getCoreValue();
    }

    private static void grant(
            CivicDatabase database,
            ServiceIdentity serviceIdentity,
            AccountId accountId,
            FiscalCapability... capabilities) {
        FiscalAuthorization authorization = new FiscalAuthorization(database);
        authorization.register(new RegisterFiscalService(
                serviceIdentity, "civiceconomy", "Civic GameTest " + serviceIdentity.value()));
        for (FiscalCapability capability : capabilities) {
            authorization.grant(new GrantFiscalCapability(
                    new ServiceIdentity("civiceconomy-gametest-admin"),
                    "grant-" + UUID.randomUUID(),
                    serviceIdentity,
                    capability,
                    accountId,
                    "Civic real-integration GameTest grant"));
        }
    }

    private static FiscalServiceSession session(
            CivicDatabase database, ServiceIdentity serviceIdentity) {
        return new FiscalAuthorization(database).openSession(serviceIdentity);
    }

    private static Path createTemporaryDirectory() {
        try {
            return Files.createTempDirectory("civiceconomy-gametest-");
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to create Civic GameTest directory", failure);
        }
    }

    private static void deleteTemporaryDirectory(Path directory) {
        try (var files = Files.walk(directory)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException failure) {
                    throw new IllegalStateException("Unable to delete Civic GameTest file " + path, failure);
                }
            });
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to clean Civic GameTest directory " + directory, failure);
        }
    }
}
