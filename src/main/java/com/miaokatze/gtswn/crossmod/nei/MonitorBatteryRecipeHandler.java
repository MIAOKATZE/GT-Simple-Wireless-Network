package com.miaokatze.gtswn.crossmod.nei;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;
import net.minecraftforge.oredict.OreDictionary;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.charging.MonitorBattery;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.recipe.MonitorBatteryRecipe;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.ShapelessRecipeHandler;
import gregtech.api.enums.ItemList;
import ic2.api.item.IElectricItem;

/** Two display-only examples, using NEI's native workbench layout and the single monitor item. */
public final class MonitorBatteryRecipeHandler extends ShapelessRecipeHandler {

    private static final String ID = "gtswn.monitor_battery";
    private static final String LANG = "gtswn.nei.monitor_battery.";
    private static final Gui SLOT_RENDERER = new Gui();

    @Override
    public String getRecipeName() {
        return text("title");
    }

    @Override
    public String getOverlayIdentifier() {
        return ID;
    }

    @Override
    public void loadTransferRects() {
        transferRects.add(new RecipeTransferRect(new Rectangle(84, 23, 24, 18), ID));
    }

    @Override
    public int getRecipeHeight(int recipe) {
        return 86;
    }

    @Override
    public void loadCraftingRecipes(String outputId, Object... results) {
        if (ID.equals(outputId) || "all".equals(outputId)) addExamples(true, true);
        else if ("item".equals(outputId) && results.length > 0 && results[0] instanceof ItemStack)
            loadCraftingRecipes((ItemStack) results[0]);
    }

    @Override
    public void loadCraftingRecipes(ItemStack result) {
        if (isMonitor(result)) addExamples(true, true);
        else if (MonitorBattery.isBattery(result)) addExamples(false, true);
    }

    @Override
    public void loadUsageRecipes(ItemStack ingredient) {
        if (isMonitor(ingredient)) addExamples(true, true);
        else if (MonitorBattery.isBattery(ingredient)) addExamples(true, false);
        else if (MonitorBatteryRecipe.isCrowbar(ingredient)) addExamples(false, true);
    }

    private void addExamples(boolean install, boolean remove) {
        ItemStack bare = GTSWNItemList.Portable_Wireless_Network_Monitor.get(1);
        ItemStack battery = ItemList.Battery_RE_LV_Lithium.get(1);
        MonitorBattery.manager(battery)
            .discharge(battery, Double.MAX_VALUE, Integer.MAX_VALUE, true, false, false);
        MonitorBattery.manager(battery)
            .charge(
                battery,
                ((IElectricItem) battery.getItem()).getMaxCharge(battery) / 2,
                Integer.MAX_VALUE,
                true,
                false);
        ItemStack installed = MonitorBattery.install(bare, battery);
        if (install) arecipes.add(new BatteryExample("install", bare, battery, installed, null));
        if (!remove) return;
        for (ItemStack tool : OreDictionary.getOres("craftingToolCrowbar")) {
            if (!MonitorBatteryRecipe.isCrowbar(tool)) continue;
            arecipes.add(new BatteryExample("remove", installed, tool.copy(), bare, MonitorBattery.remove(installed)));
            break;
        }
    }

    private static boolean isMonitor(ItemStack stack) {
        return stack != null && stack.getItem() instanceof PortableWirelessNetworkMonitor;
    }

    @Override
    public void drawBackground(int recipe) {
        super.drawBackground(recipe);
        if (((BatteryExample) arecipes.get(recipe)).returned != null) {
            // Reuse a native player-inventory slot below the untouched workbench grid and main output.
            Minecraft.getMinecraft()
                .getTextureManager()
                .bindTexture(new ResourceLocation(getGuiTexture()));
            SLOT_RENDERER.drawTexturedModalRect(118, 65, 7, 83, 18, 18);
        }
    }

    @Override
    public void drawExtras(int recipe) {
        BatteryExample example = (BatteryExample) arecipes.get(recipe);
        Minecraft.getMinecraft().fontRenderer.drawString(text(example.operation), 25, 60, 0x404040);
    }

    private static String text(String suffix) {
        return StatCollector.translateToLocal(LANG + suffix);
    }

    private final class BatteryExample extends CachedShapelessRecipe {

        private final String operation;
        private final PositionedStack returned;

        private BatteryExample(String operation, ItemStack monitor, ItemStack input, ItemStack output,
            ItemStack battery) {
            super(Arrays.asList(monitor, input), output);
            this.operation = operation;
            ingredients.get(1)
                .setTooltip(Collections.singletonList(text("remove".equals(operation) ? "tool" : "charge")));
            result.setTooltip(Collections.singletonList(text("charge")));
            returned = battery == null ? null : new PositionedStack(battery, 119, 66);
            if (returned != null) returned.setTooltip(Arrays.asList(text("return"), text("charge")));
        }

        @Override
        public List<PositionedStack> getOtherStacks() {
            return returned == null ? Collections.emptyList() : Collections.singletonList(returned);
        }
    }
}
