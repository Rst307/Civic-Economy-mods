package org.civiceconomy.issuance;

import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.nation.NationId;

/**
 * One Nation's authorization facts for one issuance period.
 *
 * <p>The ceiling is calculated by the global controller. Activation is a Nation governance
 * choice. Reservation belongs to durable mint batches, and only committed batches become used
 * quota.
 */
public record NationalIssuanceQuota(
        UUID periodId,
        NationId nationId,
        MoneyAmount ceiling,
        MoneyAmount activated,
        MoneyAmount reserved,
        MoneyAmount used,
        Instant startsAt,
        Instant endsAt) {
    public NationalIssuanceQuota {
        if (periodId == null
                || nationId == null
                || ceiling == null
                || activated == null
                || reserved == null
                || used == null
                || startsAt == null
                || endsAt == null) {
            throw new IllegalArgumentException("National Issuance Quota cannot contain null values");
        }
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("National Issuance Quota period must have positive duration");
        }
        if (activated.compareTo(ceiling) > 0) {
            throw new IllegalArgumentException("Activated issuance quota cannot exceed its ceiling");
        }
        if (encumbered(reserved, used).compareTo(activated) > 0) {
            throw new IllegalArgumentException("Reserved and used issuance quota cannot exceed activation");
        }
    }

    public static NationalIssuanceQuota unactivated(
            UUID periodId,
            NationId nationId,
            MoneyAmount ceiling,
            Instant startsAt,
            Instant endsAt) {
        return new NationalIssuanceQuota(
                periodId,
                nationId,
                ceiling,
                MoneyAmount.ZERO,
                MoneyAmount.ZERO,
                MoneyAmount.ZERO,
                startsAt,
                endsAt);
    }

    public MoneyAmount remaining() {
        return activated.minus(encumbered(reserved, used));
    }

    public NationalIssuanceQuota activate(MoneyAmount amount) {
        requireAmount(amount, true);
        if (amount.compareTo(ceiling) > 0) {
            throw new IllegalArgumentException("Activated issuance quota cannot exceed its ceiling");
        }
        if (amount.compareTo(encumbered(reserved, used)) < 0) {
            throw new IllegalStateException("Activation cannot revoke reserved or used issuance quota");
        }
        return copy(ceiling, amount, reserved, used);
    }

    public NationalIssuanceQuota withCeiling(MoneyAmount amount) {
        requireAmount(amount, true);
        MoneyAmount encumbered = encumbered(reserved, used);
        if (amount.compareTo(encumbered) < 0) {
            throw new IllegalStateException("Ceiling cannot revoke reserved or used issuance quota");
        }
        MoneyAmount nextActivation = activated.compareTo(amount) <= 0 ? activated : amount;
        return copy(amount, nextActivation, reserved, used);
    }

    public NationalIssuanceQuota reserve(MoneyAmount amount) {
        requireAmount(amount, false);
        if (amount.compareTo(remaining()) > 0) {
            throw new IllegalStateException("Mint batch exceeds remaining National Issuance Quota");
        }
        return copy(ceiling, activated, reserved.plus(amount), used);
    }

    public NationalIssuanceQuota commit(MoneyAmount amount) {
        requireAmount(amount, false);
        if (amount.compareTo(reserved) > 0) {
            throw new IllegalStateException("Cannot use issuance quota that was not reserved");
        }
        return copy(ceiling, activated, reserved.minus(amount), used.plus(amount));
    }

    public NationalIssuanceQuota release(MoneyAmount amount) {
        requireAmount(amount, false);
        if (amount.compareTo(reserved) > 0) {
            throw new IllegalStateException("Cannot release issuance quota that was not reserved");
        }
        return copy(ceiling, activated, reserved.minus(amount), used);
    }

    private NationalIssuanceQuota copy(
            MoneyAmount nextCeiling,
            MoneyAmount nextActivated,
            MoneyAmount nextReserved,
            MoneyAmount nextUsed) {
        return new NationalIssuanceQuota(
                periodId,
                nationId,
                nextCeiling,
                nextActivated,
                nextReserved,
                nextUsed,
                startsAt,
                endsAt);
    }

    private static MoneyAmount encumbered(MoneyAmount reserved, MoneyAmount used) {
        return reserved.plus(used);
    }

    private static void requireAmount(MoneyAmount amount, boolean zeroAllowed) {
        if (amount == null || (!zeroAllowed && amount.equals(MoneyAmount.ZERO))) {
            throw new IllegalArgumentException("Issuance quota amount is invalid");
        }
    }
}
