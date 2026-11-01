package com.miaokatze.gtswn.common.assembly;

import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraftforge.fluids.FluidStack;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.value.sync.BooleanSyncValue;
import com.cleanroommc.modularui.value.sync.InteractionSyncHandler;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.layout.Grid;
import com.cleanroommc.modularui.widgets.slot.ModularSlot;
import com.miaokatze.gtswn.common.assembly.pattern.OrderedPatternLayout;
import com.miaokatze.gtswn.common.assembly.pattern.PatternValidationException;

import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.modularui2.GTGuiTextures;
import gregtech.api.modularui2.GTGuis;
import gregtech.common.gui.modularui.hatch.base.MTEHatchBaseGui;
import gregtech.common.gui.modularui.util.PatternSlot;

/** Uses the native pattern hatch frame, 4x9 bank and corner controls. Every member opens the master tile. */
public final class MatrixAssemblyUi extends MTEHatchBaseGui<MTEHatch> {

    private final MatrixAssemblyMember member;
    private BooleanSyncValue editable;
    private StringSyncValue status;
    private int previewSlot;

    public MatrixAssemblyUi(MatrixAssemblyMember member) {
        super((MTEHatch) member);
        this.member = member;
    }

    static String[] description(boolean fluid) {
        return new String[] { tr("gtswn.matrix.description." + (fluid ? "fluid" : "item")),
            tr("gtswn.matrix.description.column"), tr("gtswn.matrix.description.blocking"),
            tr("gtswn.matrix.description.pattern"), tr("gtswn.matrix.description.native") };
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    @Override
    protected int getBasePanelHeight() {
        return super.getBasePanelHeight() + SLOT_SIZE + 14;
    }

    @Override
    protected void registerSyncValues(PanelSyncManager sync) {
        super.registerSyncValues(sync);
        editable = new BooleanSyncValue(
            () -> member.matrixPort()
                .group() != null && member.matrixPort()
                    .group()
                    .editable());
        status = new StringSyncValue(() -> {
            OrderedAssemblyGroup group = member.matrixPort()
                .group();
            if (member.matrixPort()
                .quarantined())
                return member.matrixPort()
                    .fault();
            if (group == null) return "minimum_five";
            if (!group.reason()
                .isEmpty()) return group.reason();
            return group.stage()
                .name()
                .toLowerCase(java.util.Locale.ROOT);
        });
        sync.syncValue("matrix_editable", editable);
        sync.syncValue("matrix_status", status);
    }

    @Override
    protected ParentWidget<?> createContentSection(ModularPanel panel, PanelSyncManager sync) {
        sync.registerSlotGroup("matrix_patterns", 4);
        Grid slots = new Grid().coverChildren()
            .gridOfWidthHeight(
                9,
                4,
                (x, y, index) -> new PatternSlot().slot(
                    new ModularSlot(
                        member.matrixPort()
                            .patterns(),
                        index).slotGroup("matrix_patterns"))
                    .setEnabledIf(widget -> editable.getBoolValue()));
        return super.createContentSection(panel, sync).child(
            Flow.column()
                .coverChildren()
                .child(slots)
                .child(
                    IKey.dynamic(() -> tr("gtswn.matrix.reason." + status.getStringValue()))
                        .asWidget()
                        .width(160)
                        .height(14)
                        .scale(.7f)));
    }

    @Override
    protected Flow createBottomLeftCornerFlow(ModularPanel panel, PanelSyncManager sync) {
        InteractionSyncHandler refund = new InteractionSyncHandler().setOnMousePressed(data -> {
            if (!data.isClient() && data.mouseButton == 0
                && member.matrixPort()
                    .group() != null)
                member.matrixPort()
                    .group()
                    .refund();
        });
        IPanelHandler overview = sync.syncedPanel("matrix_overview", true, (manager, handler) -> overview(manager));
        return super.createBottomLeftCornerFlow(panel, sync).child(
            new ButtonWidget<>().size(18)
                .overlay(GTGuiTextures.OVERLAY_BUTTON_EXPORT)
                .syncHandler(refund)
                .addTooltipLine(tr("gtswn.matrix.refund")))
            .child(
                new ButtonWidget<>().size(18)
                    .overlay(GTGuiTextures.OVERLAY_BUTTON_PLUS_LARGE)
                    .onMousePressed(button -> {
                        overview.openPanel();
                        return true;
                    })
                    .addTooltipLine(tr("gtswn.matrix.overview")))
            .child(
                new ButtonWidget<>().size(18)
                    .overlay(GTGuiTextures.OVERLAY_BUTTON_X2)
                    .setEnabledIf(widget -> false)
                    .addTooltipLine(tr("gtswn.matrix.fixed_blocking")));
    }

    private ModularPanel overview(PanelSyncManager sync) {
        StringSyncValue text = new StringSyncValue(this::overviewText);
        StringSyncValue selected = new StringSyncValue(() -> {
            OrderedAssemblyGroup group = member.matrixPort()
                .group();
            return label("preview_slot") + " "
                + (selectedSlot(group) + 1)
                + "/"
                + MatrixPatternInventory.SIZE
                + " - "
                + label(activePattern(group) ? "active_pattern" : "pattern_preview");
        });
        BooleanSyncValue browsing = new BooleanSyncValue(() -> {
            OrderedAssemblyGroup group = member.matrixPort()
                .group();
            return group == null || group.stage() == OrderedAssemblyGroup.Stage.IDLE;
        });
        sync.syncValue("matrix_overview_text", text);
        sync.syncValue("matrix_preview_slot", selected);
        sync.syncValue("matrix_preview_browsing", browsing);
        return GTGuis.createPopUpPanel("matrix_overview")
            .size(360, 300)
            .child(
                IKey.lang("gtswn.matrix.overview")
                    .asWidget()
                    .pos(8, 8)
                    .width(336))
            .child(previewButton(-1, 8, browsing))
            .child(
                IKey.dynamic(() -> translateTokens(selected.getStringValue()))
                    .asWidget()
                    .pos(30, 29)
                    .width(300)
                    .height(16)
                    .scale(.75f))
            .child(previewButton(1, 334, browsing))
            .child(
                new ListWidget<>().pos(8, 52)
                    .size(344, 240)
                    .child(
                        IKey.dynamic(() -> translateTokens(text.getStringValue()))
                            .asWidget()
                            .width(330)
                            .scale(.75f)));
    }

    private ButtonWidget<?> previewButton(int direction, int x, BooleanSyncValue browsing) {
        InteractionSyncHandler action = new InteractionSyncHandler().setOnMousePressed(mouse -> {
            if (mouse.isClient() || mouse.mouseButton != 0) return;
            OrderedAssemblyGroup group = member.matrixPort()
                .group();
            if (group == null || group.stage() == OrderedAssemblyGroup.Stage.IDLE)
                previewSlot = Math.floorMod(previewSlot + direction, MatrixPatternInventory.SIZE);
        });
        return new ButtonWidget<>().size(18)
            .pos(x, 27)
            .overlay(IKey.str(direction < 0 ? "<" : ">"))
            .syncHandler(action)
            .setEnabledIf(widget -> browsing.getBoolValue())
            .addTooltipLine(tr("gtswn.matrix." + (direction < 0 ? "previous_pattern" : "next_pattern")));
    }

    private static boolean activePattern(OrderedAssemblyGroup group) {
        return group != null && group.stage() != OrderedAssemblyGroup.Stage.IDLE && group.patternSlot() >= 0;
    }

    private int selectedSlot(OrderedAssemblyGroup group) {
        return activePattern(group) ? group.patternSlot() : previewSlot;
    }

    private String overviewText() {
        OrderedAssemblyGroup group = member.matrixPort()
            .group();
        MatrixAssemblyMember owner = group == null ? member : group.master();
        boolean active = activePattern(group);
        StringBuilder text = new StringBuilder();
        if (group != null) {
            int[] position = group.diagnosticControllerPosition();
            text.append(label("controller"))
                .append(' ')
                .append(label("dimension"))
                .append(' ')
                .append(position[0])
                .append(" @ ")
                .append(position[1])
                .append(", ")
                .append(position[2])
                .append(", ")
                .append(position[3])
                .append('\n')
                .append(
                    reason(
                        group.stage()
                            .name()
                            .toLowerCase(java.util.Locale.ROOT)))
                .append(" / ")
                .append(label("epoch"))
                .append(' ')
                .append(group.epoch())
                .append('\n');
            if (active) {
                String key = group.recipeKey();
                text.append(label("recipe_key"))
                    .append(' ')
                    .append(key.length() > 19 ? key.substring(0, 19) : key)
                    .append('\n');
            }
        }
        text.append(label("positions_one_based"))
            .append('\n');
        text.append(label(active ? "table_actual" : "table_projected"))
            .append('\n');
        OrderedPatternLayout layout = null;
        int selected = selectedSlot(group);
        if (selected < 0 || selected >= MatrixPatternInventory.SIZE) {
            text.append(reason("invalid_saved_data"))
                .append('\n');
        } else {
            ItemStack pattern = owner.matrixPort()
                .patterns()
                .getStackInSlot(selected);
            try {
                if (pattern == null) text.append(label("empty_pattern_slot"))
                    .append('\n');
                else {
                    layout = OrderedPatternLayout.decode(
                        pattern,
                        owner.getBaseMetaTileEntity()
                            .getWorld());
                    String previewReason = group == null ? "minimum_five" : group.previewReason(layout);
                    text.append(previewReason.isEmpty() ? label("preview_valid") : reason(previewReason))
                        .append('\n');
                }
            } catch (PatternValidationException ex) {
                text.append(
                    reason(
                        ex.reason()
                            .name()
                            .toLowerCase(java.util.Locale.ROOT)));
                if (ex.rawIndex() >= 0) text.append(" raw ")
                    .append(ex.rawIndex() + 1);
                text.append('\n');
            }
        }
        if (layout != null) {
            for (OrderedPatternLayout.Input input : layout.rawInputs()) {
                if (input.kind() == OrderedPatternLayout.Kind.EMPTY) text.append("raw ")
                    .append(input.rawIndex() + 1)
                    .append(" - ")
                    .append(label("empty_raw_slot"))
                    .append('\n');
            }
        }
        if (group != null) {
            for (int i = 0; i < group.itemMembers()
                .size(); i++) appendMember(text, group, layout, false, i, active);
            for (int i = 0; i < group.fluidMembers()
                .size(); i++) appendMember(text, group, layout, true, i, active);
            text.append(label("native_progress"))
                .append(' ')
                .append(
                    group.session()
                        .controller().mProgresstime)
                .append('/')
                .append(
                    group.session()
                        .controller().mMaxProgresstime)
                .append('\n');
            text.append(
                reason(
                    group.reason()
                        .isEmpty() ? "idle" : group.reason()));
        } else {
            text.append(reason("minimum_five"));
        }
        return text.toString();
    }

    private static void appendMember(StringBuilder text, OrderedAssemblyGroup group, OrderedPatternLayout layout,
        boolean fluid, int ordinal, boolean active) {
        MatrixAssemblyMember physical = (fluid ? group.fluidMembers() : group.itemMembers()).get(ordinal);
        java.util.List<OrderedPatternLayout.Input> inputs = layout == null ? java.util.Collections.emptyList()
            : fluid ? layout.fluids() : layout.items();
        OrderedPatternLayout.Input input = ordinal < inputs.size() ? inputs.get(ordinal) : null;
        ItemStack item = physical.matrixItem();
        FluidStack liquid = physical.matrixFluidStack();
        int[] position = group.diagnosticMemberPosition(fluid, ordinal);
        String kind = fluid ? "F" : "I";
        text.append('\n')
            .append(label(fluid ? "fluid_member" : "item_member"))
            .append(' ')
            .append(ordinal + 1)
            .append(" @ ")
            .append(position[0])
            .append(", ")
            .append(position[1])
            .append(", ")
            .append(position[2])
            .append('\n');
        int table = input == null ? -1 : active ? group.diagnosticTableIndex(fluid, ordinal) : input.transportIndex();
        text.append("raw ")
            .append(input == null ? "-" : input.rawIndex() + 1)
            .append(" | table ")
            .append(table < 0 ? "-" : table + 1)
            .append(" | ")
            .append(label("native_position"))
            .append(' ')
            .append(kind)
            .append(ordinal + 1)
            .append('\n');
        text.append(label("required"))
            .append(' ')
            .append(input == null ? "-" : input.amount())
            .append(" | ")
            .append(label("cached"))
            .append(' ')
            .append(fluid ? liquid == null ? 0 : liquid.amount : item == null ? 0 : item.stackSize)
            .append(" | ")
            .append(label("consumed"))
            .append(' ');
        if (active && group.diagnosticConsumptionObserved()) text.append(group.diagnosticConsumed(fluid, ordinal));
        else text.append('-');
        if (fluid) text.append(" mB");
        text.append('\n');
        if (input != null) {
            if (fluid) text.append(
                token(
                    input.fluidStack()
                        .getUnlocalizedName()));
            else {
                ItemStack material = input.itemStack();
                text.append(
                    material.hasDisplayName() ? material.getDisplayName()
                        : token(material.getUnlocalizedName() + ".name"));
            }
            text.append('\n');
        }
    }

    private static String label(String name) {
        return token("gtswn.matrix." + name);
    }

    private static String reason(String name) {
        return token("gtswn.matrix.reason." + name);
    }

    private static String token(String key) {
        return "[[" + key + "]]";
    }

    /** Only keys and facts are synchronized; rendering translates in the viewer's language. */
    private static String translateTokens(String encoded) {
        StringBuilder translated = new StringBuilder();
        int cursor = 0;
        for (int start; (start = encoded.indexOf("[[", cursor)) >= 0;) {
            int end = encoded.indexOf("]]", start + 2);
            if (end < 0) break;
            translated.append(encoded, cursor, start)
                .append(tr(encoded.substring(start + 2, end)));
            cursor = end + 2;
        }
        return translated.append(encoded, cursor, encoded.length())
            .toString();
    }
}
