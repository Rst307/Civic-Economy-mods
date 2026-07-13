package org.civiceconomy.platform.neoforge;

import java.util.Optional;
import net.neoforged.fml.ModList;
import org.civiceconomy.compat.ModCatalog;

public final class NeoForgeModCatalog implements ModCatalog {
    private final ClassLoader classLoader;

    public NeoForgeModCatalog() {
        this(NeoForgeModCatalog.class.getClassLoader());
    }

    NeoForgeModCatalog(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public Optional<String> version(String modId) {
        return ModList.get().getMods().stream()
                .filter(modInfo -> modInfo.getModId().equals(modId))
                .map(modInfo -> modInfo.getVersion().toString())
                .findFirst();
    }

    @Override
    public boolean classPresent(String className) {
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }
}
