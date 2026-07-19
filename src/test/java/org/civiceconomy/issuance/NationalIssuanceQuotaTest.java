package org.civiceconomy.issuance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.nation.NationId;
import org.junit.jupiter.api.Test;

class NationalIssuanceQuotaTest {
    private static final UUID PERIOD_ID = UUID.fromString("83ba2181-b03a-4272-98aa-a58af3ae39f4");
    private static final NationId NATION_ID =
            new NationId(UUID.fromString("84369db6-50cc-4891-a1bf-c0c13679a61d"));

    @Test
    void activationReservationCommitAndReleaseKeepDistinctQuotaFacts() {
        NationalIssuanceQuota quota = NationalIssuanceQuota.unactivated(
                        PERIOD_ID,
                        NATION_ID,
                        MoneyAmount.ofMinorUnits(1_000L),
                        Instant.parse("2026-08-01T00:00:00Z"),
                        Instant.parse("2026-08-08T00:00:00Z"))
                .activate(MoneyAmount.ofMinorUnits(600L))
                .reserve(MoneyAmount.ofMinorUnits(400L));

        assertEquals(MoneyAmount.ofMinorUnits(1_000L), quota.ceiling());
        assertEquals(MoneyAmount.ofMinorUnits(600L), quota.activated());
        assertEquals(MoneyAmount.ofMinorUnits(400L), quota.reserved());
        assertEquals(MoneyAmount.ZERO, quota.used());
        assertEquals(MoneyAmount.ofMinorUnits(200L), quota.remaining());

        NationalIssuanceQuota committed = quota.commit(MoneyAmount.ofMinorUnits(250L));
        assertEquals(MoneyAmount.ofMinorUnits(150L), committed.reserved());
        assertEquals(MoneyAmount.ofMinorUnits(250L), committed.used());
        assertEquals(MoneyAmount.ofMinorUnits(200L), committed.remaining());

        NationalIssuanceQuota released = committed.release(MoneyAmount.ofMinorUnits(150L));
        assertEquals(MoneyAmount.ZERO, released.reserved());
        assertEquals(MoneyAmount.ofMinorUnits(250L), released.used());
        assertEquals(MoneyAmount.ofMinorUnits(350L), released.remaining());
    }

    @Test
    void quotaCannotExceedCeilingOrSpendUnactivatedOrUnreservedAuthority() {
        NationalIssuanceQuota quota = NationalIssuanceQuota.unactivated(
                PERIOD_ID,
                NATION_ID,
                MoneyAmount.ofMinorUnits(1_000L),
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-08T00:00:00Z"));

        assertThrows(IllegalArgumentException.class, () -> quota.activate(MoneyAmount.ofMinorUnits(1_001L)));
        assertThrows(IllegalStateException.class, () -> quota.reserve(MoneyAmount.ofMinorUnits(1L)));

        NationalIssuanceQuota active = quota.activate(MoneyAmount.ofMinorUnits(600L));
        assertThrows(IllegalStateException.class, () -> active.reserve(MoneyAmount.ofMinorUnits(601L)));
        assertThrows(IllegalStateException.class, () -> active.commit(MoneyAmount.ofMinorUnits(1L)));
        assertThrows(IllegalStateException.class, () -> active.release(MoneyAmount.ofMinorUnits(1L)));
    }

    @Test
    void laterCeilingOrActivationCannotRevokeAlreadyReservedOrUsedQuota() {
        NationalIssuanceQuota quota = NationalIssuanceQuota.unactivated(
                        PERIOD_ID,
                        NATION_ID,
                        MoneyAmount.ofMinorUnits(1_000L),
                        Instant.parse("2026-08-01T00:00:00Z"),
                        Instant.parse("2026-08-08T00:00:00Z"))
                .activate(MoneyAmount.ofMinorUnits(800L))
                .reserve(MoneyAmount.ofMinorUnits(500L))
                .commit(MoneyAmount.ofMinorUnits(200L));

        assertThrows(
                IllegalStateException.class,
                () -> quota.withCeiling(MoneyAmount.ofMinorUnits(499L)));
        assertThrows(
                IllegalStateException.class,
                () -> quota.activate(MoneyAmount.ofMinorUnits(499L)));
        assertEquals(
                MoneyAmount.ZERO,
                quota.withCeiling(MoneyAmount.ofMinorUnits(500L)).remaining());
    }
}
