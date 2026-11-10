package com.miaokatze.gtswn.common.testworld;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.FakePlayer;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.gui.ContainerTerminal;

final class TerminalRenameScenario implements TestScenario {

    private FakePlayer player;
    private ContainerTerminal container;
    private ItemStack terminal;
    private long firstToken;

    @Override
    public String id() {
        return "terminal.rename";
    }

    @Override
    public String description() {
        return "Real server container rename, replay rejection, whitespace reset and item NBT";
    }

    @Override
    public void start(TestContext ctx) {
        player = TestWorldFixtures.player(ctx.world(), TestWorldFixtures.owner(id()));
        terminal = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
        player.inventory.setInventorySlotContents(0, terminal);
        container = new ContainerTerminal(player, true);
        container.windowId = 77;
        player.openContainer = container;
        firstToken = container.renameToken();
    }

    @Override
    public void tick(TestContext ctx) {
        if (ctx.tick() != 1) return;
        TestWorldFixtures.require(
            ctx,
            container.renameTerminal(player, 77, 0, firstToken, "", "GTSWN Fixture"),
            "Production container accepted matching rename intent");
        TestWorldFixtures
            .require(ctx, "GTSWN Fixture".equals(terminal.getDisplayName()), "Real held item receives custom name");
        TestWorldFixtures.require(
            ctx,
            !container.renameTerminal(player, 77, 0, firstToken, "GTSWN Fixture", "replay"),
            "Consumed rename token cannot replay");
        TestWorldFixtures.require(
            ctx,
            !container.renameTerminal(player, 76, 0, container.renameToken(), "GTSWN Fixture", "wrong window"),
            "Wrong window is rejected");
        NBTTagCompound saved = new NBTTagCompound();
        terminal.writeToNBT(saved);
        ItemStack restored = ItemStack.loadItemStackFromNBT(saved);
        TestWorldFixtures.require(
            ctx,
            restored != null && "GTSWN Fixture".equals(restored.getDisplayName()),
            "Item NBT roundtrip preserves custom name");
        TestWorldFixtures.require(
            ctx,
            container.renameTerminal(player, 77, 0, container.renameToken(), "GTSWN Fixture", "   "),
            "Matching whitespace rename accepted");
        TestWorldFixtures.require(ctx, !terminal.hasDisplayName(), "Whitespace rename restores default item name");
        ctx.pass("Real server container name mutation, stale intent/window rejection and item NBT passed");
    }

    @Override
    public void cleanup(TestContext ctx) {
        if (container != null && player != null) container.onContainerClosed(player);
        if (player != null) {
            player.openContainer = player.inventoryContainer;
            player.inventory.clearInventory(null, -1);
        }
        player = null;
        container = null;
    }
}
