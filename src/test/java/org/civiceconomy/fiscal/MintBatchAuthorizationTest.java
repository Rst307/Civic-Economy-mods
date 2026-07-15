package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.issuance.IssuanceQuotaRegistry;
import org.civiceconomy.mint.CancelMintBatch;
import org.civiceconomy.mint.ExternalMintMaterialCustody;
import org.civiceconomy.mint.MintBatchCoordinator;
import org.civiceconomy.mint.MintBatchRecovery;
import org.civiceconomy.mint.MintMaterialCustodyReturn;
import org.civiceconomy.mint.MintMaterialCustodyTransfer;
import org.civiceconomy.mint.MintMaterialStack;
import org.civiceconomy.mint.PrepareMintBatch;
import org.civiceconomy.mint.RegisteredMintTerritoryAuthority;
import org.civiceconomy.nation.GrantNationFiscalPermission;
import org.civiceconomy.nation.NationFacts;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.NationProvider;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.civiceconomy.persistence.StoredMintRecipeIngredient;
import org.civiceconomy.persistence.StoredNationalIssuanceQuotaAllocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MintBatchAuthorizationTest {
    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ServiceIdentity SERVICE = new ServiceIdentity("civiceconomy-mint");
    private static final ServiceIdentity ADMIN = new ServiceIdentity("trusted-admin");
    private static final NationId NATION = new NationId(UUID.fromString("8f959367-156f-4b67-941b-a1e0f1d520bf"));
    private static final UUID ACTOR = UUID.fromString("afe0c1bf-d7f6-460e-b5ed-d50453f1b51a");
    private static final UUID PERIOD = UUID.fromString("c271b844-4bf0-464d-b31a-10c61b6204af");
    private static final UUID RECIPE = UUID.fromString("e6d4402a-1856-4270-8777-e774f53da50f");
    private static final UUID MINT = UUID.fromString("f4d60ce4-61c1-49cd-afef-797867455198");

    @TempDir Path temporaryDirectory;

    @Test
    void derivesMintFactsAndMovesCustodyOnlyAfterExactAuthorizationAndTerritory() {
        try (CivicDatabase database = database()) {
            RecordingCustody custody = new RecordingCustody();
            Fixtures fixtures = fixtures(database, custody, true, true);
            var batch = fixtures.coordinator().prepare(request(SERVICE), FailurePoint.NONE);

            assertEquals("PROCESSING", batch.state());
            assertEquals(1, custody.transfers.size());
            assertEquals(MINT, custody.transfers.getFirst().mintId());
            assertEquals(List.of(material()), custody.transfers.getFirst().materials());
            assertEquals(300L, database.nationalIssuanceQuota(PERIOD, NATION.value()).reservedMinorUnits());
            assertNull(database.monetarySupplyEvent(SERVICE.value(), "prepare-batch"));
        }
    }

    @Test
    void missingAuthorityImpostorIdentityAndIneffectiveTerritoryFailBeforeReservationOrCustody() {
        try (CivicDatabase database = database()) {
            RecordingCustody custody = new RecordingCustody();
            Fixtures missingNationAuthority = fixtures(database, custody, false, true);
            assertThrows(SecurityException.class, () -> missingNationAuthority.coordinator()
                    .prepare(request(SERVICE), FailurePoint.NONE));
            assertEquals(0L, database.nationalIssuanceQuota(PERIOD, NATION.value()).reservedMinorUnits());
            assertEquals(0, custody.transfers.size());

            assertThrows(FiscalServiceIdentityMismatchException.class, () -> missingNationAuthority.coordinator()
                    .prepare(request(new ServiceIdentity("impostor-service")), FailurePoint.NONE));
            assertEquals(0, custody.transfers.size());
        }
        try (CivicDatabase database = database()) {
            RecordingCustody custody = new RecordingCustody();
            Fixtures ineffective = fixtures(database, custody, true, false);
            assertThrows(SecurityException.class, () -> ineffective.coordinator()
                    .prepare(request(SERVICE), FailurePoint.NONE));
            assertEquals(0L, database.nationalIssuanceQuota(PERIOD, NATION.value()).reservedMinorUnits());
            assertEquals(0, custody.transfers.size());
        }
    }

    @Test
    void recoveryReplaysSameCustodyTransferAfterExternalBeforeSqliteConfirmation() {
        try (CivicDatabase database = database()) {
            RecordingCustody custody = new RecordingCustody();
            Fixtures fixtures = fixtures(database, custody, true, true);
            assertThrows(SimulatedCrash.class, () -> fixtures.coordinator()
                    .prepare(request(SERVICE), FailurePoint.AFTER_EXTERNAL_BEFORE_RECORD));
            assertEquals("PREPARING", database.mintBatch(SERVICE.value(), "prepare-batch").state());
            assertEquals(1, custody.transfers.size());

            fixtures.coordinator().recoverIncomplete();
            assertEquals("PROCESSING", database.mintBatch(SERVICE.value(), "prepare-batch").state());
            assertEquals(2, custody.transfers.size());
            assertEquals(custody.transfers.get(0).transferId(), custody.transfers.get(1).transferId());
        }
    }

    @Test
    void startReplayRejectsChangedImmutablePayloadBeforeCustody() {
        try (CivicDatabase database = database()) {
            RecordingCustody custody = new RecordingCustody();
            Fixtures fixtures = fixtures(database, custody, true, true);
            var original = fixtures.coordinator().prepare(request(SERVICE), FailurePoint.NONE);

            assertEquals(
                    original,
                    fixtures.coordinator()
                            .findByRequest(
                                    SERVICE,
                                    "prepare-batch",
                                    MINT,
                                    PERIOD,
                                    ACTOR,
                                    MoneyAmount.ofMinorUnits(300L))
                            .orElseThrow());
            assertThrows(IdempotencyConflictException.class, () -> fixtures.coordinator()
                    .findByRequest(
                            SERVICE,
                            "prepare-batch",
                            UUID.randomUUID(),
                            PERIOD,
                            ACTOR,
                            MoneyAmount.ofMinorUnits(300L)));
            assertThrows(IdempotencyConflictException.class, () -> fixtures.coordinator()
                    .findByRequest(
                            SERVICE,
                            "prepare-batch",
                            MINT,
                            UUID.randomUUID(),
                            ACTOR,
                            MoneyAmount.ofMinorUnits(300L)));
            assertThrows(IdempotencyConflictException.class, () -> fixtures.coordinator()
                    .findByRequest(
                            SERVICE,
                            "prepare-batch",
                            MINT,
                            PERIOD,
                            UUID.randomUUID(),
                            MoneyAmount.ofMinorUnits(300L)));
            assertThrows(IdempotencyConflictException.class, () -> fixtures.coordinator()
                    .findByRequest(
                            SERVICE,
                            "prepare-batch",
                            MINT,
                            PERIOD,
                            ACTOR,
                            MoneyAmount.ofMinorUnits(301L)));
            assertEquals(1, custody.transfers.size());
            assertEquals(
                    300L,
                    database.nationalIssuanceQuota(PERIOD, NATION.value()).reservedMinorUnits());
        }
    }

    @Test
    void exposesExplicitDatabaseExternalDatabasePhasesForServerRuntimeComposition() {
        try (CivicDatabase database = database()) {
            RecordingCustody custody = new RecordingCustody();
            Fixtures fixtures = fixtures(database, custody, true, true);

            var take = fixtures.coordinator().prepareExternal(request(SERVICE));
            assertEquals("PREPARING", database.mintBatch(take.batchId()).state());
            assertEquals(300L,
                    database.nationalIssuanceQuota(PERIOD, NATION.value()).reservedMinorUnits());
            assertEquals(0, custody.transfers.size());

            take.apply(custody);
            assertEquals("PREPARING", database.mintBatch(take.batchId()).state());
            assertEquals(1, custody.transfers.size());

            var processing = fixtures.coordinator().confirmExternal(take);
            assertEquals("PROCESSING", processing.state());
            var materialReturn = fixtures.coordinator().prepareCancellationExternal(
                    new CancelMintBatch(SERVICE, "cancel-phased", processing.batchId(), ACTOR,
                            "Cancel phased batch"));
            assertEquals("CANCELLING", database.mintBatch(processing.batchId()).state());
            assertEquals(0, custody.returns.size());

            materialReturn.apply(custody);
            assertEquals("CANCELLING", database.mintBatch(processing.batchId()).state());
            assertEquals(300L,
                    database.nationalIssuanceQuota(PERIOD, NATION.value()).reservedMinorUnits());
            assertEquals(1, custody.returns.size());

            var cancelled = fixtures.coordinator().confirmExternal(materialReturn);
            assertEquals("CANCELLED", cancelled.state());
            assertEquals(0L,
                    database.nationalIssuanceQuota(PERIOD, NATION.value()).reservedMinorUnits());
        }
    }

    @Test
    void cancellationReplaysSameReturnAndReleasesQuotaOnlyAfterSqliteConfirmation() {
        try (CivicDatabase database = database()) {
            RecordingCustody custody = new RecordingCustody();
            Fixtures fixtures = fixtures(database, custody, true, true);
            var prepared = fixtures.coordinator().prepare(request(SERVICE), FailurePoint.NONE);

            assertThrows(SimulatedCrash.class, () -> fixtures.coordinator().cancel(
                    new CancelMintBatch(SERVICE, "cancel-batch", prepared.batchId(), ACTOR,
                            "Cancel before issuance"),
                    FailurePoint.AFTER_EXTERNAL_BEFORE_RECORD));
            assertEquals("CANCELLING", database.mintBatch(prepared.batchId()).state());
            assertEquals("RETURN_PENDING", database.mintBatch(prepared.batchId()).custodyState());
            assertEquals(300L,
                    database.nationalIssuanceQuota(PERIOD, NATION.value()).reservedMinorUnits());
            assertEquals(1, custody.returns.size());

            assertEquals(1, fixtures.coordinator().recoverIncomplete());
            assertEquals("CANCELLED", database.mintBatch(prepared.batchId()).state());
            assertEquals("RETURNED", database.mintBatch(prepared.batchId()).custodyState());
            assertEquals(0L,
                    database.nationalIssuanceQuota(PERIOD, NATION.value()).reservedMinorUnits());
            assertEquals("IDLE", database.registeredMint(MINT).transactionState());
            assertEquals(2, custody.returns.size());
            assertEquals(custody.returns.get(0).operationId(), custody.returns.get(1).operationId());
            assertNull(database.monetarySupplyEvent(SERVICE.value(), "cancel-batch"));
        }
    }

    @Test
    void completedCancellationReplayReturnsSameBatchWithoutReturningMaterialsAgain() {
        try (CivicDatabase database = database()) {
            RecordingCustody custody = new RecordingCustody();
            Fixtures fixtures = fixtures(database, custody, true, true);
            var processing = fixtures.coordinator().prepare(request(SERVICE), FailurePoint.NONE);
            var cancellation = new CancelMintBatch(
                    SERVICE,
                    "cancel-completed",
                    processing.batchId(),
                    ACTOR,
                    "Cancel completed batch");

            var cancelled = fixtures.coordinator().cancel(cancellation, FailurePoint.NONE);
            assertEquals(cancelled, fixtures.coordinator().cancel(cancellation, FailurePoint.NONE));
            assertEquals("CANCELLED", cancelled.state());
            assertEquals(1, custody.returns.size());
            assertThrows(IllegalArgumentException.class, () -> fixtures.coordinator()
                    .cancel(
                            new CancelMintBatch(
                                    SERVICE,
                                    "cancel-completed",
                                    processing.batchId(),
                                    ACTOR,
                                    "Changed cancellation reason"),
                            FailurePoint.NONE));
            assertEquals(1, custody.returns.size());
        }
    }

    @Test
    void recoveryExposesDatabaseAndExternalPhasesWithoutApplyingCustodyOnDatabaseThread() {
        try (CivicDatabase database = database()) {
            RecordingCustody custody = new RecordingCustody();
            Fixtures fixtures = fixtures(database, custody, true, true);
            var pendingTake = fixtures.coordinator().prepareExternal(request(SERVICE));
            MintBatchRecovery recovery = new MintBatchRecovery(database, CLOCK);

            assertEquals(List.of(pendingTake), recovery.pendingExternal());
            assertEquals(0, custody.transfers.size());
            pendingTake.apply(custody);
            assertEquals("PROCESSING", recovery.confirmExternal(pendingTake).state());

            var pendingReturn = fixtures.coordinator().prepareCancellationExternal(
                    new CancelMintBatch(
                            SERVICE,
                            "cancel-recovery-phases",
                            pendingTake.batchId(),
                            ACTOR,
                            "Recover cancelled batch"));
            assertEquals(List.of(pendingReturn), recovery.pendingExternal());
            assertEquals(0, custody.returns.size());
            pendingReturn.apply(custody);
            assertEquals("CANCELLED", recovery.confirmExternal(pendingReturn).state());
            assertEquals(List.of(), recovery.pendingExternal());
        }
    }
    private Fixtures fixtures(CivicDatabase database, RecordingCustody custody,
            boolean grantNationAuthority, boolean territoryEffective) {
        long now = NOW.toEpochMilli();
        database.registerNation(NATION.value(), "test", "nation", UUID.randomUUID(), now - 10L);
        database.publishIssuanceQuotaPeriod(PERIOD, "issuance", "period", now - 1000L,
                now + 604_800_000L, 1_000L, 400L,
                List.of(new StoredNationalIssuanceQuotaAllocation(NATION.value(), 400L)), "Period", now - 9L);
        database.activateNationalIssuanceQuota(UUID.randomUUID(), "governance", "activate", PERIOD,
                NATION.value(), ACTOR, 400L, "Activate", now - 8L);
        database.publishMintRecipeVersion(RECIPE, SERVICE.value(), "recipe", 1,
                List.of(new StoredMintRecipeIngredient(0, "EXACT_ITEM", "minecraft:diamond", 1L, 100L)),
                60_000L, "Recipe", now - 7L);
        database.registerMint(MINT, SERVICE.value(), "mint", NATION.value(), "minecraft:overworld",
                32, 70, 48, UUID.randomUUID(), UUID.randomUUID(), true, RECIPE, ACTOR, "Mint", now - 6L);

        NationProvider provider = new NationProvider() {
            public Optional<NationFacts> find(NationId nationId) {
                return NATION.equals(nationId) ? Optional.of(new NationFacts(NATION, ACTOR, Set.of(ACTOR))) : Optional.empty();
            }
            public Optional<NationFacts> findForCitizen(UUID playerId) {
                return ACTOR.equals(playerId) ? find(NATION) : Optional.empty();
            }
        };
        NationFiscalAuthorityRegistry nationAuthorities = new NationFiscalAuthorityRegistry(database, provider, CLOCK);
        if (grantNationAuthority) {
            nationAuthorities.grant(new GrantNationFiscalPermission(SERVICE, "grant", NATION, ACTOR, ACTOR,
                    NationFiscalPermission.MANAGE_ISSUANCE, "Manage exact Nation mint"));
        }
        FiscalAuthorization authorization = new FiscalAuthorization(database);
        authorization.register(new RegisterFiscalService(SERVICE, "civiceconomy", "Mint coordinator", ADMIN,
                "register-service", "Register service"));
        authorization.grant(new GrantFiscalCapability(ADMIN, "grant-nation", SERVICE,
                FiscalCapability.MANAGE_ISSUANCE, IssuanceQuotaRegistry.treasury(NATION), "Prepare Nation batches"));
        FiscalServiceSession session = authorization.openSession(SERVICE, "civiceconomy");
        RegisteredMintTerritoryAuthority territory = mint -> territoryEffective;
        return new Fixtures(new MintBatchCoordinator(database, session, nationAuthorities, territory, custody, CLOCK));
    }

    private PrepareMintBatch request(ServiceIdentity serviceIdentity) {
        return new PrepareMintBatch(serviceIdentity, "prepare-batch", MINT, PERIOD, ACTOR,
                MoneyAmount.ofMinorUnits(300L), List.of(material()), "Prepare authorized batch");
    }

    private MintMaterialStack material() {
        return new MintMaterialStack(0, "EXACT_ITEM", "minecraft:diamond", "minecraft:diamond", 3L);
    }

    private CivicDatabase database() {
        return CivicDatabase.open(temporaryDirectory.resolve(UUID.randomUUID() + ".sqlite3"),
                new DatabaseIdentity(UUID.fromString("ad6c3a39-a85e-44c4-82e4-b35ff310bed3"),
                        "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20"));
    }

    private record Fixtures(MintBatchCoordinator coordinator) {}

    private static final class RecordingCustody implements ExternalMintMaterialCustody {
        private final List<MintMaterialCustodyTransfer> transfers = new ArrayList<>();
        private final List<MintMaterialCustodyReturn> returns = new ArrayList<>();
        public void take(MintMaterialCustodyTransfer transfer) { transfers.add(transfer); }
        public void returnToSource(MintMaterialCustodyReturn operation) { returns.add(operation); }
    }
}
