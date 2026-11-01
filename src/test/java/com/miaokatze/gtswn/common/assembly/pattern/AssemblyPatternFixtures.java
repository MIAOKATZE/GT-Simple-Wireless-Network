package com.miaokatze.gtswn.common.assembly.pattern;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.container.ContainerNull;
import appeng.helpers.PatternHelper;
import appeng.helpers.UltimatePatternHelper;
import appeng.items.misc.ItemEncodedPattern;
import appeng.items.misc.ItemEncodedUltimatePattern;
import appeng.util.inv.MEInventoryCrafting;
import cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry;
import gregtech.api.util.GTRecipe.RecipeAssemblyLine;

/**
 * Detached interface values for domain tests; real helper fields and table, without AE renderer/global initialization.
 */
final class AssemblyPatternFixtures {

    static Item ingredient;
    static Item product;
    static Item anotherProduct;
    private static ItemEncodedPattern standardPattern;
    private static ItemEncodedUltimatePattern ultimatePattern;
    private static sun.misc.Unsafe unsafe;

    private AssemblyPatternFixtures() {}

    static synchronized void initialize() throws Exception {
        if (ingredient != null) return;
        Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        unsafe = (sun.misc.Unsafe) singleton.get(null);
        ingredient = new Item();
        product = new Item();
        anotherProduct = new Item();
        standardPattern = (ItemEncodedPattern) allocateItem(ItemEncodedPattern.class);
        ultimatePattern = (ItemEncodedUltimatePattern) allocateItem(ItemEncodedUltimatePattern.class);
        register(31220, "ingredient", ingredient);
        register(31221, "product", product);
        register(31222, "another_product", anotherProduct);
        register(31223, "standard_pattern", standardPattern);
        register(31224, "ultimate_pattern", ultimatePattern);
    }

    private static void register(int id, String name, Item item) throws Exception {
        Method method = Item.itemRegistry.getClass()
            .getDeclaredMethod("addObjectRaw", int.class, String.class, Object.class);
        method.setAccessible(true);
        method.invoke(Item.itemRegistry, id, "gtswn_assembly_test:" + name, item);
    }

    @SuppressWarnings("unchecked")
    private static Item allocateItem(Class<? extends Item> type) throws Exception {
        Item item = (Item) unsafe.allocateInstance(type);
        set(
            Item.class,
            item,
            "delegate",
            ((FMLControlledNamespacedRegistry<Item>) Item.itemRegistry).getDelegate(item, Item.class));
        return item;
    }

    static ItemStack input(int amount) {
        return new ItemStack(ingredient, amount, 0);
    }

    static ItemStack output(int amount) {
        return new ItemStack(product, amount, 0);
    }

