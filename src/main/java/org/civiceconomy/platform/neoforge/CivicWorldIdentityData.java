package org.civiceconomy.platform.neoforge;

import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

final class CivicWorldIdentityData extends SavedData {
    private static final String DATA_NAME = "civiceconomy_world_identity";
    private static final String WORLD_ID_TAG = "WorldId";
    private static final Factory<CivicWorldIdentityData> FACTORY =
            new Factory<>(CivicWorldIdentityData::new, CivicWorldIdentityData::load);

    private final UUID worldId;
    private boolean newlyCreated;

    private CivicWorldIdentityData() {
        this(UUID.randomUUID(), true);
        setDirty();
    }

    private CivicWorldIdentityData(UUID worldId, boolean newlyCreated) {
        this.worldId = worldId;
        this.newlyCreated = newlyCreated;
    }

    static UUID getOrCreate(MinecraftServer server) {
        var storage = server.overworld().getDataStorage();
        CivicWorldIdentityData identity = storage.computeIfAbsent(FACTORY, DATA_NAME);
        if (identity.newlyCreated) {
            storage.save();
            identity.newlyCreated = false;
        }
        return identity.worldId;
    }

    private static CivicWorldIdentityData load(CompoundTag root, HolderLookup.Provider registries) {
        if (!root.hasUUID(WORLD_ID_TAG)) {
            throw new IllegalStateException("Civic world identity is missing its UUID");
        }
        return new CivicWorldIdentityData(root.getUUID(WORLD_ID_TAG), false);
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        root.putUUID(WORLD_ID_TAG, worldId);
        return root;
    }
}
