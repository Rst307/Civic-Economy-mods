package org.civiceconomy.integration.lightmanscurrency;

import org.civiceconomy.fiscal.ExternalTreasuryWithdrawal;

@FunctionalInterface
public interface TreasuryWithdrawalProgressObserver {
    TreasuryWithdrawalProgressObserver NONE = withdrawal -> {};

    void afterTreasuryDebit(ExternalTreasuryWithdrawal withdrawal);

    default void afterCashDelivery(ExternalTreasuryWithdrawal withdrawal) {}
}
