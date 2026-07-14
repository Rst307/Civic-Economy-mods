package org.civiceconomy.platform.neoforge;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

final class CivicDebugWorldData extends SavedData {
    private static final String DATA_NAME = "civiceconomy_debug_world";
    private static final Factory<CivicDebugWorldData> FACTORY =
            new Factory<>(CivicDebugWorldData::new, CivicDebugWorldData::load);

    private boolean enabled;

    private CivicDebugWorldData() {}

    static CivicDebugWorldData get(MinecraftServer server) {
        return server.overworld()
                .getDataStorage()
                .computeIfAbsent(FACTORY, DATA_NAME);
    }

    boolean enabled() {
        return enabled;
    }

    boolean enable() {
        if (!enabled) {
            enabled = true;
            setDirty();
            return true;
        }
        return false;
    }

    private static CivicDebugWorldData load(
            CompoundTag root, HolderLookup.Provider registries) {
        CivicDebugWorldData data = new CivicDebugWorldData();
        data.enabled = root.getBoolean("Enabled");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        root.putBoolean("Enabled", enabled);
        return root;
    }
}
