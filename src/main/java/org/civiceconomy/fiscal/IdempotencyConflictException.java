package org.civiceconomy.fiscal;

public final class IdempotencyConflictException extends IllegalStateException {
    public IdempotencyConflictException(ServiceIdentity serviceIdentity, String requestId) {
        super("Request ID " + requestId + " was already used by service " + serviceIdentity.value()
                + " with a different payload");
    }
}
