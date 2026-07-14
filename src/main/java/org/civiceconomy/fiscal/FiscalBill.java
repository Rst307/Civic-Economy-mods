package org.civiceconomy.fiscal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record FiscalBill(
        UUID billId,
        ServiceIdentity serviceIdentity,
        String requestId,
        AccountId payerAccount,
        AccountId beneficiaryAccount,
        MoneyAmount amount,
        FiscalBillKind kind,
        String purpose,
        Instant dueAt,
        Optional<UUID> escrowId,
        MoneyAmount settledAmount,
        FiscalBillState state) {
    public MoneyAmount remainingAmount() {
        return amount.minus(settledAmount);
    }
}
