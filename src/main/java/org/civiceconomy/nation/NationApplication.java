package org.civiceconomy.nation;

import java.time.Instant;
import java.util.UUID;

public record NationApplication(
        NationApplicationId applicationId,
        UUID ftbTeamId,
        UUID applicantPlayerId,
        Instant createdAt,
        Instant expiresAt,
        NationApplicationState state) {}
