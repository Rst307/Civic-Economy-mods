package org.civiceconomy.platform.neoforge;

import org.civiceconomy.persistence.StoredMintBatch;
import org.civiceconomy.persistence.StoredMintIssuanceOperation;
import org.civiceconomy.persistence.StoredMintRecoveryIncident;

record MintBatchStatus(
        StoredMintBatch batch,
        StoredMintIssuanceOperation issuance,
        StoredMintRecoveryIncident recoveryIncident) {}
