package org.civiceconomy.gametest;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.civiceconomy.CivicEconomy;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.platform.neoforge.CivicContent;
import org.civiceconomy.platform.neoforge.FacilityAccountingInterfaceBlockEntity;
import org.civiceconomy.platform.neoforge.ServerFacilityAccountingBaselineSnapshotSource;
import org.civiceconomy.production.FacilityAccountingInterface;
import org.civiceconomy.production.FacilityAccountingInterfacePosition;
import org.civiceconomy.production.FacilityCorePosition;
import org.civiceconomy.production.RegisteredFacility;
import org.civiceconomy.production.RegisteredFacilityState;
import org.civiceconomy.territory.TerritoryClaimPosition;

@GameTestHolder(CivicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FacilityAccountingInterfaceBlockGameTests {
    private FacilityAccountingInterfaceBlockGameTests() {}

    @GameTest(template = "empty")
    public static void automationCanInsertIntoAccountingInterface(GameTestHelper helper) {
        BlockPos position = new BlockPos(1, 1, 1);
        helper.setBlock(position, CivicContent.FACILITY_ACCOUNTING_INTERFACE.get());
        IItemHandler itemHandler = helper.getLevel().getCapability(
                Capabilities.ItemHandler.BLOCK,
                helper.absolutePos(position),
                Direction.UP);
        helper.assertTrue(
                itemHandler != null,
                "Facility Accounting Interface exposes a server item handler");
        ItemStack remainder = itemHandler.insertItem(
                0, new ItemStack(Items.IRON_INGOT, 5), false);
        FacilityAccountingInterfaceBlockEntity accountingInterface =
                helper.getBlockEntity(position);
        helper.assertTrue(remainder.isEmpty(), "automation inserted the complete stack");
        helper.assertValueEqual(
                Items.IRON_INGOT,
                accountingInterface.getItem(0).getItem(),
                "automation inserted the exact item");
        helper.assertValueEqual(
                5,
                accountingInterface.getItem(0).getCount(),
                "automation inserted the exact count");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void baselineCaptureFailsClosedWithoutCreate(GameTestHelper helper) {
        if (CivicEconomy.compatibilityReport().productionScoringEnabled()) {
            helper.succeed();
            return;
        }
        BlockPos position = helper.absolutePos(new BlockPos(1, 1, 1));
        String dimensionId = helper.getLevel().dimension().location().toString();
        TerritoryClaimPosition claim = new TerritoryClaimPosition(
                dimensionId, position.getX() >> 4, position.getZ() >> 4);
        RegisteredFacility facility = new RegisteredFacility(
                UUID.randomUUID(),
                new ServiceIdentity("civiceconomy-no-create-gametest"),
                "no-create-facility",
                new NationId(UUID.randomUUID()),
                UUID.randomUUID(),
                new FacilityCorePosition(
                        dimensionId, position.getX(), position.getY(), position.getZ()),
                Set.of(claim),
                UUID.randomUUID(),
                RegisteredFacilityState.BASELINING,
                "verify missing Create fails closed",
                Instant.parse("2026-09-01T00:00:00Z"));
        FacilityAccountingInterface accountingInterface = new FacilityAccountingInterface(
                UUID.randomUUID(),
                facility.serviceIdentity(),
                "no-create-interface",
                facility.facilityId(),
                new FacilityAccountingInterfacePosition(
                        dimensionId, position.getX(), position.getY(), position.getZ()),
                facility.actorPlayerId(),
                "verify missing Create fails closed",
                Instant.parse("2026-09-01T00:00:00Z"));

        try {
            new ServerFacilityAccountingBaselineSnapshotSource(helper.getLevel().getServer())
                    .capture(facility, accountingInterface);
            helper.fail("Facility baseline capture accepted missing Create");
        } catch (IllegalStateException expected) {
            helper.assertTrue(
                    expected.getMessage().contains("Create is unavailable"),
                    "missing Create fails closed before reading world state");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void inventorySurvivesBlockEntitySaveAndReload(GameTestHelper helper) {
        BlockPos position = new BlockPos(1, 1, 1);
        helper.setBlock(position, CivicContent.FACILITY_ACCOUNTING_INTERFACE.get());
        FacilityAccountingInterfaceBlockEntity original = helper.getBlockEntity(position);
        original.setItem(4, new ItemStack(Items.DIAMOND, 7));

        BlockEntity restored = BlockEntity.loadStatic(
                helper.absolutePos(position),
                original.getBlockState(),
                original.saveWithFullMetadata(helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());

        helper.assertTrue(
                restored instanceof FacilityAccountingInterfaceBlockEntity,
                "saved Facility Accounting Interface reloads its registered block entity");
        FacilityAccountingInterfaceBlockEntity restoredInterface =
                (FacilityAccountingInterfaceBlockEntity) restored;
        helper.assertValueEqual(
                Items.DIAMOND,
                restoredInterface.getItem(4).getItem(),
                "Facility Accounting Interface item identity");
        helper.assertValueEqual(
                7,
                restoredInterface.getItem(4).getCount(),
                "Facility Accounting Interface item count");
        helper.succeed();
    }
}
