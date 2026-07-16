package org.civiceconomy.fiscal;

import java.util.UUID;

public final class BudgetFiscalServiceProvisioner {
    public static final ServiceIdentity SERVICE_IDENTITY =
            new ServiceIdentity("civiceconomy-budget");
    private static final ServiceIdentity INTERNAL_ADMINISTRATOR =
            new ServiceIdentity("civiceconomy-internal");

    private final FiscalAuthorization authorization;

    public BudgetFiscalServiceProvisioner(FiscalAuthorization authorization) {
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
                "Civic Budget",
                INTERNAL_ADMINISTRATOR,
                "budget-service-registration-v1",
                "Internal service for authorized Budget operations"));
        authorization.grant(new GrantFiscalCapability(
                INTERNAL_ADMINISTRATOR,
                "budget-service-grant-v1:" + treasuryAccountId.value(),
                SERVICE_IDENTITY,
                FiscalCapability.MANAGE_BUDGET,
                treasuryAccountId,
                "Exact National Treasury scope for Budget operations"));
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
                    "Budget scope must be a National Treasury");
        }
        String nationId = value.substring(prefix.length(), value.length() - suffix.length());
        try {
            UUID.fromString(nationId);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "Budget scope must contain a stable NationId", failure);
        }
    }
}
