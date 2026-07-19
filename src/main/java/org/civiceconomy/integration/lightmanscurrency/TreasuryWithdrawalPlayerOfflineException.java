package org.civiceconomy.integration.lightmanscurrency;

import java.util.UUID;

public final class TreasuryWithdrawalPlayerOfflineException extends IllegalStateException {
    public TreasuryWithdrawalPlayerOfflineException(UUID playerId) {
        super("Treasury Withdrawal player is offline: " + playerId);
    }
}
