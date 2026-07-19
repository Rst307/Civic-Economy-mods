package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FiscalLedgerEntriesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void committedPaymentCreatesOneImmutableEntryPerAccountAndReplayDoesNotDuplicate() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId recipient = new AccountId("organization:bridge-builder:fiscal");

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = new FiscalLedger(
                    database,
                    account -> account.equals(treasury)
                            ? MoneyAmount.ofMinorUnits(1_000)
                            : MoneyAmount.ZERO);
            Reservation reservation = ledger.reserve(new ReserveFunds(
                    new ServiceIdentity("public-works"),
                    "ledger-payment-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Ledger payment fixture"));
            PaymentCoordinator coordinator = new PaymentCoordinator(database, ignored -> {});
            SettleReservation request = new SettleReservation(
                    new ServiceIdentity("public-works"),
                    "ledger-payment",
                    reservation.reservationId(),
                    recipient,
                    MoneyAmount.ofMinorUnits(300));

            PaymentTransaction payment = coordinator.settle(request, FailurePoint.NONE);
            assertEquals(payment, coordinator.settle(request, FailurePoint.NONE));

            List<LedgerEntry> treasuryEntries = ledger.ledgerEntries(treasury);
            List<LedgerEntry> recipientEntries = ledger.ledgerEntries(recipient);
            assertEquals(1, treasuryEntries.size());
            assertEquals(1, recipientEntries.size());
            assertEquals(payment.transactionId(), treasuryEntries.getFirst().transactionId());
            assertEquals(LedgerDirection.OUTFLOW, treasuryEntries.getFirst().direction());
            assertEquals(recipient, treasuryEntries.getFirst().counterpartyAccount());
            assertEquals(MoneyAmount.ofMinorUnits(300), treasuryEntries.getFirst().amount());
            assertEquals(PaymentKind.PAYMENT, treasuryEntries.getFirst().transactionKind());
            assertEquals(payment.transactionId(), recipientEntries.getFirst().transactionId());
            assertEquals(LedgerDirection.INFLOW, recipientEntries.getFirst().direction());
            assertEquals(treasury, recipientEntries.getFirst().counterpartyAccount());
        }
    }

    @Test
    void refundCreatesASeparateReversePairWithoutMutatingPaymentEntries() {
        AccountId treasury = new AccountId("nation:aurora:treasury");
        AccountId recipient = new AccountId("organization:bridge-builder:fiscal");

        try (CivicDatabase database = database()) {
            FiscalLedger ledger = new FiscalLedger(
                    database,
                    account -> account.equals(treasury)
                            ? MoneyAmount.ofMinorUnits(1_000)
                            : MoneyAmount.ZERO);
            Reservation reservation = ledger.reserve(new ReserveFunds(
                    new ServiceIdentity("public-works"),
                    "ledger-refund-hold",
                    treasury,
                    MoneyAmount.ofMinorUnits(300),
                    "Ledger refund fixture"));
            PaymentCoordinator coordinator = new PaymentCoordinator(database, ignored -> {});
            PaymentTransaction payment = coordinator.settle(
                    new SettleReservation(
                            new ServiceIdentity("public-works"),
                            "ledger-refund-payment",
                            reservation.reservationId(),
                            recipient,
                            MoneyAmount.ofMinorUnits(300)),
                    FailurePoint.NONE);
            RefundPayment request = new RefundPayment(
                    new ServiceIdentity("public-works"),
                    "ledger-refund",
                    payment.transactionId(),
                    MoneyAmount.ofMinorUnits(100),
                    "Partial contract refund");

            PaymentTransaction refund = coordinator.refund(request, FailurePoint.NONE);
            assertEquals(refund, coordinator.refund(request, FailurePoint.NONE));

            List<LedgerEntry> treasuryEntries = ledger.ledgerEntries(treasury);
            List<LedgerEntry> recipientEntries = ledger.ledgerEntries(recipient);
            assertEquals(2, treasuryEntries.size());
            assertEquals(2, recipientEntries.size());
            assertEquals(PaymentKind.PAYMENT, treasuryEntries.get(0).transactionKind());
            assertEquals(LedgerDirection.OUTFLOW, treasuryEntries.get(0).direction());
            assertEquals(MoneyAmount.ofMinorUnits(300), treasuryEntries.get(0).amount());
            assertEquals(refund.transactionId(), treasuryEntries.get(1).transactionId());
            assertEquals(PaymentKind.REFUND, treasuryEntries.get(1).transactionKind());
            assertEquals(LedgerDirection.INFLOW, treasuryEntries.get(1).direction());
            assertEquals(MoneyAmount.ofMinorUnits(100), treasuryEntries.get(1).amount());
            assertEquals(PaymentKind.REFUND, recipientEntries.get(1).transactionKind());
            assertEquals(LedgerDirection.OUTFLOW, recipientEntries.get(1).direction());
        }
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("ledger.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("4b617458-7f03-4fd2-a94e-4dc37ecbd682"),
                        "0.1.0",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
