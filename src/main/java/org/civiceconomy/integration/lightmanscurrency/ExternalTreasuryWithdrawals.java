package org.civiceconomy.integration.lightmanscurrency;

import org.civiceconomy.fiscal.ExternalTreasuryWithdrawal;

interface ExternalTreasuryWithdrawals {
    void apply(ExternalTreasuryWithdrawal withdrawal);
}
