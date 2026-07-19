package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommittedTerritoryPrepaymentVerifierTest {
    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void requiresExactCommittedTreasuryToClearingPaymentAndBlocksNormalRefund() {
        NationId nationId = new NationId(
                UUID.fromString("9ef16d6c-0d7d-4759-b998-e813201d3e91"));
        UUID teamId = UUID.fromString("17ae4846-ad60-4bd9-b21d-c4ced792e40d");
        AccountId treasury = new AccountId("nation:" + nationId.value() + ":treasury");
        AccountId clearing = new AccountId("system:territory:prepayment-clearing");

        try (CivicDatabase database = database()) {
            database.registerNation(
                    nationId.value(), "territory-test", "register", teamId, NOW.toEpochMilli());
            var reservation = database.reserve(
                    "territory-test", "reserve", treasury.value(), 250L, "Territory prepayment");
            var payment = database.preparePayment(
                    "territory-test", "pay", reservation.reservationId(), clearing.value(), 250L);
            database.markExternalApplied(payment.transactionId());
            database.commitPayment(payment.transactionId(), reservation.reservationId());
            CommittedTerritoryPrepaymentVerifier verifier =
                    new CommittedTerritoryPrepaymentVerifier(database, clearing);

            assertTrue(verifier.isCommitted(
                    payment.transactionId(), nationId, MoneyAmount.ofMinorUnits(250L)));
            assertFalse(new CommittedTerritoryPrepaymentVerifier(
                            database, new AccountId("system:wrong-clearing"))
                    .isCommitted(payment.transactionId(), nationId, MoneyAmount.ofMinorUnits(250L)));
            assertFalse(verifier.isCommitted(
                    payment.transactionId(), nationId, MoneyAmount.ofMinorUnits(249L)));

            TerritoryClaimPermit permit = new TerritoryClaimPermitRegistry(
                            database,
                            verifier,
                            Clock.fixed(NOW.plusSeconds(1L), ZoneOffset.UTC))
                    .issue(new IssueTerritoryClaimPermit(
                            new ServiceIdentity("civiceconomy-territory"),
                            "issue-paid-permit",
                            nationId,
                            teamId,
                            UUID.fromString("e7313fe1-1c59-44c1-bfc9-e84e925d3c19"),
                            "minecraft:overworld",
                            1,
                            2,
                            16,
                            17,
                            250L,
                            payment.transactionId(),
                            NOW.plusSeconds(120L)));

            assertTrue(database.territoryClaimPermitByPrepaymentTransaction(
                            payment.transactionId())
                    .permitId()
                    .equals(permit.permitId()));
            assertThrows(
                    IllegalStateException.class,
                    () -> database.prepareRefund(
                            "territory-test",
                            "normal-refund-must-fail",
                            payment.transactionId(),
                            250L,
                            "Permit-linked refund"));
        }
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("committed-territory-prepayment.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("fdc22f43-9bce-4490-9c58-311068295b92"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
