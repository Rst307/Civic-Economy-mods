package org.civiceconomy.strength;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.civiceconomy.nation.NationId;
import org.junit.jupiter.api.Test;

class MintComplianceCalculatorTest {
    @Test
    void averagesCleanAndRecoveredCommittedBatches() {
        NationId nationId = new NationId(
                UUID.fromString("11111111-2222-3333-4444-555555555555"));
        MintComplianceAssessment assessment = new MintComplianceCalculator().assess(
                nationId,
                1_000L,
                2_000L,
                2_000,
                List.of(
                        new MintComplianceObservation(
                                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                                MintComplianceOutcome.CLEAN_COMMIT),
                        new MintComplianceObservation(
                                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                                MintComplianceOutcome.RECOVERED_COMMIT)));

        assertEquals(2, assessment.observationCount());
        assertEquals(1, assessment.cleanCommitCount());
        assertEquals(1, assessment.recoveredCommitCount());
        assertEquals(6_000, assessment.normalizedBasisPoints());
        assertFalse(assessment.anomalous());
    }

    @Test
    void duplicateBatchObservationsFailClosed() {
        NationId nationId = new NationId(
                UUID.fromString("11111111-2222-3333-4444-555555555555"));
        UUID batchId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        assertThrows(IllegalArgumentException.class, () -> new MintComplianceCalculator().assess(
                nationId,
                1_000L,
                2_000L,
                5_000,
                List.of(
                        new MintComplianceObservation(
                                batchId, MintComplianceOutcome.OPEN_INCIDENT),
                        new MintComplianceObservation(
                                batchId, MintComplianceOutcome.OPEN_INCIDENT))));
    }
}
