package org.civiceconomy.platform.neoforge;

import org.civiceconomy.persistence.StoredMintBatch;
import org.civiceconomy.persistence.StoredMintIssuanceOperation;

record MintBatchStatus(
        StoredMintBatch batch,
        StoredMintIssuanceOperation issuance) {}
