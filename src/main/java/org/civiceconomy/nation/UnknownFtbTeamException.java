package org.civiceconomy.nation;

import java.util.UUID;

public final class UnknownFtbTeamException extends IllegalArgumentException {
    public UnknownFtbTeamException(UUID ftbTeamId) {
        super("Cannot register a Nation for unknown FTB Team " + ftbTeamId);
    }
}
