package org.civiceconomy.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.civiceconomy.persistence.StoredCreateRecipeCompletion;
import org.civiceconomy.persistence.StoredFacilityAccountingReceipt;
import org.civiceconomy.persistence.StoredFacilityProductionDecision;
import org.civiceconomy.persistence.StoredProductionInventoryChange;
import org.civiceconomy.territory.TerritoryClaimPosition;
import org.civiceconomy.strength.NationalStrengthComponent;
import org.civiceconomy.strength.NationalStrengthComponentState;
import org.civiceconomy.strength.NationalStrengthSnapshotBuilder;
import org.civiceconomy.strength.NationalStrengthSnapshotConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionMarginalReturnContributionRegistryTest {
    private static final Instant POLICY_CLOCK = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant EFFECTIVE_AT = POLICY_CLOCK.plusSeconds(60L);
    private static final Instant OBSERVED_AT = POLICY_CLOCK.plusSeconds(120L);
    private static final Clock CLOCK = Clock.fixed(POLICY_CLOCK, ZoneOffset.UTC);
    private static final NationId NATION = new NationId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final UUID TEAM =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACTOR =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID FACILITY =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID INTERFACE =
            UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID OBSERVATION =
            UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID RECEIPT =
            UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("production-binding-test");

    @TempDir
    Path temporaryDirectory;

    @Test
    void bindsAcceptedObservationToEvidenceTimeVersionsAcrossRestart() {
        Path file = temporaryDirectory.resolve("production-contribution-binding.sqlite3");
        BoundProductionMarginalReturnContribution expected;
        try (CivicDatabase database = database(file)) {
            UUID exportId = persistAcceptedObservationAndExport(database);
            ProductionMarginalReturnContributionRegistry registry = configuredRegistry(database);

            ProductionContributionBindingAssessment assessment =
                    registry.bindExport(exportId).get(0);
            expected = assessment.binding().orElseThrow();

            assertEquals(ProductionContributionBindingDecision.BOUND, assessment.decision());
            assertEquals(exportId, expected.anchorExportId());
            assertEquals(OBSERVATION, expected.contribution().observationId());
            assertEquals(FACILITY, expected.contribution().facilityId());
            assertEquals(
                    new ProductionIndustryId("food-processing"),
                    expected.contribution().industryId());
            assertEquals(15L, expected.contribution().valueMinorUnits());
            assertEquals(EFFECTIVE_AT, expected.industryAssignment().effectiveAt());
            assertEquals(EFFECTIVE_AT, expected.marginalReturnPolicy().effectiveAt());
            assertEquals(OBSERVED_AT, expected.evidenceAt());
        }

        try (CivicDatabase reopened = database(file)) {
            BoundProductionMarginalReturnContribution restored =
                    unconfiguredRegistry(reopened).binding(OBSERVATION);

            assertEquals(expected, restored);
        }
    }

    @Test
    void missingIndustryAssignmentFailsClosedWithoutCreatingABinding() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("missing-industry-binding.sqlite3"))) {
            UUID exportId = persistAcceptedObservationAndExport(database);
            GlobalReferencePriceRegistry prices = schedulePrices(database);
            ProductionMarginalReturnPolicyRegistry policies = schedulePolicy(database);
            ProductionMarginalReturnContributionRegistry registry =
                    new ProductionMarginalReturnContributionRegistry(
                            database,
                            new ProductionValueAddedCalculator(prices),
                            new ProductionIndustryAssignmentRegistry(database, CLOCK),
                            policies,
                            Clock.fixed(OBSERVED_AT.plusSeconds(1L), ZoneOffset.UTC));

            ProductionContributionBindingAssessment assessment =
                    registry.bindExport(exportId).get(0);

            assertEquals(
                    ProductionContributionBindingDecision.EXCLUDED_MISSING_INDUSTRY_ASSIGNMENT,
                    assessment.decision());
            assertEquals(ProductionValueAddedDecision.INCLUDED,
                    assessment.valueAdded().decision());
            assertEquals(15L, assessment.valueAdded().valueAddedMinorUnits());
            assertEquals("No trusted Production Industry assignment was effective at evidence time",
                    assessment.reason());
            assertNull(registry.binding(OBSERVATION));
        }
    }

    @Test
    void missingMarginalReturnPolicyFailsClosedWithoutCreatingABinding() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("missing-marginal-policy-binding.sqlite3"))) {
            UUID exportId = persistAcceptedObservationAndExport(database);
            GlobalReferencePriceRegistry prices = schedulePrices(database);
            ProductionMarginalReturnContributionRegistry registry =
                    new ProductionMarginalReturnContributionRegistry(
                            database,
                            new ProductionValueAddedCalculator(prices),
                            scheduleIndustry(database),
                            new ProductionMarginalReturnPolicyRegistry(database, CLOCK),
                            Clock.fixed(OBSERVED_AT.plusSeconds(1L), ZoneOffset.UTC));

            ProductionContributionBindingAssessment assessment =
                    registry.bindExport(exportId).get(0);

            assertEquals(
                    ProductionContributionBindingDecision
                            .EXCLUDED_MISSING_MARGINAL_RETURN_POLICY,
                    assessment.decision());
            assertEquals(ProductionValueAddedDecision.INCLUDED,
                    assessment.valueAdded().decision());
            assertEquals("No trusted Production Marginal Return Policy was effective at evidence time",
                    assessment.reason());
            assertNull(registry.binding(OBSERVATION));
        }
    }

    @Test
    void excludedValueAddedRemainsUnboundAndExplainable() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("unpriced-contribution-binding.sqlite3"))) {
            UUID exportId = persistAcceptedObservationAndExport(database);
            ProductionMarginalReturnContributionRegistry registry =
                    new ProductionMarginalReturnContributionRegistry(
                            database,
                            new ProductionValueAddedCalculator(
                                    new GlobalReferencePriceRegistry(database, CLOCK)),
                            scheduleIndustry(database),
                            schedulePolicy(database),
                            Clock.fixed(OBSERVED_AT.plusSeconds(1L), ZoneOffset.UTC));

            ProductionContributionBindingAssessment assessment =
                    registry.bindExport(exportId).get(0);

            assertEquals(
                    ProductionContributionBindingDecision.EXCLUDED_VALUE_ADDED,
                    assessment.decision());
            assertEquals(
                    ProductionValueAddedDecision.EXCLUDED_UNPRICED,
                    assessment.valueAdded().decision());
            assertEquals(
                    "Production Value Added was excluded: EXCLUDED_UNPRICED",
                    assessment.reason());
            assertNull(registry.binding(OBSERVATION));
        }
    }

    @Test
    void splitExportsOfOneReceiptKeepTheFirstExportAsTheOnlyContributionAnchor() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("split-export-contribution-binding.sqlite3"))) {
            persistAcceptedObservation(database, 2);
            UUID firstExportId = export(database, "split-export-one", 1);
            UUID secondExportId = export(database, "split-export-two", 1);
            ProductionMarginalReturnContributionRegistry registry = configuredRegistry(database);

            BoundProductionMarginalReturnContribution first = registry
                    .bindExport(firstExportId)
                    .get(0)
                    .binding()
                    .orElseThrow();
            BoundProductionMarginalReturnContribution replay = registry
                    .bindExport(secondExportId)
                    .get(0)
                    .binding()
                    .orElseThrow();

            assertEquals(firstExportId, first.anchorExportId());
            assertEquals(first, replay);
            assertEquals(firstExportId, replay.anchorExportId());
        }
    }

    @Test
    void laterIndustryAndPolicyVersionsCannotRewriteAnExistingContribution() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("immutable-version-binding.sqlite3"))) {
            UUID exportId = persistAcceptedObservationAndExport(database);
            ProductionMarginalReturnContributionRegistry originalRegistry =
                    configuredRegistry(database);
            BoundProductionMarginalReturnContribution original = originalRegistry
                    .bindExport(exportId)
                    .get(0)
                    .binding()
                    .orElseThrow();

            Instant laterClock = OBSERVED_AT.plusSeconds(1L);
            Instant laterEffectiveAt = OBSERVED_AT.plusSeconds(2L);
            Clock schedulingClock = Clock.fixed(laterClock, ZoneOffset.UTC);
            ProductionIndustryAssignmentRegistry industries =
                    new ProductionIndustryAssignmentRegistry(database, schedulingClock);
            industries.schedule(new ScheduleProductionIndustryAssignment(
                    SERVICE,
                    "later-milling-industry",
                    "civic-admin-console:test",
                    "6.0.6",
                    "create:milling/wheat",
                    new ProductionIndustryId("later-food-processing"),
                    laterEffectiveAt,
                    "Later trusted milling industry"));
            ProductionMarginalReturnPolicyRegistry policies =
                    new ProductionMarginalReturnPolicyRegistry(database, schedulingClock);
            policies.schedule(new ScheduleProductionMarginalReturnPolicy(
                    SERVICE,
                    "later-marginal-policy",
                    "civic-admin-console:test",
                    new ProductionMarginalReturnPolicy(200L, 4_000, 2_000L, 2_000),
                    laterEffectiveAt,
                    "Later trusted marginal-return policy"));
            ProductionMarginalReturnContributionRegistry laterRegistry =
                    new ProductionMarginalReturnContributionRegistry(
                            database,
                            new ProductionValueAddedCalculator(
                                    new GlobalReferencePriceRegistry(database, schedulingClock)),
                            industries,
                            policies,
                            Clock.fixed(laterEffectiveAt.plusSeconds(1L), ZoneOffset.UTC));

            BoundProductionMarginalReturnContribution replay = laterRegistry
                    .bindExport(exportId)
                    .get(0)
                    .binding()
                    .orElseThrow();

            assertEquals(original, replay);
            assertEquals(EFFECTIVE_AT, replay.industryAssignment().effectiveAt());
            assertEquals(EFFECTIVE_AT, replay.marginalReturnPolicy().effectiveAt());
        }
    }

    @Test
    void listsBindingsOnlyForThePersistedNationAndHalfOpenEvidenceWindow() {
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("nation-window-bindings.sqlite3"))) {
            UUID exportId = persistAcceptedObservationAndExport(database);
            ProductionMarginalReturnContributionRegistry registry = configuredRegistry(database);
            BoundProductionMarginalReturnContribution expected = registry
                    .bindExport(exportId)
                    .get(0)
                    .binding()
                    .orElseThrow();

            assertEquals(
                    List.of(expected),
                    registry.bindings(
                            NATION,
                            OBSERVED_AT.minusMillis(1L),
                            OBSERVED_AT.plusMillis(1L)));
            assertEquals(
                    List.of(),
                    registry.bindings(
                            new NationId(UUID.fromString(
                                    "99999999-9999-9999-9999-999999999999")),
                            OBSERVED_AT.minusMillis(1L),
                            OBSERVED_AT.plusMillis(1L)));
            assertEquals(
                    List.of(),
                    registry.bindings(
                            NATION,
                            OBSERVED_AT.minusMillis(1L),
                            OBSERVED_AT));
        }
    }

    @Test
    void persistsTheCurrentRollingAssessmentAndExplanationAcrossRestart() {
        Path file = temporaryDirectory.resolve("rolling-assessment.sqlite3");
        RollingProductionMarginalReturnAssessment expected;
        Instant assessedAt = OBSERVED_AT.plus(Duration.ofDays(3));
        try (CivicDatabase database = database(file)) {
            UUID exportId = persistAcceptedObservationAndExport(database);
            ProductionMarginalReturnContributionRegistry contributions =
                    configuredRegistry(database);
            contributions.bindExport(exportId);
            RollingProductionMarginalReturnRegistry rolling =
                    new RollingProductionMarginalReturnRegistry(
                            database,
                            contributions,
                            new RollingProductionMarginalReturnCalculator(
                                    Duration.ofDays(30), Duration.ofDays(7)));

            expected = rolling.assess(NATION, assessedAt);

            assertEquals(15L, expected.rawValueMinorUnits());
            assertEquals(15L, expected.finalValueMinorUnits());
            assertEquals(1, expected.contributions().size());
        }

        try (CivicDatabase reopened = database(file)) {
            RollingProductionMarginalReturnAssessment restored =
                    new RollingProductionMarginalReturnRegistry(
                                    reopened,
                                    unconfiguredRegistry(reopened),
                                    new RollingProductionMarginalReturnCalculator(
                                            Duration.ofDays(30), Duration.ofDays(7)))
                            .current(NATION);

            assertEquals(expected, restored);
        }
    }

    @Test
    void completeRollingEvidenceActivatesTheProductionStrengthComponent() {
        Instant assessedAt = OBSERVED_AT.plus(Duration.ofDays(3));
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("production-strength.sqlite3"))) {
            UUID exportId = persistAcceptedObservationAndExport(database);
            configuredRegistry(database).bindExport(exportId);
            scheduleStrengthPolicy(database, "production-strength-policy", 60L);

            var recalculation = new NationalStrengthSnapshotBuilder(
                            database,
                            new NationalStrengthSnapshotConfiguration(
                                    Duration.ofDays(7),
                                    Duration.ofDays(60),
                                    Duration.ofHours(8),
                                    Duration.ofDays(30),
                                    10_000L),
                            Map.of(),
                            true)
                    .recalculateAll(assessedAt.toEpochMilli())
                    .nations()
                    .get(NATION);

            assertEquals(15L, recalculation.productionMarginalReturn().finalValueMinorUnits());
            assertEquals(
                    5_000,
                    recalculation.assessment()
                            .component(NationalStrengthComponent.PRODUCTION_AND_INFRASTRUCTURE)
                            .normalizedInputBasisPoints());
            assertEquals(
                    NationalStrengthComponentState.ACTIVE,
                    recalculation.assessment().componentState(
                            NationalStrengthComponent.PRODUCTION_AND_INFRASTRUCTURE));
        }
    }

    @Test
    void completeBindingWithoutStrengthPolicyKeepsProductionStrengthPaused() {
        Instant assessedAt = OBSERVED_AT.plus(Duration.ofDays(3));
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("missing-production-strength-policy.sqlite3"))) {
            UUID exportId = persistAcceptedObservationAndExport(database);
            configuredRegistry(database).bindExport(exportId);

            var recalculation = new NationalStrengthSnapshotBuilder(
                            database,
                            configuration(),
                            Map.of(),
                            true)
                    .recalculateAll(assessedAt.toEpochMilli())
                    .nations()
                    .get(NATION);

            assertEquals(
                    NationalStrengthComponentState.PAUSED_ANOMALY,
                    recalculation.assessment().componentState(
                            NationalStrengthComponent.PRODUCTION_AND_INFRASTRUCTURE));
            assertEquals(
                    0,
                    recalculation.assessment()
                            .component(NationalStrengthComponent.PRODUCTION_AND_INFRASTRUCTURE)
                            .normalizedInputBasisPoints());
            assertNull(database.rollingProductionMarginalReturnAssessment(NATION.value()));
        }
    }

    @Test
    void laterStrengthPolicyChangesOnlyLaterAssessmentWithoutRebindingEvidence() {
        Instant firstAssessmentAt = OBSERVED_AT.plus(Duration.ofDays(1));
        Instant secondPolicyAt = OBSERVED_AT.plus(Duration.ofDays(2));
        Instant secondAssessmentAt = OBSERVED_AT.plus(Duration.ofDays(3));
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("versioned-production-strength-policy.sqlite3"))) {
            UUID exportId = persistAcceptedObservationAndExport(database);
            ProductionMarginalReturnContributionRegistry contributions = configuredRegistry(database);
            BoundProductionMarginalReturnContribution original =
                    contributions.bindExport(exportId).getFirst().binding().orElseThrow();
            scheduleStrengthPolicy(
                    database, "initial-production-strength-policy", 60L, EFFECTIVE_AT);
            scheduleStrengthPolicy(
                    database, "later-production-strength-policy", 240L, secondPolicyAt);

            var first = new NationalStrengthSnapshotBuilder(
                            database, configuration(), Map.of(), true)
                    .recalculateAll(firstAssessmentAt.toEpochMilli())
                    .nations()
                    .get(NATION);
            var second = new NationalStrengthSnapshotBuilder(
                            database, configuration(), Map.of(), true)
                    .recalculateAll(secondAssessmentAt.toEpochMilli())
                    .nations()
                    .get(NATION);

            assertEquals(
                    5_000,
                    first.assessment()
                            .component(NationalStrengthComponent.PRODUCTION_AND_INFRASTRUCTURE)
                            .normalizedInputBasisPoints());
            assertEquals(
                    2_500,
                    second.assessment()
                            .component(NationalStrengthComponent.PRODUCTION_AND_INFRASTRUCTURE)
                            .normalizedInputBasisPoints());
            assertEquals(original, contributions.binding(OBSERVATION));
        }
    }

    @Test
    void exportedIncludedObservationWithoutABindingKeepsProductionStrengthPaused() {
        Instant assessedAt = OBSERVED_AT.plus(Duration.ofDays(3));
        try (CivicDatabase database = database(
                temporaryDirectory.resolve("unbound-production-strength.sqlite3"))) {
            persistAcceptedObservationAndExport(database);
            scheduleStrengthPolicy(database, "unbound-production-strength-policy", 60L);

            var recalculation = new NationalStrengthSnapshotBuilder(
                            database,
                            configuration(),
                            Map.of(),
                            true)
                    .recalculateAll(assessedAt.toEpochMilli())
                    .nations()
                    .get(NATION);

            assertEquals(
                    NationalStrengthComponentState.PAUSED_ANOMALY,
                    recalculation.assessment().componentState(
                            NationalStrengthComponent.PRODUCTION_AND_INFRASTRUCTURE));
        }
    }

    private void scheduleStrengthPolicy(
            CivicDatabase database, String requestId, long fullStrengthScaleMinorUnits) {
        scheduleStrengthPolicy(
                database, requestId, fullStrengthScaleMinorUnits, EFFECTIVE_AT);
    }

    private void scheduleStrengthPolicy(
            CivicDatabase database,
            String requestId,
            long fullStrengthScaleMinorUnits,
            Instant effectiveAt) {
        new ProductionStrengthPolicyRegistry(database, CLOCK).schedule(
                new ScheduleProductionStrengthPolicy(
                        SERVICE,
                        requestId,
                        "civic-admin-console:test",
                        new ProductionStrengthPolicy(
                                Duration.ofDays(30),
                                Duration.ofDays(7),
                                fullStrengthScaleMinorUnits),
                        effectiveAt,
                        "Trusted rolling production strength policy"));
    }

    private static NationalStrengthSnapshotConfiguration configuration() {
        return new NationalStrengthSnapshotConfiguration(
                Duration.ofDays(7),
                Duration.ofDays(60),
                Duration.ofHours(8),
                Duration.ofDays(30),
                10_000L);
    }

    private ProductionMarginalReturnContributionRegistry configuredRegistry(
            CivicDatabase database) {
        GlobalReferencePriceRegistry prices = schedulePrices(database);
        ProductionIndustryAssignmentRegistry industries = scheduleIndustry(database);
        return new ProductionMarginalReturnContributionRegistry(
                database,
                new ProductionValueAddedCalculator(prices),
                industries,
                schedulePolicy(database),
                Clock.fixed(OBSERVED_AT.plusSeconds(1L), ZoneOffset.UTC));
    }

    private ProductionIndustryAssignmentRegistry scheduleIndustry(CivicDatabase database) {
        ProductionIndustryAssignmentRegistry industries =
                new ProductionIndustryAssignmentRegistry(database, CLOCK);
        industries.schedule(new ScheduleProductionIndustryAssignment(
                SERVICE,
                "milling-industry",
                "civic-admin-console:test",
                "6.0.6",
                "create:milling/wheat",
                new ProductionIndustryId("food-processing"),
                EFFECTIVE_AT,
                "Trusted milling industry"));
        return industries;
    }

    private GlobalReferencePriceRegistry schedulePrices(CivicDatabase database) {
        GlobalReferencePriceRegistry prices = new GlobalReferencePriceRegistry(database, CLOCK);
        prices.schedule(new ScheduleGlobalReferencePrice(
                SERVICE,
                "wheat-price",
                "civic-admin-console:test",
                "minecraft:wheat",
                "components:{}",
                10L,
                EFFECTIVE_AT,
                "Trusted wheat reference price"));
        prices.schedule(new ScheduleGlobalReferencePrice(
                SERVICE,
                "flour-price",
                "civic-admin-console:test",
                "create:wheat_flour",
                "components:{}",
                25L,
                EFFECTIVE_AT,
                "Trusted flour reference price"));
        return prices;
    }

    private ProductionMarginalReturnPolicyRegistry schedulePolicy(CivicDatabase database) {
        ProductionMarginalReturnPolicyRegistry policies =
                new ProductionMarginalReturnPolicyRegistry(database, CLOCK);
        policies.schedule(new ScheduleProductionMarginalReturnPolicy(
                SERVICE,
                "marginal-policy",
                "civic-admin-console:test",
                new ProductionMarginalReturnPolicy(100L, 5_000, 1_000L, 2_500),
                EFFECTIVE_AT,
                "Trusted marginal-return policy"));
        return policies;
    }

    private ProductionMarginalReturnContributionRegistry unconfiguredRegistry(
            CivicDatabase database) {
        return new ProductionMarginalReturnContributionRegistry(
                database,
                new ProductionValueAddedCalculator(
                        new GlobalReferencePriceRegistry(database, CLOCK)),
                new ProductionIndustryAssignmentRegistry(database, CLOCK),
                new ProductionMarginalReturnPolicyRegistry(database, CLOCK),
                Clock.fixed(OBSERVED_AT.plusSeconds(1L), ZoneOffset.UTC));
    }

    private UUID persistAcceptedObservationAndExport(CivicDatabase database) {
        persistAcceptedObservation(database, 1);
        return export(database, "trusted-export", 1);
    }

    private void persistAcceptedObservation(CivicDatabase database, int count) {
        registerFacilityAndInterface(database);
        database.recordFacilityProductionObservation(
                new StoredCreateRecipeCompletion(
                        OBSERVATION,
                        "6.0.6",
                        "MILLSTONE",
                        "create:milling/wheat",
                        "minecraft:overworld",
                        2,
                        70,
                        2,
                        OBSERVED_AT.toEpochMilli()),
                List.of(storedChange(
                        OBSERVATION, "INPUT", 0, "minecraft:wheat", count)),
                List.of(storedChange(
                        OBSERVATION, "OUTPUT", 0, "create:wheat_flour", count)),
                new StoredFacilityAccountingReceipt(
                        RECEIPT,
                        INTERFACE,
                        "minecraft:overworld",
                        17,
                        72,
                        4,
                        OBSERVED_AT.toEpochMilli()),
                List.of(storedChange(
                        RECEIPT, "RECEIPT", 4, "create:wheat_flour", count)),
                new StoredFacilityProductionDecision(
                        OBSERVATION,
                        FACILITY,
                        INTERFACE,
                        RECEIPT,
                        "INCLUDED",
                        "Accepted trusted production evidence",
                        OBSERVED_AT.toEpochMilli()));
    }

    private UUID export(CivicDatabase database, String requestId, int count) {
        return new ProductionInventoryExportCoordinator(
                        database,
                        Clock.fixed(OBSERVED_AT.plusSeconds(1L), ZoneOffset.UTC),
                        request -> new ProductionStack(
                                "create:wheat_flour", "components:{}", request.count()))
                .export(new ProductionInventoryExportRequest(
                        SERVICE,
                        requestId,
                        INTERFACE,
                        ACTOR,
                        4,
                        count,
                        ProductionInventoryExportKind.SALE,
                        "trusted-sale-terminal",
                        OBSERVED_AT.plusSeconds(1L).toEpochMilli(),
                        "Anchor verified production evidence"))
                .exportId();
    }

    private void registerFacilityAndInterface(CivicDatabase database) {
        database.registerNation(
                NATION.value(), SERVICE.value(), "binding nation", TEAM,
                POLICY_CLOCK.minusSeconds(1L).toEpochMilli());
        new RegisteredFacilityRegistry(database, (nation, team, claim) -> true, CLOCK, 4)
                .register(new RegisterFacility(
                        SERVICE,
                        "binding-facility",
                        FACILITY,
                        NATION,
                        TEAM,
                        new FacilityCorePosition("minecraft:overworld", 1, 70, 1),
                        List.of(
                                new TerritoryClaimPosition("minecraft:overworld", 0, 0),
                                new TerritoryClaimPosition("minecraft:overworld", 1, 0)),
                        ACTOR,
                        "Production binding facility"));
        new FacilityAccountingInterfaceRegistry(database, CLOCK).register(
                new RegisterFacilityAccountingInterface(
                        SERVICE,
                        "binding-interface",
                        INTERFACE,
                        FACILITY,
                        new FacilityAccountingInterfacePosition(
                                "minecraft:overworld", 17, 72, 4),
                        ACTOR,
                        "Production binding interface"));
    }

    private static FacilityProductionObservation observation() {
        return new FacilityProductionObservation(
                new CreateRecipeCompletion(
                        OBSERVATION,
                        "6.0.6",
                        CreateMachineKind.MILLSTONE,
                        "create:milling/wheat",
                        "minecraft:overworld",
                        2,
                        70,
                        2,
                        OBSERVED_AT.toEpochMilli(),
                        new MachineInventoryDelta(
                                List.of(change(0, "minecraft:wheat")),
                                List.of(change(0, "create:wheat_flour")))),
                new FacilityAccountingReceipt(
                        RECEIPT,
                        INTERFACE,
                        new FacilityAccountingInterfacePosition(
                                "minecraft:overworld", 17, 72, 4),
                        OBSERVED_AT.toEpochMilli(),
                        List.of(change(4, "create:wheat_flour"))),
                new FacilityProductionDecision(
                        OBSERVATION,
                        FACILITY,
                        INTERFACE,
                        RECEIPT,
                        FacilityProductionDecisionKind.INCLUDED,
                        "Accepted trusted production evidence"),
                OBSERVED_AT);
    }

    private static MachineInventoryChange change(int slot, String itemId) {
        return new MachineInventoryChange(
                slot, new ProductionStack(itemId, "components:{}", 1));
    }

    private static StoredProductionInventoryChange storedChange(
            UUID sourceId, String role, int slot, String itemId) {
        return storedChange(sourceId, role, slot, itemId, 1);
    }

    private static StoredProductionInventoryChange storedChange(
            UUID sourceId, String role, int slot, String itemId, int count) {
        return new StoredProductionInventoryChange(
                sourceId, role, slot, itemId, "components:{}", count);
    }

    private CivicDatabase database(Path file) {
        return CivicDatabase.open(
                file,
                new DatabaseIdentity(
                        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
