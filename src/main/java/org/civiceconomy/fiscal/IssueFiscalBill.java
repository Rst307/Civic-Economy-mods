package org.civiceconomy.fiscal;

import java.time.Instant;

public record IssueFiscalBill(
        ServiceIdentity serviceIdentity,
        String requestId,
        AccountId payerAccount,
        AccountId beneficiaryAccount,
        MoneyAmount amount,
        FiscalBillKind kind,
        String purpose,
        Instant dueAt) {
    public IssueFiscalBill {
        if (serviceIdentity == null
                || payerAccount == null
                || beneficiaryAccount == null
                || amount == null
                || kind == null
                || dueAt == null) {
            throw new IllegalArgumentException("Fiscal Bill identity, accounts, amount, kind, and due date cannot be null");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID cannot be blank");
        }
        if (payerAccount.equals(beneficiaryAccount)) {
            throw new IllegalArgumentException("Fiscal Bill payer and beneficiary must differ");
        }
        if (amount.equals(MoneyAmount.ZERO)) {
            throw new IllegalArgumentException("Fiscal Bill amount must be positive");
        }
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("Fiscal Bill purpose cannot be blank");
        }
    }
}
