package org.civiceconomy.monetary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalCapability;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermanentDestructionFiscalServiceProvisionerTest {
    private static final AccountId TREASURY = new AccountId(
            "nation:11111111-1111-1111-1111-111111111111:treasury");

    @TempDir Path temporaryDirectory;

    @Test
    void provisionsOnlyExactPermanentDestructionAuthorityForANationalTreasury() {
        try (CivicDatabase database = database()) {
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            new PermanentDestructionFiscalServiceProvisioner(authorization)
                    .ensureAuthorized(TREASURY);

            var view = authorization.describe(
                    PermanentDestructionFiscalServiceProvisioner.SERVICE_IDENTITY);
            assertEquals("civiceconomy", view.service().ownerModId());
            assertEquals(1, view.grants().size());
            assertEquals(FiscalCapability.PERMANENT_DESTRUCTION,
                    view.grants().getFirst().grant().capability());
            assertEquals(TREASURY, view.grants().getFirst().grant().accountId());

            assertThrows(
                    IllegalArgumentException.class,
                    () -> new PermanentDestructionFiscalServiceProvisioner(authorization)
                            .ensureAuthorized(new AccountId("player:11111111-1111-1111-1111-111111111111")));
        }
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("permanent-destruction-service.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("1e04c9c8-f60e-47f9-9fb1-a8c024cff688"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
