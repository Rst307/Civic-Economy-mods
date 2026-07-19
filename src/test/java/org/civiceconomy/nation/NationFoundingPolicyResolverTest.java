package org.civiceconomy.nation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NationFoundingPolicyResolverTest {
    private static final Instant SCHEDULED_AT = Instant.parse("2026-07-19T08:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-20T00:00:00Z");
    private static final Instant ACTIVATED_AT = Instant.parse("2026-07-21T12:00:00Z");
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-founding-policy-test");

    @TempDir Path temporaryDirectory;

    @Test
    void foundingUsesTheEffectiveCandidateEvidenceThresholdAndCitizenshipPolicies() {
        try (CivicDatabase database = database()) {
            schedulePolicies(database);

            NationFoundingPolicy formal = resolver(database).current(false);
            NationFoundingPolicy debugWorld = resolver(database).current(true);

            assertEquals(3, formal.minimumEffectiveCandidates());
            assertEquals(Duration.ofDays(45), formal.observationWindow());
            assertEquals(Duration.ofDays(30), formal.citizenshipTransferCooldown());
            assertFalse(formal.minimumCandidateBypassAllowed());
            assertTrue(debugWorld.minimumCandidateBypassAllowed());
            assertEquals(Duration.ofDays(30), debugWorld.citizenshipTransferCooldown());
        }
    }

    @Test
    void missingCitizenshipPolicyFailsFoundingClosed() {
        try (CivicDatabase database = database()) {
            scheduleCandidatePolicies(database);

            SecurityException failure = assertThrows(
                    SecurityException.class, () -> resolver(database).current(false));

            assertEquals("Citizenship policy is not configured", failure.getMessage());
        }
    }

    private void schedulePolicies(CivicDatabase database) {
        scheduleCandidatePolicies(database);
        new CitizenshipPolicyRegistry(database, schedulingClock())
                .schedule(new ScheduleCitizenshipPolicy(
                        SERVICE,
                        "founding-citizenship-policy",
                        "civic-admin-console:test",
                        new CitizenshipPolicy(
                                Duration.ofDays(2),
                                Duration.ofDays(30),
                                Duration.ofMinutes(3)),
                        EFFECTIVE_AT,
                        "Founding must honor the governed transfer cooldown"));
    }

    private void scheduleCandidatePolicies(CivicDatabase database) {
        new CandidateOnlineEvidencePolicyRegistry(database, schedulingClock())
                .schedule(new ScheduleCandidateOnlineEvidencePolicy(
                        SERVICE,
                        "founding-candidate-evidence-policy",
                        "civic-admin-console:test",
                        new CandidateOnlineEvidencePolicy(Duration.ofDays(45)),
                        EFFECTIVE_AT,
                        "Founding evidence window"));
        new NationFoundingCandidateThresholdPolicyRegistry(database, schedulingClock())
                .schedule(new ScheduleNationFoundingCandidateThresholdPolicy(
                        SERVICE,
                        "founding-candidate-threshold-policy",
                        "civic-admin-console:test",
                        new NationFoundingCandidateThresholdPolicy(3),
                        EFFECTIVE_AT,
                        "Formal founding threshold"));
    }

    private NationFoundingPolicyResolver resolver(CivicDatabase database) {
        return new NationFoundingPolicyResolver(
                database, Clock.fixed(ACTIVATED_AT, ZoneOffset.UTC));
    }

    private static Clock schedulingClock() {
        return Clock.fixed(SCHEDULED_AT, ZoneOffset.UTC);
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("nation-founding-policy.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("8f2a47bb-32cf-4f11-ac49-bf3b9c455c87"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
