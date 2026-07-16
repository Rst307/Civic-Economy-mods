package org.civiceconomy.fiscal;

public final class FiscalBillPaymentServiceProvisioner {
    public static final ServiceIdentity SERVICE_IDENTITY =
            new ServiceIdentity("civiceconomy-fiscal-bill-payment");
    private static final ServiceIdentity INTERNAL_ADMINISTRATOR =
            new ServiceIdentity("civiceconomy-internal");

    private final FiscalAuthorization authorization;

    public FiscalBillPaymentServiceProvisioner(FiscalAuthorization authorization) {
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
                "Civic Fiscal Bill Payment",
                INTERNAL_ADMINISTRATOR,
                "fiscal-bill-payment-service-registration-v1",
                "Internal service for payer-authorized Fiscal Bill payment"));
        authorization.grant(new GrantFiscalCapability(
                INTERNAL_ADMINISTRATOR,
                "fiscal-bill-payment-grant-v1:" + payerAccountId.value(),
                SERVICE_IDENTITY,
                FiscalCapability.SETTLE_PAYMENT,
                payerAccountId,
                "Exact player account scope for Fiscal Bill payment"));
    }
}
