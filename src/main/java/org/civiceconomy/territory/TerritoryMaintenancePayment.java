package org.civiceconomy.territory;

import java.util.Optional;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.PaymentTransaction;
import org.civiceconomy.fiscal.Reservation;
import org.civiceconomy.monetary.MonetarySupplyEvent;

public record TerritoryMaintenancePayment(
        Reservation reservation,
        PaymentTransaction publicFundPayment,
        Optional<MonetarySupplyEvent> permanentDestruction,
        MoneyAmount publicFundAmount,
        MoneyAmount destroyedAmount,
        TerritoryMaintenanceSettlement settlement) {}
