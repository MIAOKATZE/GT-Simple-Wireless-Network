package com.miaokatze.gtswn.common.gui;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S2FPacketSetSlot;

import com.cleanroommc.modularui.api.UIFactory;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.factory.GuiData;
import com.cleanroommc.modularui.factory.GuiManager;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.sync.ItemSlotSH;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.slot.ItemSlot;
import com.cleanroommc.modularui.widgets.slot.PlayerSlotGroup;
import com.miaokatze.gtswn.client.gui.GuiDeviceTerminalSettings;
import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.device.DeviceTerminalSource;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketDeviceTeleportRequest;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Settings use the existing real supply transaction and exact carried terminal location on both sides. */
public final class DeviceTerminalSettingsGuiFactory implements UIFactory<DeviceTerminalSettingsGuiFactory.Data> {

    private static final DeviceTerminalSettingsGuiFactory INSTANCE = new DeviceTerminalSettingsGuiFactory();
    public static final int WIDTH = 360, HEIGHT = 328;

    private DeviceTerminalSettingsGuiFactory() {
        GuiManager.registerFactory(this);
    }

    public static void init() {}

    public static void requestOpen(EntityPlayer player, ItemStack terminal) {
        int slot = DeviceTerminalSource.location(player, terminal);
        UUID id = ItemDeviceInfoTerminal.readTerminalId(terminal);
        if (terminal != null && terminal.getItem() instanceof ItemDeviceInfoTerminal
            && DeviceTerminalSource.validSlot(slot))
            GTSWNPacketHandler.NETWORK
                .sendToServer(PacketDeviceTeleportRequest.openSettings(id == null ? new UUID(0, 0) : id, slot));
    }

    public static void open(EntityPlayerMP player, UUID terminalId, int slot) {
        if (!DeviceTerminalSource.validSlot(slot)) return;
        ItemStack terminal = DeviceTerminalSource.stack(player, slot);
        if (terminal == null || !(terminal.getItem() instanceof ItemDeviceInfoTerminal)) return;
        UUID actual = ItemDeviceInfoTerminal.readTerminalId(terminal);
        if (new UUID(0, 0).equals(terminalId) && actual == null) {
            terminalId = ItemDeviceInfoTerminal.getOrCreateTerminalId(terminal);
            player.inventory.markDirty();
            if (slot >= 0) player.playerNetServerHandler
                .sendPacket(new S2FPacketSetSlot(0, slot < 9 ? 36 + slot : slot, terminal));
            else TerminalBaubles.syncChangedStack(player, terminal);
        } else if (!terminalId.equals(actual)) return;
        GuiManager.open(INSTANCE, new Data(player, terminalId, slot), player);
    }

    public static final class Data extends GuiData {

        final UUID terminalId;
        final int sourceSlot;
        ContainerTerminal container;
        ParentWidget<?> supplies;

        Data(EntityPlayer player, UUID terminalId, int sourceSlot) {
            super(player);
            this.terminalId = terminalId;
            this.sourceSlot = sourceSlot;
        }
    }

    @Override
    public String getFactoryName() {
        return "gtswn:device_terminal_settings";
    }

    @Override
    public void writeGuiData(Data data, PacketBuffer buffer) {
        buffer.writeLong(data.terminalId.getMostSignificantBits());
        buffer.writeLong(data.terminalId.getLeastSignificantBits());
        buffer.writeInt(data.sourceSlot);
    }

    @Override
    public Data readGuiData(EntityPlayer player, PacketBuffer buffer) {
        return new Data(player, new UUID(buffer.readLong(), buffer.readLong()), buffer.readInt());
    }

    @Override
    public ModularPanel createPanel(Data data, PanelSyncManager sync, UISettings settings) {
        ContainerTerminal container = ContainerTerminal.forDeviceSettings(data.getPlayer(), data.sourceSlot);
        data.container = container;
        settings.customContainer(() -> container);
        settings.canInteractWith(container::canInteractWith);
        settings.useTheme(GtswnMuiTheme.THEME_ID);
        sync.registerSlotGroup(new PlayerSlotGroup(PlayerSlotGroup.NAME));
        ModularPanel panel = GtswnMuiTheme.panel("device_terminal_settings", WIDTH, HEIGHT);
        ParentWidget<?> supplies = new ParentWidget<>().size(WIDTH, HEIGHT)
            .name("device.settings.supplies");
        data.supplies = supplies;
        supplies.child(
            IKey.lang(TerminalSupplyStore.Kind.TELEPORT_ANCHOR.labelKey())
                .asWidget()
                .pos(12, 62)
                .size(290, 14)
                .alignment(Alignment.CenterLeft));
        supplies.child(
            IKey.dynamic(
                () -> net.minecraft.util.StatCollector.translateToLocalFormatted(
                    "gtswn.device.settings.anchor_count",
                    container.supplyCount,
                    TerminalSupplyStore.CAPACITY))
                .asWidget()
                .pos(12, 87)
                .size(280, 14)
                .alignment(Alignment.CenterLeft));
        supplies.child(
            IKey.lang("gtswn.device.settings.anchor_hint")
                .asWidget()
                .pos(12, 112)
                .size(336, 34)
                .alignment(Alignment.TopLeft));
        supplies.child(
            IKey.lang("container.inventory")
                .asWidget()
                .pos(99, 157)
                .size(162, 12)
                .alignment(Alignment.CenterLeft));
        for (int index = 0; index < 37; index++) {
            ItemSlotSH handler = new ItemSlotSH(container.terminalSlot(index));
            sync.syncValue("device_settings_slot", index, handler);
            int x = index == 0 ? 318 : 99 + ((index - 1) % 9) * 18;
            int y = index == 0 ? 76 : index <= 27 ? 174 + ((index - 1) / 9) * 18 : 232;
            supplies.child(
                new ItemSlot().syncHandler(handler)
                    .background(GtswnMuiTheme.LIST_PANEL)
                    .pos(x, y)
                    .name("device.settings.slot." + index));
        }
        panel.child(supplies);
        return panel;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public ModularScreen createScreen(Data data, ModularPanel panel) {
        return new GuiDeviceTerminalSettings(
            DeviceTerminalSource.stack(data.getPlayer(), data.sourceSlot),
            panel,
            data.container,
            data.supplies);
    }
}
