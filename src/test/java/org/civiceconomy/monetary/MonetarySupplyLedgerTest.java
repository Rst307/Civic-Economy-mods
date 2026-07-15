package org.civiceconomy.monetary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MonetarySupplyLedgerTest {
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final ServiceIdentity SERVICE = new ServiceIdentity("civiceconomy-mint");

    @TempDir Path temporaryDirectory;

    @Test
    void recordsIssuanceAndPermanentDestructionExactlyOnceAcrossRestart() {
        MonetarySupplyEvent issuance;
        try (CivicDatabase database = database()) {
            MonetarySupplyLedger ledger = ledger(database, 1_000L);
            issuance = ledger.confirm(new ConfirmMonetarySupplyChange(
                    SERVICE, "issue-aurora-1", MonetarySupplyChange.ISSUANCE,
                    700L, "mint-batch:aurora-1", "Committed registered mint batch"));
            assertEquals(issuance, ledger.confirm(new ConfirmMonetarySupplyChange(
                    SERVICE, "issue-aurora-1", MonetarySupplyChange.ISSUANCE,
                    700L, "mint-batch:aurora-1", "Committed registered mint batch")));
            assertThrows(IdempotencyConflictException.class, () -> ledger.confirm(
                    new ConfirmMonetarySupplyChange(
                            SERVICE, "issue-aurora-1", MonetarySupplyChange.ISSUANCE,
                            701L, "mint-batch:aurora-1", "Committed registered mint batch")));
            assertEquals(MoneyAmount.ofMinorUnits(700L), ledger.cumulativeNetIssuance());
            ledger.confirm(new ConfirmMonetarySupplyChange(
                    SERVICE, "destroy-maintenance-1", MonetarySupplyChange.PERMANENT_DESTRUCTION,
                    300L, "maintenance-cycle:1", "Confirmed maintenance destruction"));
            assertEquals(MoneyAmount.ofMinorUnits(400L), ledger.cumulativeNetIssuance());
        }

        try (CivicDatabase database = database()) {
            assertEquals(MoneyAmount.ofMinorUnits(400L), ledger(database, 1_000L).cumulativeNetIssuance());
        }
    }

    @Test
    void hardCapAndDestructionUnderflowFailBeforeWritingAnEvent() {
        try (CivicDatabase database = database()) {
            MonetarySupplyLedger ledger = ledger(database, 500L);
            ledger.confirm(new ConfirmMonetarySupplyChange(
                    SERVICE, "issue-500", MonetarySupplyChange.ISSUANCE,
                    500L, "mint-batch:500", "At hard cap"));

            assertThrows(IssuanceHardCapExceededException.class, () -> ledger.confirm(
                    new ConfirmMonetarySupplyChange(
                            SERVICE, "issue-over-cap", MonetarySupplyChange.ISSUANCE,
                            1L, "mint-batch:501", "Would exceed cap")));
            assertThrows(DestructionExceedsNetIssuanceException.class, () -> ledger.confirm(
                    new ConfirmMonetarySupplyChange(
                            SERVICE, "destroy-too-much", MonetarySupplyChange.PERMANENT_DESTRUCTION,
                            501L, "destruction:501", "Would manufacture issuance room")));
            assertEquals(1, ledger.events().size());
            assertEquals(MoneyAmount.ofMinorUnits(500L), ledger.cumulativeNetIssuance());
        }
    }

    @Test
    void normalSupplyRequestsCannotInvokeStockCorrection() {
        assertThrows(IllegalArgumentException.class, () -> new ConfirmMonetarySupplyChange(
                SERVICE,
                "forged-correction",
                MonetarySupplyChange.STOCK_CORRECTION_INCREASE,
                1L,
                "forged:correction",
                "Normal callers cannot correct stock"));
    }

    private MonetarySupplyLedger ledger(CivicDatabase database, long hardCap) {
        return new MonetarySupplyLedger(
                database, MoneyAmount.ofMinorUnits(hardCap), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("monetary-supply.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("03833283-4515-4d07-aa3a-31223f3aab4c"),
                        "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20"));
    }
}
