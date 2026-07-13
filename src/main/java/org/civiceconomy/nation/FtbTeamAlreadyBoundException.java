package org.civiceconomy.nation;

import java.util.UUID;

public final class FtbTeamAlreadyBoundException extends IllegalStateException {
    public FtbTeamAlreadyBoundException(UUID ftbTeamId, NationId nationId) {
        super("FTB Team " + ftbTeamId + " is already bound to Nation " + nationId.value());
    }
}
