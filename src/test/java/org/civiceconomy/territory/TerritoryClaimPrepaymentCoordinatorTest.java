package org.civiceconomy.territory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalLedger;
import org.civiceconomy.fiscal.FiscalTestSessions;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.PaymentCoordinator;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.GrantNationFiscalPermission;
import org.civiceconomy.nation.NationFacts;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.NationProvider;
import org.civiceconomy.nation.NationRegistry;
import org.civiceconomy.nation.NationTeam;
import org.civiceconomy.nation.NationTeamDirectory;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerritoryClaimPrepaymentCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-07-14T13:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final NationId NATION_ID = new NationId(
            UUID.fromString("751d75bf-d14f-4bee-afbc-b6fac022087c"));
    private static final UUID TEAM_ID =
            UUID.fromString("f5ad0218-6b73-4377-af7a-81c9f3325e97");
    private static final UUID ACTOR_ID =
            UUID.fromString("40c5796c-16f2-45e5-a958-41698ee205c5");
    private static final UUID RAW_FTB_MEMBER_ID =
            UUID.fromString("67a14465-573d-4641-9b45-0fe9bf578d54");
    private static final ServiceIdentity TERRITORY_SERVICE =
            new ServiceIdentity("civiceconomy-territory");
    private static final AccountId TREASURY =
            new AccountId("nation:" + NATION_ID.value() + ":treasury");
    private static final AccountId CLEARING =
            new AccountId("system:territory:prepayment-clearing");

    @TempDir
    Path temporaryDirectory;

    @Test
    void authorizedCitizenPrepaysFromExactNationalTreasuryBeforePermitIsReady() {
        Map<AccountId, Long> balances = new HashMap<>();
        balances.put(TREASURY, 1_000L);
        Set<UUID> externallyApplied = new HashSet<>();

        try (CivicDatabase database = database()) {
            database.registerNation(
                    NATION_ID.value(), "nation-test", "register", TEAM_ID, NOW.minusSeconds(60).toEpochMilli());
            NationProvider provider = provider();
            NationFiscalAuthorityRegistry nationAuthorities =
                    new NationFiscalAuthorityRegistry(database, provider, CLOCK);
            nationAuthorities.grant(new GrantNationFiscalPermission(
                    new ServiceIdentity("nation-governance"),
                    "grant-territory-finance",
                    NATION_ID,
                    ACTOR_ID,
                    ACTOR_ID,
                    NationFiscalPermission.MANAGE_TERRITORY_FINANCE,
                    "Authorize territory expansion"));

            var session = authorizeFiscalService(database);
            FiscalLedger ledger = FiscalLedger.authorized(
                    database,
                    account -> MoneyAmount.ofMinorUnits(balances.getOrDefault(account, 0L)),
                    session);
            PaymentCoordinator payments = PaymentCoordinator.authorized(
                    database,
                    payment -> {
                        if (!externallyApplied.add(payment.transactionId())) {
                            return;
                        }
                        long source = balances.getOrDefault(payment.sourceAccount(), 0L);
                        if (source < payment.amount().minorUnits()) {
                            throw new IllegalStateException("insufficient test balance");
                        }
                        balances.put(
                                payment.sourceAccount(), source - payment.amount().minorUnits());
                        balances.merge(
                                payment.recipientAccount(), payment.amount().minorUnits(), Math::addExact);
                    },
                    session);
            TerritoryClaimPermitRegistry permits = new TerritoryClaimPermitRegistry(
                    database,
                    new CommittedTerritoryPrepaymentVerifier(database, CLEARING),
                    CLOCK);
            TerritoryClaimPrepaymentCoordinator coordinator =
                    new TerritoryClaimPrepaymentCoordinator(
                            new NationRegistry(database, emptyTeams()),
                            nationAuthorities,
                            accountId -> balances.putIfAbsent(accountId, 0L),
                            ledger,
                            payments,
                            permits,
                            TERRITORY_SERVICE,
                            CLEARING,
                            CLOCK);
            TerritoryExpansionQuote quote = new TerritoryExpansionQuote(
                    NATION_ID,
                    17,
                    1,
                    0,
                    1,
                    MoneyAmount.ofMinorUnits(250L),
                    NOW);

            PrepareTerritoryClaimPrepayment request =
                    new PrepareTerritoryClaimPrepayment(
                            "claim-overworld-18-9",
                            NATION_ID,
                            TEAM_ID,
                            ACTOR_ID,
                            "minecraft:overworld",
                            18,
                            9,
                            17,
                            quote,
                            NOW.plusSeconds(120L));
            TerritoryClaimPermit permit = coordinator.prepare(request);

            assertEquals(TerritoryClaimPermitState.READY, permit.state());
            assertEquals(permit, coordinator.prepare(request));
            assertEquals(250L, permit.prepayment().minorUnits());
            assertEquals(750L, balances.get(TREASURY));
            assertEquals(250L, balances.get(CLEARING));
            assertEquals(
                    "CIVIC_COMMITTED",
                    database.paymentTransaction(permit.prepaymentTransactionId()).state());
        }
    }

    @Test
    void rejectsRawFtbMemberBeforeClearingProvisionReservationOrPayment() {
        Map<AccountId, Long> balances = new HashMap<>();
        balances.put(TREASURY, 1_000L);
        AtomicInteger clearingProvisionCalls = new AtomicInteger();

        try (CivicDatabase database = database()) {
            database.registerNation(
                    NATION_ID.value(), "nation-test", "register", TEAM_ID, NOW.minusSeconds(60).toEpochMilli());
            NationFiscalAuthorityRegistry nationAuthorities =
                    new NationFiscalAuthorityRegistry(database, provider(), CLOCK);
            var session = authorizeFiscalService(database);
            TerritoryClaimPrepaymentCoordinator coordinator =
                    new TerritoryClaimPrepaymentCoordinator(
                            new NationRegistry(database, emptyTeams()),
                            nationAuthorities,
                            accountId -> {
                                clearingProvisionCalls.incrementAndGet();
                                balances.putIfAbsent(accountId, 0L);
                            },
                            FiscalLedger.authorized(
                                    database,
                                    account -> MoneyAmount.ofMinorUnits(
                                            balances.getOrDefault(account, 0L)),
                                    session),
                            PaymentCoordinator.authorized(
                                    database,
                                    ignored -> {
                                        throw new AssertionError(
                                                "Unauthorized actor reached external payment");
                                    },
                                    session),
                            new TerritoryClaimPermitRegistry(
                                    database,
                                    new CommittedTerritoryPrepaymentVerifier(database, CLEARING),
                                    CLOCK),
                            TERRITORY_SERVICE,
                            CLEARING,
                            CLOCK);

            assertThrows(
                    SecurityException.class,
                    () -> coordinator.prepare(new PrepareTerritoryClaimPrepayment(
                            "raw-ftb-member-claim",
                            NATION_ID,
                            TEAM_ID,
                            RAW_FTB_MEMBER_ID,
                            "minecraft:overworld",
                            19,
                            9,
                            17,
                            chargedQuote(),
                            NOW.plusSeconds(120L))));

            assertEquals(0, clearingProvisionCalls.get());
            assertEquals(1_000L, balances.get(TREASURY));
            assertEquals(0L, database.activeReservedMinorUnits(TREASURY.value()));
        }
    }

    @Test
    void retryAfterAmbiguousExternalApplicationDoesNotPayTwice() {
        Map<AccountId, Long> balances = new HashMap<>();
        balances.put(TREASURY, 1_000L);
        Set<UUID> externallyApplied = new HashSet<>();
        AtomicBoolean failAfterFirstApplication = new AtomicBoolean(true);

        try (CivicDatabase database = database()) {
            database.registerNation(
                    NATION_ID.value(), "nation-test", "register", TEAM_ID, NOW.minusSeconds(60).toEpochMilli());
            NationFiscalAuthorityRegistry nationAuthorities =
                    new NationFiscalAuthorityRegistry(database, provider(), CLOCK);
            nationAuthorities.grant(new GrantNationFiscalPermission(
                    new ServiceIdentity("nation-governance"),
                    "grant-territory-finance",
                    NATION_ID,
                    ACTOR_ID,
                    ACTOR_ID,
                    NationFiscalPermission.MANAGE_TERRITORY_FINANCE,
                    "Authorize territory expansion"));
            var session = authorizeFiscalService(database);
            TerritoryClaimPrepaymentCoordinator coordinator =
                    new TerritoryClaimPrepaymentCoordinator(
                            new NationRegistry(database, emptyTeams()),
                            nationAuthorities,
                            accountId -> balances.putIfAbsent(accountId, 0L),
                            FiscalLedger.authorized(
                                    database,
                                    account -> MoneyAmount.ofMinorUnits(
                                            balances.getOrDefault(account, 0L)),
                                    session),
                            PaymentCoordinator.authorized(
                                    database,
                                    payment -> {
                                        if (externallyApplied.add(payment.transactionId())) {
                                            balances.compute(
                                                    payment.sourceAccount(),
                                                    (ignored, balance) -> Math.subtractExact(
                                                            balance,
                                                            payment.amount().minorUnits()));
                                            balances.merge(
                                                    payment.recipientAccount(),
                                                    payment.amount().minorUnits(),
                                                    Math::addExact);
                                            if (failAfterFirstApplication.getAndSet(false)) {
                                                throw new IllegalStateException(
                                                        "ambiguous external application");
                                            }
                                        }
                                    },
                                    session),
                            new TerritoryClaimPermitRegistry(
                                    database,
                                    new CommittedTerritoryPrepaymentVerifier(database, CLEARING),
                                    CLOCK),
                            TERRITORY_SERVICE,
                            CLEARING,
                            CLOCK);
            PrepareTerritoryClaimPrepayment request =
                    new PrepareTerritoryClaimPrepayment(
                            "ambiguous-real-payment",
                            NATION_ID,
                            TEAM_ID,
                            ACTOR_ID,
                            "minecraft:overworld",
                            20,
                            9,
                            17,
                            chargedQuote(),
                            NOW.plusSeconds(120L));

            assertThrows(IllegalStateException.class, () -> coordinator.prepare(request));
            assertEquals(750L, balances.get(TREASURY));
            assertEquals(250L, balances.get(CLEARING));

            TerritoryClaimPermit recovered = coordinator.prepare(request);

            assertEquals(TerritoryClaimPermitState.READY, recovered.state());
            assertEquals(750L, balances.get(TREASURY));
            assertEquals(250L, balances.get(CLEARING));
            assertEquals(
                    "CIVIC_COMMITTED",
                    database.paymentTransaction(recovered.prepaymentTransactionId()).state());
        }
    }

    private org.civiceconomy.fiscal.FiscalServiceSession authorizeFiscalService(
            CivicDatabase database) {
        FiscalAuthorization authorization = new FiscalAuthorization(database);
        new TerritoryFiscalServiceProvisioner(authorization).ensureAuthorized(TREASURY);
        return FiscalTestSessions.open(database, TERRITORY_SERVICE, "civiceconomy");
    }

    private NationProvider provider() {
        NationFacts facts = new NationFacts(NATION_ID, ACTOR_ID, Set.of(ACTOR_ID));
        return new NationProvider() {
            @Override
            public Optional<NationFacts> find(NationId nationId) {
                return NATION_ID.equals(nationId) ? Optional.of(facts) : Optional.empty();
            }

            @Override
            public Optional<NationFacts> findForCitizen(UUID playerId) {
                return ACTOR_ID.equals(playerId) ? Optional.of(facts) : Optional.empty();
            }
        };
    }

    private TerritoryExpansionQuote chargedQuote() {
        return new TerritoryExpansionQuote(
                NATION_ID,
                17,
                1,
                0,
                1,
                MoneyAmount.ofMinorUnits(250L),
                NOW);
    }

    private NationTeamDirectory emptyTeams() {
        return new NationTeamDirectory() {
            @Override
            public Optional<NationTeam> find(UUID teamId) {
                return Optional.empty();
            }

            @Override
            public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
                return Optional.empty();
            }
        };
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("territory-claim-prepayment.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("6e4dbdcc-7fd1-48e4-b7d4-b42264ddfc13"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
