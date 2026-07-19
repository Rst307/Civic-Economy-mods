package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FiscalLedgerBillTest {
    private static final Instant NOW = Instant.parse("2026-07-14T05:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void issuedFiscalBillSurvivesRestartWithoutReservingPayerFunds() {
        AccountId payer = new AccountId("player:river");
        AccountId treasury = new AccountId("nation:aurora:treasury");
        IssueFiscalBill request = new IssueFiscalBill(
                new ServiceIdentity("aurora-revenue-office"),
                "issue-building-fee",
                payer,
                treasury,
                MoneyAmount.ofMinorUnits(300),
                FiscalBillKind.FEE,
                "Building permit fee",
                NOW.plusSeconds(604_800));
        FiscalBill issued;

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database, payer);

            issued = ledger.issueBill(request);

            assertEquals(FiscalBillState.ISSUED, issued.state());
            assertEquals(Optional.empty(), issued.escrowId());
            assertEquals(MoneyAmount.ZERO, issued.settledAmount());
            assertEquals(MoneyAmount.ofMinorUnits(300), issued.remainingAmount());
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(payer));
            assertEquals(MoneyAmount.ofMinorUnits(1_000), ledger.availableBalance(payer));
        }

        try (CivicDatabase reopened = database()) {
            FiscalLedger ledger = ledger(reopened, payer);
            assertEquals(issued, ledger.issueBill(request));
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(payer));
        }
    }

    @Test
    void fundingFiscalBillCreatesARecipientBoundEscrowExactlyOnce() {
        AccountId payer = new AccountId("player:river");
        AccountId treasury = new AccountId("nation:aurora:treasury");
        IssueFiscalBill issue = new IssueFiscalBill(
                new ServiceIdentity("aurora-revenue-office"),
                "issue-market-fee",
                payer,
                treasury,
                MoneyAmount.ofMinorUnits(300),
                FiscalBillKind.FEE,
                "Market permit fee",
                NOW.plusSeconds(604_800));
        FundFiscalBill funding;
        FiscalBill funded;

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database, payer);
            FiscalBill issued = ledger.issueBill(issue);
            funding = new FundFiscalBill(
                    new ServiceIdentity("civiceconomy-player-payment"),
                    "fund-market-fee",
                    issued.billId());

            funded = ledger.fundBill(funding);

            assertEquals(FiscalBillState.RESERVED, funded.state());
            Escrow escrow = ledger.escrow(funded.escrowId().orElseThrow());
            assertEquals(Optional.of(treasury), escrow.requiredRecipientAccount());
            assertEquals(MoneyAmount.ofMinorUnits(300), ledger.reservedBalance(payer));
            assertEquals(MoneyAmount.ofMinorUnits(700), ledger.availableBalance(payer));
        }

        try (CivicDatabase reopened = database()) {
            FiscalLedger ledger = ledger(reopened, payer);
            assertEquals(funded, ledger.fundBill(funding));
            assertEquals(MoneyAmount.ofMinorUnits(300), ledger.reservedBalance(payer));
        }
    }

    @Test
    void fiscalBillRejectsRedirectedPaymentAndTracksPartialThenFinalPayment() {
        AccountId payer = new AccountId("player:river");
        AccountId treasury = new AccountId("nation:aurora:treasury");
        IssueFiscalBill issue = new IssueFiscalBill(
                new ServiceIdentity("aurora-revenue-office"),
                "issue-trade-dues",
                payer,
                treasury,
                MoneyAmount.ofMinorUnits(300),
                FiscalBillKind.DUES,
                "Trade guild dues",
                NOW.plusSeconds(604_800));

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database, payer);
            FiscalBill issued = ledger.issueBill(issue);
            FiscalBill funded = ledger.fundBill(new FundFiscalBill(
                    new ServiceIdentity("civiceconomy-player-payment"),
                    "fund-trade-dues",
                    issued.billId()));
            Escrow escrow = ledger.escrow(funded.escrowId().orElseThrow());
            PaymentCoordinator coordinator = new PaymentCoordinator(database, ignored -> {});

            assertThrows(
                    PaymentRecipientMismatchException.class,
                    () -> coordinator.settle(
                            new SettleReservation(
                                    new ServiceIdentity("civiceconomy-player-payment"),
                                    "redirect-trade-dues",
                                    escrow.reservationId(),
                                    new AccountId("player:attacker"),
                                    MoneyAmount.ofMinorUnits(100)),
                            FailurePoint.NONE));
            assertEquals(MoneyAmount.ofMinorUnits(300), ledger.reservedBalance(payer));
            assertEquals(FiscalBillState.RESERVED, ledger.issueBill(issue).state());

            coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("civiceconomy-player-payment"),
                            "partial-trade-dues",
                            escrow.reservationId(),
                            treasury,
                            MoneyAmount.ofMinorUnits(100)),
                    FailurePoint.NONE);
            FiscalBill partial = ledger.issueBill(issue);
            assertEquals(FiscalBillState.PARTIALLY_PAID, partial.state());
            assertEquals(MoneyAmount.ofMinorUnits(100), partial.settledAmount());
            assertEquals(MoneyAmount.ofMinorUnits(200), partial.remainingAmount());

            coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("civiceconomy-player-payment"),
                            "final-trade-dues",
                            escrow.reservationId(),
                            treasury,
                            MoneyAmount.ofMinorUnits(200)),
                    FailurePoint.NONE);
            FiscalBill paid = ledger.issueBill(issue);
            assertEquals(FiscalBillState.PAID, paid.state());
            assertEquals(MoneyAmount.ofMinorUnits(300), paid.settledAmount());
            assertEquals(MoneyAmount.ZERO, paid.remainingAmount());
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(payer));
        }
    }

    @Test
    void releasingFundedBillCancelsItAndPreservesPartialPayment() {
        AccountId payer = new AccountId("player:river");
        AccountId treasury = new AccountId("nation:aurora:treasury");
        IssueFiscalBill issue = new IssueFiscalBill(
                new ServiceIdentity("aurora-revenue-office"),
                "issue-cancelled-fee",
                payer,
                treasury,
                MoneyAmount.ofMinorUnits(300),
                FiscalBillKind.FEE,
                "Cancelled inspection fee",
                NOW.plusSeconds(604_800));

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database, payer);
            FiscalBill funded = ledger.fundBill(new FundFiscalBill(
                    new ServiceIdentity("civiceconomy-player-payment"),
                    "fund-cancelled-fee",
                    ledger.issueBill(issue).billId()));
            Escrow escrow = ledger.escrow(funded.escrowId().orElseThrow());
            new PaymentCoordinator(database, ignored -> {}).settle(
                    new SettleReservation(
                            new ServiceIdentity("civiceconomy-player-payment"),
                            "partial-cancelled-fee",
                            escrow.reservationId(),
                            treasury,
                            MoneyAmount.ofMinorUnits(100)),
                    FailurePoint.NONE);

            ledger.release(new ReleaseReservation(
                    new ServiceIdentity("aurora-revenue-office"),
                    "cancel-inspection-fee",
                    escrow.reservationId(),
                    "Inspection withdrawn"));

            FiscalBill cancelled = ledger.issueBill(issue);
            assertEquals(FiscalBillState.CANCELLED, cancelled.state());
            assertEquals(MoneyAmount.ofMinorUnits(100), cancelled.settledAmount());
            assertEquals(MoneyAmount.ofMinorUnits(200), cancelled.remainingAmount());
            assertEquals(MoneyAmount.ZERO, ledger.reservedBalance(payer));
        }
    }

    @Test
    void fundedFiscalBillExpiresWithItsEscrowAtTheDueDate() {
        AccountId payer = new AccountId("player:river");
        AccountId treasury = new AccountId("nation:aurora:treasury");
        Instant dueAt = NOW.plusSeconds(60);
        IssueFiscalBill issue = new IssueFiscalBill(
                new ServiceIdentity("aurora-revenue-office"),
                "issue-expiring-tax",
                payer,
                treasury,
                MoneyAmount.ofMinorUnits(300),
                FiscalBillKind.TAX,
                "Expiring property tax",
                dueAt);

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = ledger(database, payer);
            FiscalBill funded = ledger.fundBill(new FundFiscalBill(
                    new ServiceIdentity("civiceconomy-player-payment"),
                    "fund-expiring-tax",
                    ledger.issueBill(issue).billId()));

            FiscalLedger atDueDate = ledger(database, payer, dueAt);
            atDueDate.expireEscrow(new ExpireEscrow(
                    new ServiceIdentity("civiceconomy-server"),
                    "expire-property-tax",
                    funded.escrowId().orElseThrow()));

            FiscalBill expired = atDueDate.issueBill(issue);
            assertEquals(FiscalBillState.EXPIRED, expired.state());
            assertEquals(MoneyAmount.ZERO, expired.settledAmount());
            assertEquals(MoneyAmount.ofMinorUnits(300), expired.remainingAmount());
            assertEquals(MoneyAmount.ZERO, atDueDate.reservedBalance(payer));
        }
    }

    private FiscalLedger ledger(CivicDatabase database, AccountId payer) {
        return ledger(database, payer, NOW);
    }

    private FiscalLedger ledger(CivicDatabase database, AccountId payer, Instant now) {
        return new FiscalLedger(
                database,
                account -> account.equals(payer)
                        ? MoneyAmount.ofMinorUnits(1_000)
                        : MoneyAmount.ZERO,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("bill.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("4b617458-7f03-4fd2-a94e-4dc37ecbd682"),
                        "0.1.0",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
