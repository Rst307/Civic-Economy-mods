package org.civiceconomy;

import com.mojang.logging.LogUtils;
import java.util.stream.Collectors;
import net.neoforged.fml.common.Mod;
import org.civiceconomy.compat.CompatibilityReport;
import org.civiceconomy.compat.CompatibilityScanner;
import org.civiceconomy.platform.neoforge.NeoForgeModCatalog;
import org.slf4j.Logger;

@Mod(CivicEconomy.MOD_ID)
public final class CivicEconomy {
    public static final String MOD_ID = "civiceconomy";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile CompatibilityReport compatibilityReport;

    public CivicEconomy() {
        CompatibilityReport report = CompatibilityScanner.firstSlice().scan(new NeoForgeModCatalog());
        if (!report.startupAllowed()) {
            String details = report.problems().stream()
                    .map(problem -> problem.modId() + ": " + problem.reason())
                    .collect(Collectors.joining("; "));
            throw new IllegalStateException("Civic Economy compatibility check failed closed: " + details);
        }

        compatibilityReport = report;
        if (report.productionScoringEnabled()) {
            LOGGER.info("Civic Economy compatibility check passed; Create production scoring is enabled");
        } else if (report.problems().isEmpty()) {
            LOGGER.info("Civic Economy compatibility check passed; Create is absent and production scoring is disabled");
        } else {
            LOGGER.warn(
                    "Civic Economy fiscal core is enabled, but Create production scoring is disabled: {}",
                    report.problems());
        }
    }

    public static CompatibilityReport compatibilityReport() {
        CompatibilityReport report = compatibilityReport;
        if (report == null) {
            throw new IllegalStateException("Civic Economy has not completed startup");
        }
        return report;
    }
}
