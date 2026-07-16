package org.civiceconomy.fiscal;

import java.util.UUID;

public final class FiscalBillFiscalServiceProvisioner {
    public static final ServiceIdentity SERVICE_IDENTITY =
            new ServiceIdentity("civiceconomy-fiscal-bill");
    private static final ServiceIdentity INTERNAL_ADMINISTRATOR =
            new ServiceIdentity("civiceconomy-internal");

    private final FiscalAuthorization authorization;

    public FiscalBillFiscalServiceProvisioner(FiscalAuthorization authorization) {
        if (authorization == null) {
            throw new IllegalArgumentException("Fiscal Authorization cannot be null");
        }
        this.authorization = authorization;
    }

    public void ensureIssueAuthorized(AccountId treasuryAccountId) {
        requireNationalTreasury(treasuryAccountId);
        authorization.register(new RegisterFiscalService(
                SERVICE_IDENTITY,
                "civiceconomy",
                "Civic Fiscal Bill",
                INTERNAL_ADMINISTRATOR,
                "fiscal-bill-service-registration-v1",
                "Internal service for authorized Fiscal Bill operations"));
        authorization.grant(new GrantFiscalCapability(
                INTERNAL_ADMINISTRATOR,
                "fiscal-bill-issue-grant-v1:" + treasuryAccountId.value(),
                SERVICE_IDENTITY,
                FiscalCapability.ISSUE_BILL,
                treasuryAccountId,
                "Exact National Treasury beneficiary scope for Fiscal Bill issuance"));
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
                    "Fiscal Bill beneficiary scope must be a National Treasury");
        }
        String nationId = value.substring(prefix.length(), value.length() - suffix.length());
        try {
            UUID.fromString(nationId);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "Fiscal Bill beneficiary scope must contain a stable NationId", failure);
        }
    }
}
