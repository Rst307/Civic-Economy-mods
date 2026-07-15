package org.civiceconomy.platform.neoforge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import org.civiceconomy.mint.MintMaterialStack;

final class MintMaterialCustodyData extends SavedData {
    private static final String DATA_NAME = "civiceconomy_mint_material_custody";
    private static final Factory<MintMaterialCustodyData> FACTORY =
            new Factory<>(MintMaterialCustodyData::new, MintMaterialCustodyData::load);

    private final Map<UUID, Operation> operations = new LinkedHashMap<>();

    private MintMaterialCustodyData() {}

    static MintMaterialCustodyData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    Operation operation(UUID operationId) {
        return operations.get(operationId);
    }

    void put(Operation operation) {
        operations.put(operation.operationId(), operation);
        setDirty();
    }

    private static MintMaterialCustodyData load(
            CompoundTag root, HolderLookup.Provider registries) {
        MintMaterialCustodyData data = new MintMaterialCustodyData();
        ListTag savedOperations = root.getList("Operations", Tag.TAG_COMPOUND);
        for (int index = 0; index < savedOperations.size(); index++) {
            Operation operation = Operation.load(savedOperations.getCompound(index), registries);
            if (data.operations.put(operation.operationId(), operation) != null) {
                throw new IllegalStateException("Duplicate persisted Mint custody operation");
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        ListTag savedOperations = new ListTag();
        operations.values().forEach(operation -> savedOperations.add(operation.save(registries)));
        root.put("Operations", savedOperations);
        return root;
    }

    record Operation(
            UUID operationId,
            UUID batchId,
            UUID mintId,
            UUID playerId,
            UUID returnOperationId,
            String state,
            List<MintMaterialStack> manifest,
            List<ItemStack> heldStacks,
            List<SlotMutation> takeMutations,
            List<SlotMutation> returnMutations) {
        Operation {
            manifest = List.copyOf(manifest);
            heldStacks = copyStacks(heldStacks);
            takeMutations = List.copyOf(takeMutations);
            returnMutations = List.copyOf(returnMutations);
        }

        Operation withState(String newState) {
            return new Operation(
                    operationId, batchId, mintId, playerId, returnOperationId, newState,
                    manifest, heldStacks, takeMutations, returnMutations);
        }

        Operation withReturnPlan(UUID operationId, List<SlotMutation> mutations) {
            return new Operation(
                    this.operationId, batchId, mintId, playerId, operationId, "RETURN_PLANNED",
                    manifest, heldStacks, takeMutations, mutations);
        }

        CompoundTag save(HolderLookup.Provider registries) {
            CompoundTag root = new CompoundTag();
            root.putUUID("OperationId", operationId);
            root.putUUID("BatchId", batchId);
            root.putUUID("MintId", mintId);
            root.putUUID("PlayerId", playerId);
            if (returnOperationId != null) {
                root.putUUID("ReturnOperationId", returnOperationId);
            }
            root.putString("State", state);
            ListTag savedManifest = new ListTag();
            for (MintMaterialStack material : manifest) {
                CompoundTag saved = new CompoundTag();
                saved.putInt("GroupIndex", material.groupIndex());
                saved.putString("MatcherKind", material.matcherKind());
                saved.putString("MatcherValue", material.matcherValue());
                saved.putString("ItemId", material.itemId());
                saved.putLong("Count", material.count());
                savedManifest.add(saved);
            }
            root.put("Manifest", savedManifest);
            root.put("HeldStacks", saveStacks(heldStacks, registries));
            root.put("TakeMutations", saveMutations(takeMutations, registries));
            root.put("ReturnMutations", saveMutations(returnMutations, registries));
            return root;
        }

        static Operation load(CompoundTag root, HolderLookup.Provider registries) {
            List<MintMaterialStack> manifest = new ArrayList<>();
            ListTag savedManifest = root.getList("Manifest", Tag.TAG_COMPOUND);
            for (int index = 0; index < savedManifest.size(); index++) {
                CompoundTag saved = savedManifest.getCompound(index);
                manifest.add(new MintMaterialStack(
                        saved.getInt("GroupIndex"),
                        saved.getString("MatcherKind"),
                        saved.getString("MatcherValue"),
                        saved.getString("ItemId"),
                        saved.getLong("Count")));
            }
            return new Operation(
                    root.getUUID("OperationId"),
                    root.getUUID("BatchId"),
                    root.getUUID("MintId"),
                    root.getUUID("PlayerId"),
                    root.hasUUID("ReturnOperationId") ? root.getUUID("ReturnOperationId") : null,
                    root.getString("State"),
                    manifest,
                    loadStacks(root.getList("HeldStacks", Tag.TAG_COMPOUND), registries),
                    loadMutations(root.getList("TakeMutations", Tag.TAG_COMPOUND), registries),
                    loadMutations(root.getList("ReturnMutations", Tag.TAG_COMPOUND), registries));
        }
    }

    record SlotMutation(int slot, ItemStack before, ItemStack after) {
        SlotMutation {
            before = before.copy();
            after = after.copy();
        }
    }

    private static ListTag saveStacks(
            List<ItemStack> stacks, HolderLookup.Provider registries) {
        ListTag saved = new ListTag();
        stacks.forEach(stack -> {
            CompoundTag entry = new CompoundTag();
            entry.put("Stack", stack.saveOptional(registries));
            saved.add(entry);
        });
        return saved;
    }

    private static List<ItemStack> loadStacks(
            ListTag saved, HolderLookup.Provider registries) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int index = 0; index < saved.size(); index++) {
            stacks.add(ItemStack.parseOptional(
                    registries, saved.getCompound(index).getCompound("Stack")));
        }
        return List.copyOf(stacks);
    }

    private static ListTag saveMutations(
            List<SlotMutation> mutations, HolderLookup.Provider registries) {
        ListTag saved = new ListTag();
        for (SlotMutation mutation : mutations) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("Slot", mutation.slot());
            entry.put("Before", mutation.before().saveOptional(registries));
            entry.put("After", mutation.after().saveOptional(registries));
            saved.add(entry);
        }
        return saved;
    }

    private static List<SlotMutation> loadMutations(
            ListTag saved, HolderLookup.Provider registries) {
        List<SlotMutation> mutations = new ArrayList<>();
        for (int index = 0; index < saved.size(); index++) {
            CompoundTag entry = saved.getCompound(index);
            mutations.add(new SlotMutation(
                    entry.getInt("Slot"),
                    ItemStack.parseOptional(registries, entry.getCompound("Before")),
                    ItemStack.parseOptional(registries, entry.getCompound("After"))));
        }
        return List.copyOf(mutations);
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        return stacks.stream().map(ItemStack::copy).toList();
    }
}
