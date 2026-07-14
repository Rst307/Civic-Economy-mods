package org.civiceconomy.fiscal;

public record RegisterFiscalService(
        ServiceIdentity serviceIdentity,
        String ownerModId,
        String displayName) {
    public RegisterFiscalService {
        if (serviceIdentity == null) {
            throw new IllegalArgumentException("Fiscal service identity cannot be null");
        }
        if (ownerModId == null || ownerModId.isBlank()) {
            throw new IllegalArgumentException("Fiscal service owner mod ID cannot be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Fiscal service display name cannot be blank");
        }
    }
}
