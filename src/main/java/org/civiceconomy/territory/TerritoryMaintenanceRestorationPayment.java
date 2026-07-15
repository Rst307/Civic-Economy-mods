package org.civiceconomy.territory;

import java.util.Optional;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.PaymentTransaction;
import org.civiceconomy.fiscal.Reservation;
import org.civiceconomy.monetary.MonetarySupplyEvent;

public record TerritoryMaintenanceRestorationPayment(
        TerritoryMaintenanceRestoration restoration,
        Optional<Reservation> reservation,
        Optional<PaymentTransaction> publicFundPayment,
        Optional<MonetarySupplyEvent> permanentDestruction,
        MoneyAmount publicFundAmount,
        MoneyAmount destroyedAmount) {
    public TerritoryMaintenanceRestorationPayment {
        if (restoration == null
                || reservation == null
                || publicFundPayment == null
                || permanentDestruction == null
                || publicFundAmount == null
                || destroyedAmount == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Restoration payment cannot contain null values");
        }
    }
}
