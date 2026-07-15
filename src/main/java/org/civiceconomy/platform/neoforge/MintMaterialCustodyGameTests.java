package org.civiceconomy.platform.neoforge;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.civiceconomy.CivicEconomy;
import org.civiceconomy.mint.MintMaterialCustodyReturn;
import org.civiceconomy.mint.MintMaterialCustodyTransfer;
import org.civiceconomy.mint.MintMaterialStack;

@GameTestHolder(CivicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MintMaterialCustodyGameTests {
    private MintMaterialCustodyGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void realPlayerInventoryCustodyRecoversTakeAndReturnExactlyOnce(
            GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(playerId, "civic-mint-custody"),
                ClientInformation.createDefault());
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        UUID batchId = UUID.randomUUID();
        UUID mintId = UUID.randomUUID();
        MintMaterialStack material = new MintMaterialStack(
                0, "EXACT_ITEM", "minecraft:diamond", "minecraft:diamond", 3L);
        MintMaterialCustodyTransfer transfer = new MintMaterialCustodyTransfer(
                batchId, batchId, mintId, playerId, List.of(material));

        ServerPlayerMintMaterialCustody crashingTake = new ServerPlayerMintMaterialCustody(
                helper.getLevel().getServer(),
                requestedId -> requestedId.equals(playerId) ? player : null,
                () -> {
                    throw new SimulatedInventoryCrash();
                });
        try {
            crashingTake.take(transfer);
            helper.fail("Expected crash after real inventory removal");
        } catch (SimulatedInventoryCrash expected) {
            helper.assertValueEqual(
                    2,
                    player.getInventory().getItem(0).getCount(),
                    "diamonds after crashed custody take");
        }

        ServerPlayerMintMaterialCustody custody = new ServerPlayerMintMaterialCustody(
                helper.getLevel().getServer(),
                requestedId -> requestedId.equals(playerId) ? player : null,
                () -> {});
        custody.take(transfer);
        custody.take(transfer);
        helper.assertValueEqual(
                2,
                player.getInventory().getItem(0).getCount(),
                "diamonds after custody replay");

        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        custody.take(transfer);
        helper.assertValueEqual(
                2,
                player.getInventory().getItem(0).getCount(),
                "diamonds when held marker persisted before inventory");

        UUID returnId = UUID.nameUUIDFromBytes(
                ("mint-return:" + batchId).getBytes(StandardCharsets.UTF_8));
        MintMaterialCustodyReturn materialReturn = new MintMaterialCustodyReturn(
                returnId, batchId, mintId, playerId, List.of(material));
        ServerPlayerMintMaterialCustody crashingReturn = new ServerPlayerMintMaterialCustody(
                helper.getLevel().getServer(),
                requestedId -> requestedId.equals(playerId) ? player : null,
                () -> {
                    throw new SimulatedInventoryCrash();
                });
        try {
            crashingReturn.returnToSource(materialReturn);
            helper.fail("Expected crash after real inventory return");
        } catch (SimulatedInventoryCrash expected) {
            helper.assertValueEqual(
                    5,
                    player.getInventory().getItem(0).getCount(),
                    "diamonds after crashed custody return");
        }

        custody.returnToSource(materialReturn);
        custody.returnToSource(materialReturn);
        helper.assertValueEqual(
                5,
                player.getInventory().getItem(0).getCount(),
                "diamonds after return replay");
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 2));
        custody.returnToSource(materialReturn);
        helper.assertValueEqual(
                5,
                player.getInventory().getItem(0).getCount(),
                "diamonds when returned marker persisted before inventory");
        helper.assertValueEqual(
                "RETURNED",
                MintMaterialCustodyData.get(helper.getLevel().getServer())
                        .operation(batchId)
                        .state(),
                "persisted custody state");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void realPlayerInventoryRejectsItemThatIsNotInDeclaredTag(
            GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                new GameProfile(playerId, "civic-mint-tag"),
                ClientInformation.createDefault());
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
        UUID batchId = UUID.randomUUID();
        MintMaterialStack falseTagClaim = new MintMaterialStack(
                0, "TAG", "minecraft:logs", "minecraft:diamond", 3L);
        ServerPlayerMintMaterialCustody custody = new ServerPlayerMintMaterialCustody(
                helper.getLevel().getServer(),
                requestedId -> requestedId.equals(playerId) ? player : null,
                () -> {});

        try {
            custody.take(new MintMaterialCustodyTransfer(
                    batchId, batchId, UUID.randomUUID(), playerId, List.of(falseTagClaim)));
            helper.fail("Expected false tag membership to fail closed");
        } catch (IllegalStateException expected) {
            helper.assertValueEqual(
                    3,
                    player.getInventory().getItem(0).getCount(),
                    "diamonds after rejected tag manifest");
            helper.succeed();
        }
    }

    private static final class SimulatedInventoryCrash extends RuntimeException {}
}
