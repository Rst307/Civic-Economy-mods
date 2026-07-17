package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredMintComplianceObservation(
        UUID batchId, String operationState, boolean hadIncident, boolean openIncident) {}
