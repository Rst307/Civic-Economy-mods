package org.civiceconomy.fiscal;

public final class PreparedFiscalBillPayment {
    private final PaymentTransaction transaction;
    private final FiscalServiceSession session;

    PreparedFiscalBillPayment(
            PaymentTransaction transaction, FiscalServiceSession session) {
        if (transaction == null || session == null) {
            throw new IllegalArgumentException(
                    "Prepared Fiscal Bill payment cannot contain null state");
        }
        this.transaction = transaction;
        this.session = session;
    }

    public PaymentTransaction transaction() {
        return transaction;
    }

    FiscalServiceSession session() {
        return session;
    }
}
