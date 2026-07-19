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
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FiscalBillCancellationCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-07-16T14:00:00Z");
    private static final UUID PAYER =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID IMPOSTOR =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @TempDir Path temporaryDirectory;

    @Test
    void foreignPayerFailsBeforeCancellationServiceProvisioningOrRelease() {
        try (CivicDatabase database = database()) {
            FiscalBill funded = fundedBill(database, "foreign-cancellation-bill", PAYER, 300L);
            FiscalBillCancellationCoordinator coordinator =
                    new FiscalBillCancellationCoordinator(
                            database,
                            Clock.fixed(NOW, ZoneOffset.UTC),
                            authorization -> authorization.openSession(
                                    FiscalBillCancellationServiceProvisioner.SERVICE_IDENTITY,
                                    "civiceconomy"));

            assertThrows(
                    SecurityException.class,
                    () -> coordinator.cancel(
                            IMPOSTOR,
                            funded.billId(),
                            "impostor-cancellation",
                            "Not my Bill"));

            assertNull(database.fiscalService(
                    FiscalBillCancellationServiceProvisioner.SERVICE_IDENTITY.value()));
            assertNull(database.reservationRelease(
                    FiscalBillCancellationServiceProvisioner.SERVICE_IDENTITY.value(),
                    "impostor-cancellation"));
        }
    }

    @Test
    void exactPayerReleasesTheFundedBillExactlyOnceWithoutPayment() {
        try (CivicDatabase database = database()) {
            FiscalBill funded = fundedBill(database, "exact-cancellation-bill", PAYER, 300L);
            UUID escrowId = funded.escrowId().orElseThrow();
            UUID reservationId = FiscalLedger.toEscrow(database.escrow(escrowId)).reservationId();
            FiscalBillCancellationCoordinator coordinator =
                    new FiscalBillCancellationCoordinator(
                            database,
                            Clock.fixed(NOW, ZoneOffset.UTC),
                            authorization -> authorization.openSession(
                                    FiscalBillCancellationServiceProvisioner.SERVICE_IDENTITY,
                                    "civiceconomy"));

            FiscalBill cancelled = coordinator.cancel(
                    PAYER,
                    funded.billId(),
                    "exact-cancellation",
                    "Payer withdrew the request");

            assertEquals(FiscalBillState.CANCELLED, cancelled.state());
            assertEquals(MoneyAmount.ZERO, cancelled.settledAmount());
            assertEquals(MoneyAmount.ofMinorUnits(300L), cancelled.remainingAmount());
            assertEquals(
                    EscrowState.RELEASED,
                    FiscalLedger.toEscrow(database.escrow(escrowId)).state());
            assertEquals(
                    reservationId,
                    database.reservationRelease(
                                    FiscalBillCancellationServiceProvisioner
                                            .SERVICE_IDENTITY
                                            .value(),
                                    "exact-cancellation")
                            .reservationId());
            assertNull(database.paymentTransaction("exact-cancellation"));
            assertEquals(
                    cancelled,
                    coordinator.cancel(
                            PAYER,
                            funded.billId(),
                            "exact-cancellation",
                            "Payer withdrew the request"));

            var authorization = new FiscalAuthorization(database)
                    .describe(FiscalBillCancellationServiceProvisioner.SERVICE_IDENTITY);
            assertEquals(FiscalServiceState.ENABLED, authorization.state());
            assertEquals(1, authorization.grants().size());
            assertEquals(
                    FiscalCapability.RESERVE_FUNDS,
                    authorization.grants().getFirst().grant().capability());
            assertEquals(
                    new AccountId("player:" + PAYER),
                    authorization.grants().getFirst().grant().accountId());
            assertTrue(authorization.grants().getFirst().active());
        }
    }

    @Test
    void preparedPaymentBlocksCancellationWithoutReleasingTheHold() {
        try (CivicDatabase database = database()) {
            FiscalBill funded = fundedBill(database, "pending-payment-bill", PAYER, 300L);
            Escrow escrow = FiscalLedger.toEscrow(
                    database.escrow(funded.escrowId().orElseThrow()));
            new PaymentCoordinator(database, ignored -> {}).prepare(new SettleReservation(
                    FiscalBillPaymentServiceProvisioner.SERVICE_IDENTITY,
                    "pending-before-cancellation",
                    escrow.reservationId(),
                    funded.beneficiaryAccount(),
                    MoneyAmount.ofMinorUnits(300L)));
            FiscalBillCancellationCoordinator coordinator =
                    new FiscalBillCancellationCoordinator(
                            database,
                            Clock.fixed(NOW, ZoneOffset.UTC),
                            authorization -> authorization.openSession(
                                    FiscalBillCancellationServiceProvisioner.SERVICE_IDENTITY,
                                    "civiceconomy"));

            assertThrows(
                    ReservationHasPendingPaymentException.class,
                    () -> coordinator.cancel(
                            PAYER,
                            funded.billId(),
                            "blocked-cancellation",
                            "Payment is already pending"));

            assertNull(database.reservationRelease(
                    FiscalBillCancellationServiceProvisioner.SERVICE_IDENTITY.value(),
                    "blocked-cancellation"));
            assertEquals(
                    FiscalBillState.RESERVED,
                    FiscalLedger.toFiscalBill(database.fiscalBill(funded.billId())).state());
            assertEquals(
                    EscrowState.RESERVED,
                    FiscalLedger.toEscrow(database.escrow(escrow.escrowId())).state());
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
                "Cancellation " + requestId,
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
                temporaryDirectory.resolve("fiscal-bill-cancellation.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
