package org.civiceconomy.platform.neoforge;

import java.util.UUID;

record DatabaseRestoreActivation(
        UUID operationId, UUID rollbackBackupOperationId, String rollbackFileName) {}
