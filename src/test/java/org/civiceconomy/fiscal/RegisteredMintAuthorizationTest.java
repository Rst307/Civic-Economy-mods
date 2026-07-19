package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.issuance.IssuanceQuotaRegistry;
import org.civiceconomy.mint.MintIngredientMatcherKind;
import org.civiceconomy.mint.MintRecipeIngredient;
import org.civiceconomy.mint.MintRegistry;
import org.civiceconomy.mint.PublishMintRecipeVersion;
import org.civiceconomy.mint.RegisterMint;
import org.civiceconomy.nation.GrantNationFiscalPermission;
import org.civiceconomy.nation.NationFacts;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.NationProvider;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegisteredMintAuthorizationTest {
    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ServiceIdentity SERVICE = new ServiceIdentity("civiceconomy-mint");
    private static final ServiceIdentity ADMIN = new ServiceIdentity("trusted-admin");
    private static final NationId NATION =
            new NationId(UUID.fromString("946d498b-9ae4-4f34-af35-299a8a8a92c8"));
    private static final UUID ACTOR =
            UUID.fromString("7aa402ef-05ac-4f64-88c9-96d49cbf52b8");
    private static final UUID RECIPE =
            UUID.fromString("b281d84d-1cd2-443c-bd2e-09224df8f56c");
    private static final UUID MINT =
            UUID.fromString("b85ad991-a124-4df9-8ef6-e879f94464a4");

    @TempDir Path temporaryDirectory;

    @Test
    void exactGlobalAndNationAuthorityPublishRecipeAndRegisterMint() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database, true);
            var recipe = fixtures.registry().publishRecipe(recipeRequest(SERVICE));
            assertEquals(1, recipe.versionNumber());

            var mint = fixtures.registry().register(new RegisterMint(
                    SERVICE,
                    "register-mint",
                    MINT,
                    NATION,
                    "minecraft:overworld",
                    5,
                    72,
                    9,
                    UUID.fromString("6fc2e7cf-a2f6-4c78-9826-a0c65b8beaa3"),
                    UUID.fromString("218c75c2-da8c-4148-98a6-b0966e848083"),
                    true,
                    RECIPE,
                    ACTOR,
                    "Nation licenses its capital mint"));
            assertEquals(NATION, mint.nationId());
            assertEquals(ACTOR, mint.actorPlayerId());
            assertEquals("IDLE", mint.transactionState());
        }
    }

    @Test
    void missingNationAuthorityAndImpostorIdentityFailBeforeMintPersistence() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database, false);
            fixtures.registry().publishRecipe(recipeRequest(SERVICE));

            assertThrows(SecurityException.class, () -> fixtures.registry().register(
                    new RegisterMint(
                            SERVICE,
                            "unauthorized-mint",
                            MINT,
                            NATION,
                            "minecraft:overworld",
                            5,
                            72,
                            9,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            false,
                            RECIPE,
                            ACTOR,
                            "Must fail")));
            assertNull(database.registeredMint(SERVICE.value(), "unauthorized-mint"));

            assertThrows(FiscalServiceIdentityMismatchException.class, () ->
                    fixtures.registry().publishRecipe(recipeRequest(
                            new ServiceIdentity("impostor-service"))));
        }
    }

    private Fixtures fixtures(CivicDatabase database, boolean grantNationAuthority) {
        database.registerNation(
                NATION.value(), "test", "nation", UUID.randomUUID(), NOW.minusMillis(1L).toEpochMilli());
        NationProvider provider = new NationProvider() {
            @Override
            public Optional<NationFacts> find(NationId nationId) {
                return NATION.equals(nationId)
                        ? Optional.of(new NationFacts(NATION, ACTOR, Set.of(ACTOR)))
                        : Optional.empty();
            }

            @Override
            public Optional<NationFacts> findForCitizen(UUID playerId) {
                return ACTOR.equals(playerId) ? find(NATION) : Optional.empty();
            }
        };
        NationFiscalAuthorityRegistry nationAuthorities =
                new NationFiscalAuthorityRegistry(database, provider, CLOCK);
        if (grantNationAuthority) {
            nationAuthorities.grant(new GrantNationFiscalPermission(
                    SERVICE,
                    "grant-manage-issuance",
                    NATION,
                    ACTOR,
                    ACTOR,
                    NationFiscalPermission.MANAGE_ISSUANCE,
                    "Nation appoints its mint registrar"));
        }
        FiscalAuthorization authorization = new FiscalAuthorization(database);
        authorization.register(new RegisterFiscalService(
                SERVICE,
                "civiceconomy",
                "Civic mint controller",
                ADMIN,
                "register-service",
                "Register exact mint service"));
        authorization.grant(new GrantFiscalCapability(
                ADMIN,
                "grant-global",
                SERVICE,
                FiscalCapability.MANAGE_ISSUANCE,
                IssuanceQuotaRegistry.GLOBAL_ISSUANCE_SCOPE,
                "Publish global recipes"));
        authorization.grant(new GrantFiscalCapability(
                ADMIN,
                "grant-nation",
                SERVICE,
                FiscalCapability.MANAGE_ISSUANCE,
                IssuanceQuotaRegistry.treasury(NATION),
                "Register exact Nation mints"));
        FiscalServiceSession session = authorization.openSession(SERVICE, "civiceconomy");
        return new Fixtures(new MintRegistry(database, session, nationAuthorities, CLOCK));
    }

    private PublishMintRecipeVersion recipeRequest(ServiceIdentity serviceIdentity) {
        return new PublishMintRecipeVersion(
                serviceIdentity,
                "publish-recipe-v1",
                RECIPE,
                1,
                List.of(
                        new MintRecipeIngredient(
                                0,
                                MintIngredientMatcherKind.EXACT_ITEM,
                                "minecraft:iron_ingot",
                                2L,
                                MoneyAmount.ZERO),
                        new MintRecipeIngredient(
                                1,
                                MintIngredientMatcherKind.TAG,
                                "c:gems/diamond",
                                1L,
                                MoneyAmount.ofMinorUnits(100L))),
                Duration.ofMinutes(1L),
                "Initial global recipe");
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("registered-mint-authorization.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("0272df38-448b-48fd-b0f0-1e459de349a0"),
                        "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20"));
    }

    private record Fixtures(MintRegistry registry) {}
}
