package org.civiceconomy.nation;

import java.util.UUID;

public final class CitizenshipTransferCooldownException extends IllegalStateException {
    private final long eligibleAtEpochMillis;

    public CitizenshipTransferCooldownException(UUID playerId, long eligibleAtEpochMillis) {
        super("Player " + playerId + " cannot acquire Citizenship until epoch millis " + eligibleAtEpochMillis);
        this.eligibleAtEpochMillis = eligibleAtEpochMillis;
    }

    public long eligibleAtEpochMillis() {
        return eligibleAtEpochMillis;
    }
}
