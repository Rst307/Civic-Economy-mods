package org.civiceconomy.strength;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.civiceconomy.nation.CitizenshipCorrectionGraceRegistry;
import org.civiceconomy.nation.CitizenshipRegistry;
import org.civiceconomy.nation.NationPopulationCalculator;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.OnlineTimeLedger;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.production.GlobalReferencePriceRegistry;
import org.civiceconomy.production.ProductionIndustryAssignmentRegistry;
import org.civiceconomy.production.ProductionMarginalReturnContributionRegistry;
import org.civiceconomy.production.ProductionMarginalReturnPolicyRegistry;
import org.civiceconomy.production.ProductionValueAddedCalculator;
import org.civiceconomy.production.RollingProductionMarginalReturnAssessment;
import org.civiceconomy.production.RollingProductionMarginalReturnCalculator;
import org.civiceconomy.production.RollingProductionMarginalReturnRegistry;
import org.civiceconomy.territory.TerritoryClaimPosition;
import org.civiceconomy.territory.TerritoryMaintenanceRegistry;

public final class NationalStrengthSnapshotBuilder {
    private final CivicDatabase database;
    private final NationalStrengthRecalculator recalculator;
    private final Duration citizenshipTransferCooldown;
    private final Duration effectiveCitizenObservationWindow;
    private final Duration fullCitizenContributionTime;
    private final Duration complianceWindow;
    private final DiminishingStrengthNormalizer effectiveCitizenNormalizer;
    private final DiminishingStrengthNormalizer effectiveTerritoryNormalizer;
    private final Duration productionWindow;
    private final Duration productionFullWeightWindow;
    private final DiminishingStrengthNormalizer productionNormalizer;
    private final boolean productionScoringAvailable;
    private final Map<UUID, List<TerritoryClaimPosition>> currentClaimsByTeam;

    public NationalStrengthSnapshotBuilder(
            CivicDatabase database,
            Duration citizenshipTransferCooldown,
            Duration effectiveCitizenObservationWindow,
            Duration fullCitizenContributionTime,
            int effectiveCitizenFullStrengthScale,
            long activityWindowMillis,
            long activityFullStrengthScale) {
        if (database == null || citizenshipTransferCooldown == null
                || effectiveCitizenObservationWindow == null
                || fullCitizenContributionTime == null
                || citizenshipTransferCooldown.isNegative()
                || effectiveCitizenObservationWindow.isNegative()
                || effectiveCitizenObservationWindow.isZero()
                || fullCitizenContributionTime.isNegative()
                || fullCitizenContributionTime.isZero()) {
            throw new IllegalArgumentException("National Strength population settings are invalid");
        }
        this.database = database;
        this.citizenshipTransferCooldown = citizenshipTransferCooldown;
        this.effectiveCitizenObservationWindow = effectiveCitizenObservationWindow;
        this.fullCitizenContributionTime = fullCitizenContributionTime;
        this.complianceWindow = Duration.ofMillis(activityWindowMillis);
        this.effectiveCitizenNormalizer =
                new DiminishingStrengthNormalizer(effectiveCitizenFullStrengthScale);
        this.effectiveTerritoryNormalizer = new DiminishingStrengthNormalizer(100D);
        this.productionWindow = Duration.ofDays(30);
        this.productionFullWeightWindow = Duration.ofDays(7);
        this.productionNormalizer = new DiminishingStrengthNormalizer(1D);
        this.productionScoringAvailable = false;
        this.currentClaimsByTeam = Map.of();
        this.recalculator = new NationalStrengthRecalculator(
                database, activityWindowMillis, activityFullStrengthScale);
    }

    public NationalStrengthSnapshotBuilder(
            CivicDatabase database,
            NationalStrengthSnapshotConfiguration configuration,
            Map<UUID, List<TerritoryClaimPosition>> currentClaimsByTeam) {
        this(database, configuration, currentClaimsByTeam, false);
    }

