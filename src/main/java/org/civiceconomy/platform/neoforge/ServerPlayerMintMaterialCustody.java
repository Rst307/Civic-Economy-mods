package org.civiceconomy.platform.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.civiceconomy.mint.ExternalMintMaterialCustody;
import org.civiceconomy.mint.MintMaterialCustodyReturn;
import org.civiceconomy.mint.MintMaterialCustodyTransfer;
import org.civiceconomy.mint.MintMaterialStack;
import org.civiceconomy.platform.neoforge.MintMaterialCustodyData.Operation;
import org.civiceconomy.platform.neoforge.MintMaterialCustodyData.SlotMutation;

public final class ServerPlayerMintMaterialCustody implements ExternalMintMaterialCustody {
    private static final Runnable NO_FAILURE = () -> {};

    private final MinecraftServer server;
    private final Function<UUID, ServerPlayer> players;
    private final Runnable afterInventoryMutation;

    public ServerPlayerMintMaterialCustody(MinecraftServer server) {
        this(server, playerId -> server.getPlayerList().getPlayer(playerId), NO_FAILURE);
    }

    ServerPlayerMintMaterialCustody(
            MinecraftServer server,
            Function<UUID, ServerPlayer> players,
            Runnable afterInventoryMutation) {
        this.server = server;
        this.players = players;
        this.afterInventoryMutation = afterInventoryMutation;
    }

    @Override
    public void take(MintMaterialCustodyTransfer transfer) {
        requireServerThread();
        MintMaterialCustodyData data = MintMaterialCustodyData.get(server);
        Operation operation = data.operation(transfer.transferId());
        if (operation == null) {
            ServerPlayer player = requirePlayer(transfer.actorPlayerId());
            TakePlan plan = prepareTake(player.getInventory(), transfer.materials());
            operation = new Operation(
                    transfer.transferId(), transfer.batchId(), transfer.mintId(),
                    transfer.actorPlayerId(), null, "TAKE_PLANNED", transfer.materials(),
                    plan.heldStacks(), plan.mutations(), List.of());
            data.put(operation);
            saveData();
        } else {
            requireSameTransfer(operation, transfer);
        }
        if (!"TAKE_PLANNED".equals(operation.state()) && !"HELD".equals(operation.state())) {
            return;
        }
        ServerPlayer player = requirePlayer(operation.playerId());
        recoverOrApply(player.getInventory(), operation.takeMutations());
        if ("HELD".equals(operation.state())) {
            return;
        }
        afterInventoryMutation.run();
        data.put(operation.withState("HELD"));
        saveData();
    }

    @Override
    public void returnToSource(MintMaterialCustodyReturn request) {
        requireServerThread();
        MintMaterialCustodyData data = MintMaterialCustodyData.get(server);
        Operation operation = data.operation(request.operationId());
        if (operation == null) {
            operation = findHeldOperation(data, request);
        }
        requireSameReturn(operation, request);
        if ("RETURNED".equals(operation.state())) {
            requireSameReturnOperation(operation, request);
            recoverOrApply(
                    requirePlayer(operation.playerId()).getInventory(),
                    operation.returnMutations());
            return;
        }
        ServerPlayer player = requirePlayer(operation.playerId());
        if ("HELD".equals(operation.state())) {
            operation = operation.withReturnPlan(
                    request.operationId(),
                    prepareReturn(player.getInventory(), operation.heldStacks()));
            data.put(operation);
            saveData();
        }
        if (!"RETURN_PLANNED".equals(operation.state())) {
            throw new IllegalStateException("Mint materials are not held for return");
        }
        requireSameReturnOperation(operation, request);
        recoverOrApply(player.getInventory(), operation.returnMutations());
        afterInventoryMutation.run();
        data.put(operation.withState("RETURNED"));
        saveData();
    }

    private Operation findHeldOperation(
            MintMaterialCustodyData data, MintMaterialCustodyReturn request) {
        Operation held = data.operation(request.batchId());
        if (held != null) {
            return held;
        }
        throw new IllegalStateException("Unknown held Mint material operation");
    }

    private void requireSameTransfer(Operation operation, MintMaterialCustodyTransfer transfer) {
        if (!operation.operationId().equals(transfer.transferId())
                || !operation.batchId().equals(transfer.batchId())
                || !operation.mintId().equals(transfer.mintId())
                || !operation.playerId().equals(transfer.actorPlayerId())
                || !operation.manifest().equals(transfer.materials())) {
            throw new IllegalArgumentException("Mint custody replay changed its immutable payload");
        }
    }

    private void requireSameReturn(Operation operation, MintMaterialCustodyReturn request) {
        if (!operation.batchId().equals(request.batchId())
                || !operation.mintId().equals(request.mintId())
                || !operation.playerId().equals(request.actorPlayerId())
                || !operation.manifest().equals(request.materials())) {
            throw new IllegalArgumentException("Mint return replay changed its immutable payload");
        }
    }

    private void requireSameReturnOperation(
            Operation operation, MintMaterialCustodyReturn request) {
        if (!request.operationId().equals(operation.returnOperationId())) {
            throw new IllegalArgumentException("Mint return replay changed its operation ID");
        }
    }

