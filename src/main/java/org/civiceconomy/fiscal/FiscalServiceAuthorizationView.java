package org.civiceconomy.fiscal;

import java.util.List;

public record FiscalServiceAuthorizationView(
        RegisteredFiscalService service,
        FiscalServiceState state,
        List<FiscalCapabilityGrantStatus> grants) {
    public FiscalServiceAuthorizationView {
        if (service == null || state == null || grants == null) {
            throw new IllegalArgumentException("Fiscal service authorization view cannot be null");
        }
        grants = List.copyOf(grants);
    }
}
