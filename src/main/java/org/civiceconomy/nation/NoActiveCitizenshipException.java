package org.civiceconomy.nation;

import java.util.UUID;

public final class NoActiveCitizenshipException extends IllegalStateException {
    public NoActiveCitizenshipException(UUID playerId) {
        super("Player " + playerId + " has no active Citizenship");
    }
}
