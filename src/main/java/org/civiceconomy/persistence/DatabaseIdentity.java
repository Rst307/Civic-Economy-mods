package org.civiceconomy.persistence;

import java.util.UUID;

public record DatabaseIdentity(
        UUID worldId,
        String civicVersion,
        String lightmansCurrencyVersion,
        String ftbTeamsVersion,
        String ftbChunksVersion) {}
