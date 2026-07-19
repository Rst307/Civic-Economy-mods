package org.civiceconomy.nation;

import java.util.UUID;

public final class CitizenshipNationMismatchException extends IllegalStateException {
    public CitizenshipNationMismatchException(UUID playerId, NationId expected, NationId actual) {
        super("Player " + playerId + " has Citizenship in Nation " + actual.value()
                + ", not Nation " + expected.value());
    }
}
