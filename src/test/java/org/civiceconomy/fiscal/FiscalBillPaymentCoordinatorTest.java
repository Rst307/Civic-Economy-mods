package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FiscalBillPaymentCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-07-16T13:00:00Z");
    private static final UUID PAYER =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID IMPOSTOR =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @TempDir Path temporaryDirectory;

    @Test
    void foreignPayerFailsBeforePaymentServiceProvisioningOrExternalEffect() {
        try (CivicDatabase database = database()) {
            FiscalBill funded = fundedBill(database, "foreign-payment-bill", PAYER, 300L);
            AtomicInteger externalCalls = new AtomicInteger();
            FiscalBillPaymentCoordinator coordinator = new FiscalBillPaymentCoordinator(
                    database,
                    ignored -> externalCalls.incrementAndGet(),
                    authorization -> authorization.openSession(
                            FiscalBillPaymentServiceProvisioner.SERVICE_IDENTITY,
                            "civiceconomy"));

            assertThrows(
                    SecurityException.class,
                    () -> coordinator.pay(IMPOSTOR, funded.billId(), "impostor-payment"));

            assertNull(database.fiscalService(
                    FiscalBillPaymentServiceProvisioner.SERVICE_IDENTITY.value()));
            assertNull(database.paymentTransaction("impostor-payment"));
            assertEquals(0, externalCalls.get());
        }
    }

    @Test
    void exactPayerPaysThePersistedRemainingAmountToThePersistedBeneficiaryOnce() {
        try (CivicDatabase database = database()) {
            FiscalBill funded = fundedBill(database, "exact-payment-bill", PAYER, 300L);
            AtomicInteger externalCalls = new AtomicInteger();
            AtomicReference<ExternalPayment> externalPayment = new AtomicReference<>();
            FiscalBillPaymentCoordinator coordinator = new FiscalBillPaymentCoordinator(
                    database,
                    payment -> {
                        externalCalls.incrementAndGet();
                        externalPayment.set(payment);
                    },
                    authorization -> authorization.openSession(
                            FiscalBillPaymentServiceProvisioner.SERVICE_IDENTITY,
                            "civiceconomy"));

            PaymentTransaction paid = coordinator.pay(
                    PAYER, funded.billId(), "exact-payment");

            assertEquals(TransactionState.CIVIC_COMMITTED, paid.state());
            assertEquals(new AccountId("player:" + PAYER), paid.sourceAccount());
            assertEquals(funded.beneficiaryAccount(), paid.recipientAccount());
            assertEquals(MoneyAmount.ofMinorUnits(300L), paid.amount());
            assertEquals(paid, coordinator.pay(PAYER, funded.billId(), "exact-payment"));
            assertEquals(1, externalCalls.get());
            assertEquals(paid.transactionId(), externalPayment.get().transactionId());
            assertEquals(paid.sourceAccount(), externalPayment.get().sourceAccount());
            assertEquals(paid.recipientAccount(), externalPayment.get().recipientAccount());
            assertEquals(paid.amount(), externalPayment.get().amount());

            FiscalBill bill = FiscalLedger.toFiscalBill(database.fiscalBill(funded.billId()));
            assertEquals(FiscalBillState.PAID, bill.state());
            assertEquals(MoneyAmount.ofMinorUnits(300L), bill.settledAmount());
            assertEquals(MoneyAmount.ZERO, bill.remainingAmount());

            var authorization = new FiscalAuthorization(database)
                    .describe(FiscalBillPaymentServiceProvisioner.SERVICE_IDENTITY);
            assertEquals(FiscalServiceState.ENABLED, authorization.state());
            assertEquals(1, authorization.grants().size());
            assertEquals(
                    FiscalCapability.SETTLE_PAYMENT,
                    authorization.grants().getFirst().grant().capability());
            assertEquals(
                    new AccountId("player:" + PAYER),
                    authorization.grants().getFirst().grant().accountId());
            assertTrue(authorization.grants().getFirst().active());
        }
    }

    @Test
    void paymentPhasesKeepExternalLcBetweenSqlitePreparationAndCommit() {
        try (CivicDatabase database = database()) {
            FiscalBill funded = fundedBill(database, "phased-payment-bill", PAYER, 300L);
            AtomicInteger externalCalls = new AtomicInteger();
            FiscalBillPaymentCoordinator coordinator = new FiscalBillPaymentCoordinator(
                    database,
                    ignored -> externalCalls.incrementAndGet(),
                    authorization -> authorization.openSession(
                            FiscalBillPaymentServiceProvisioner.SERVICE_IDENTITY,
                            "civiceconomy"));

            PreparedFiscalBillPayment prepared = coordinator.prepare(
                    PAYER, funded.billId(), "phased-payment");

            assertEquals(TransactionState.PREPARED, prepared.transaction().state());
            assertEquals(0, externalCalls.get());
            assertEquals(
                    FiscalBillState.RESERVED,
                    FiscalLedger.toFiscalBill(database.fiscalBill(funded.billId())).state());

            coordinator.applyExternal(prepared);

            assertEquals(1, externalCalls.get());
            assertEquals(
                    TransactionState.PREPARED.name(),
                    database.paymentTransaction(prepared.transaction().transactionId()).state());
            assertEquals(
                    FiscalBillState.RESERVED,
                    FiscalLedger.toFiscalBill(database.fiscalBill(funded.billId())).state());

            PaymentTransaction committed = coordinator.commit(prepared);

            assertEquals(TransactionState.CIVIC_COMMITTED, committed.state());
            assertEquals(
                    FiscalBillState.PAID,
                    FiscalLedger.toFiscalBill(database.fiscalBill(funded.billId())).state());
        }
    }

    private static FiscalBill fundedBill(
            CivicDatabase database, String requestId, UUID payer, long amount) {
        FiscalBill issued = FiscalLedger.toFiscalBill(database.issueFiscalBill(
                UUID.randomUUID(),
                FiscalBillFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                requestId,
                "player:" + payer,
                "nation:11111111-1111-1111-1111-111111111111:treasury",
                amount,
                FiscalBillKind.FEE.name(),
                "Payment " + requestId,
                NOW.plusSeconds(3_600L).toEpochMilli()));
        return new FiscalLedger(
                        database,
                        ignored -> MoneyAmount.ofMinorUnits(1_000L),
                        Clock.fixed(NOW, ZoneOffset.UTC))
                .fundBill(new FundFiscalBill(
                        FiscalBillFundingServiceProvisioner.SERVICE_IDENTITY,
                        "fund-" + requestId,
                        issued.billId()));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("fiscal-bill-payment.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
