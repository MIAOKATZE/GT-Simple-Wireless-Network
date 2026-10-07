package com.miaokatze.gtswn.crossmod.nei;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraftforge.oredict.OreDictionary;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.charging.MonitorBattery;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.recipe.MonitorBatteryRecipe;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.TemplateRecipeHandler;
import ic2.api.item.IElectricItem;

/** Display-only examples for the dynamic recipe; never adds recipes or monitor item-list variants. */
public final class MonitorBatteryRecipeHandler extends TemplateRecipeHandler {

    private static final String ID = "gtswn.monitor_battery";
    private static final String LANG = "gtswn.nei.monitor_battery.";

    @Override
    public String getRecipeName() {
        return text("title");
    }

    @Override
    public String getOverlayIdentifier() {
        return ID;
    }

    @Override
    public String getGuiTexture() {
        return "minecraft:textures/gui/container/crafting_table.png";
    }

    @Override
    public int getRecipeHeight(int recipe) {
        return 96;
    }

    @Override
    public int recipiesPerPage() {
        return 1;
    }

    @Override
    public void loadCraftingRecipes(String outputId, Object... results) {
        if (ID.equals(outputId) || "all".equals(outputId)) addExamples(null, false);
        else super.loadCraftingRecipes(outputId, results);
    }

    @Override
    public void loadCraftingRecipes(ItemStack result) {
        if (isMonitor(result)) addExamples(null, false);
        else if (MonitorBattery.isBattery(result)) addExamples(result, false);
    }

    @Override
    public void loadUsageRecipes(ItemStack ingredient) {
        if (isMonitor(ingredient)) addExamples(null, false);
        else if (MonitorBattery.isBattery(ingredient)) addExamples(ingredient, true);
        else if (MonitorBatteryRecipe.isCrowbar(ingredient)) addExamples(ingredient, true);
    }

    private void addExamples(ItemStack filter, boolean usage) {
        List<ItemStack> batteries = batteries();
        List<ItemStack> crowbars = new ArrayList<>();
        for (ItemStack tool : OreDictionary.getOres("craftingToolCrowbar")) {
            if (MonitorBatteryRecipe.isCrowbar(tool)) crowbars.add(tool.copy());
        }
        // A lookup with a particular battery/tool also covers eligible items absent from ore registrations.
        if (filter != null && MonitorBattery.isBattery(filter)) addDistinct(batteries, filter);
        if (filter != null && MonitorBatteryRecipe.isCrowbar(filter)) addDistinct(crowbars, filter);
        ItemStack bare = GTSWNItemList.Portable_Wireless_Network_Monitor.get(1);
        for (ItemStack battery : batteries) {
            ItemStack charged = chargedExample(battery);
            ItemStack installed = MonitorBattery.install(bare, charged);
            List<BatteryExample> examples = new ArrayList<>();
            examples.add(new BatteryExample("install", bare, charged, installed, null));
            if (!crowbars.isEmpty()) {
                examples.add(new BatteryExample("remove", installed, crowbars, bare, MonitorBattery.remove(installed)));
            }
            if (!batteries.isEmpty()) {
                ItemStack old = chargedExample(batteries.get((batteries.indexOf(battery) + 1) % batteries.size()));
                ItemStack before = MonitorBattery.install(bare, old);
                examples.add(new BatteryExample("replace", before, charged, installed, MonitorBattery.remove(before)));
            }
            for (BatteryExample example : examples) {
                if (filter == null || (usage ? example.contains(example.ingredients, filter)
                    : example.contains(example.getOtherStacks(), filter))) arecipes.add(example);
            }
        }
    }

    private static List<ItemStack> batteries() {
        List<ItemStack> result = new ArrayList<>();
        for (String name : OreDictionary.getOreNames()) {
            if (!name.startsWith("battery")) continue;
            for (ItemStack stack : OreDictionary.getOres(name)) {
                if (MonitorBattery.isBattery(stack)) addDistinct(result, stack);
            }
        }
        return result;
    }

    private static void addDistinct(List<ItemStack> stacks, ItemStack candidate) {
        for (ItemStack stack : stacks) {
            if (stack.isItemEqual(candidate)) return;
        }
        ItemStack copy = candidate.copy();
        copy.stackSize = 1;
        stacks.add(copy);
    }

    private static ItemStack chargedExample(ItemStack battery) {
        ItemStack result = battery.copy();
        MonitorBattery.manager(result)
            .discharge(result, Double.MAX_VALUE, Integer.MAX_VALUE, true, false, false);
        MonitorBattery.manager(result)
            .charge(
                result,
                ((IElectricItem) result.getItem()).getMaxCharge(result) / 2,
                Integer.MAX_VALUE,
                true,
                false);
        return result;
    }

    private static boolean isMonitor(ItemStack stack) {
        return stack != null && stack.getItem() instanceof PortableWirelessNetworkMonitor;
    }

    @Override
    public void drawBackground(int recipe) {
        for (int x : new int[] { 8, 44, 108 }) slot(x, 24);
        if (((BatteryExample) arecipes.get(recipe)).returned != null) slot(144, 24);
    }

    private static void slot(int x, int y) {
        Gui.drawRect(x - 1, y - 1, x + 17, y + 17, 0xFF555555);
        Gui.drawRect(x, y, x + 16, y + 16, 0xFFAAAAAA);
    }

    @Override
    public void drawExtras(int recipe) {
        BatteryExample example = (BatteryExample) arecipes.get(recipe);
        drawFit(text(example.operation), 0, 1);
        drawFit("+", 30, 28);
        drawFit("->", 78, 28);
        drawFit(text("shapeless"), 0, 48);
        drawFit(text("charge"), 0, 60);
        if (example.returned != null) drawFit(text("return"), 0, 72);
        if ("remove".equals(example.operation)) drawFit(text("tool"), 0, 84);
    }

    private static void drawFit(String value, int x, int y) {
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        float scale = Math.min(1, (166F - x) / Math.max(1, font.getStringWidth(value)));
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0);
        GL11.glScalef(scale, scale, 1);
        font.drawString(value, 0, 0, 0x404040);
        GL11.glPopMatrix();
    }

    private static String text(String suffix) {
        return StatCollector.translateToLocal(LANG + suffix);
    }

    private final class BatteryExample extends CachedRecipe {

        private final String operation;
        private final List<PositionedStack> ingredients;
        private final PositionedStack result;
        private final PositionedStack returned;

        private BatteryExample(String operation, ItemStack monitor, Object input, ItemStack output, ItemStack battery) {
            this.operation = operation;
            PositionedStack second = new PositionedStack(input, 44, 24);
            if ("remove".equals(operation)) second.setTooltip(Collections.singletonList(text("tool")));
            ingredients = Arrays.asList(new PositionedStack(monitor, 8, 24), second);
            result = new PositionedStack(output, 108, 24);
            returned = battery == null ? null : new PositionedStack(battery, 144, 24);
            if (returned != null) returned.setTooltip(Arrays.asList(text("return"), text("charge")));
        }

        @Override
        public List<PositionedStack> getIngredients() {
            return getCycledIngredients(cycleticks / 20, ingredients);
        }

        @Override
        public PositionedStack getResult() {
            return result;
        }

        @Override
        public List<PositionedStack> getOtherStacks() {
            return returned == null ? Collections.emptyList() : Collections.singletonList(returned);
        }
    }
}
