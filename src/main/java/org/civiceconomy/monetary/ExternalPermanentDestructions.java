package org.civiceconomy.monetary;

@FunctionalInterface
public interface ExternalPermanentDestructions {
    void apply(ExternalPermanentDestruction destruction);
}
