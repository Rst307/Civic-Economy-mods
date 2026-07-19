package org.civiceconomy.platform.neoforge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.civiceconomy.mint.MintMaterialStack;
import org.civiceconomy.persistence.StoredMintRecipeIngredient;

final class MintMaterialManifestSelector {
    private MintMaterialManifestSelector() {}

    static List<MintMaterialStack> select(
            Inventory inventory,
            List<StoredMintRecipeIngredient> ingredients,
            long issuedMinorUnits) {
        if (inventory == null
                || ingredients == null
                || ingredients.isEmpty()
                || issuedMinorUnits <= 0L) {
            throw new IllegalArgumentException("Mint material selection values are invalid");
        }
        Map<Integer, List<StoredMintRecipeIngredient>> byGroup = new LinkedHashMap<>();
        ingredients.stream()
                .sorted(Comparator.comparingInt(StoredMintRecipeIngredient::groupIndex))
                .forEach(ingredient -> byGroup
                        .computeIfAbsent(ingredient.groupIndex(), ignored -> new ArrayList<>())
                        .add(ingredient));
        List<MintMaterialStack> selected = new ArrayList<>();
        for (var group : byGroup.entrySet()) {
            MintMaterialStack material = group.getValue().stream()
                    .map(ingredient -> select(inventory, ingredient, issuedMinorUnits))
                    .flatMap(java.util.Optional::stream)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Player inventory lacks one complete Mint Recipe group "
                                    + group.getKey()));
            selected.add(material);
        }
        return List.copyOf(selected);
    }

    private static java.util.Optional<MintMaterialStack> select(
            Inventory inventory,
            StoredMintRecipeIngredient ingredient,
            long issuedMinorUnits) {
        long required = ingredient.quantityUnits();
        if (ingredient.perFaceValueMinorUnits() > 0L) {
            if (issuedMinorUnits % ingredient.perFaceValueMinorUnits() != 0L) {
                return java.util.Optional.empty();
            }
            required = Math.multiplyExact(
                    required, issuedMinorUnits / ingredient.perFaceValueMinorUnits());
        }
        Map<String, Long> availableByItem = new java.util.TreeMap<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!matches(stack, ingredient)) {
                continue;
            }
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            availableByItem.merge(itemId, (long) stack.getCount(), Math::addExact);
        }
        long exactRequired = required;
        return availableByItem.entrySet().stream()
                .filter(entry -> entry.getValue() >= exactRequired)
                .map(entry -> new MintMaterialStack(
                        ingredient.groupIndex(),
                        ingredient.matcherKind(),
                        ingredient.matcherValue(),
                        entry.getKey(),
                        exactRequired))
                .findFirst();
    }

    private static boolean matches(ItemStack stack, StoredMintRecipeIngredient ingredient) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation matcher = ResourceLocation.tryParse(ingredient.matcherValue());
        if (matcher == null) {
            return false;
        }
        if ("EXACT_ITEM".equals(ingredient.matcherKind())) {
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(matcher);
        }
        return "TAG".equals(ingredient.matcherKind()) && stack.is(ItemTags.create(matcher));
    }
}
