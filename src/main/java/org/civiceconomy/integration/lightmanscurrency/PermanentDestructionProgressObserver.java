package org.civiceconomy.integration.lightmanscurrency;

import org.civiceconomy.monetary.ExternalPermanentDestruction;

public interface PermanentDestructionProgressObserver {
    PermanentDestructionProgressObserver NONE =
            new PermanentDestructionProgressObserver() {};

    default void afterExternalApplied(ExternalPermanentDestruction destruction) {}

    default void afterExternalRecorded(ExternalPermanentDestruction destruction) {}
}
