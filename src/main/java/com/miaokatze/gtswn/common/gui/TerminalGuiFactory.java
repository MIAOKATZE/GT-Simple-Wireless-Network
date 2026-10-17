package com.miaokatze.gtswn.common.gui;

import java.util.function.Supplier;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.api.UIFactory;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.DynamicDrawable;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.factory.GuiData;
import com.cleanroommc.modularui.factory.GuiManager;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.value.sync.ItemSlotSH;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.PageButton;
import com.cleanroommc.modularui.widgets.PagedWidget;
import com.cleanroommc.modularui.widgets.slot.ItemSlot;
import com.cleanroommc.modularui.widgets.slot.ModularSlot;
import com.cleanroommc.modularui.widgets.slot.PlayerSlotGroup;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.miaokatze.gtswn.client.gui.GuiQuantumTerminal;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkColor;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketRenameQuantumTerminal;
import com.miaokatze.gtswn.network.PacketSetQuantumNetworkColor;
import com.miaokatze.gtswn.network.PacketSetQuantumNetworkSafety;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Both sides construct the same slot handlers; only ordinary client button callbacks send business packets. */
public final class TerminalGuiFactory implements UIFactory<GuiData> {

    private static final TerminalGuiFactory QUANTUM = new TerminalGuiFactory(true);
    private static final TerminalGuiFactory LINK = new TerminalGuiFactory(false);
    private static QuantumNetworkData clientSnapshot;
    private final boolean quantum;

    private TerminalGuiFactory(boolean quantum) {
        this.quantum = quantum;
        GuiManager.registerFactory(this);
    }

    public static void init() {}

    public static void receiveData(QuantumNetworkData data) {
        clientSnapshot = data;
    }

    public static void open(EntityPlayerMP player, boolean quantum) {
        ContainerTerminal context = new ContainerTerminal(player, quantum);
        if (context.canInteractWith(player)) GuiManager.open(quantum ? QUANTUM : LINK, new GuiData(player), player);
    }

    @Override
    public String getFactoryName() {
        return quantum ? "gtswn:quantum_terminal" : "gtswn:link_terminal";
    }

    @Override
    public void writeGuiData(GuiData data, PacketBuffer buffer) {}

