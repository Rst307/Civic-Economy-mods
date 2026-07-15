package org.civiceconomy.mint;

@FunctionalInterface
public interface ExternalMintIssuances {
    void apply(ExternalMintIssuance issuance);
}
