package org.civiceconomy.fiscal;

public final class FiscalBillCancellationServiceProvisioner {
    public static final ServiceIdentity SERVICE_IDENTITY =
            new ServiceIdentity("civiceconomy-fiscal-bill-cancellation");
    private static final ServiceIdentity INTERNAL_ADMINISTRATOR =
            new ServiceIdentity("civiceconomy-internal");

    private final FiscalAuthorization authorization;

    public FiscalBillCancellationServiceProvisioner(FiscalAuthorization authorization) {
        if (authorization == null) {
            throw new IllegalArgumentException("Fiscal Authorization cannot be null");
        }
        this.authorization = authorization;
    }

    public void ensureAuthorized(AccountId payerAccountId) {
        FiscalBillFundingServiceProvisioner.requirePlayerAccount(payerAccountId);
        authorization.register(new RegisterFiscalService(
                SERVICE_IDENTITY,
                "civiceconomy",
                "Civic Fiscal Bill Cancellation",
                INTERNAL_ADMINISTRATOR,
                "fiscal-bill-cancellation-service-registration-v1",
                "Internal service for payer-authorized Fiscal Bill cancellation"));
        authorization.grant(new GrantFiscalCapability(
                INTERNAL_ADMINISTRATOR,
                "fiscal-bill-cancellation-grant-v1:" + payerAccountId.value(),
                SERVICE_IDENTITY,
                FiscalCapability.RESERVE_FUNDS,
                payerAccountId,
                "Exact player account scope for Fiscal Bill cancellation"));
    }
}