    @Override
    public GuiData readGuiData(EntityPlayer player, PacketBuffer buffer) {
        return new GuiData(player);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public ModularScreen createScreen(GuiData data, ModularPanel panel) {
        return new GuiQuantumTerminal(data.getPlayer(), panel);
    }

    @Override
    public ModularPanel createPanel(GuiData data, PanelSyncManager sync, UISettings settings) {
        EntityPlayer player = data.getPlayer();
        ContainerTerminal container = ContainerTerminal.forUi(player, quantum);
        settings.customContainer(() -> container);
        settings.canInteractWith(container::canInteractWith);
        settings.useTheme(GtswnMuiTheme.THEME_ID);
        // The terminal already supplies the complete locked player inventory. Prevent MSM's fallback from
        // binding a second set of 36 player slots during construct().
        sync.registerSlotGroup(new PlayerSlotGroup(PlayerSlotGroup.NAME));
        String prefix = quantum ? "quantum" : "link";
        ModularPanel panel = GtswnMuiTheme.panel(prefix + "_terminal", 450, 286);
        PagedWidget.Controller tabs = new PagedWidget.Controller();
        PagedWidget<?> pages = new PagedWidget<>().controller(tabs)
            .pos(12, 40)
            .size(426, 140);
        pages.addPage(quantum ? quantumDetails(player) : linkDetails(player, container));
        pages.addPage(effects(player));
        if (quantum) pages.addPage(rename(player, container, tabs, pages));
        panel.child(pages);
        panel.child(
            IKey.lang(quantum ? "gtswn.gui.quantum.network_details" : "gtswn.gui.terminal.link_details")
                .color(GtswnMuiTheme.TEXT_TITLE)
                .asWidget()
                .pos(14, 15)
                .size(190, 14)
                .alignment(Alignment.CenterLeft));
        panel.child(tab(prefix + ".tab.details", "gtswn.gui.terminal.details", 0, tabs, 218));
        panel.child(tab(prefix + ".tab.effects", "gtswn.gui.terminal.effects", 1, tabs, 292));
        if (quantum) panel.child(tab("quantum.rename", "gtswn.gui.quantum.rename", 2, tabs, 366));
        panel.child(
            IKey.lang("container.inventory")
                .color(GtswnMuiTheme.TEXT_LABEL)
                .asWidget()
                .pos(14, 186)
                .size(165, 12)
                .alignment(Alignment.CenterLeft));

        // Explicit insertion order is the vanilla slot order on both sides, including slot 0's reserve adapter.
        for (int index = 0; index < 37; index++) {
            ModularSlot slot = container.terminalSlot(index);
            ItemSlotSH handler = new ItemSlotSH(slot);
            sync.syncValue("terminal_slot", index, handler);
            int x = index == 0 ? 406 : 14 + ((index - 1) % 9) * 18;
            int y = index == 0 ? 222 : index <= 27 ? 201 + ((index - 1) / 9) * 18 : 259;
            panel.child(
                new ItemSlot().syncHandler(handler)
                    .background(GtswnMuiTheme.LIST_PANEL)
                    .pos(x, y)
                    .name(prefix + ".slot." + index));
        }
        panel.child(
            IKey.lang(quantum ? "gtswn.gui.terminal.anchor" : "gtswn.gui.terminal.tube")
                .color(GtswnMuiTheme.TEXT_TITLE)
                .asWidget()
                .pos(204, 202)
                .size(200, 13)
                .alignment(Alignment.CenterLeft));
        panel.child(label(() -> tr("gtswn.gui.terminal.preload") + ": " + container.supplyCount, 204, 225, 194));
        panel.child(label(() -> tr("gtswn.gui.terminal.capacity"), 204, 244, 222));
        return panel;
    }

    private static PageButton tab(String name, String text, int page, PagedWidget.Controller tabs, int x) {
        return new PageButton(page, tabs).name(name)
            .pos(x, 12)
            .size(70, 20)
            .background(true, GtswnMuiTheme.TAB_ACTIVE)
            .background(false, GtswnMuiTheme.TAB_INACTIVE)
            .overlay(
                IKey.lang(text)
                    .color(GtswnMuiTheme.TEXT_BODY));
    }

    private ParentWidget<?> quantumDetails(EntityPlayer player) {
        ParentWidget<?> body = new ParentWidget<>().size(426, 140)
            .background(GtswnMuiTheme.LIST_PANEL)
            .name("quantum.details");
        body.child(label(() -> detailsLine(player, 0), 8, 8, 240));
        for (int i = 1; i <= 6; i++) {
            final int line = i;
            body.child(label(() -> detailsLine(player, line), 8, 8 + i * 17, 240).color(() -> {
                QuantumNetworkData snapshot = clientSnapshot;
                return snapshot != null
                    && (line == 1 && snapshot.managementOnly
                        || line == 3 && snapshot.online
                            && !snapshot.channelsInfinite
                            && snapshot.usedChannels > snapshot.totalChannels
                        || line == 6 && !snapshot.online) ? GtswnMuiTheme.STATE_OFFLINE : GtswnMuiTheme.TEXT_BODY;
            }));
        }
        for (int i = 0; i < QuantumNetworkColor.COUNT; i++) {
            final int color = i;
            body.child(
                new ButtonWidget<>().name("quantum.color." + i)
                    .pos(260 + (i / 8) * 78, 6 + (i % 8) * 14)
                    .size(76, 14)
                    .background(
                        new DynamicDrawable(
                            () -> clientSnapshot != null && clientSnapshot.managementOnly
                                ? GtswnMuiTheme.BUTTON_DISABLED
                                : GtswnMuiTheme.BUTTON))
                    .hoverBackground(
                        new DynamicDrawable(
                            () -> clientSnapshot != null && clientSnapshot.managementOnly
                                ? GtswnMuiTheme.BUTTON_DISABLED
                                : GtswnMuiTheme.BUTTON_HOVER))
                    .child(
                        new ParentWidget<>().size(76, 14)
                            .child(
                                new Rectangle().color(0xff000000 | QuantumNetworkColor.rgb(i))
                                    .asWidget()
                                    .pos(3, 3)
                                    .size(7, 7))
                            .child(
                                IKey.dynamic(
                                    () -> (QuantumNetworkColor.get(player.getHeldItem()) == color ? "* " : "")
                                        + tr("gtswn.color." + QuantumNetworkColor.name(color)))
                                    .color(GtswnMuiTheme.TEXT_BODY)
                                    .asWidget()
                                    .scale(0.8f)
                                    .pos(13, 0)
                                    .size(60, 14)
                                    .alignment(Alignment.CenterLeft)))
                    .tooltip(
                        t -> t.addLine(IKey.lang("gtswn.color." + QuantumNetworkColor.name(color)))
                            .addLine(IKey.lang("gtswn.gui.quantum.color_scope")))
                    .onMousePressed(mouse -> {
                        if (mouse != 0 || clientSnapshot != null && clientSnapshot.managementOnly) return false;
                        GTSWNPacketHandler.NETWORK.sendToServer(new PacketSetQuantumNetworkColor(color));
                        return true;
                    }));
        }
        body.child(
            new ButtonWidget<>().name("quantum.safety")
                .pos(260, 120)
                .size(154, 16)
                .background(
                    new DynamicDrawable(
                        () -> canToggleSafety(clientSnapshot) ? GtswnMuiTheme.BUTTON : GtswnMuiTheme.BUTTON_DISABLED))
                .hoverBackground(
                    new DynamicDrawable(
                        () -> canToggleSafety(clientSnapshot) ? GtswnMuiTheme.BUTTON_HOVER
                            : GtswnMuiTheme.BUTTON_DISABLED))
                .overlay(
                    IKey.dynamic(
                        () -> tr("gtswn.gui.quantum.safety") + ": "
                            + (clientSnapshot == null ? "..." : clientSnapshot.networkSafetyEnabled ? "ON" : "OFF")))
                .tooltip(
                    t -> t.addLine(IKey.lang("gtswn.gui.quantum.safety_scope"))
                        .addLine(IKey.dynamic(() -> tr(safetyReason(clientSnapshot)))))
                .onMousePressed(mouse -> {
                    QuantumNetworkData snapshot = clientSnapshot;
                    int[] anchor = ItemNetworkQuantumTerminal.getAnchor(player.getHeldItem());
                    if (mouse != 0 || !canToggleSafety(snapshot)
                        || anchor == null
                        || snapshot.anchorDim != anchor[0]
                        || snapshot.anchorX != anchor[1]
                        || snapshot.anchorY != anchor[2]
                        || snapshot.anchorZ != anchor[3]) return false;
                    GTSWNPacketHandler.NETWORK.sendToServer(
                        new PacketSetQuantumNetworkSafety(
                            player.inventory.currentItem,
                            snapshot,
                            !snapshot.networkSafetyEnabled));
                    receiveData(null);
                    return true;
                }));
        return body;
    }

    private static String detailsLine(EntityPlayer player, int line) {
        QuantumNetworkData data = clientSnapshot;
        ItemStack held = player.getHeldItem();
        if (held == null || !ItemNetworkQuantumTerminal.isBound(held))
            return line == 0 ? tr("gtswn.gui.quantum.unbound") : "";
        if (data == null) return line == 0 ? "..." : "";
        if (line == 0) return data.anchorX + ", " + data.anchorY + ", " + data.anchorZ + " @ " + data.anchorDim;
        if (data.managementOnly) return line == 1 ? tr("gtswn.gui.quantum.safety_management_only") : "";
        switch (line) {
            case 1:
                return tr("gtswn.gui.quantum.node_count") + ": " + (data.online ? data.quantumNodeCount : 0);
            case 2:
                return tr("gtswn.gui.quantum.incorporation_count") + ": " + (data.online ? data.incorporationCount : 0);
            case 3:
                int percentage = data.online && data.totalChannels > 0
                    ? (int) (data.usedChannels * 100L / data.totalChannels)
                    : 0;
                return tr("gtswn.gui.quantum.channels") + ": "
                    + (data.online ? data.usedChannels : 0)
                    + " / "
                    + (data.online && data.channelsInfinite ? "\u221e"
                        : (data.online ? data.totalChannels : 0) + " (" + percentage + "%)");
            case 4:
                return tr("gtswn.gui.quantum.incorporation_channels") + ": "
                    + (data.online ? data.incorporationChannels : 0);
            case 5:
                return tr("gtswn.gui.quantum.color_current") + ": "
                    + tr("gtswn.color." + QuantumNetworkColor.name(QuantumNetworkColor.get(held)));
            default:
                return data.online ? "" : tr("gtswn.gui.quantum.offline");
        }
    }

    private ParentWidget<?> linkDetails(EntityPlayer player, ContainerTerminal container) {
        ParentWidget<?> body = new ParentWidget<>().size(426, 140)
            .background(GtswnMuiTheme.LIST_PANEL)
            .name("link.details");
        body.child(label(() -> tr("gtswn.gui.terminal.player") + ": " + player.getCommandSenderName(), 10, 12, 404));
        body.child(label(() -> tr("gtswn.gui.terminal.dimension") + ": " + player.dimension, 10, 34, 404));
        body.child(label(() -> tr("gtswn.gui.terminal.level") + ": " + player.experienceLevel, 10, 56, 404));
        body.child(label(() -> tr("gtswn.gui.terminal.team") + ": " + container.team(), 10, 78, 404));
        body.child(label(() -> "EU: " + container.balance(), 10, 104, 404));
        return body;
    }

    private ParentWidget<?> rename(EntityPlayer player, ContainerTerminal container, PagedWidget.Controller tabs,
        PagedWidget<?> pages) {
        ParentWidget<?> body = new ParentWidget<>().size(426, 140)
            .background(GtswnMuiTheme.LIST_PANEL)
            .name("quantum.rename.page");
        StringValue draft = new StringValue(ContainerTerminal.customName(player.getHeldItem()));
        String[] expected = { ContainerTerminal.customName(player.getHeldItem()) };
        Runnable[] submit = { () -> {} };
        Runnable[] cancel = { () -> {} };
        TextFieldWidget field = new TextFieldWidget() {

            @Override
            public Interactable.Result onKeyPressed(char character, int keyCode) {
                if (isFocused() && (keyCode == 28 || keyCode == 156)) {
                    submit[0].run();
                    return Interactable.Result.SUCCESS;
                }
                if (isFocused() && keyCode == 1) {
                    cancel[0].run();
                    return Interactable.Result.SUCCESS;
                }
                return super.onKeyPressed(character, keyCode);
            }
        }.value(draft)
            .setMaxLength(TerminalNameRules.MAX_LENGTH)
            .pos(12, 36)
            .size(400, 20)
            .name("quantum.rename.field");
        pages.onPageChange(page -> {
            if (page == 2) {
                expected[0] = ContainerTerminal.customName(player.getHeldItem());
                draft.setStringValue(expected[0]);
                field.setText(expected[0]);
                field.getContext()
                    .focus(field);
            } else if (field.isFocused()) field.getContext()
                .removeFocus();
        });
        submit[0] = () -> {
            if (container.renameToken() == 0) return;
            String name = field.getText();
            if (TerminalNameRules.normalize(name) == null) return;
            GTSWNPacketHandler.NETWORK.sendToServer(
                new PacketRenameQuantumTerminal(
                    container.windowId,
                    container.heldIndex(),
                    container.renameToken(),
                    expected[0],
                    name));
            tabs.setPage(0);
        };
        cancel[0] = () -> {
            draft.setStringValue(expected[0]);
            field.setText(expected[0]);
            tabs.setPage(0);
        };
        body.child(
            IKey.lang("gtswn.gui.quantum.rename_title")
                .color(GtswnMuiTheme.TEXT_TITLE)
                .asWidget()
                .pos(12, 12));
        body.child(field);
        body.child(
            IKey.lang("gtswn.gui.quantum.rename_hint")
                .color(GtswnMuiTheme.TEXT_MUTED)
                .asWidget()
                .pos(12, 66));
        body.child(
            new ButtonWidget<>().name("quantum.rename.confirm")
                .pos(246, 100)
                .size(80, 22)
                .background(GtswnMuiTheme.BUTTON)
                .hoverBackground(GtswnMuiTheme.BUTTON_HOVER)
                .overlay(IKey.lang("gui.done"))
                .onMousePressed(mouse -> {
                    if (mouse != 0) return false;
                    submit[0].run();
                    return true;
                }));
        body.child(
            new ButtonWidget<>().name("quantum.rename.cancel")
                .pos(332, 100)
                .size(80, 22)
                .background(GtswnMuiTheme.BUTTON)
                .overlay(IKey.lang("gui.cancel"))
                .onMousePressed(mouse -> {
                    if (mouse != 0) return false;
                    cancel[0].run();
                    return true;
                }));
        return body;
    }

    private ParentWidget<?> effects(EntityPlayer player) {
        ParentWidget<?> body = new ParentWidget<>().size(426, 140)
            .background(GtswnMuiTheme.LIST_PANEL)
            .name(quantum ? "quantum.effects" : "link.effects");
        for (int id = 0; id < 8; id++) {
            if (quantum && (id == 4 || id >= 6)) continue;
            final int setting = id;
            int position = !quantum && id >= 5 ? id == 5 ? 7 : id - 1 : id;
            int rows = quantum ? 3 : 4;
            body.child(
                new ButtonWidget<>().name((quantum ? "quantum" : "link") + ".effects." + id)
                    .pos(8 + (position / rows) * 208, 7 + (position % rows) * (quantum ? 42 : 32))
                    .size(202, quantum ? 34 : 26)
                    .background(GtswnMuiTheme.BUTTON)
                    .hoverBackground(GtswnMuiTheme.BUTTON_HOVER)
                    .overlay(IKey.dynamic(() -> effectLabel(quantum, setting)))
                    .tooltip(t -> t.addLine(IKey.lang("gtswn.gui.terminal.numeric_hint")))
                    .onMousePressed(mouse -> {
                        if (mouse != 0 && mouse != 1) return false;
                        if (mouse == 1 && !(setting >= 1 && setting <= 3 || setting == 7 && !quantum)) return false;
                        if (!changeEffect(quantum, setting, mouse == 1))
                            player.addChatMessage(new ChatComponentTranslation("gtswn.command.effects.save_failed"));
                        return true;
                    }));
        }
        return body;
    }

    static String effectLabel(boolean quantum, int setting) {
        String[] keys = { "enabled", "density", "size", "distance", "material", "reset", "voltage_colors",
            "voltage_ratio" };
        Object[] values = { (quantum ? Config.quantumParticlesEnabled : Config.tapParticlesEnabled) ? "ON" : "OFF",
            (quantum ? Config.quantumParticleDensityPercent : Config.tapParticleDensityPercent) + "%",
            (quantum ? Config.quantumParticleSizePercent : Config.tapParticleSizePercent) + "%",
            quantum ? Config.quantumRenderDistance : Config.tapRenderDistance, Config.tapMaterial, "",
            Config.tapVoltageColorsEnabled ? "ON" : "OFF", Config.tapVoltageColorPercent + "%" };
        return tr("gtswn.gui.terminal." + keys[setting]) + (setting == 5 ? "" : ": " + values[setting]);
    }

    public static boolean changeEffect(boolean quantum, int setting, boolean decrease) {
        if (setting == 5) return Config.resetParticleConfiguration(!quantum);
        switch (setting) {
            case 0:
                if (quantum) Config.quantumParticlesEnabled = !Config.quantumParticlesEnabled;
                else Config.tapParticlesEnabled = !Config.tapParticlesEnabled;
                break;
            case 1:
                int density = quantum ? Config.quantumParticleDensityPercent : Config.tapParticleDensityPercent;
                density = decrease ? Math.max(0, density - 25) : density >= 200 ? 0 : Math.min(200, density + 25);
                if (quantum) Config.quantumParticleDensityPercent = density;
                else Config.tapParticleDensityPercent = density;
                break;
            case 2:
                int size = quantum ? Config.quantumParticleSizePercent : Config.tapParticleSizePercent;
                size = decrease ? Math.max(10, size - 10) : size >= 400 ? 10 : Math.min(400, size + 10);
                if (quantum) Config.quantumParticleSizePercent = size;
                else Config.tapParticleSizePercent = size;
                break;
            case 3:
                if (quantum)
                    Config.quantumRenderDistance = Config.adjustRenderDistance(Config.quantumRenderDistance, decrease);
                else Config.tapRenderDistance = Config.adjustRenderDistance(Config.tapRenderDistance, decrease);
                break;
            case 4:
                if (quantum) return true;
                String[] materials = { "old", "oldplus", "low", "medium", "high" };
                Config.tapMaterial = materials[(java.util.Arrays.asList(materials)
                    .indexOf(Config.tapMaterial) + 1) % materials.length];
                break;
            case 6:
                if (quantum) return true;
                Config.tapVoltageColorsEnabled = !Config.tapVoltageColorsEnabled;
                break;
            case 7:
                if (quantum) return true;
                Config.tapVoltageColorPercent = decrease
                    ? Config.tapVoltageColorPercent <= 25 ? 100 : Config.tapVoltageColorPercent - 25
                    : Config.tapVoltageColorPercent >= 100 ? 25 : Config.tapVoltageColorPercent + 25;
                break;
            default:
                return true;
        }
        return Config.saveParticleConfiguration();
    }

    public static boolean canToggleSafety(QuantumNetworkData data) {
        return data != null && data.online
            && data.globalSafetyEnabled
            && data.canChangeNetworkSafety
            && !data.networkSafetyIdentity.isEmpty()
            && data.networkSafetyRequestToken != 0;
    }

    public static String safetyReason(QuantumNetworkData data) {
        if (data == null) return "gtswn.gui.quantum.safety_waiting";
        if (!data.globalSafetyEnabled) return "gtswn.gui.quantum.safety_global_off";
        if (!data.online) return "gtswn.gui.quantum.safety_offline";
        if (!data.canChangeNetworkSafety) return "gtswn.gui.quantum.safety_permission";
        if (data.networkSafetyIdentity.isEmpty() || data.networkSafetyRequestToken == 0)
            return "gtswn.gui.quantum.safety_waiting";
        return "gtswn.gui.quantum.safety_authorized";
    }

    private static com.cleanroommc.modularui.widgets.TextWidget<?> label(Supplier<String> text, int x, int y,
        int width) {
        return IKey.dynamic(text)
            .color(GtswnMuiTheme.TEXT_BODY)
            .asWidget()
            .pos(x, y)
            .size(width, 14)
            .alignment(Alignment.CenterLeft);
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }
}
