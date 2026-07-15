package org.civiceconomy.territory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class TerritoryMaintenanceAssessmentProcessor {
    private static final Comparator<TerritoryMaintenanceClaimSnapshot> ORDER = Comparator
            .comparing((TerritoryMaintenanceClaimSnapshot claim) -> claim.nationId().value())
            .thenComparing(TerritoryMaintenanceClaimSnapshot::dimensionId)
            .thenComparingInt(TerritoryMaintenanceClaimSnapshot::chunkX)
            .thenComparingInt(TerritoryMaintenanceClaimSnapshot::chunkZ);

    private final TerritoryMaintenanceRegistry registry;

    public TerritoryMaintenanceAssessmentProcessor(TerritoryMaintenanceRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Assessment Processor requires a Registry");
        }
        this.registry = registry;
    }

    public TerritoryMaintenanceAssessmentBatch assess(AssessTerritoryMaintenanceCycle request) {
        List<TerritoryMaintenanceClaimSnapshot> claims = request.claims().stream()
                .sorted(ORDER)
                .toList();
        requireUniqueClaims(claims);
        TerritoryMaintenanceCycle cycle = registry.openCycle(new OpenTerritoryMaintenanceCycle(
                request.serviceIdentity(),
                request.requestId() + ":cycle",
                request.startsAt(),
                request.endsAt()));
        var stored = registry.registerAssessmentBatch(
                cycle.cycleId(),
                request.serviceIdentity(),
                request.requestId() + ":batch",
                claims,
                snapshotSha256(claims));
        List<TerritoryMaintenanceClaimSnapshot> persistedClaims =
                registry.assessmentBatchClaims(cycle.cycleId());
        requireCompletePersistedSnapshot(
                stored.claimCount(), stored.snapshotSha256(), persistedClaims);
        List<TerritoryFiscalAssessment> assessments = persistAssessments(
                request.serviceIdentity(),
                request.requestId(),
                request.reason(),
                cycle,
                persistedClaims);
        return new TerritoryMaintenanceAssessmentBatch(cycle, assessments);
    }

    public java.util.Optional<TerritoryMaintenanceAssessmentBatch> recover(
            org.civiceconomy.fiscal.ServiceIdentity serviceIdentity,
            String requestId,
            String reason) {
        if (serviceIdentity == null
                || requestId == null
                || requestId.isBlank()
                || reason == null
                || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Assessment recovery values are invalid");
        }
        var stored = registry.assessmentBatch(serviceIdentity, requestId + ":batch");
        if (stored == null) {
            return java.util.Optional.empty();
        }
        TerritoryMaintenanceCycle cycle = registry.cycle(stored.cycleId());
        List<TerritoryMaintenanceClaimSnapshot> claims =
                registry.assessmentBatchClaims(stored.cycleId());
        requireCompletePersistedSnapshot(
                stored.claimCount(), stored.snapshotSha256(), claims);
        return java.util.Optional.of(new TerritoryMaintenanceAssessmentBatch(
                cycle,
                persistAssessments(serviceIdentity, requestId, reason, cycle, claims)));
    }

    private static void requireCompletePersistedSnapshot(
            int expectedClaimCount,
            String expectedSnapshotSha256,
            List<TerritoryMaintenanceClaimSnapshot> claims) {
        if (claims.size() != expectedClaimCount
                || !snapshotSha256(claims).equals(expectedSnapshotSha256)) {
            throw new IllegalStateException(
                    "Territory Maintenance persisted Claim Snapshot is incomplete or corrupt");
        }
    }

    private List<TerritoryFiscalAssessment> persistAssessments(
            org.civiceconomy.fiscal.ServiceIdentity serviceIdentity,
            String requestId,
            String reason,
            TerritoryMaintenanceCycle cycle,
            List<TerritoryMaintenanceClaimSnapshot> claims) {
        return claims.stream()
                .map(claim -> registry.assess(new AssessTerritoryFiscalValidity(
                        serviceIdentity,
                        assessmentRequestId(requestId, claim),
                        cycle.cycleId(),
                        claim.nationId(),
                        claim.ftbTeamId(),
                        claim.dimensionId(),
                        claim.chunkX(),
                        claim.chunkZ(),
                        claim.maintenanceDueMinorUnits(),
                        claim.priority(),
                        reason)))
                .toList();
    }

    static String snapshotSha256(List<TerritoryMaintenanceClaimSnapshot> claims) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (TerritoryMaintenanceClaimSnapshot claim : claims) {
                update(digest, claim.nationId().value().toString());
                update(digest, claim.ftbTeamId().toString());
                update(digest, claim.dimensionId());
                update(digest, Integer.toString(claim.chunkX()));
                update(digest, Integer.toString(claim.chunkZ()));
                update(digest, Long.toString(claim.maintenanceDueMinorUnits()));
                update(digest, claim.priority().name());
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static void requireUniqueClaims(List<TerritoryMaintenanceClaimSnapshot> claims) {
        Set<ClaimKey> keys = new HashSet<>();
        if (claims.stream().anyMatch(claim -> !keys.add(new ClaimKey(
                claim.nationId().value(),
                claim.dimensionId(),
                claim.chunkX(),
                claim.chunkZ())))) {
            throw new IllegalArgumentException(
                    "Territory Maintenance assessment cycle contains duplicate claims");
        }
    }

    private static String assessmentRequestId(
            String cycleRequestId, TerritoryMaintenanceClaimSnapshot claim) {
        String identity = claim.nationId().value()
                + "\n"
                + claim.dimensionId()
                + "\n"
                + claim.chunkX()
                + "\n"
                + claim.chunkZ();
        UUID claimIdentity = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        return cycleRequestId + ":assessment:" + claimIdentity;
    }

    private record ClaimKey(UUID nationId, String dimensionId, int chunkX, int chunkZ) {}
}
