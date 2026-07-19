package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredNationalIssuanceQuota(
        UUID periodId,
        UUID nationId,
        long ceilingMinorUnits,
        long activatedMinorUnits,
        long reservedMinorUnits,
        long usedMinorUnits) {}
