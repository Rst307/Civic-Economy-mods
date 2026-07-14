package org.civiceconomy.integration.lightmanscurrency;

import org.civiceconomy.monetary.ExternalPermanentDestruction;

@FunctionalInterface
interface ExternalPermanentDestructions {
    void apply(ExternalPermanentDestruction destruction);
}
