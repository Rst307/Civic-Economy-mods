package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.issuance.ActivateNationalIssuanceQuota;
import org.civiceconomy.issuance.IssuanceQuotaRegistry;
import org.civiceconomy.issuance.NationalIssuanceQuotaAllocation;
import org.civiceconomy.issuance.PublishIssuanceQuotaPeriod;
import org.civiceconomy.nation.GrantNationFiscalPermission;
import org.civiceconomy.nation.NationFacts;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.NationProvider;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IssuanceQuotaAuthorizationTest {
    private static final Instant NOW = Instant.parse("2026-07-31T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ServiceIdentity SERVICE = new ServiceIdentity("civiceconomy-issuance");
    private static final ServiceIdentity ADMIN = new ServiceIdentity("trusted-admin");
    private static final NationId NATION =
            new NationId(UUID.fromString("b568ea56-0643-4a5c-9f7d-72b3d9b3fc2a"));
    private static final UUID ACTOR = UUID.fromString("0e9b2a24-fbd4-49d0-90fd-59e8fa9590d3");
    private static final UUID PERIOD = UUID.fromString("594e910a-c360-44d3-b38d-85989ec3e0ce");

    @TempDir Path temporaryDirectory;

    @Test
    void ownerBoundServiceAndExactNationAuthorityPublishAndActivateQuota() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database, true);
            var period = fixtures.registry().publish(publishRequest(SERVICE));
            assertEquals(MoneyAmount.ofMinorUnits(400L), period.nationalQuotas().getFirst().ceiling());

            var quota = fixtures.registry().activate(new ActivateNationalIssuanceQuota(
                    SERVICE,
                    "activate-300",
                    PERIOD,
                    NATION,
                    ACTOR,
                    MoneyAmount.ofMinorUnits(300L),
                    "Nation enables only part of its ceiling"));
            assertEquals(MoneyAmount.ofMinorUnits(300L), quota.activated());
            assertEquals(MoneyAmount.ofMinorUnits(300L), quota.remaining());
        }
    }

    @Test
    void missingNationAuthorityAndImpostorIdentityFailBeforeActivation() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database, false);
            fixtures.registry().publish(publishRequest(SERVICE));

            assertThrows(SecurityException.class, () -> fixtures.registry().activate(
                    new ActivateNationalIssuanceQuota(
                            SERVICE, "unauthorized", PERIOD, NATION, ACTOR,
                            MoneyAmount.ofMinorUnits(1L), "Must fail")));
            assertNull(database.nationalIssuanceQuotaActivation(SERVICE.value(), "unauthorized"));

            assertThrows(FiscalServiceIdentityMismatchException.class, () -> fixtures.registry().publish(
                    publishRequest(new ServiceIdentity("impostor-service"))));
        }
    }

    private Fixtures fixtures(CivicDatabase database, boolean grantNationAuthority) {
        database.registerNation(NATION.value(), "test", "nation", UUID.randomUUID(), NOW.minusSeconds(1).toEpochMilli());
        NationProvider provider = new NationProvider() {
            @Override
            public Optional<NationFacts> find(NationId nationId) {
                return NATION.equals(nationId)
                        ? Optional.of(new NationFacts(NATION, ACTOR, Set.of(ACTOR)))
                        : Optional.empty();
            }

            @Override
            public Optional<NationFacts> findForCitizen(UUID playerId) {
                return ACTOR.equals(playerId) ? find(NATION) : Optional.empty();
            }
        };
        NationFiscalAuthorityRegistry nationAuthorities =
                new NationFiscalAuthorityRegistry(database, provider, CLOCK);
        if (grantNationAuthority) {
            nationAuthorities.grant(new GrantNationFiscalPermission(
                    SERVICE, "grant-manage-issuance", NATION, ACTOR, ACTOR,
                    NationFiscalPermission.MANAGE_ISSUANCE, "Nation appoints its issuance manager"));
        }
        FiscalAuthorization authorization = new FiscalAuthorization(database);
        authorization.register(new RegisterFiscalService(
                SERVICE, "civiceconomy", "Civic issuance controller", ADMIN,
                "register-issuance", "Register exact issuance service"));
        authorization.grant(new GrantFiscalCapability(
                ADMIN, "grant-global-issuance", SERVICE, FiscalCapability.MANAGE_ISSUANCE,
                IssuanceQuotaRegistry.GLOBAL_ISSUANCE_SCOPE, "Publish global quota periods"));
        authorization.grant(new GrantFiscalCapability(
                ADMIN, "grant-nation-issuance", SERVICE, FiscalCapability.MANAGE_ISSUANCE,
                IssuanceQuotaRegistry.treasury(NATION), "Activate exact Nation quota"));
        FiscalServiceSession session = authorization.openSession(SERVICE, "civiceconomy");
        return new Fixtures(new IssuanceQuotaRegistry(
                database, session, nationAuthorities, MoneyAmount.ofMinorUnits(1_000L), CLOCK));
    }

    private PublishIssuanceQuotaPeriod publishRequest(ServiceIdentity serviceIdentity) {
        return new PublishIssuanceQuotaPeriod(
                serviceIdentity,
                "publish-period",
                PERIOD,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-08T00:00:00Z"),
                MoneyAmount.ofMinorUnits(400L),
                List.of(new NationalIssuanceQuotaAllocation(NATION, MoneyAmount.ofMinorUnits(400L))),
                "Conservative first period");
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("issuance-authorization.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("3044f14c-1ffd-4eb6-a6b4-10fe53c3c9a7"),
                        "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20"));
    }

    private record Fixtures(IssuanceQuotaRegistry registry) {}
}
