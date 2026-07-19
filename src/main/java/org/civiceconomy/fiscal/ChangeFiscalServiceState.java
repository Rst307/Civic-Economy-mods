package org.civiceconomy.fiscal;

public record ChangeFiscalServiceState(
        ServiceIdentity administrator,
        String requestId,
        ServiceIdentity serviceIdentity,
        FiscalServiceState state,
        String reason) {
    public ChangeFiscalServiceState {
        if (administrator == null || serviceIdentity == null || state == null) {
            throw new IllegalArgumentException("Fiscal service state identities and state cannot be null");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Fiscal service state request ID cannot be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Fiscal service state reason cannot be blank");
        }
    }
}
