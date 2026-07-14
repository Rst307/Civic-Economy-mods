package org.civiceconomy.fiscal;

public record RegisterFiscalService(
        ServiceIdentity serviceIdentity,
        String ownerModId,
        String displayName,
        ServiceIdentity administrator,
        String requestId,
        String reason) {
    public RegisterFiscalService(
            ServiceIdentity serviceIdentity, String ownerModId, String displayName) {
        this(
                serviceIdentity,
                ownerModId,
                displayName,
                new ServiceIdentity("civiceconomy-internal"),
                "register:" + serviceIdentity.value(),
                "Internal fiscal service registration");
    }

    public RegisterFiscalService {
        if (serviceIdentity == null || administrator == null) {
            throw new IllegalArgumentException(
                    "Fiscal service identity and administrator cannot be null");
        }
        if (ownerModId == null || ownerModId.isBlank()) {
            throw new IllegalArgumentException("Fiscal service owner mod ID cannot be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Fiscal service display name cannot be blank");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Fiscal service registration request ID cannot be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Fiscal service registration reason cannot be blank");
        }
    }
}
