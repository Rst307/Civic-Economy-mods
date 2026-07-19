package org.civiceconomy.fiscal;

public final class BudgetDisbursementPaymentServiceProvisioner {
    public static final ServiceIdentity SERVICE_IDENTITY =
            NationBudgetDisbursementApprovalCoordinator.SERVICE_IDENTITY;
    private static final ServiceIdentity INTERNAL_ADMINISTRATOR =
            new ServiceIdentity("civiceconomy-internal");

    private final FiscalAuthorization authorization;

    public BudgetDisbursementPaymentServiceProvisioner(
            FiscalAuthorization authorization) {
        if (authorization == null) {
            throw new IllegalArgumentException("Fiscal Authorization cannot be null");
        }
        this.authorization = authorization;
    }

    public void ensureAuthorized(AccountId treasuryAccount) {
        BudgetFiscalServiceProvisioner.requireNationalTreasury(treasuryAccount);
        authorization.register(new RegisterFiscalService(
                SERVICE_IDENTITY,
                "civiceconomy",
                "Civic Budget Disbursement Payment",
                INTERNAL_ADMINISTRATOR,
                "budget-disbursement-payment-service-registration-v1",
                "Internal service for approved Budget Disbursement payment"));
        authorization.grant(new GrantFiscalCapability(
                INTERNAL_ADMINISTRATOR,
                "budget-disbursement-payment-grant-v1:" + treasuryAccount.value(),
                SERVICE_IDENTITY,
                FiscalCapability.SETTLE_PAYMENT,
                treasuryAccount,
                "Exact National Treasury scope for approved Budget Disbursement payment"));
    }
}
