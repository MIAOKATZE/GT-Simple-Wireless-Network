package com.miaokatze.gtswn.common.testworld;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.FakePlayer;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;

final class PortableMonitorScenario implements TestScenario {

    private FakePlayer owner, outsider;
    private ItemStack stack;
    private TileEntityNetworkInfoPanel panel;
    private PanelOwnerScope scope;

    @Override
    public String id() {
        return "portable.binding_nbt";
    }

    @Override
    public String description() {
        return "Real portable owner binding, nearby panel binding, foreign actor rejection and NBT";
    }

    @Override
    public void start(TestContext ctx) {
        owner = TestActors.player(ctx.world(), "owner");
        outsider = TestActors.player(ctx.world(), "outsider");
        scope = new PanelOwnerScope(ctx.world(), owner.getUniqueID());
        stack = GTSWNItemList.Portable_Wireless_Network_Monitor.get(1);
        owner.inventory.setInventorySlotContents(0, stack);
        panel = TestWorldFixtures.panel(ctx.world(), owner.getUniqueID());
        owner.setPosition(panel.xCoord + .5, panel.yCoord, panel.zCoord + 2.5);
        outsider.setPosition(panel.xCoord + .5, panel.yCoord, panel.zCoord + 2.5);
        ((PortableWirelessNetworkMonitor) stack.getItem()).onItemRightClick(stack, ctx.world(), owner);
    }

    @Override
    public void tick(TestContext ctx) {
        if (ctx.tick() != 3) return;
        TestWorldFixtures.require(
            ctx,
            PortableWirelessNetworkMonitor.isMonitorBound(stack) && owner.getUniqueID()
                .toString()
                .equals(PortableWirelessNetworkMonitor.getOwnerUUID(stack)),
            "Real item callback binds portable monitor to synthetic owner");
        MonitorBindings.bind(stack, outsider, panel);
        TestWorldFixtures.require(
            ctx,
            MonitorBindings.read(stack)
                .isEmpty(),
            "Foreign actor cannot add panel binding");
        MonitorBindings.bind(stack, owner, panel);
        TestWorldFixtures.require(
            ctx,
            MonitorBindings.read(stack)
                .size() == 1,
            "Production nearby-panel binding adds actual panel identity");
        MonitorBindings.bind(stack, owner, panel);
        TestWorldFixtures.require(
            ctx,
            MonitorBindings.read(stack)
                .size() == 1,
            "Repeated bind is idempotent");
        ((PortableWirelessNetworkMonitor) stack.getItem()).onItemRightClick(stack, ctx.world(), owner);
        TestWorldFixtures.require(
            ctx,
            PortableWirelessNetworkMonitor.getHudMode(stack) == 1,
            "Real server callback advances HUD preference");
        NBTTagCompound tag = new NBTTagCompound();
        stack.writeToNBT(tag);
        ItemStack restored = ItemStack.loadItemStackFromNBT(tag);
        TestWorldFixtures.require(
            ctx,
            MonitorBindings.read(restored)
                .size() == 1
                && MonitorBindings.read(restored)
                    .get(0).panelId.equals(panel.getPortableMonitorId()),
            "Portable item NBT preserves the real panel UUID binding");
        TestWorldFixtures.require(
            ctx,
            MonitorBindings.remove(stack, panel.getPortableMonitorId()) && MonitorBindings.read(stack)
                .isEmpty(),
            "Production remove clears only selected panel binding");
        ctx.pass(
            "Real portable owner/panel binding, foreign actor rejection, HUD preference and item NBT passed; client HUD rendering remains MANUAL");
    }

    @Override
    public void cleanup(TestContext ctx) {
        TestWorldFixtures.panel(ctx.world(), null);
        if (scope != null) scope.close();
        scope = null;
        if (owner != null) owner.inventory.clearInventory(null, -1);
        if (outsider != null) outsider.inventory.clearInventory(null, -1);
        owner = null;
        outsider = null;
    }
}
