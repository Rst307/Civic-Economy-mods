package org.civiceconomy.platform.neoforge;

final class MintBatchStatusFormatter {
    private MintBatchStatusFormatter() {}

    static String format(MintBatchStatus status) {
        var batch = status.batch();
        var issuance = status.issuance();
        var incident = status.recoveryIncident();
        return "Mint Batch status: batch=" + batch.batchId()
                + " state=" + batch.state()
                + " custody=" + batch.custodyState()
                + " amount=" + batch.issuedMinorUnits()
                + " preparedAt=" + batch.preparedAtEpochMillis()
                + " completesAt=" + batch.processingCompletesAtEpochMillis()
                + " reason=" + batch.reason()
                + " issuanceState=" + (issuance == null ? "NOT_PREPARED" : issuance.state())
                + " externalReference="
                + (issuance == null ? null : issuance.externalReference())
                + " materialReference="
                + (issuance == null ? null : issuance.materialConsumptionReference())
                + " recoveryIncidentState=" + (incident == null ? "NONE" : incident.state())
                + " recoveryStep=" + (incident == null ? null : incident.step())
                + " recoveryFailureKind="
                + (incident == null ? null : incident.failureKind())
                + " recoveryFailure=" + (incident == null ? null : incident.failureMessage())
                + " recoveryOccurrences="
                + (incident == null ? 0L : incident.occurrenceCount())
                + " recoveryFirstObservedAt="
                + (incident == null ? null : incident.firstObservedAtEpochMillis())
                + " recoveryLastObservedAt="
                + (incident == null ? null : incident.lastObservedAtEpochMillis())
                + " recoveryResolutionKind="
                + (incident == null ? null : incident.resolutionKind())
                + " recoveryResolution="
                + (incident == null ? null : incident.resolutionDetail())
                + " recoveryResolvedAt="
                + (incident == null ? null : incident.resolvedAtEpochMillis());
    }
}
