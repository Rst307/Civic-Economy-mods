package org.civiceconomy.platform.neoforge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;

public final class NeoForgeCallerModResolver {
    private static final String FISCAL_INFRASTRUCTURE_PREFIX = "org.civiceconomy.fiscal.";
    private static final String RESOLVER_CLASS = NeoForgeCallerModResolver.class.getName();

    private NeoForgeCallerModResolver() {}

    public static ModContainer resolveCallingModContainer() {
        ModList mods = ModList.get();
        List<String> stackClasses = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames.map(frame -> frame.getDeclaringClass().getName()).toList());
        String modId = resolveModId(stackClasses, ownershipIndex(mods));
        return mods.getModContainerById(modId)
                .map(ModContainer.class::cast)
                .orElseThrow(() -> new UntrustedFiscalServiceCallerException(
                        "Resolved fiscal caller Mod is not loaded: " + modId));
    }

    static String resolveModId(
            List<String> stackClasses, Map<String, Set<String>> ownershipByClass) {
        for (String className : stackClasses) {
            if (className.startsWith(FISCAL_INFRASTRUCTURE_PREFIX)
                    || className.equals(RESOLVER_CLASS)) {
                continue;
            }
            Set<String> owners = ownershipByClass.get(className);
            if (owners == null || owners.isEmpty()) {
                continue;
            }
            if (owners.size() != 1) {
                throw new UntrustedFiscalServiceCallerException(
                        "Fiscal caller class has ambiguous NeoForge Mod ownership: "
                                + className + " -> " + owners);
            }
            return owners.iterator().next();
        }
        throw new UntrustedFiscalServiceCallerException(
                "Unable to resolve a uniquely owned external NeoForge Mod caller");
    }

    private static Map<String, Set<String>> ownershipIndex(ModList mods) {
        Map<String, Set<String>> ownership = new HashMap<>();
        mods.getMods().forEach(modInfo -> modInfo.getOwningFile()
                .getFile()
                .getScanResult()
                .getClasses()
                .forEach(classData -> ownership
                        .computeIfAbsent(
                                classData.clazz().getClassName(), ignored -> new HashSet<>())
                        .add(modInfo.getModId())));
        Map<String, Set<String>> immutable = new HashMap<>();
        ownership.forEach((className, owners) -> immutable.put(className, Set.copyOf(owners)));
        return Map.copyOf(immutable);
    }
}
