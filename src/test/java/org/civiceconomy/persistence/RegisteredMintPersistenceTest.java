package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegisteredMintPersistenceTest {
    private static final UUID NATION =
            UUID.fromString("a5f3c85b-9346-4195-a639-bf83f8b0f711");
    private static final UUID RECIPE =
            UUID.fromString("2dc67565-50e4-46cc-8d29-d3414375a950");
    private static final UUID MINT =
            UUID.fromString("4e79047d-f0aa-4fad-8904-b69fdc939b22");
    private static final long NOW =
            Instant.parse("2026-08-01T00:00:00Z").toEpochMilli();

    @TempDir Path temporaryDirectory;

    @Test
    void persistsStructuredVersionedRecipeAndPermanentMintIdentityAcrossReopen() {
        List<StoredMintRecipeIngredient> ingredients = recipeIngredients();
        try (CivicDatabase database = database()) {
            registerNation(database);
            StoredMintRecipeVersion recipe = database.publishMintRecipeVersion(
                    RECIPE,
                    "mint-controller",
                    "publish-recipe-v1",
                    1,
                    ingredients,
                    60_000L,
                    "Initial conservative composite recipe",
                    NOW);
            assertEquals(1, recipe.versionNumber());
            assertEquals(ingredients, database.mintRecipeIngredients(RECIPE));

            StoredRegisteredMint mint = database.registerMint(
                    MINT,
                    "mint-controller",
                    "register-capital-mint",
                    NATION,
                    "minecraft:overworld",
                    12,
                    70,
                    -4,
                    UUID.fromString("b2572c8b-ddb5-49e0-8a4c-1ce3a1b5a544"),
                    UUID.fromString("018db9bd-3a4d-46fd-88d0-dd71eaebf70a"),
                    true,
                    RECIPE,
                    UUID.fromString("a716eace-6ed2-459a-8847-abda0456e8c1"),
                    "Register capital mint",
                    NOW + 1L);
            assertEquals("IDLE", mint.transactionState());
            assertEquals(RECIPE, mint.recipeVersionId());
            assertEquals(
                    UUID.fromString("a716eace-6ed2-459a-8847-abda0456e8c1"),
                    mint.actorPlayerId());
        }

        try (CivicDatabase database = database()) {
            assertNotNull(database.mintRecipeVersion(RECIPE));
            assertEquals(recipeIngredients(), database.mintRecipeIngredients(RECIPE));
            StoredRegisteredMint mint = database.registeredMint(MINT);
            assertNotNull(mint);
            assertEquals(NATION, mint.nationId());
            assertEquals("minecraft:overworld", mint.dimensionId());
            assertEquals(-4, mint.blockZ());
        }
    }

    @Test
    void recipeReplayIsImmutableAndVersionAndLocationAreUnique() {
        try (CivicDatabase database = database()) {
            registerNation(database);
            database.publishMintRecipeVersion(
                    RECIPE, "mint-controller", "recipe-v1", 1, recipeIngredients(),
                    60_000L, "Recipe v1", NOW);
            assertThrows(IllegalArgumentException.class, () -> database.publishMintRecipeVersion(
                    RECIPE, "mint-controller", "recipe-v1", 1,
                    List.of(new StoredMintRecipeIngredient(
                            0, "EXACT_ITEM", "minecraft:iron_ingot", 2L, 0L)),
                    60_000L, "Recipe v1", NOW));
            assertThrows(IllegalStateException.class, () -> database.publishMintRecipeVersion(
                    UUID.randomUUID(), "mint-controller", "duplicate-version", 1,
                    recipeIngredients(), 60_000L, "Duplicate version", NOW + 1L));

            registerMint(database, MINT, "register-first", 12, 70, -4,
                    UUID.fromString("018db9bd-3a4d-46fd-88d0-dd71eaebf70a"));
            assertThrows(IllegalStateException.class, () -> registerMint(
                    database, UUID.randomUUID(), "duplicate-location", 12, 70, -4,
                    UUID.randomUUID()));
            assertThrows(IllegalStateException.class, () -> registerMint(
                    database, UUID.randomUUID(), "duplicate-license", 13, 70, -4,
                    UUID.fromString("018db9bd-3a4d-46fd-88d0-dd71eaebf70a")));
        }
    }

    @Test
    void mintCannotReferenceUnknownNationOrRecipe() {
        try (CivicDatabase database = database()) {
            registerNation(database);
            assertThrows(IllegalStateException.class, () -> registerMint(
                    database, MINT, "unknown-recipe", 1, 2, 3, UUID.randomUUID()));

            database.publishMintRecipeVersion(
                    RECIPE, "mint-controller", "recipe-v1", 1, recipeIngredients(),
                    60_000L, "Recipe v1", NOW);
            assertThrows(IllegalStateException.class, () -> database.registerMint(
                    MINT,
                    "mint-controller",
                    "unknown-nation",
                    UUID.randomUUID(),
                    "minecraft:overworld",
                    1,
                    2,
                    3,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    false,
                    RECIPE,
                    UUID.randomUUID(),
                    "Must fail",
                    NOW + 1L));
        }
    }

    private StoredRegisteredMint registerMint(
            CivicDatabase database,
            UUID mintId,
            String requestId,
            int blockX,
            int blockY,
            int blockZ,
            UUID licenseId) {
        return database.registerMint(
                mintId,
                "mint-controller",
                requestId,
                NATION,
                "minecraft:overworld",
                blockX,
                blockY,
                blockZ,
                UUID.fromString("b2572c8b-ddb5-49e0-8a4c-1ce3a1b5a544"),
                licenseId,
                true,
                RECIPE,
                UUID.fromString("a716eace-6ed2-459a-8847-abda0456e8c1"),
                "Register mint",
                NOW + 1L);
    }

    private List<StoredMintRecipeIngredient> recipeIngredients() {
        return List.of(
                new StoredMintRecipeIngredient(
                        0, "EXACT_ITEM", "minecraft:iron_ingot", 2L, 0L),
                new StoredMintRecipeIngredient(
                        1, "TAG", "c:gems/diamond", 1L, 100L),
                new StoredMintRecipeIngredient(
                        1, "EXACT_ITEM", "minecraft:diamond", 1L, 100L));
    }

    private void registerNation(CivicDatabase database) {
        database.registerNation(NATION, "test", "register-nation", UUID.randomUUID(), NOW - 1L);
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("registered-mint.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("b092e709-875b-4e43-946f-d1caabebcb24"),
                        "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20"));
    }
}
