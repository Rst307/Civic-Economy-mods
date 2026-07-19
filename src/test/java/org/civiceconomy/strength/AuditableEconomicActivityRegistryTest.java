package org.civiceconomy.strength;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

class AuditableEconomicActivityRegistryTest {
    private static final ServiceIdentity SERVICE = new ServiceIdentity("public-projects");
    private static final NationId NATION = new NationId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final String TREASURY = "nation:" + NATION.value() + ":treasury";

    @TempDir Path temporaryDirectory;

    @Test
    void registersOneExactCommittedPaymentEvidenceWithStrictReplay() {
        try (CivicDatabase database = database()) {
            database.registerNation(
                    NATION.value(),
                    "civiceconomy-tests",
                    "activity-nation",
                    UUID.fromString("22222222-2222-2222-2222-222222222222"),
                    1_000L);
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    SERVICE, "civiceconomy-tests", "Public project evidence fixture"));
            authorization.grant(new GrantFiscalCapability(
                    new ServiceIdentity("activity-admin"),
                    "grant-record-activity",
                    SERVICE,
                    FiscalCapability.RECORD_ECONOMIC_ACTIVITY,
                    new org.civiceconomy.fiscal.AccountId(TREASURY),
                    "Record exact National Treasury project activity"));
            var reservation = database.reserve(
                    SERVICE.value(), "activity-hold", TREASURY, 100L, "Rail milestone");
            var payment = database.preparePayment(
                    SERVICE.value(),
                    "activity-payment",
                    reservation.reservationId(),
                    "organization:rail-builder:fiscal",
                    100L);
            database.markExternalApplied(payment.transactionId());
            database.commitPayment(payment.transactionId(), reservation.reservationId());
            AuditableEconomicActivityRegistry registry = new AuditableEconomicActivityRegistry(
                    database,
                    FiscalTestSessions.open(database, SERVICE, "civiceconomy-tests"));
            RegisterAuditableEconomicActivityEvidence request =
                    new RegisterAuditableEconomicActivityEvidence(
                            SERVICE,
                            "record-rail-milestone",
                            payment.transactionId(),
                            NATION,
                            AuditableEconomicActivitySubject.PUBLIC_PROJECT,
                            "project:rail-corridor-1",
                            "controller:nation-1111",
                            "controller:rail-builder",
                            100L,
                            "budget:rail-2026",
                            2_000L);

            AuditableEconomicActivityAssessment recorded = registry.register(request);
            AuditableEconomicActivityAssessment replay = registry.register(request);

            assertEquals(recorded, replay);
            assertEquals(AuditableEconomicActivityDecision.INCLUDED, recorded.decision());
            assertEquals(payment.transactionId(), recorded.transactionId());
            assertEquals(100L, recorded.includedValue().minorUnits());
            assertThrows(
                    org.civiceconomy.fiscal.IdempotencyConflictException.class,
                    () -> registry.register(new RegisterAuditableEconomicActivityEvidence(
                            SERVICE,
                            "record-rail-milestone",
                            payment.transactionId(),
                            NATION,
                            AuditableEconomicActivitySubject.PUBLIC_PROJECT,
                            "project:changed-subject",
                            "controller:nation-1111",
                            "controller:rail-builder",
                            100L,
                            "budget:rail-2026",
                            2_000L)));
            assertEquals(92, database.schemaVersion());
        }
    }

    @Test
    void missingExactTreasuryCapabilityFailsBeforeEvidencePersistence() {
        try (CivicDatabase database = database()) {
            database.registerNation(
                    NATION.value(),
                    "civiceconomy-tests",
                    "unauthorized-activity-nation",
                    UUID.fromString("33333333-3333-3333-3333-333333333333"),
                    1_000L);
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    SERVICE, "civiceconomy-tests", "Unprivileged activity fixture"));
            var reservation = database.reserve(
                    SERVICE.value(), "unauthorized-hold", TREASURY, 100L, "Rail milestone");
            var payment = database.preparePayment(
                    SERVICE.value(),
                    "unauthorized-payment",
                    reservation.reservationId(),
                    "organization:rail-builder:fiscal",
                    100L);
            database.markExternalApplied(payment.transactionId());
            database.commitPayment(payment.transactionId(), reservation.reservationId());
            AuditableEconomicActivityRegistry registry = new AuditableEconomicActivityRegistry(
                    database,
                    FiscalTestSessions.open(database, SERVICE, "civiceconomy-tests"));
            RegisterAuditableEconomicActivityEvidence request =
                    new RegisterAuditableEconomicActivityEvidence(
                            SERVICE,
                            "unauthorized-evidence",
                            payment.transactionId(),
                            NATION,
                            AuditableEconomicActivitySubject.PUBLIC_PROJECT,
                            "project:unauthorized",
                            "controller:nation-1111",
                            "controller:rail-builder",
                            100L,
                            "budget:unauthorized",
                            2_000L);

            assertThrows(
                    org.civiceconomy.fiscal.FiscalAccessDeniedException.class,
                    () -> registry.register(request));
            assertNull(database.auditableEconomicActivityEvidence(
                    SERVICE.value(), "unauthorized-evidence"));
        }
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("auditable-activity.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