    static IAEItemStack item(ItemStack nativeStack) {
        ItemStack definition = nativeStack.copy();
        long[] amount = { nativeStack.stackSize };
        return (IAEItemStack) Proxy.newProxyInstance(
            AssemblyPatternFixtures.class.getClassLoader(),
            new Class<?>[] { IAEItemStack.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getStackSize":
                        return amount[0];
                    case "setStackSize":
                        amount[0] = (Long) args[0];
                        return proxy;
                    case "getItemStack": {
                        ItemStack copy = definition.copy();
                        copy.stackSize = (int) Math.min(Integer.MAX_VALUE, amount[0]);
                        return copy;
                    }
                    case "getItem":
                        return definition.getItem();
                    case "getItemDamage":
                        return definition.getItemDamage();
                    case "getUsedPercent":
                        return 0f;
                    case "copy":
                        return item(definition).setStackSize(amount[0]);
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "toString":
                        return "Detached item x" + amount[0];
                    default:
                        throw new UnsupportedOperationException("Item fixture: " + method.getName());
                }
            });
    }

    static IAEFluidStack fluid(FluidStack nativeStack) {
        FluidStack definition = nativeStack.copy();
        long[] amount = { nativeStack.amount };
        return (IAEFluidStack) Proxy.newProxyInstance(
            AssemblyPatternFixtures.class.getClassLoader(),
            new Class<?>[] { IAEFluidStack.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getStackSize":
                        return amount[0];
                    case "setStackSize":
                        amount[0] = (Long) args[0];
                        return proxy;
                    case "getFluidStack": {
                        FluidStack copy = definition.copy();
                        copy.amount = (int) Math.min(Integer.MAX_VALUE, amount[0]);
                        return copy;
                    }
                    case "getFluid":
                        return definition.getFluid();
                    case "getUsedPercent":
                        return 0f;
                    case "copy":
                        return fluid(definition).setStackSize(amount[0]);
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "toString":
                        return "Detached fluid x" + amount[0];
                    default:
                        throw new UnsupportedOperationException("Fluid fixture: " + method.getName());
                }
            });
    }

    static ICraftingPatternDetails standard(IAEItemStack[] inputs, IAEItemStack output) throws Exception {
        PatternHelper helper = (PatternHelper) unsafe.allocateInstance(PatternHelper.class);
        set(PatternHelper.class, helper, "patternItem", pattern(standardPattern, inputs, output));
        set(PatternHelper.class, helper, "inputs", inputs);
        set(PatternHelper.class, helper, "outputs", new IAEItemStack[] { output });
        return helper;
    }

    static ICraftingPatternDetails ultimate(IAEStack<?>[] inputs, IAEItemStack output) throws Exception {
        UltimatePatternHelper helper = (UltimatePatternHelper) unsafe.allocateInstance(UltimatePatternHelper.class);
        set(UltimatePatternHelper.class, helper, "patternItem", pattern(ultimatePattern, inputs, output));
        set(UltimatePatternHelper.class, helper, "aeInputs", inputs);
        set(UltimatePatternHelper.class, helper, "aeOutputs", new IAEStack<?>[] { output });
        return helper;
    }

    private static ItemStack pattern(Item item, IAEStack<?>[] inputs, IAEItemStack output) {
        ItemStack stack = new ItemStack(item);
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList in = new NBTTagList(), out = new NBTTagList();
        for (IAEStack<?> input : inputs) {
            NBTTagCompound entry = new NBTTagCompound();
            if (input != null) encodeDeclaration(input, entry);
            in.appendTag(entry);
        }
        NBTTagCompound entry = new NBTTagCompound();
        encodeDeclaration(output, entry);
        out.appendTag(entry);
        tag.setTag("in", in);
        tag.setTag("out", out);
        stack.setTagCompound(tag);
        return stack;
    }

    private static void encodeDeclaration(IAEStack<?> stack, NBTTagCompound tag) {
        if (stack instanceof IAEItemStack) {
            ((IAEItemStack) stack).getItemStack()
                .writeToNBT(tag);
            tag.setString("StackType", "item");
        } else {
            FluidStack fluid = ((IAEFluidStack) stack).getFluidStack();
            tag.setString(
                "FluidName",
                fluid.getFluid()
                    .getName());
            if (fluid.tag != null) tag.setTag("tag", fluid.tag.copy());
            tag.setString("StackType", "fluid");
        }
        tag.setLong("Cnt", stack.getStackSize());
    }

    static MEInventoryCrafting table(IAEStack<?>... inputs) throws Exception {
        MEInventoryCrafting table = new MEInventoryCrafting(new ContainerNull(), inputs.length, 1);
        // Exercise the actual generic table contract without Platform's optional fluid-to-packet rendering adapter.
        set(MEInventoryCrafting.class, table, "aeStackList", inputs);
        for (int i = 0; i < inputs.length; i++) {
            if (inputs[i] instanceof IAEItemStack)
                table.setInventorySlotContents(i, ((IAEItemStack) inputs[i]).getItemStack());
        }
        return table;
    }

    static RecipeAssemblyLine recipe(ItemStack[] inputs, FluidStack[] fluids, ItemStack output) {
        String flag = "com.gtnh.gt5u.ignore-invalid-assline-recipe";
        String previous = System.getProperty(flag);
        try {
            // The test is outside a Forge world; skip only GT's first-world-tick validator registration.
            System.setProperty(flag, "true");
            return new RecipeAssemblyLine(
                input(1),
                100,
                480,
                inputs,
                fluids,
                output,
                200,
                120,
                new ItemStack[inputs.length][]);
        } finally {
            if (previous == null) System.clearProperty(flag);
            else System.setProperty(flag, previous);
        }
    }

    static Object setStatic(Field field, Object value) throws Exception {
        field.setAccessible(true);
        Object previous = field.get(null);
        unsafe.putObjectVolatile(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field), value);
        return previous;
    }

    static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