    private static TakePlan prepareTake(
            Inventory inventory, List<MintMaterialStack> materials) {
        List<ItemStack> before = snapshot(inventory);
        List<ItemStack> working = copy(before);
        List<ItemStack> held = new ArrayList<>();
        for (MintMaterialStack material : materials) {
            long remaining = material.count();
            for (int slot = 0; slot < working.size() && remaining > 0L; slot++) {
                ItemStack stack = working.get(slot);
                if (!matches(stack, material)) {
                    continue;
                }
                int removedCount = (int) Math.min(remaining, stack.getCount());
                ItemStack removed = stack.copy();
                removed.setCount(removedCount);
                held.add(removed);
                stack.shrink(removedCount);
                remaining -= removedCount;
            }
            if (remaining != 0L) {
                throw new IllegalStateException("Player inventory lacks exact Mint materials");
            }
        }
        return new TakePlan(held, mutations(before, working));
    }

    private static List<SlotMutation> prepareReturn(
            Inventory inventory, List<ItemStack> heldStacks) {
        List<ItemStack> before = snapshot(inventory);
        List<ItemStack> working = copy(before);
        for (ItemStack held : heldStacks) {
            ItemStack remaining = held.copy();
            for (int slot = 0; slot < working.size() && !remaining.isEmpty(); slot++) {
                ItemStack target = working.get(slot);
                if (target.isEmpty()
                        || !ItemStack.isSameItemSameComponents(target, remaining)
                        || target.getCount() >= target.getMaxStackSize()) {
                    continue;
                }
                int moved = Math.min(
                        remaining.getCount(), target.getMaxStackSize() - target.getCount());
                target.grow(moved);
                remaining.shrink(moved);
            }
            for (int slot = 0; slot < working.size() && !remaining.isEmpty(); slot++) {
                if (!working.get(slot).isEmpty()) {
                    continue;
                }
                int moved = Math.min(remaining.getCount(), remaining.getMaxStackSize());
                ItemStack placed = remaining.copy();
                placed.setCount(moved);
                working.set(slot, placed);
                remaining.shrink(moved);
            }
            if (!remaining.isEmpty()) {
                throw new IllegalStateException("Player inventory cannot receive returned Mint materials");
            }
        }
        return mutations(before, working);
    }

    private static boolean matches(ItemStack stack, MintMaterialStack material) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation itemId = ResourceLocation.tryParse(material.itemId());
        if (itemId == null || !BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(itemId)) {
            return false;
        }
        if ("EXACT_ITEM".equals(material.matcherKind())) {
            return material.matcherValue().equals(material.itemId());
        }
        if ("TAG".equals(material.matcherKind())) {
            ResourceLocation tagId = ResourceLocation.tryParse(material.matcherValue());
            if (tagId == null) {
                return false;
            }
            TagKey<Item> tag = ItemTags.create(tagId);
            return stack.is(tag);
        }
        return false;
    }

    private static void recoverOrApply(
            Inventory inventory, List<SlotMutation> mutations) {
        boolean allBefore = mutations.stream()
                .allMatch(mutation -> ItemStack.matches(
                        inventory.getItem(mutation.slot()), mutation.before()));
        boolean allAfter = mutations.stream()
                .allMatch(mutation -> ItemStack.matches(
                        inventory.getItem(mutation.slot()), mutation.after()));
        if (!allBefore && !allAfter) {
            throw new IllegalStateException(
                    "Mint custody inventory differs from both persisted recovery states");
        }
        if (allBefore) {
            for (SlotMutation mutation : mutations) {
                inventory.setItem(mutation.slot(), mutation.after().copy());
            }
            inventory.setChanged();
        }
    }

    private static List<SlotMutation> mutations(
            List<ItemStack> before, List<ItemStack> after) {
        List<SlotMutation> mutations = new ArrayList<>();
        for (int slot = 0; slot < before.size(); slot++) {
            if (!ItemStack.matches(before.get(slot), after.get(slot))) {
                mutations.add(new SlotMutation(slot, before.get(slot), after.get(slot)));
            }
        }
        return List.copyOf(mutations);
    }

    private static List<ItemStack> snapshot(Inventory inventory) {
        List<ItemStack> stacks = new ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            stacks.add(inventory.getItem(slot).copy());
        }
        return List.copyOf(stacks);
    }

    private static List<ItemStack> copy(List<ItemStack> stacks) {
        return stacks.stream().map(ItemStack::copy).collect(ArrayList::new, List::add, List::addAll);
    }

    private ServerPlayer requirePlayer(UUID playerId) {
        ServerPlayer player = players.apply(playerId);
        if (player == null) {
            throw new IllegalStateException("Mint material owner is not online");
        }
        return player;
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Mint inventory custody must run on the server thread");
        }
    }

    private void saveData() {
        server.overworld().getDataStorage().save();
    }

    private record TakePlan(List<ItemStack> heldStacks, List<SlotMutation> mutations) {}
}
