package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredNationApplicationEvidence(
        UUID applicationId, UUID playerId, long attributedMillis) {}
