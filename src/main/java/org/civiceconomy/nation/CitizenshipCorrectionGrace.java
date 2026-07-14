package org.civiceconomy.nation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record CitizenshipCorrectionGrace(
        UUID graceId,
        UUID citizenshipId,
        UUID playerId,
        NationId nationId,
        UUID ftbTeamId,
        String reason,
        Instant startedAt,
        Instant deadline,
        Optional<CitizenshipCorrectionResolution> resolution,
        Optional<String> resolutionReason,
        Optional<Instant> resolvedAt) {
    public CitizenshipCorrectionGrace {
        if (graceId == null || citizenshipId == null || playerId == null || nationId == null
                || ftbTeamId == null || reason == null || reason.isBlank()
                || startedAt == null || deadline == null || resolution == null
                || resolutionReason == null || resolvedAt == null) {
            throw new IllegalArgumentException("Citizenship Correction Grace cannot contain null or blank values");
        }
        if (!deadline.isAfter(startedAt)) {
            throw new IllegalArgumentException("Citizenship Correction Grace deadline must follow its start");
        }
        if (resolution.isPresent() != resolvedAt.isPresent()
                || resolution.isPresent() != resolutionReason.isPresent()) {
            throw new IllegalArgumentException("Citizenship Correction Grace resolution fields must agree");
        }
    }

    public boolean active() {
        return resolution.isEmpty();
    }
}
