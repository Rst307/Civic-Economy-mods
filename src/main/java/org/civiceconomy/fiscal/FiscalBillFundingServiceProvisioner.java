package org.civiceconomy.fiscal;

import java.util.UUID;

public final class FiscalBillFundingServiceProvisioner {
    public static final ServiceIdentity SERVICE_IDENTITY =
            new ServiceIdentity("civiceconomy-fiscal-bill-funding");
    private static final ServiceIdentity INTERNAL_ADMINISTRATOR =
            new ServiceIdentity("civiceconomy-internal");

    private final FiscalAuthorization authorization;

    public FiscalBillFundingServiceProvisioner(FiscalAuthorization authorization) {
        if (authorization == null) {
            throw new IllegalArgumentException("Fiscal Authorization cannot be null");
        }
        this.authorization = authorization;
    }

    public void ensureAuthorized(AccountId payerAccountId) {
        requirePlayerAccount(payerAccountId);
        authorization.register(new RegisterFiscalService(
                SERVICE_IDENTITY,
                "civiceconomy",
                "Civic Fiscal Bill Funding",
                INTERNAL_ADMINISTRATOR,
                "fiscal-bill-funding-service-registration-v1",
                "Internal service for payer-authorized Fiscal Bill funding"));
        authorization.grant(new GrantFiscalCapability(
                INTERNAL_ADMINISTRATOR,
                "fiscal-bill-funding-grant-v1:" + payerAccountId.value(),
                SERVICE_IDENTITY,
                FiscalCapability.FUND_BILL,
                payerAccountId,
                "Exact player account scope for Fiscal Bill funding"));
    }

    private static void requirePlayerAccount(AccountId accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("Fiscal Bill payer account cannot be null");
        }
        String value = accountId.value();
        String prefix = "player:";
        if (!value.startsWith(prefix)) {
            throw new IllegalArgumentException(
                    "Fiscal Bill funding scope must be a player account");
        }
        try {
            UUID.fromString(value.substring(prefix.length()));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "Fiscal Bill funding scope must contain a player UUID", failure);
        }
    }
}
