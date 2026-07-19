package org.civiceconomy.nation;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredCitizenship;
import org.civiceconomy.persistence.StoredNationActivation;

public final class NationActivationCoordinator {
    private final CivicDatabase database;
    private final NationalTreasuryProvisioner treasuryProvisioner;
    private final NationFoundingPolicy policy;
    private final Clock clock;

    public NationActivationCoordinator(
            CivicDatabase database,
            NationalTreasuryProvisioner treasuryProvisioner,
            NationFoundingPolicy policy,
            Clock clock) {
        if (database == null || treasuryProvisioner == null || policy == null || clock == null) {
            throw new IllegalArgumentException("Nation activation dependencies cannot be null");
        }
        this.database = database;
        this.treasuryProvisioner = treasuryProvisioner;
        this.policy = policy;
        this.clock = clock;
    }

    public ActivatedNation activate(ActivateNationApplication request) {
        StoredNationActivation activation = database.nationActivationRegistration(
                request.serviceIdentity().value(), request.requestId());
        if (activation == null) {
            activation = prepare(request);
        } else {
            requireSameRequest(activation, request);
        }
        if ("PREPARED".equals(activation.state())) {
            treasuryProvisioner.ensureExists(
                    new NationId(activation.nationId()),
                    new AccountId(activation.treasuryAccountId()));
            activation = database.markNationTreasuryProvisioned(
                    activation.applicationId(), clock.millis());
        }
        if ("TREASURY_PROVISIONED".equals(activation.state())) {
            activation = database.commitNationActivation(
                    activation.applicationId(), clock.millis());
        }
        if (!"COMMITTED".equals(activation.state())) {
            throw new IllegalStateException(
                    "Unknown Nation activation state " + activation.state());
        }
        return toActivatedNation(activation);
    }

    private StoredNationActivation prepare(ActivateNationApplication request) {
        var application = database.nationApplication(request.applicationId().value());
        if (application == null) {
            throw new IllegalArgumentException(
                    "Unknown Nation Application " + request.applicationId());
        }
        if (!"PENDING".equals(application.state())) {
            throw new IllegalStateException(
                    "Nation Application " + request.applicationId() + " is not PENDING");
        }
        if (clock.millis() >= application.expiresAtEpochMillis()) {
            throw new IllegalStateException(
                    "Nation Application " + request.applicationId() + " has expired");
        }
        long observationWindowMillis = policy.observationWindow().toMillis();
        long observedUntil = clock.millis();
        long windowStart = observedUntil >= observationWindowMillis
                ? observedUntil - observationWindowMillis
                : 0;
        int effectiveCandidates = database.claimNationApplicationEvidence(
                        request.applicationId().value(),
                        windowStart,
                        observedUntil,
                        observedUntil)
                .size();
        int required = policy.minimumCandidateBypassAllowed()
                ? 1
                : policy.minimumEffectiveCandidates();
        if (effectiveCandidates < required) {
            throw new InsufficientEffectiveCandidatesException(required, effectiveCandidates);
        }
        List<UUID> candidatePlayerIds = database
                .nationApplicationCandidates(request.applicationId().value()).stream()
                .filter(candidate -> candidate.endedAtEpochMillis() == null)
                .map(candidate -> candidate.playerId())
                .toList();
        requireCitizenshipEligibility(candidatePlayerIds);

        NationId nationId = NationId.create();
        AccountId treasuryAccountId = new AccountId(
                "nation:" + nationId.value() + ":treasury");
        return database.prepareNationActivation(
                request.applicationId().value(),
                request.serviceIdentity().value(),
                request.requestId(),
                nationId.value(),
                application.ftbTeamId(),
                treasuryAccountId.value(),
                request.capital().dimensionId(),
                request.capital().chunkX(),
                request.capital().chunkZ(),
                request.reason(),
                policy.minimumEffectiveCandidates(),
                policy.minimumCandidateBypassAllowed(),
                observationWindowMillis,
                clock.millis());
    }

    private void requireCitizenshipEligibility(List<UUID> candidatePlayerIds) {
        long now = clock.millis();
        long cooldownMillis = policy.citizenshipTransferCooldown().toMillis();
        for (UUID playerId : candidatePlayerIds) {
            StoredCitizenship active = database.currentCitizenship(playerId);
            if (active != null) {
                throw new ActiveCitizenshipException(
                        playerId, new NationId(active.nationId()));
            }
            List<StoredCitizenship> history = database.citizenshipHistory(playerId);
            if (!history.isEmpty()) {
                StoredCitizenship previous = history.getLast();
                long eligibleAt = Math.addExact(
                        previous.endedAtEpochMillis(), cooldownMillis);
                if (now < eligibleAt) {
                    throw new CitizenshipTransferCooldownException(playerId, eligibleAt);
                }
            }
        }
    }

    private static void requireSameRequest(
            StoredNationActivation activation, ActivateNationApplication request) {
        if (!activation.applicationId().equals(request.applicationId().value())
                || !activation.capitalDimensionId().equals(request.capital().dimensionId())
                || activation.capitalChunkX() != request.capital().chunkX()
                || activation.capitalChunkZ() != request.capital().chunkZ()
                || !activation.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static ActivatedNation toActivatedNation(StoredNationActivation activation) {
        return new ActivatedNation(
                new RegisteredNation(
                        new NationId(activation.nationId()),
                        activation.ftbTeamId(),
                        activation.committedAtEpochMillis()),
                new Capital(
                        activation.capitalDimensionId(),
                        activation.capitalChunkX(),
                        activation.capitalChunkZ()),
                new AccountId(activation.treasuryAccountId()));
    }
}
