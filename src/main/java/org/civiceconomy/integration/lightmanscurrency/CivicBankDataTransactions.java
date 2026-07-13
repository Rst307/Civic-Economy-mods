package org.civiceconomy.integration.lightmanscurrency;

import java.util.UUID;

public interface CivicBankDataTransactions {
    boolean civicEconomy$wasApplied(UUID transactionId);

    void civicEconomy$recordApplied(UUID transactionId);
}
