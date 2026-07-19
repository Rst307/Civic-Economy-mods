package org.civiceconomy.mint;

@FunctionalInterface
public interface RegisteredMintTerritoryAuthority {
    boolean isEffective(RegisteredMint mint);
}