package org.civiceconomy.monetary;

import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalCapability;
import org.civiceconomy.fiscal.GrantFiscalCapability;
import org.civiceconomy.fiscal.RegisterFiscalService;
import org.civiceconomy.fiscal.ServiceIdentity;

public final class PermanentDestructionFiscalServiceProvisioner {
    public static final ServiceIdentity SERVICE_IDENTITY =
            new ServiceIdentity("civiceconomy-permanent-destruction");
    private static final ServiceIdentity INTERNAL_ADMINISTRATOR =
            new ServiceIdentity("civiceconomy-internal");

    private final FiscalAuthorization authorization;

    public PermanentDestructionFiscalServiceProvisioner(FiscalAuthorization authorization) {
        if (authorization == null) {
            throw new IllegalArgumentException("Fiscal Authorization cannot be null");
        }
        this.authorization = authorization;
    }

    public void ensureAuthorized(AccountId treasuryAccountId) {
        requireNationalTreasury(treasuryAccountId);
        authorization.register(new RegisterFiscalService(
                SERVICE_IDENTITY,
                "civiceconomy",
                "Civic Permanent Destruction",
                INTERNAL_ADMINISTRATOR,
                "permanent-destruction-service-registration-v1",
                "Internal service for authorized Permanent Destruction"));
        authorization.grant(new GrantFiscalCapability(
                INTERNAL_ADMINISTRATOR,
                "permanent-destruction-service-grant-v1:"
                        + treasuryAccountId.value(),
                SERVICE_IDENTITY,
                FiscalCapability.PERMANENT_DESTRUCTION,
                treasuryAccountId,
                "Exact National Treasury scope for Permanent Destruction"));
    }

    private static void requireNationalTreasury(AccountId accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("National Treasury account cannot be null");
        }
        String value = accountId.value();
        String prefix = "nation:";
        String suffix = ":treasury";
        if (!value.startsWith(prefix) || !value.endsWith(suffix)) {
            throw new IllegalArgumentException(
                    "Permanent Destruction scope must be a National Treasury");
        }
        String nationId = value.substring(prefix.length(), value.length() - suffix.length());
        try {
            UUID.fromString(nationId);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "Permanent Destruction scope must contain a stable NationId", failure);
        }
    }
}
