package org.civiceconomy.fiscal;

public record ReserveFunds(
        ServiceIdentity serviceIdentity,
        String requestId,
        AccountId sourceAccount,
        MoneyAmount amount,
        String purpose) {
    public ReserveFunds {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID cannot be blank");
        }
        if (amount.equals(MoneyAmount.ZERO)) {
            throw new IllegalArgumentException("Reservation amount must be positive");
        }
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("Reservation purpose cannot be blank");
        }
    }
}
