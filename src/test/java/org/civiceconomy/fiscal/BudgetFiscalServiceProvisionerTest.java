package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.UUID;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BudgetFiscalServiceProvisionerTest {
    private static final AccountId TREASURY = new AccountId(
            "nation:11111111-1111-1111-1111-111111111111:treasury");

    @TempDir Path temporaryDirectory;

    @Test
    void provisionsOnlyExactBudgetAuthorityForANationalTreasury() {
        try (CivicDatabase database = database()) {
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            new BudgetFiscalServiceProvisioner(authorization)
                    .ensureAuthorized(TREASURY);

            var view = authorization.describe(
                    BudgetFiscalServiceProvisioner.SERVICE_IDENTITY);
            assertEquals("civiceconomy", view.service().ownerModId());
            assertEquals(1, view.grants().size());
            assertEquals(
                    FiscalCapability.MANAGE_BUDGET,
                    view.grants().getFirst().grant().capability());
            assertEquals(TREASURY, view.grants().getFirst().grant().accountId());

            assertThrows(
                    IllegalArgumentException.class,
                    () -> new BudgetFiscalServiceProvisioner(authorization)
                            .ensureAuthorized(new AccountId(
                                    "player:11111111-1111-1111-1111-111111111111")));
        }
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("budget-service.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("1e04c9c8-f60e-47f9-9fb1-a8c024cff699"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
