package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.nation.CitizenshipCorrectionGraceRegistry;
import org.civiceconomy.nation.CitizenshipRegistry;
import org.civiceconomy.nation.FtbTeamsNationProvider;
import org.civiceconomy.nation.GrantNationFiscalPermission;
import org.civiceconomy.nation.JoinCitizenship;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.NationProvider;
import org.civiceconomy.nation.NationRegistry;
import org.civiceconomy.nation.NationTeam;
import org.civiceconomy.nation.NationTeamDirectory;
import org.civiceconomy.nation.RegisterNation;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FiscalBillInspectionTest {
    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID PAYER =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_PAYER =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID BENEFICIARY_NATION =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_NATION =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID UNAUTHORIZED_CITIZEN =
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @TempDir Path temporaryDirectory;

    @Test
    void payerInspectionDerivesTheExactPlayerAccountAndRejectsForeignIds() {
        try (CivicDatabase database = database()) {
            FiscalBill ownFirst = issue(
                    database,
                    "own-first",
                    PAYER,
                    BENEFICIARY_NATION,
                    300L,
                    Instant.parse("2026-07-20T00:00:00Z"));
            FiscalBill ownSecond = issue(
                    database,
                    "own-second",
                    PAYER,
                    OTHER_NATION,
                    500L,
                    Instant.parse("2026-07-21T00:00:00Z"));
            FiscalBill foreign = issue(
                    database,
                    "foreign",
                    OTHER_PAYER,
                    BENEFICIARY_NATION,
                    700L,
                    Instant.parse("2026-07-22T00:00:00Z"));
            FiscalBillInspection inspection = new FiscalBillInspection(database);

            assertEquals(List.of(ownFirst, ownSecond), inspection.listForPayer(PAYER));
            assertEquals(ownSecond, inspection.statusForPayer(PAYER, ownSecond.billId()));
            assertThrows(
                    SecurityException.class,
                    () -> inspection.statusForPayer(PAYER, foreign.billId()));
            assertThrows(
                    SecurityException.class,
                    () -> inspection.statusForPayer(
                            PAYER,
                            UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")));
        }
    }

    @Test
    void nationInspectionRequiresExactViewAccountAuthorityAndRejectsForeignIds() {
        try (CivicDatabase database = database()) {
            Fixtures fixtures = fixtures(database);
            fixtures.authorities().grant(new GrantNationFiscalPermission(
                    new ServiceIdentity("civiceconomy-governance"),
                    "grant-bill-inspection",
                    fixtures.nationOne(),
                    PAYER,
                    PAYER,
                    NationFiscalPermission.VIEW_ACCOUNT,
                    "Treasury viewer may inspect receivables"));
            FiscalBill ownFirst = issue(
                    database,
                    "nation-own-first",
                    PAYER,
                    fixtures.nationOne().value(),
                    300L,
                    Instant.parse("2026-07-20T00:00:00Z"));
            FiscalBill ownSecond = issue(
                    database,
                    "nation-own-second",
                    OTHER_PAYER,
                    fixtures.nationOne().value(),
                    500L,
                    Instant.parse("2026-07-21T00:00:00Z"));
            FiscalBill foreign = issue(
                    database,
                    "nation-foreign",
                    PAYER,
                    fixtures.nationTwo().value(),
                    700L,
                    Instant.parse("2026-07-22T00:00:00Z"));
            NationFiscalBillInspection inspection = new NationFiscalBillInspection(
                    database, fixtures.provider(), fixtures.authorities());

            assertEquals(List.of(ownFirst, ownSecond), inspection.list(PAYER));
            assertEquals(ownSecond, inspection.status(PAYER, ownSecond.billId()));
            assertThrows(
                    SecurityException.class,
                    () -> inspection.list(UNAUTHORIZED_CITIZEN));
            assertThrows(
                    SecurityException.class,
                    () -> inspection.status(PAYER, foreign.billId()));
            assertThrows(
                    SecurityException.class,
                    () -> inspection.status(
                            PAYER,
                            UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")));
        }
    }

    private static FiscalBill issue(
            CivicDatabase database,
            String requestId,
            UUID payer,
            UUID beneficiaryNation,
            long amount,
            Instant dueAt) {
        return FiscalLedger.toFiscalBill(database.issueFiscalBill(
                UUID.randomUUID(),
                "civiceconomy-fiscal-bill",
                requestId,
                "player:" + payer,
                "nation:" + beneficiaryNation + ":treasury",
                amount,
                FiscalBillKind.FEE.name(),
                "Inspection " + requestId,
                dueAt.toEpochMilli()));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("fiscal-bill-inspection.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }

    private static Fixtures fixtures(CivicDatabase database) {
        NationTeamDirectory teams = new NationTeamDirectory() {
            @Override
            public Optional<NationTeam> find(UUID teamId) {
                if (BENEFICIARY_NATION.equals(teamId)) {
                    return Optional.of(new NationTeam(
                            BENEFICIARY_NATION,
                            PAYER,
                            Set.of(PAYER, UNAUTHORIZED_CITIZEN)));
                }
                if (OTHER_NATION.equals(teamId)) {
                    return Optional.of(new NationTeam(
                            OTHER_NATION, OTHER_PAYER, Set.of(OTHER_PAYER)));
                }
                return Optional.empty();
            }

            @Override
            public Optional<NationTeam> findEffectiveTeamForPlayer(UUID playerId) {
                if (Set.of(PAYER, UNAUTHORIZED_CITIZEN).contains(playerId)) {
                    return find(BENEFICIARY_NATION);
                }
                if (OTHER_PAYER.equals(playerId)) {
                    return find(OTHER_NATION);
                }
                return Optional.empty();
            }
        };
        NationRegistry nations = new NationRegistry(database, teams);
        NationId nationOne = nations.register(new RegisterNation(
                new ServiceIdentity("civiceconomy"),
                "register-bill-inspection-one",
                BENEFICIARY_NATION)).nationId();
        NationId nationTwo = nations.register(new RegisterNation(
                new ServiceIdentity("civiceconomy"),
                "register-bill-inspection-two",
                OTHER_NATION)).nationId();
        CitizenshipRegistry citizenships = new CitizenshipRegistry(
                database, Duration.ZERO, CLOCK);
        citizenships.join(new JoinCitizenship(
                new ServiceIdentity("civiceconomy"),
                "join-bill-inspection-payer",
                PAYER,
                nationOne));
        citizenships.join(new JoinCitizenship(
                new ServiceIdentity("civiceconomy"),
                "join-bill-inspection-unauthorized",
                UNAUTHORIZED_CITIZEN,
                nationOne));
        citizenships.join(new JoinCitizenship(
                new ServiceIdentity("civiceconomy"),
                "join-bill-inspection-other",
                OTHER_PAYER,
                nationTwo));
        NationProvider provider = new FtbTeamsNationProvider(
                nations,
                citizenships,
                new CitizenshipCorrectionGraceRegistry(database, CLOCK),
                teams);
        return new Fixtures(
                nationOne,
                nationTwo,
                provider,
                new NationFiscalAuthorityRegistry(database, provider, CLOCK));
    }

    private record Fixtures(
            NationId nationOne,
            NationId nationTwo,
            NationProvider provider,
            NationFiscalAuthorityRegistry authorities) {}
}
