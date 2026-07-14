package org.civiceconomy.territory;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;

public record ConfirmTerritoryMaintenanceSettlement(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID cycleId,
        NationId nationId,
        UUID reservationId,
        UUID publicFundPaymentId,
        UUID destructionOperationId,
        String reason) {
    public ConfirmTerritoryMaintenanceSettlement {
        if (serviceIdentity == null
                || cycleId == null
                || nationId == null
                || reservationId == null
                || publicFundPaymentId == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Settlement cannot contain null values");
        }
        if (requestId == null
                || requestId.isBlank()
                || reason == null
                || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Settlement values are invalid");
        }
    }
}
