package org.civiceconomy.nation;

public record CitizenshipReconciliationResult(
        int startedGraceCount,
        int restoredCount,
        int endedCitizenshipCount) {
    public CitizenshipReconciliationResult {
        if (startedGraceCount < 0 || restoredCount < 0 || endedCitizenshipCount < 0) {
            throw new IllegalArgumentException("Citizenship reconciliation counts cannot be negative");
        }
    }
}
