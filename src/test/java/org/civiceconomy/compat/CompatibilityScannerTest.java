package org.civiceconomy.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CompatibilityScannerTest {
    @Test
    void aSupportedRuntimeCatalogAllowsTheFiscalCoreToStart() {
        ModCatalog catalog = new ModCatalog() {
            private final Map<String, String> versions = Map.of(
                    "lightmanscurrency", "1.21-2.3.0.5",
                    "ftbteams", "2101.1.10",
                    "ftbchunks", "2101.1.20");

            @Override
            public Optional<String> version(String modId) {
                return Optional.ofNullable(versions.get(modId));
            }

            @Override
            public boolean classPresent(String className) {
                return true;
            }
        };

        CompatibilityReport report = CompatibilityScanner.firstSlice().scan(catalog);

        assertTrue(report.startupAllowed());
        assertFalse(report.productionScoringEnabled());
        assertTrue(report.problems().isEmpty());
    }
}
