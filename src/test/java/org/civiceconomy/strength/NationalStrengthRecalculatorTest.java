package org.civiceconomy.strength;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.EnumSet;
import java.util.UUID;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalCapability;
import org.civiceconomy.fiscal.FiscalTestSessions;
import org.civiceconomy.fiscal.GrantFiscalCapability;
import org.civiceconomy.fiscal.RegisterFiscalService;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.NationEffectiveCitizenPopulation;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NationalStrengthRecalculatorTest {
    private static final ServiceIdentity SERVICE = new ServiceIdentity("public-projects");
    private static final NationId NATION = new NationId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final String TREASURY = "nation:" + NATION.value() + ":treasury";

    @TempDir Path temporaryDirectory;

    @Test
    void recomputationUsesPersistedWindowAndReturnsItsExplanation() {
        try (CivicDatabase database = database()) {
            database.registerNation(
                    NATION.value(),
                    "civiceconomy-tests",
                    "recompute-nation",
                    UUID.fromString("22222222-2222-2222-2222-222222222222"),
                    1_000L);
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    SERVICE, "civiceconomy-tests", "Recompute fixture"));
            authorization.grant(new GrantFiscalCapability(
                    new ServiceIdentity("activity-admin"),
                    "grant-record-activity",
                    SERVICE,
                    FiscalCapability.RECORD_ECONOMIC_ACTIVITY,
                    new org.civiceconomy.fiscal.AccountId(TREASURY),
                    "Record exact National Treasury project activity"));
            var reservation = database.reserve(
                    SERVICE.value(), "recompute-hold", TREASURY, 2_500L, "Recompute project");
            var payment = database.preparePayment(
                    SERVICE.value(),
                    "recompute-payment",
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
                            "recompute-evidence",
                            payment.transactionId(),
                            NATION,
                            AuditableEconomicActivitySubject.PUBLIC_PROJECT,
                            "project:recompute",
                            "controller:nation",
                            "controller:builder",
                            2_500L,
                            "budget:recompute",
                            50L));

            NationalStrengthRecalculation recalculation = new NationalStrengthRecalculator(
                            database, 100L, 10_000L)
                    .recalculate(
                            NATION,
                            100L,
                            new NationEffectiveCitizenPopulation(
                                    NATION, Instant.ofEpochMilli(100L), List.of()),
                            new EffectiveTerritoryStrengthAssessment(
                                    NATION,
                                    UUID.fromString("22222222-2222-2222-2222-222222222222"),
                                    false,
                                    List.of()),
                            new MintComplianceCalculator().assess(
                                    NATION, 0L, 100L, 5_000, List.of()),
                            new NationalStrengthComponents(
                                    1_000,
                                    2_000,
                                    9_000,
                                    3_000,
                                    4_000,
                                    EnumSet.noneOf(NationalStrengthComponent.class)));

            assertEquals(5_000, recalculation.activityWindow().normalizedBasisPoints());
            assertEquals(1, recalculation.activityWindow().acceptedCount());
            assertEquals(
                    1_000,
                    recalculation.assessment().auditableEconomicActivityContribution());
            assertEquals(
                    5_000,
                    recalculation.assessment()
                            .component(NationalStrengthComponent.AUDITABLE_ECONOMIC_ACTIVITY)
                            .normalizedInputBasisPoints());
        }
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("national-strength-recompute.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
