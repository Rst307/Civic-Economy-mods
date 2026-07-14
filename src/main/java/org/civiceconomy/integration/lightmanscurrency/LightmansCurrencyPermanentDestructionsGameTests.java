package org.civiceconomy.integration.lightmanscurrency;

import io.github.lightman314.lightmanscurrency.api.money.bank.BankAPI;
import io.github.lightman314.lightmanscurrency.api.money.bank.IBankAccount;
import io.github.lightman314.lightmanscurrency.api.money.coins.CoinAPI;
import io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue;
import io.github.lightman314.lightmanscurrency.api.money.value.builtin.CoinValue;
import io.github.lightman314.lightmanscurrency.common.data.CustomSaveData;
import io.github.lightman314.lightmanscurrency.common.data.types.BankDataCache;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Clock;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.civiceconomy.CivicEconomy;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.ExternalPayment;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalCapability;
import org.civiceconomy.fiscal.GrantFiscalCapability;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.RegisterFiscalService;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.monetary.ConfirmPermanentDestruction;
import org.civiceconomy.monetary.ExternalPermanentDestruction;
import org.civiceconomy.monetary.MonetarySupplyChange;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;

@GameTestHolder(CivicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LightmansCurrencyPermanentDestructionsGameTests {
    private LightmansCurrencyPermanentDestructionsGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void realFiscalAccountDestructionIsIdempotentAndPersistsItsMarker(
            GameTestHelper helper) {
        UUID fundingPlayerId = UUID.randomUUID();
        UUID cleanupPlayerId = UUID.randomUUID();
        UUID destructionId = UUID.randomUUID();
        BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
        AccountId fundingAccount = new AccountId("player:" + fundingPlayerId);
        AccountId cleanupAccount = new AccountId("player:" + cleanupPlayerId);
        AccountId treasury = new AccountId("nation:" + UUID.randomUUID() + ":treasury");
        LightmansCurrencyFiscalAccounts accounts =
                LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
        accounts.create(treasury, FiscalAccountKind.NATIONAL_TREASURY, "Destruction GameTest Treasury");
        reset(bankData, fundingPlayerId, 1_000L);
        reset(bankData, cleanupPlayerId, 0L);
        LightmansCurrencyPayments payments = LightmansCurrencyPayments.live(helper.getLevel());
        payments.apply(new ExternalPayment(
                UUID.randomUUID(), fundingAccount, treasury, MoneyAmount.ofMinorUnits(1_000L)));
        ExternalPermanentDestruction destruction = new ExternalPermanentDestruction(
                destructionId, treasury, MoneyAmount.ofMinorUnits(600L));

        LightmansCurrencyPermanentDestructions.live(helper.getLevel()).apply(destruction);
        LightmansCurrencyPermanentDestructions.live(helper.getLevel()).apply(destruction);

        helper.assertValueEqual(
                400L, accounts.balance(treasury).minorUnits(), "real LC balance after destruction replay");
        CivicFiscalAccountData liveData = accounts.data();
        helper.assertTrue(
                liveData.wasPermanentDestructionApplied(destructionId),
                "live fiscal SavedData should contain the destruction marker");
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        CivicFiscalAccountData reloaded =
                CivicFiscalAccountData.load(liveData.save(new CompoundTag(), registries), registries);
        helper.assertTrue(
                reloaded.wasPermanentDestructionApplied(destructionId),
                "serialized fiscal SavedData should reload the destruction marker");
        helper.assertValueEqual(
                400L,
                mainChainBalance(reloaded.account(treasury)),
                "serialized fiscal SavedData should reload the destroyed LC balance");

        payments.apply(new ExternalPayment(
                UUID.randomUUID(), treasury, cleanupAccount, MoneyAmount.ofMinorUnits(400L)));
        bankData.deleteAccount(fundingPlayerId);
        bankData.deleteAccount(cleanupPlayerId);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void realLcDestructionRecoversAcrossSqliteReopen(GameTestHelper helper) {
        UUID fundingPlayerId = UUID.randomUUID();
        UUID cleanupPlayerId = UUID.randomUUID();
        BankDataCache bankData = CustomSaveData.getData(BankDataCache.TYPE);
        AccountId fundingAccount = new AccountId("player:" + fundingPlayerId);
        AccountId cleanupAccount = new AccountId("player:" + cleanupPlayerId);
        AccountId treasury = new AccountId("nation:" + UUID.randomUUID() + ":treasury");
        ServiceIdentity service = new ServiceIdentity("civiceconomy-destruction-gametest");
        LightmansCurrencyFiscalAccounts accounts =
                LightmansCurrencyFiscalAccounts.forLevel(helper.getLevel());
        accounts.create(treasury, FiscalAccountKind.NATIONAL_TREASURY, "Recovery GameTest Treasury");
        reset(bankData, fundingPlayerId, 1_000L);
        reset(bankData, cleanupPlayerId, 0L);
        LightmansCurrencyPayments payments = LightmansCurrencyPayments.live(helper.getLevel());
        payments.apply(new ExternalPayment(
                UUID.randomUUID(), fundingAccount, treasury, MoneyAmount.ofMinorUnits(1_000L)));
        Path temporaryDirectory = createTemporaryDirectory();
        Path databaseFile = temporaryDirectory.resolve("destruction-recovery.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.randomUUID(),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
        ConfirmPermanentDestruction request = new ConfirmPermanentDestruction(
                service,
                "real-lc-recovery",
                treasury,
                MoneyAmount.ofMinorUnits(600L),
                "Real LC destruction recovery GameTest");

        try {
            try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
                authorize(database, service, treasury);
                database.confirmMonetarySupplyChange(
                        UUID.randomUUID(),
                        service.value(),
                        "seed-real-lc-recovery",
                        MonetarySupplyChange.ISSUANCE.name(),
                        1_000L,
                        "mint-batch:real-lc-recovery",
                        "Seed GameTest issuance",
                        System.currentTimeMillis(),
                        2_000L);
                var session = new FiscalAuthorization(database).openSession(service);
                var realDestructions = LightmansCurrencyPermanentDestructions.live(helper.getLevel());
                PermanentDestructionCoordinator coordinator =
                        PermanentDestructionCoordinator.authorized(
                                database,
                                destruction -> {
                                    realDestructions.apply(destruction);
                                    throw new IllegalStateException("simulated process death");
                                },
                                session,
                                Clock.systemUTC());
                try {
                    coordinator.confirm(request);
                    throw new AssertionError("Expected simulated process death");
                } catch (IllegalStateException expected) {
                    helper.assertValueEqual(
                            "simulated process death", expected.getMessage(), "controlled crash point");
                }
                helper.assertValueEqual(
                        400L, accounts.balance(treasury).minorUnits(), "LC balance before SQLite recovery");
                helper.assertValueEqual(
                        1_000L,
                        database.cumulativeNetIssuanceMinorUnits(),
                        "net issuance before SQLite recovery");
            }

            try (CivicDatabase reopened = CivicDatabase.open(databaseFile, identity)) {
                var session = new FiscalAuthorization(reopened).openSession(service);
                PermanentDestructionCoordinator recovery = PermanentDestructionCoordinator.live(
                        reopened, session, Clock.systemUTC(), helper.getLevel());
                recovery.recoverAll();
                recovery.confirm(request);

                helper.assertValueEqual(
                        400L, accounts.balance(treasury).minorUnits(), "LC balance after recovery replay");
                helper.assertValueEqual(
                        400L,
                        reopened.cumulativeNetIssuanceMinorUnits(),
                        "net issuance after recovery");
                helper.assertValueEqual(
                        2, reopened.monetarySupplyEvents().size(), "issuance plus one destruction event");
            }
        } finally {
            payments.apply(new ExternalPayment(
                    UUID.randomUUID(), treasury, cleanupAccount, MoneyAmount.ofMinorUnits(400L)));
            bankData.deleteAccount(fundingPlayerId);
            bankData.deleteAccount(cleanupPlayerId);
            deleteTemporaryDirectory(temporaryDirectory);
        }
        helper.succeed();
    }

    private static void authorize(
            CivicDatabase database, ServiceIdentity service, AccountId treasury) {
        FiscalAuthorization authorization = new FiscalAuthorization(database);
        authorization.register(new RegisterFiscalService(
                service, CivicEconomy.MOD_ID, "Permanent Destruction GameTest service"));
        authorization.grant(new GrantFiscalCapability(
                new ServiceIdentity("civiceconomy-gametest-admin"),
                "grant-real-lc-destruction",
                service,
                FiscalCapability.PERMANENT_DESTRUCTION,
                treasury,
                "Real LC destruction GameTest"));
    }

    private static Path createTemporaryDirectory() {
        try {
            return Files.createTempDirectory("civiceconomy-destruction-gametest-");
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to create destruction GameTest directory", failure);
        }
    }

    private static void deleteTemporaryDirectory(Path directory) {
        try (var files = Files.walk(directory)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException failure) {
                    throw new IllegalStateException("Unable to delete destruction GameTest file " + path, failure);
                }
            });
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to clean destruction GameTest directory", failure);
        }
    }

    private static IBankAccount reset(BankDataCache bankData, UUID playerId, long balance) {
        IBankAccount account = bankData.getAccount(playerId);
        account.getMoneyStorage().clear();
        if (balance > 0L) {
            MoneyValue value = CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, balance);
            if (!BankAPI.getApi().BankDepositFromServer(account, value)) {
                throw new IllegalStateException("Unable to seed LC GameTest account " + playerId);
            }
        }
        return account;
    }

    private static long mainChainBalance(IBankAccount account) {
        MoneyValue unit = CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, 1L);
        return account.getMoneyStorage().valueOf(unit.getUniqueName()).getCoreValue();
    }
}
