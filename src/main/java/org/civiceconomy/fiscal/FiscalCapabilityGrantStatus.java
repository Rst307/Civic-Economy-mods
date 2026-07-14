package org.civiceconomy.fiscal;

import java.util.Optional;

public record FiscalCapabilityGrantStatus(
        FiscalCapabilityGrant grant,
        Optional<FiscalCapabilityRevocation> revocation) {
    public FiscalCapabilityGrantStatus {
        if (grant == null || revocation == null) {
            throw new IllegalArgumentException("Fiscal capability grant status cannot be null");
        }
    }

    public boolean active() {
        return revocation.isEmpty();
    }
}
