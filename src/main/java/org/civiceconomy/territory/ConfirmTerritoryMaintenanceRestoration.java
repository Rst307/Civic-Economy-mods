package org.civiceconomy.territory;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ConfirmTerritoryMaintenanceRestoration(
        ServiceIdentity serviceIdentity,
        UUID restorationId,
        UUID reservationId,
        UUID publicFundPaymentId,
        UUID destructionOperationId) {
    public ConfirmTerritoryMaintenanceRestoration {
        if (serviceIdentity == null
                || restorationId == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Restoration confirmation cannot contain null values");
        }
        if ((reservationId == null) != (publicFundPaymentId == null)
                || (reservationId == null && destructionOperationId != null)) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Restoration evidence is inconsistent");
        }
    }
}
