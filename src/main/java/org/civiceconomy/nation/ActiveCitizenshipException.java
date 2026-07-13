package org.civiceconomy.nation;

import java.util.UUID;

public final class ActiveCitizenshipException extends IllegalStateException {
    public ActiveCitizenshipException(UUID playerId, NationId nationId) {
        super("Player " + playerId + " already has active Citizenship in Nation " + nationId.value());
    }
}
