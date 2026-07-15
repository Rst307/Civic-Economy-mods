package org.civiceconomy.issuance;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalCapability;
import org.civiceconomy.fiscal.FiscalServiceSession;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredIssuanceQuotaPeriod;
import org.civiceconomy.persistence.StoredNationalIssuanceQuota;
import org.civiceconomy.persistence.StoredNationalIssuanceQuotaAllocation;

public final class IssuanceQuotaRegistry {
    public static final AccountId GLOBAL_ISSUANCE_SCOPE = new AccountId("monetary-supply:global");

    private final CivicDatabase database;
    private final FiscalAuthorization fiscalAuthorization;
    private final FiscalServiceSession session;
    private final NationFiscalAuthorityRegistry nationAuthorities;
    private final MoneyAmount hardCap;
    private final Clock clock;

    public IssuanceQuotaRegistry(
            CivicDatabase database,
            FiscalServiceSession session,
            NationFiscalAuthorityRegistry nationAuthorities,
            MoneyAmount hardCap,
            Clock clock) {
        if (database == null || session == null || nationAuthorities == null || hardCap == null || clock == null) {
            throw new IllegalArgumentException("Issuance Quota Registry dependencies cannot be null");
        }
        this.database = database;
        this.fiscalAuthorization = new FiscalAuthorization(database);
        this.session = session;
        this.nationAuthorities = nationAuthorities;
        this.hardCap = hardCap;
        this.clock = clock;
    }

    public IssuanceQuotaPeriod publish(PublishIssuanceQuotaPeriod request) {
        fiscalAuthorization.require(
                session,
                request.serviceIdentity(),
                FiscalCapability.MANAGE_ISSUANCE,
                GLOBAL_ISSUANCE_SCOPE);
        StoredIssuanceQuotaPeriod stored = database.publishIssuanceQuotaPeriod(
                request.periodId(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.startsAt().toEpochMilli(),
                request.endsAt().toEpochMilli(),
                hardCap.minorUnits(),
                request.globalQuota().minorUnits(),
                request.allocations().stream()
                        .map(allocation -> new StoredNationalIssuanceQuotaAllocation(
                                allocation.nationId().value(), allocation.ceiling().minorUnits()))
                        .toList(),
                request.reason(),
                clock.millis());
        return new IssuanceQuotaPeriod(
                stored.periodId(),
                new org.civiceconomy.fiscal.ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                Instant.ofEpochMilli(stored.startsAtEpochMillis()),
                Instant.ofEpochMilli(stored.endsAtEpochMillis()),
                MoneyAmount.ofMinorUnits(stored.hardCapMinorUnits()),
                MoneyAmount.ofMinorUnits(stored.globalQuotaMinorUnits()),
                request.allocations().stream()
                        .map(allocation -> quota(stored.periodId(), allocation.nationId()))
                        .toList(),
                stored.reason(),
                Instant.ofEpochMilli(stored.publishedAtEpochMillis()));
    }

    public NationalIssuanceQuota activate(ActivateNationalIssuanceQuota request) {
        AccountId treasury = treasury(request.nationId());
        fiscalAuthorization.require(
                session,
                request.serviceIdentity(),
                FiscalCapability.MANAGE_ISSUANCE,
                treasury);
        nationAuthorities.require(
                request.nationId(),
                request.actorPlayerId(),
                NationFiscalPermission.MANAGE_ISSUANCE);
        database.activateNationalIssuanceQuota(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.periodId(),
                request.nationId().value(),
                request.actorPlayerId(),
                request.amount().minorUnits(),
                request.reason(),
                clock.millis());
        return quota(request.periodId(), request.nationId());
    }

    public NationalIssuanceQuota quota(UUID periodId, NationId nationId) {
        StoredNationalIssuanceQuota stored = database.nationalIssuanceQuota(periodId, nationId.value());
        if (stored == null) {
            throw new IllegalArgumentException("Unknown National Issuance Quota");
        }
        StoredIssuanceQuotaPeriod period = database.issuanceQuotaPeriod(periodId);
        return new NationalIssuanceQuota(
                periodId,
                nationId,
                MoneyAmount.ofMinorUnits(stored.ceilingMinorUnits()),
                MoneyAmount.ofMinorUnits(stored.activatedMinorUnits()),
                MoneyAmount.ofMinorUnits(stored.reservedMinorUnits()),
                MoneyAmount.ofMinorUnits(stored.usedMinorUnits()),
                Instant.ofEpochMilli(period.startsAtEpochMillis()),
                Instant.ofEpochMilli(period.endsAtEpochMillis()));
    }

    public static AccountId treasury(NationId nationId) {
        return new AccountId("nation:" + nationId.value() + ":treasury");
    }
}
