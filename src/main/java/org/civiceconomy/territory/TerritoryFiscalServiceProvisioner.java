package org.civiceconomy.territory;

import java.util.EnumSet;
import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalCapability;
import org.civiceconomy.fiscal.GrantFiscalCapability;
import org.civiceconomy.fiscal.RegisterFiscalService;
import org.civiceconomy.fiscal.ServiceIdentity;

public final class TerritoryFiscalServiceProvisioner {
    public static final ServiceIdentity SERVICE_IDENTITY =
            new ServiceIdentity("civiceconomy-territory");
    private static final ServiceIdentity INTERNAL_ADMINISTRATOR =
            new ServiceIdentity("civiceconomy-internal");
    private static final EnumSet<FiscalCapability> REQUIRED_CAPABILITIES = EnumSet.of(
            FiscalCapability.RESERVE_FUNDS,
            FiscalCapability.SETTLE_PAYMENT,
            FiscalCapability.COMPENSATE_PAYMENT);

    private final FiscalAuthorization authorization;

    public TerritoryFiscalServiceProvisioner(FiscalAuthorization authorization) {
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
                "Civic Territory Finance",
                INTERNAL_ADMINISTRATOR,
                "territory-service-registration-v1",
                "Internal service for authorized Territory Claim prepayment"));
        for (FiscalCapability capability : REQUIRED_CAPABILITIES) {
            authorization.grant(new GrantFiscalCapability(
                    INTERNAL_ADMINISTRATOR,
                    "territory-service-grant-v1:" + capability.name()
                            + ":" + treasuryAccountId.value(),
                    SERVICE_IDENTITY,
                    capability,
                    treasuryAccountId,
                    "Exact National Treasury scope for Territory Claim prepayment"));
        }
    }

    private static void requireNationalTreasury(AccountId accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("National Treasury account cannot be null");
        }
        String value = accountId.value();
        String prefix = "nation:";
        String suffix = ":treasury";
        if (!value.startsWith(prefix) || !value.endsWith(suffix)) {
            throw new IllegalArgumentException("Territory fiscal scope must be a National Treasury");
        }
        String nationId = value.substring(prefix.length(), value.length() - suffix.length());
        try {
            UUID.fromString(nationId);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "Territory fiscal scope must contain a stable NationId", failure);
        }
    }
}
