package org.civiceconomy.integration.ftb;

import java.util.Set;
import java.util.UUID;

public record FtbTeamFacts(
        UUID teamId,
        UUID effectiveTeamId,
        UUID ownerId,
        Set<UUID> members,
        boolean playerTeam,
        boolean partyTeam,
        boolean serverTeam) {
    public FtbTeamFacts {
        if (teamId == null || effectiveTeamId == null || ownerId == null || members == null) {
            throw new IllegalArgumentException("FTB Team facts cannot contain null values");
        }
        members = Set.copyOf(members);
    }
}
