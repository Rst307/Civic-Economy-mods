package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredNation(
        UUID nationId,
        String serviceIdentity,
        String requestId,
        UUID ftbTeamId,
        long registeredAtEpochMillis) {}
