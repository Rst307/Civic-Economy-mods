package org.civiceconomy.platform.neoforge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.civiceconomy.persistence.StoredMintBatch;
import org.civiceconomy.persistence.StoredMintIssuanceOperation;
import org.civiceconomy.persistence.StoredMintRecoveryIncident;
import org.junit.jupiter.api.Test;

class MintBatchStatusFormattingTest {
    @Test
    void statusIncludesLatestMintRecoveryIncidentEvidence() {
        UUID batchId = UUID.fromString("a85c0fa2-0c5f-45af-8817-2197392c9cb9");
        UUID operationId = UUID.fromString("d663d43f-777c-44d7-b6c5-8cdf044cf309");
        MintBatchStatus status = new MintBatchStatus(
                new StoredMintBatch(
                        batchId,
                        "mint-controller",
                        "prepare",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        300L,
                        UUID.randomUUID(),
                        "COMMITTING",
                        "HELD",
                        "mint-controller",
                        "custody",
                        "inventory-move",
                        "Prepare Mint Batch",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        100L,
                        101L,
                        201L),
                new StoredMintIssuanceOperation(
                        operationId,
                        batchId,
                        "mint-controller",
                        "issue",
                        "nation:treasury",
                        300L,
                        "PREPARED",
                        null,
                        null,
                        "Complete authorized Mint Batch",
                        201L,
                        null,
                        null,
                        null),
                new StoredMintRecoveryIncident(
                        UUID.randomUUID(),
                        operationId,
                        batchId,
                        "TREASURY_CREDIT",
                        "OPEN",
                        "IllegalStateException",
                        "LC Treasury unavailable",
                        202L,
                        303L,
                        2L,
                        null,
                        null,
                        null));

        String formatted = MintBatchStatusFormatter.format(status);
        assertTrue(formatted.contains("recoveryIncidentState=OPEN"));
        assertTrue(formatted.contains("recoveryStep=TREASURY_CREDIT"));
        assertTrue(formatted.contains("recoveryFailureKind=IllegalStateException"));
        assertTrue(formatted.contains("recoveryFailure=LC Treasury unavailable"));
        assertTrue(formatted.contains("recoveryOccurrences=2"));
        assertTrue(formatted.contains("recoveryFirstObservedAt=202"));
        assertTrue(formatted.contains("recoveryLastObservedAt=303"));
    }
}
