package org.civiceconomy.nation;

import java.util.UUID;

public final class NationApplicationAlreadyPendingException extends IllegalStateException {
    private final NationApplicationId applicationId;

    public NationApplicationAlreadyPendingException(
            UUID ftbTeamId, NationApplicationId applicationId) {
        super("FTB Team " + ftbTeamId + " already has pending Nation Application "
                + applicationId.value());
        this.applicationId = applicationId;
    }

    public NationApplicationId applicationId() {
        return applicationId;
    }
}
