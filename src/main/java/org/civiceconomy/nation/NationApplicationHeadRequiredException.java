package org.civiceconomy.nation;

import java.util.UUID;

public final class NationApplicationHeadRequiredException extends SecurityException {
    public NationApplicationHeadRequiredException(UUID ftbTeamId, UUID applicantPlayerId) {
        super("Player " + applicantPlayerId + " is not the head of FTB Team " + ftbTeamId);
    }
}
