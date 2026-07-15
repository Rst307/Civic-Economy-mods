package org.civiceconomy;

import com.mojang.logging.LogUtils;
import java.util.stream.Collectors;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.civiceconomy.compat.CompatibilityReport;
import org.civiceconomy.compat.CompatibilityScanner;
import org.civiceconomy.gametest.FtbIntegrationGameTests;
import org.civiceconomy.gametest.LightmansCurrencyFiscalAccountsGameTests;
import org.civiceconomy.gametest.LightmansCurrencyMonetaryGuardGameTests;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyPermanentDestructionsGameTests;
import org.civiceconomy.gametest.LightmansCurrencyPlayerPaymentsGameTests;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyFiscalAccounts;
import org.civiceconomy.integration.lightmanscurrency.LightmansCurrencyMonetaryGuard;
import org.civiceconomy.platform.neoforge.NeoForgeModCatalog;
import org.civiceconomy.platform.neoforge.CivicServerRuntime;
import org.civiceconomy.platform.neoforge.CivicServerRuntimeGameTests;
import org.civiceconomy.platform.neoforge.FiscalAdministrationCommands;
import org.slf4j.Logger;

@Mod(CivicEconomy.MOD_ID)
public final class CivicEconomy {
    public static final String MOD_ID = "civiceconomy";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile CompatibilityReport compatibilityReport;
    private final CivicServerRuntime serverRuntime;

    public CivicEconomy(IEventBus modEventBus) {
        CivicServerRuntime runtime = new CivicServerRuntime();
        serverRuntime = runtime;
        modEventBus.addListener(RegisterGameTestsEvent.class, CivicEconomy::registerGameTests);
        CompatibilityReport report = CompatibilityScanner.firstSlice().scan(new NeoForgeModCatalog());
        if (!report.startupAllowed()) {
            String details = report.problems().stream()
                    .map(problem -> problem.modId() + ": " + problem.reason())
                    .collect(Collectors.joining("; "));
            throw new IllegalStateException("Civic Economy compatibility check failed closed: " + details);
        }

        compatibilityReport = report;
        LightmansCurrencyMonetaryGuard.install();
        LightmansCurrencyFiscalAccounts.registerWithLightmansCurrency();
        NeoForge.EVENT_BUS.addListener(runtime::onServerStarted);
        NeoForge.EVENT_BUS.addListener(runtime::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(runtime::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(runtime::onServerTick);
        NeoForge.EVENT_BUS.addListener(runtime::onServerStopping);
        NeoForge.EVENT_BUS.addListener(runtime::onServerStopped);
        NeoForge.EVENT_BUS.addListener(FiscalAdministrationCommands::register);
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

    private static void registerGameTests(RegisterGameTestsEvent event) {
        LOGGER.info("Registering Civic Economy GameTests");
        event.register(FtbIntegrationGameTests.class);
        event.register(CivicServerRuntimeGameTests.class);
        event.register(LightmansCurrencyFiscalAccountsGameTests.class);
        event.register(LightmansCurrencyMonetaryGuardGameTests.class);
        event.register(LightmansCurrencyPermanentDestructionsGameTests.class);
        event.register(LightmansCurrencyPlayerPaymentsGameTests.class);
    }
}
