package org.civiceconomy.platform.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NeoForgeCallerModResolverTest {
    @Test
    void resolvesTheFirstUniquelyOwnedExternalCallerClass() {
        String owner = NeoForgeCallerModResolver.resolveModId(
                List.of(
                        "org.civiceconomy.fiscal.FiscalAuthorization",
                        "org.civiceconomy.platform.neoforge.NeoForgeCallerModResolver",
                        "com.publicworks.integration.CivicBridge",
                        "java.lang.Thread"),
                Map.of(
                        "org.civiceconomy.fiscal.FiscalAuthorization", Set.of("civiceconomy"),
                        "com.publicworks.integration.CivicBridge", Set.of("publicworksmod")));

        assertEquals("publicworksmod", owner);
    }

    @Test
    void ambiguousOrUnownedCallersFailClosed() {
        Map<String, Set<String>> ambiguousOwnership = Map.of(
                "com.shared.integration.CivicBridge", Set.of("alpha", "beta"));

        assertThrows(
                UntrustedFiscalServiceCallerException.class,
                () -> NeoForgeCallerModResolver.resolveModId(
                        List.of("com.shared.integration.CivicBridge"), ambiguousOwnership));
        assertThrows(
                UntrustedFiscalServiceCallerException.class,
                () -> NeoForgeCallerModResolver.resolveModId(
                        List.of("com.unknown.CivicBridge"), Map.of()));
    }
}
