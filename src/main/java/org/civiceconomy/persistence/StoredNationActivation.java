package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredNationActivation(
        UUID applicationId,
        String serviceIdentity,
        String requestId,
        UUID nationId,
        UUID ftbTeamId,
        String treasuryAccountId,
        String capitalDimensionId,
        int capitalChunkX,
        int capitalChunkZ,
        String reason,
        int minimumEffectiveCandidates,
        boolean minimumCandidateBypassAllowed,
        long observationWindowMillis,
        String state,
        long preparedAtEpochMillis,
        Long treasuryProvisionedAtEpochMillis,
        Long committedAtEpochMillis) {}
