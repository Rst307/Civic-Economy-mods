package org.civiceconomy.strength;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.EnumSet;
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

class AuditableEconomicActivityWindowStrengthTest {
    private static final ServiceIdentity SERVICE = new ServiceIdentity("public-projects");
    private static final NationId NATION = new NationId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final String TREASURY = "nation:" + NATION.value() + ":treasury";

    @TempDir Path temporaryDirectory;

    @Test
    void acceptedRollingValueFeedsTheTwentyPercentNationalStrengthComponent() {
        try (CivicDatabase database = database()) {
            database.registerNation(
                    NATION.value(),
                    "civiceconomy-tests",
                    "strength-nation",
                    UUID.fromString("22222222-2222-2222-2222-222222222222"),
                    1_000L);
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    SERVICE, "civiceconomy-tests", "Strength fixture"));
            authorization.grant(new GrantFiscalCapability(
                    new ServiceIdentity("activity-admin"),
                    "grant-record-activity",
                    SERVICE,
                    FiscalCapability.RECORD_ECONOMIC_ACTIVITY,
                    new org.civiceconomy.fiscal.AccountId(TREASURY),
                    "Record exact National Treasury project activity"));
            var reservation = database.reserve(
                    SERVICE.value(), "strength-hold", TREASURY, 2_500L, "Strength project");
            var payment = database.preparePayment(
                    SERVICE.value(),
                    "strength-payment",
                    reservation.reservationId(),
                    "organization:builder:fiscal",
                    2_500L);
            database.markExternalApplied(payment.transactionId());
            database.commitPayment(payment.transactionId(), reservation.reservationId());
            new AuditableEconomicActivityRegistry(
                            database,
                            FiscalTestSessions.open(database, SERVICE, "civiceconomy-tests"))
                    .register(new RegisterAuditableEconomicActivityEvidence(
                            SERVICE,
                            "strength-evidence",
                            payment.transactionId(),
                            NATION,
                            AuditableEconomicActivitySubject.PUBLIC_PROJECT,
                            "project:strength",
                            "controller:nation",
                            "controller:builder",
                            2_500L,
                            "budget:strength",
                            50L));

            int normalized = new AuditableEconomicActivityWindow(database, 10_000L)
                    .assess(NATION, 0L, 100L)
                    .normalizedBasisPoints();
            NationalStrengthAssessment strength = new NationalStrengthCalculator().assess(
                    new NationalStrengthComponents(
                            0, 0, normalized, 0, 0, EnumSet.noneOf(NationalStrengthComponent.class)));

            assertEquals(5_000, normalized);
            assertEquals(1_000, strength.auditableEconomicActivityContribution());
        }
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("activity-strength.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
