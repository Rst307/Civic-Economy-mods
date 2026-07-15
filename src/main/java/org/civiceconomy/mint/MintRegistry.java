package org.civiceconomy.mint;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalCapability;
import org.civiceconomy.fiscal.FiscalServiceSession;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.issuance.IssuanceQuotaRegistry;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredMintRecipeIngredient;
import org.civiceconomy.persistence.StoredMintRecipeVersion;
import org.civiceconomy.persistence.StoredRegisteredMint;

public final class MintRegistry {
    private final CivicDatabase database;
    private final FiscalAuthorization fiscalAuthorization;
    private final FiscalServiceSession session;
    private final NationFiscalAuthorityRegistry nationAuthorities;
    private final Clock clock;

    public MintRegistry(
            CivicDatabase database,
            FiscalServiceSession session,
            NationFiscalAuthorityRegistry nationAuthorities,
            Clock clock) {
        if (database == null || session == null || nationAuthorities == null || clock == null) {
            throw new IllegalArgumentException("Mint Registry dependencies cannot be null");
        }
        this.database = database;
        this.fiscalAuthorization = new FiscalAuthorization(database);
        this.session = session;
        this.nationAuthorities = nationAuthorities;
        this.clock = clock;
    }

    public MintRecipeVersion publishRecipe(PublishMintRecipeVersion request) {
        fiscalAuthorization.require(
                session,
                request.serviceIdentity(),
                FiscalCapability.MANAGE_ISSUANCE,
                IssuanceQuotaRegistry.GLOBAL_ISSUANCE_SCOPE);
        StoredMintRecipeVersion stored = database.publishMintRecipeVersion(
                request.recipeVersionId(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.versionNumber(),
                request.ingredients().stream().map(MintRegistry::toStored).toList(),
                request.processingDuration().toMillis(),
                request.reason(),
                clock.millis());
        return toRecipe(stored);
    }

    public RegisteredMint register(RegisterMint request) {
        fiscalAuthorization.require(
                session,
                request.serviceIdentity(),
                FiscalCapability.MANAGE_ISSUANCE,
                IssuanceQuotaRegistry.treasury(request.nationId()));
        nationAuthorities.require(
                request.nationId(),
                request.actorPlayerId(),
                NationFiscalPermission.MANAGE_ISSUANCE);
        StoredRegisteredMint stored = database.registerMint(
                request.mintId(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.nationId().value(),
                request.dimensionId(),
                request.blockX(),
                request.blockY(),
                request.blockZ(),
                request.operatorOrganizationId(),
                request.licenseId(),
                request.automationAllowed(),
                request.recipeVersionId(),
                request.actorPlayerId(),
                request.reason(),
                clock.millis());
        return toMint(stored);
    }

    private MintRecipeVersion toRecipe(StoredMintRecipeVersion stored) {
        return new MintRecipeVersion(
                stored.recipeVersionId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                stored.versionNumber(),
                database.mintRecipeIngredients(stored.recipeVersionId()).stream()
                        .map(MintRegistry::toIngredient)
                        .toList(),
                Duration.ofMillis(stored.processingDurationMillis()),
                stored.reason(),
                Instant.ofEpochMilli(stored.publishedAtEpochMillis()));
    }

    private static RegisteredMint toMint(StoredRegisteredMint stored) {
        return new RegisteredMint(
                stored.mintId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                new NationId(stored.nationId()),
                stored.dimensionId(),
                stored.blockX(),
                stored.blockY(),
                stored.blockZ(),
                stored.operatorOrganizationId(),
                stored.licenseId(),
                stored.automationAllowed(),
                stored.recipeVersionId(),
                stored.actorPlayerId(),
                stored.transactionState(),
                stored.reason(),
                Instant.ofEpochMilli(stored.registeredAtEpochMillis()));
    }

    private static StoredMintRecipeIngredient toStored(MintRecipeIngredient ingredient) {
        return new StoredMintRecipeIngredient(
                ingredient.groupIndex(),
                ingredient.matcherKind().name(),
                ingredient.matcherValue(),
                ingredient.quantityUnits(),
                ingredient.perFaceValue().minorUnits());
    }

    private static MintRecipeIngredient toIngredient(StoredMintRecipeIngredient ingredient) {
        return new MintRecipeIngredient(
                ingredient.groupIndex(),
                MintIngredientMatcherKind.valueOf(ingredient.matcherKind()),
                ingredient.matcherValue(),
                ingredient.quantityUnits(),
                MoneyAmount.ofMinorUnits(ingredient.perFaceValueMinorUnits()));
    }
}
