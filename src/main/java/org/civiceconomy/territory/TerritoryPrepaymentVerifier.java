package org.civiceconomy.territory;

import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.nation.NationId;

@FunctionalInterface
public interface TerritoryPrepaymentVerifier {
    boolean isCommitted(UUID transactionId, NationId nationId, MoneyAmount amount);
}
