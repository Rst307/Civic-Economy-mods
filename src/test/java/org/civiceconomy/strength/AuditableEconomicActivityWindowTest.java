package org.civiceconomy.strength;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.UUID;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalCapability;
import org.civiceconomy.fiscal.FiscalTestSessions;
import org.civiceconomy.fiscal.GrantFiscalCapability;
import org.civiceconomy.fiscal.RegisterFiscalService;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditableEconomicActivityWindowTest {
    private static final ServiceIdentity SERVICE = new ServiceIdentity("public-projects");
    private static final NationId NATION = new NationId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final String TREASURY = "nation:" + NATION.value() + ":treasury";

    @TempDir Path temporaryDirectory;

    @Test
    void laterRefundIsExcludedFromTheCurrentRollingWindow() {
        try (CivicDatabase database = database()) {
            database.registerNation(
                    NATION.value(),
                    "civiceconomy-tests",
                    "window-nation",
                    UUID.fromString("22222222-2222-2222-2222-222222222222"),
                    1_000L);
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    SERVICE, "civiceconomy-tests", "Window fixture"));
            authorization.grant(new GrantFiscalCapability(
                    new ServiceIdentity("activity-admin"),
                    "grant-record-activity",
                    SERVICE,
                    FiscalCapability.RECORD_ECONOMIC_ACTIVITY,
                    new org.civiceconomy.fiscal.AccountId(TREASURY),
                    "Record exact National Treasury project activity"));
            var reservation = database.reserve(
                    SERVICE.value(), "window-hold", TREASURY, 100L, "Window project");
            var payment = database.preparePayment(
                    SERVICE.value(),
                    "window-payment",
                    reservation.reservationId(),
                    "organization:builder:fiscal",
                    100L);
            database.markExternalApplied(payment.transactionId());
            database.commitPayment(payment.transactionId(), reservation.reservationId());
            AuditableEconomicActivityRegistry registry = new AuditableEconomicActivityRegistry(
                    database,
                    FiscalTestSessions.open(database, SERVICE, "civiceconomy-tests"));
            registry.register(new RegisterAuditableEconomicActivityEvidence(
                    SERVICE,
                    "window-evidence",
                    payment.transactionId(),
                    NATION,
                    AuditableEconomicActivitySubject.PUBLIC_PROJECT,
                    "project:window",
                    "controller:nation",
                    "controller:builder",
                    100L,
                    "budget:window",
                    0L));
            var boundaryReservation = database.reserve(
                    SERVICE.value(), "boundary-hold", TREASURY, 10L, "Boundary project");
            var boundaryPayment = database.preparePayment(
                    SERVICE.value(),
                    "boundary-payment",
                    boundaryReservation.reservationId(),
                    "organization:boundary-builder:fiscal",
                    10L);
            database.markExternalApplied(boundaryPayment.transactionId());
            database.commitPayment(
                    boundaryPayment.transactionId(), boundaryReservation.reservationId());
            registry.register(new RegisterAuditableEconomicActivityEvidence(
                    SERVICE,
                    "boundary-evidence",
                    boundaryPayment.transactionId(),
                    NATION,
                    AuditableEconomicActivitySubject.PUBLIC_PROJECT,
                    "project:boundary",
                    "controller:nation",
                    "controller:boundary-builder",
                    10L,
                    "budget:boundary",
                    100L));

            AuditableEconomicActivityWindow window =
                    new AuditableEconomicActivityWindow(database, 10_000L);
            AuditableEconomicActivityWindowAssessment beforeRefund =
                    window.assess(NATION, 0L, 100L);
            assertEquals(100L, beforeRefund.acceptedValueMinorUnits());
            assertEquals(1, beforeRefund.acceptedCount());
            assertEquals(0, beforeRefund.excludedCount());

            var refund = database.prepareRefund(
                    SERVICE.value(), "window-refund", payment.transactionId(), 40L, "Correction");
            database.markExternalApplied(refund.transactionId());
            database.commitRefund(refund.transactionId(), payment.transactionId());

            AuditableEconomicActivityWindowAssessment afterRefund =
                    window.assess(NATION, 0L, 100L);
            assertEquals(0L, afterRefund.acceptedValueMinorUnits());
            assertEquals(0, afterRefund.acceptedCount());
            assertEquals(1, afterRefund.excludedCount());
            assertEquals(1, afterRefund.excludedByDecision()
                    .get(AuditableEconomicActivityDecision.EXCLUDED_REFUNDED));
        }
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("activity-window.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
