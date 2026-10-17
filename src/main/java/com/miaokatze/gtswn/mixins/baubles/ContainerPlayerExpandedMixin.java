package com.miaokatze.gtswn.mixins.baubles;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.miaokatze.gtswn.common.baubles.TerminalBaubleLayout;

import baubles.api.expanded.BaubleExpandedSlots;
import baubles.common.BaublesConfig;
import baubles.common.container.SlotBauble;

/** Reorders only the terminal cells on screen, never the slots or their stored inventory indices. */
@Pseudo
@Mixin(targets = "baubles.common.container.ContainerPlayerExpanded", remap = false)
public abstract class ContainerPlayerExpandedMixin {

    @Shadow
    public abstract int getBaubleSlotCount();

    @Shadow
    public abstract SlotBauble getBaubleSlot(int ordinal);

    @Shadow
    public abstract int getColumns();

    @Shadow
    public abstract boolean canScroll();

    @Unique
    private float gtswn$scroll;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void gtswn$layoutTerminals(InventoryPlayer inventory, boolean local, EntityPlayer player, CallbackInfo ci) {
        gtswn$applyTerminalLayout();
    }

    @Inject(method = "scrollTo", at = @At("RETURN"), remap = false)
    private void gtswn$scrollTerminals(float scroll, CallbackInfo ci) {
        gtswn$scroll = scroll;
        gtswn$applyTerminalLayout();
    }

    @Unique
    private void gtswn$applyTerminalLayout() {
        int count = getBaubleSlotCount(), columns = Math.max(1, getColumns());
        int[] categories = new int[count];
        for (int i = 0; i < count; i++) categories[i] = TerminalBaubleLayout.category(getBaubleSlot(i).getSlotType());
        boolean oldLayout = BaublesConfig.useOldGuiRendering;
        int[] display = TerminalBaubleLayout.displayOrdinals(categories, columns, oldLayout);
        int scrolled = TerminalBaubleLayout
            .scrollRows(BaubleExpandedSlots.slotsCurrentlyUsed(), columns, gtswn$scroll, canScroll());
        for (int i = 0; i < count; i++) {
            if (categories[i] < 0) continue;
            SlotBauble slot = getBaubleSlot(i);
            int cell = display[i];
            if (oldLayout) {
                slot.xDisplayPosition = 80 + 18 * (cell / 4);
                slot.yDisplayPosition = 8 + 18 * (cell % 4);
            } else {
                slot.xDisplayPosition = -18 - 18 * (cell % columns);
                int y = 12 + 18 * (cell / columns - scrolled);
                slot.yDisplayPosition = canScroll() && (y < 12 || y > 144) ? -2000 : y;
            }
        }
    }
}