    public NationalStrengthSnapshotBuilder(
            CivicDatabase database,
            NationalStrengthSnapshotConfiguration configuration,
            Map<UUID, List<TerritoryClaimPosition>> currentClaimsByTeam,
            boolean productionScoringAvailable) {
        if (database == null || configuration == null || currentClaimsByTeam == null
                || currentClaimsByTeam.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                        || entry.getValue() == null
                        || entry.getValue().stream().anyMatch(java.util.Objects::isNull))) {
            throw new IllegalArgumentException("National Strength snapshot dependencies are invalid");
        }
        this.database = database;
        this.citizenshipTransferCooldown = configuration.citizenshipTransferCooldown();
        this.effectiveCitizenObservationWindow =
                configuration.effectiveCitizenObservationWindow();
        this.fullCitizenContributionTime = configuration.fullCitizenContributionTime();
        this.complianceWindow = configuration.complianceWindow();
        this.effectiveCitizenNormalizer = new DiminishingStrengthNormalizer(
                configuration.effectiveCitizenFullStrengthScale());
        this.effectiveTerritoryNormalizer = new DiminishingStrengthNormalizer(
                configuration.effectiveTerritoryFullStrengthScale());
        this.productionWindow = configuration.productionWindow();
        this.productionFullWeightWindow = configuration.productionFullWeightWindow();
        this.productionNormalizer = new DiminishingStrengthNormalizer(
                configuration.productionFullStrengthScaleMinorUnits());
        this.productionScoringAvailable = productionScoringAvailable;
        this.currentClaimsByTeam = currentClaimsByTeam.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        this.recalculator = new NationalStrengthRecalculator(
                database,
                configuration.activityWindow().toMillis(),
                configuration.activityFullStrengthScale());
    }

    public NationalStrengthSnapshot recalculateAll(long recalculatedAtEpochMillis) {
        if (recalculatedAtEpochMillis <= 0L) {
            throw new IllegalArgumentException("National Strength snapshot time must be positive");
        }
        LinkedHashMap<NationId, NationalStrengthRecalculation> recalculations =
                new LinkedHashMap<>();
        Instant recalculatedAt = Instant.ofEpochMilli(recalculatedAtEpochMillis);
        Clock recalculationClock = Clock.fixed(recalculatedAt, ZoneOffset.UTC);
        NationPopulationCalculator populations = new NationPopulationCalculator(
                new CitizenshipRegistry(
                        database, citizenshipTransferCooldown, recalculationClock),
                new CitizenshipCorrectionGraceRegistry(database, recalculationClock),
                new OnlineTimeLedger(database),
                effectiveCitizenObservationWindow,
                fullCitizenContributionTime);
        TerritoryMaintenanceRegistry maintenance =
                new TerritoryMaintenanceRegistry(database, recalculationClock);
        MintComplianceSource complianceSource = new MintComplianceSource(database);
        RollingProductionMarginalReturnCalculator productionCalculator =
                new RollingProductionMarginalReturnCalculator(
                        productionWindow, productionFullWeightWindow);
        RollingProductionMarginalReturnRegistry productionSource =
                new RollingProductionMarginalReturnRegistry(
                        database,
                        new ProductionMarginalReturnContributionRegistry(
                                database,
                                new ProductionValueAddedCalculator(
                                        new GlobalReferencePriceRegistry(
                                                database, recalculationClock)),
                                new ProductionIndustryAssignmentRegistry(
                                        database, recalculationClock),
                                new ProductionMarginalReturnPolicyRegistry(
                                        database, recalculationClock),
                                recalculationClock),
                        productionCalculator);
        for (var stored : database.registeredNations()) {
            NationId nationId = new NationId(stored.nationId());
            var population = populations.calculate(nationId, recalculatedAt);
            List<TerritoryClaimPosition> currentClaims =
                    currentClaimsByTeam.get(stored.ftbTeamId());
            var history = maintenance.restorationHistory(
                    nationId, stored.ftbTeamId(), recalculatedAt);
            List<EffectiveTerritoryClaimAssessment> territoryClaims = currentClaims == null
                    ? List.of()
                    : currentClaims.stream()
                            .distinct()
                            .sorted(Comparator.comparing(TerritoryClaimPosition::dimensionId)
                                    .thenComparingInt(TerritoryClaimPosition::chunkX)
                                    .thenComparingInt(TerritoryClaimPosition::chunkZ))
                            .map(position -> {
                                var conclusion = history.get(position);
                                EffectiveTerritoryClaimState state = conclusion == null
                                        ? EffectiveTerritoryClaimState.UNASSESSED
                                        : conclusion.previouslySuspended()
                                                ? EffectiveTerritoryClaimState.SUSPENDED
                                                : EffectiveTerritoryClaimState.EFFECTIVE;
                                return new EffectiveTerritoryClaimAssessment(position, state);
                            })
                            .toList();
            EffectiveTerritoryStrengthAssessment territory =
                    new EffectiveTerritoryStrengthAssessment(
                            nationId,
                            stored.ftbTeamId(),
                            currentClaims != null,
                            territoryClaims);
            long complianceStart = recalculatedAtEpochMillis <= complianceWindow.toMillis()
                    ? 0L
                    : recalculatedAtEpochMillis - complianceWindow.toMillis();
            MintComplianceAssessment compliance = complianceSource.assess(
                    nationId, complianceStart, recalculatedAtEpochMillis);
            RollingProductionMarginalReturnAssessment production =
                    productionScoringAvailable
                            ? productionSource.assess(nationId, recalculatedAt)
                            : productionCalculator.assess(
                                    nationId, List.of(), recalculatedAt);
            EnumSet<NationalStrengthComponent> anomalies =
                    EnumSet.noneOf(NationalStrengthComponent.class);
            if (!productionScoringAvailable) {
                anomalies.add(NationalStrengthComponent.PRODUCTION_AND_INFRASTRUCTURE);
            }
            if (!production.healthy()) {
                anomalies.add(NationalStrengthComponent.PRODUCTION_AND_INFRASTRUCTURE);
            }
            if (territory.anomalous()) {
                anomalies.add(NationalStrengthComponent.EFFECTIVE_TERRITORY);
            }
            if (compliance.anomalous()) {
                anomalies.add(NationalStrengthComponent.COMPLIANCE);
            }
            NationalStrengthComponents conservativeInputs = new NationalStrengthComponents(
                    effectiveCitizenNormalizer.normalize(population.populationEquivalent()),
                    productionNormalizer.normalize(production.finalValueMinorUnits()),
                    0,
                    effectiveTerritoryNormalizer.normalize(
                            territory.effectiveClaimCount()),
                    compliance.normalizedBasisPoints(),
                    anomalies);
            recalculations.put(
                    nationId,
                    recalculator.recalculate(
                            nationId,
                            recalculatedAtEpochMillis,
                            population,
                            territory,
                            compliance,
                            production,
                            conservativeInputs));
        }
        return new NationalStrengthSnapshot(recalculatedAtEpochMillis, recalculations);
    }
}
