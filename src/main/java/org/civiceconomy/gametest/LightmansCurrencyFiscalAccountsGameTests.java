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
import java.util.List;
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
import org.civiceconomy.fiscal.FiscalLedger;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.PaymentCoordinator;
import org.civiceconomy.fiscal.PaymentKind;
import org.civiceconomy.fiscal.PaymentTransaction;
import org.civiceconomy.fiscal.RefundPayment;
import org.civiceconomy.fiscal.RecoveryAction;
import org.civiceconomy.fiscal.ReleaseReservation;
import org.civiceconomy.fiscal.Reservation;
import org.civiceconomy.fiscal.ReserveFunds;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.SettleReservation;
import org.civiceconomy.fiscal.SimulatedCrash;
import org.civiceconomy.fiscal.TransactionState;
import org.civiceconomy.integration.lightmanscurrency.FiscalAccountKind;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyFiscalAccounts;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyPayments;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;

@GameTestHolder(CivicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LightmansCurrencyFiscalAccountsGameTests {
    private LightmansCurrencyFiscalAccountsGameTests() {}

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
            FiscalLedger ledger = new FiscalLedger(database, accounts);
            CreateBudget budgetRequest = new CreateBudget(
                    new ServiceIdentity("civiceconomy-gametest"),
                    "treasury-budget-" + UUID.randomUUID(),
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "GAMETEST:TREASURY_SETTLEMENT",
                    "Real LC treasury settlement GameTest",
                    Instant.now().plusSeconds(3_600));
            Budget draft = ledger.createBudget(budgetRequest);
            Budget approved = ledger.approveBudget(new ApproveBudget(
                    new ServiceIdentity("civiceconomy-gametest-approver"),
                    "approve-treasury-budget-" + UUID.randomUUID(),
                    draft.budgetId()));
            Escrow escrow = ledger.escrow(approved.escrowId().orElseThrow());
            String paymentRequestId = "treasury-payment-" + UUID.randomUUID();
            PaymentCoordinator coordinator = new PaymentCoordinator(database, payments);
            try {
                coordinator.settle(
                        new SettleReservation(
                                new ServiceIdentity("civiceconomy-gametest"),
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
                    coordinator.transaction(paymentRequestId).state(),
                    "recovered Civic payment state");
            helper.assertValueEqual(
                    MoneyAmount.ZERO, ledger.reservedBalance(treasury), "remaining National Treasury Reservation");
            helper.assertValueEqual(
                    EscrowState.SETTLED,
                    ledger.escrow(escrow.escrowId()).state(),
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
            FiscalLedger ledger = new FiscalLedger(database, accounts);
            Reservation reservation = ledger.reserve(new ReserveFunds(
                    new ServiceIdentity("civiceconomy-gametest"),
                    "refund-hold-" + UUID.randomUUID(),
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Real LC treasury refund GameTest"));
            PaymentCoordinator coordinator = new PaymentCoordinator(database, payments);
            PaymentTransaction original = coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("civiceconomy-gametest"),
                            "refund-payment-" + UUID.randomUUID(),
                            reservation.reservationId(),
                            recipientAccount,
                            MoneyAmount.ofMinorUnits(300)),
                    FailurePoint.NONE);
            RefundPayment request = new RefundPayment(
                    new ServiceIdentity("civiceconomy-gametest"),
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
                    coordinator.transaction(original.requestId()).refundedAmount(),
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
            FiscalLedger ledger = new FiscalLedger(database, accounts);
            Reservation reservation = ledger.reserve(new ReserveFunds(
                    new ServiceIdentity("civiceconomy-gametest"),
                    "compensation-hold-" + UUID.randomUUID(),
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Real LC compensation GameTest"));
            reservationId = reservation.reservationId();
            PaymentCoordinator coordinator = new PaymentCoordinator(database, payments);
            try {
                coordinator.settle(
                        new SettleReservation(
                                new ServiceIdentity("civiceconomy-gametest"),
                                paymentRequestId,
                                reservationId,
                                recipientAccount,
                                MoneyAmount.ofMinorUnits(300)),
                        FailurePoint.AFTER_EXTERNAL_APPLIED);
                helper.fail("Expected the controlled post-external payment crash");
            } catch (SimulatedCrash expected) {
                // The recorded external payment is now eligible for controlled compensation.
            }
            transactionId = coordinator.transaction(paymentRequestId).transactionId();
            try {
                coordinator.compensate(
                        new CompensatePayment(
                                new ServiceIdentity("civiceconomy-gametest-admin"),
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
            FiscalLedger ledger = new FiscalLedger(reopened, accounts);
            PaymentCoordinator recovery = new PaymentCoordinator(reopened, payments);
            recovery.recoverIncomplete();
            PaymentTransaction compensated = recovery.compensate(
                    new CompensatePayment(
                            new ServiceIdentity("civiceconomy-gametest-admin"),
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
                    ledger.reservedBalance(treasury),
                    "Reservation preserved after compensation");
            helper.assertValueEqual(
                    List.of(RecoveryAction.COMPENSATION_STARTED, RecoveryAction.COMPENSATION_COMPLETED),
                    recovery.recoveryAudit(transactionId).stream().map(entry -> entry.action()).toList(),
                    "durable compensation audit actions");
            ledger.release(new ReleaseReservation(
                    new ServiceIdentity("civiceconomy-gametest"),
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
