package org.civiceconomy.territory;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;

public record TerritoryMaintenanceSettlement(
        UUID settlementId,
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID cycleId,
        NationId nationId,
        Optional<UUID> reservationId,
        Optional<UUID> publicFundPaymentId,
        Optional<UUID> destructionOperationId,
        TerritoryFiscalValidity validity,
        String reason,
        Instant settledAt) {
    public TerritoryMaintenanceSettlement {
        if (settlementId == null
                || serviceIdentity == null
                || cycleId == null
                || nationId == null
                || reservationId == null
                || publicFundPaymentId == null
                || destructionOperationId == null
                || validity == null
                || settledAt == null) {
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
