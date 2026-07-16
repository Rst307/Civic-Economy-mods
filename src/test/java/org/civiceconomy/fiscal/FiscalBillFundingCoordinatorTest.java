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

class FiscalBillFundingCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final UUID PAYER =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID IMPOSTOR =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @TempDir Path temporaryDirectory;

    @Test
    void foreignPayerFailsBeforeFundingServiceProvisioningOrHoldCreation() {
        try (CivicDatabase database = database()) {
            FiscalBill issued = issue(database, "foreign-payer-bill", PAYER, 300L);
            FiscalBillFundingCoordinator coordinator = new FiscalBillFundingCoordinator(
                    database,
                    ignored -> MoneyAmount.ofMinorUnits(1_000L),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    authorization -> authorization.openSession(
                            FiscalBillFundingServiceProvisioner.SERVICE_IDENTITY,
                            "civiceconomy"));

            assertThrows(
                    SecurityException.class,
                    () -> coordinator.fund(IMPOSTOR, issued.billId(), "impostor-funding"));
            assertNull(database.fiscalService(
                    FiscalBillFundingServiceProvisioner.SERVICE_IDENTITY.value()));
            assertNull(database.fiscalBill(issued.billId()).escrowId());
        }
    }

    @Test
    void exactPayerFundsThePersistedBeneficiaryExactlyOnce() {
        try (CivicDatabase database = database()) {
            FiscalBill issued = issue(database, "exact-payer-bill", PAYER, 300L);
            FiscalBillFundingCoordinator coordinator = new FiscalBillFundingCoordinator(
                    database,
                    ignored -> MoneyAmount.ofMinorUnits(1_000L),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    authorization -> authorization.openSession(
                            FiscalBillFundingServiceProvisioner.SERVICE_IDENTITY,
                            "civiceconomy"));

            FiscalBill funded = coordinator.fund(PAYER, issued.billId(), "exact-funding");

            assertEquals(FiscalBillState.RESERVED, funded.state());
            assertEquals(new AccountId("player:" + PAYER), funded.payerAccount());
            assertEquals(issued.beneficiaryAccount(), funded.beneficiaryAccount());
            assertTrue(funded.escrowId().isPresent());
            var escrow = database.escrow(funded.escrowId().orElseThrow());
            assertEquals("player:" + PAYER, escrow.sourceAccount());
            assertEquals(issued.beneficiaryAccount().value(), escrow.requiredRecipientAccount());
            assertEquals(300L, escrow.amountMinorUnits());
            assertEquals("RESERVED", escrow.state());
            assertEquals(funded, coordinator.fund(PAYER, issued.billId(), "exact-funding"));
            var authorization = new FiscalAuthorization(database)
                    .describe(FiscalBillFundingServiceProvisioner.SERVICE_IDENTITY);
            assertEquals(FiscalServiceState.ENABLED, authorization.state());
            assertEquals(1, authorization.grants().size());
            assertEquals(
                    FiscalCapability.FUND_BILL,
                    authorization.grants().getFirst().grant().capability());
            assertEquals(
                    new AccountId("player:" + PAYER),
                    authorization.grants().getFirst().grant().accountId());
            assertTrue(authorization.grants().getFirst().active());
        }
    }

    private static FiscalBill issue(
            CivicDatabase database, String requestId, UUID payer, long amount) {
        return FiscalLedger.toFiscalBill(database.issueFiscalBill(
                UUID.randomUUID(),
                FiscalBillFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                requestId,
                "player:" + payer,
                "nation:11111111-1111-1111-1111-111111111111:treasury",
                amount,
                FiscalBillKind.FEE.name(),
                "Funding " + requestId,
                NOW.plusSeconds(3_600L).toEpochMilli()));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("fiscal-bill-funding.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
