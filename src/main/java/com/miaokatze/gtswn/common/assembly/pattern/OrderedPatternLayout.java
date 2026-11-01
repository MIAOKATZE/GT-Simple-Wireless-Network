package com.miaokatze.gtswn.common.assembly.pattern;

import static com.miaokatze.gtswn.common.assembly.pattern.PatternValidationException.Reason.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import appeng.api.implementations.ICraftingPatternItem;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.helpers.PatternHelper;
import appeng.helpers.UltimatePatternHelper;
import appeng.items.misc.ItemEncodedPattern;
import appeng.items.misc.ItemEncodedUltimatePattern;
import appeng.items.misc.ItemTunnelPattern;
import appeng.util.inv.MEInventoryCrafting;

/** Immutable raw-position mapping. No condensed input view or caller-owned stack is used for allocation. */
public final class OrderedPatternLayout {

    private static final String FLUID_DROP = "com.glodblock.github.common.item.ItemFluidDrop";
    private static final String FLUID_PACKET = "com.glodblock.github.common.item.ItemFluidPacket";

    public enum Kind {
        EMPTY,
        ITEM,
        FLUID
    }

    public static final class Input {

        private final int rawIndex;
        private final int transportIndex;
        private final int tableIndex;
        private final int kindOrdinal;
        private final ItemStack item;
        private final FluidStack fluid;

        private Input(int rawIndex, int transportIndex, int tableIndex, int kindOrdinal, ItemStack item,
            FluidStack fluid) {
            this.rawIndex = rawIndex;
            this.transportIndex = transportIndex;
            this.tableIndex = tableIndex;
            this.kindOrdinal = kindOrdinal;
            this.item = item == null ? null : item.copy();
            this.fluid = fluid == null ? null : fluid.copy();
        }

        public int rawIndex() {
            return rawIndex;
        }

        public int transportIndex() {
            return transportIndex;
        }

        public int tableIndex() {
            return tableIndex;
        }

        public int kindOrdinal() {
            return kindOrdinal;
        }

        public Kind kind() {
            return item != null ? Kind.ITEM : fluid != null ? Kind.FLUID : Kind.EMPTY;
        }

        public long amount() {
            return item != null ? item.stackSize : fluid != null ? fluid.amount : 0;
        }

        public ItemStack itemStack() {
            return item == null ? null : item.copy();
        }

        public FluidStack fluidStack() {
            return fluid == null ? null : fluid.copy();
        }

        private Input at(int raw, int transport, int table, int ordinal) {
            return new Input(raw, transport, table, ordinal, item, fluid);
        }
    }

    /** A detached, fully validated delivery; every returned native stack is a fresh copy. */
    public static final class Payload {

        private final OrderedPatternLayout layout;
        private final List<Input> inputs;
        private final List<Input> items;
        private final List<Input> fluids;

        private Payload(OrderedPatternLayout layout, List<Input> inputs) {
            this.layout = layout;
            this.inputs = Collections.unmodifiableList(new ArrayList<>(inputs));
            items = select(inputs, Kind.ITEM);
            fluids = select(inputs, Kind.FLUID);
        }

        public List<Input> inputs() {
            return inputs;
        }

        public List<Input> items() {
            return items;
        }

        public List<Input> fluids() {
            return fluids;
        }
    }

    private final ICraftingPatternDetails details;
    private final ItemStack pattern;
    private final List<Input> rawInputs;
    private final List<Input> items;
    private final List<Input> fluids;
    private final ItemStack output;

    private OrderedPatternLayout(ICraftingPatternDetails details, List<Input> rawInputs, ItemStack output) {
        this.details = details;
        pattern = details.getPattern()
            .copy();
        this.rawInputs = Collections.unmodifiableList(rawInputs);
        items = select(rawInputs, Kind.ITEM);
        fluids = select(rawInputs, Kind.FLUID);
        this.output = output.copy();
    }

    /** Official decoders may mark an invalid stack: they receive a copy, never the stored pattern. */
    public static OrderedPatternLayout decode(ItemStack pattern, World world) {
        requirePatternItem(pattern);
        requireProcessingNbt(pattern);
        ICraftingPatternDetails details = ((ICraftingPatternItem) pattern.getItem())
            .getPatternForItem(pattern.copy(), world);
        if (details == null) throw rejected(INVALID_ENCODING, -1);
        return decode(details);
    }

    public static OrderedPatternLayout decode(ICraftingPatternDetails details) {
        if (details == null
            || (details.getClass() != PatternHelper.class && details.getClass() != UltimatePatternHelper.class))
            throw rejected(UNKNOWN_PATTERN, -1);
        requirePatternItem(details.getPattern());
        if (details.isCraftable()) throw rejected(CRAFTING_PATTERN, -1);
        if (details.canSubstitute()) throw rejected(SUBSTITUTE_PATTERN, -1);
        if (details.isInputOnly()) throw rejected(INPUT_ONLY_PATTERN, -1);
        NBTTagCompound encoded = requireProcessingNbt(details.getPattern());

        IAEStack<?>[] raw = details.getAEInputs();
        NBTTagList encodedInputs = encoded.getTagList("in", 10);
        if (raw == null || raw.length != encodedInputs.tagCount()) throw rejected(INVALID_ENCODING, -1);
        List<Input> inputs = new ArrayList<>(raw.length);
        int transport = 0, item = 0, fluid = 0;
        for (int i = 0; i < raw.length; i++) {
            NBTTagCompound source = encodedInputs.getCompoundTagAt(i);
            validateEncoded(source, raw[i], i);
            Input input = resource(raw[i], i);
            if (input.kind() == Kind.EMPTY) {
                inputs.add(input.at(i, -1, -1, -1));
            } else {
                inputs.add(input.at(i, transport++, -1, input.kind() == Kind.ITEM ? item++ : fluid++));
            }
        }
        if (item == 0 || item > 16 || fluid > 4) throw rejected(INVALID_ENCODING, -1);

        IAEStack<?>[] declaredOutputs = details.getAEOutputs();
        ItemStack output = null;
        for (IAEStack<?> declared : declaredOutputs) {
            if (declared == null) continue;
            Input result = resource(declared, -1);
            if (result.kind() != Kind.ITEM || output != null) throw rejected(UNSUPPORTED_OUTPUT, -1);
            output = result.itemStack();
        }
        // Inspect pre-decoder amounts too: the legacy decoder casts long Cnt to int.
        NBTTagList encodedOutputs = encoded.getTagList("out", 10);
        for (int i = 0; i < encodedOutputs.tagCount(); i++) {
            NBTTagCompound source = encodedOutputs.getCompoundTagAt(i);
            if (!source.hasNoTags()) encodedAmount(source, -1);
        }
        if (output == null) throw rejected(UNSUPPORTED_OUTPUT, -1);
        return new OrderedPatternLayout(details, inputs, output);
    }

    public ICraftingPatternDetails details() {
        return details;
    }

    public ItemStack pattern() {
        return pattern.copy();
    }

    public List<Input> rawInputs() {
        return rawInputs;
    }

    public List<Input> items() {
        return items;
    }

    public List<Input> fluids() {
        return fluids;
    }

    public ItemStack output() {
        return output.copy();
    }

    public Payload declaredPayload() {
        List<Input> compact = new ArrayList<>();
        for (Input input : rawInputs) if (input.kind() != Kind.EMPTY) compact.add(input);
        return new Payload(this, compact);
    }

    /** Fixed CPU paths submit either raw positions (no cache) or non-null positions (normal cache). */
    public Payload validateTable(InventoryCrafting table) {
        if (table == null || table.getClass() != MEInventoryCrafting.class) throw rejected(UNKNOWN_TABLE, -1);
        MEInventoryCrafting generic = (MEInventoryCrafting) table;
        int nonEmpty = items.size() + fluids.size();
        boolean compact = table.getSizeInventory() == nonEmpty;
        if (!compact && table.getSizeInventory() != rawInputs.size()) throw rejected(TABLE_SHAPE, -1);
        List<Input> delivered = new ArrayList<>(nonEmpty);
        for (Input declared : rawInputs) {
            if (compact && declared.kind() == Kind.EMPTY) continue;
            int index = compact ? declared.transportIndex : declared.rawIndex;
            IAEStack<?> stack = generic.getAEStackInSlot(index);
            if (stack == null && generic.getStackInSlot(index) != null)
                throw rejected(TABLE_RESOURCE, declared.rawIndex);
            Input actual = resource(stack, declared.rawIndex);
            if (!sameResource(declared, actual)) throw rejected(TABLE_RESOURCE, declared.rawIndex);
            if (declared.amount() != actual.amount()) throw rejected(TABLE_AMOUNT, declared.rawIndex);
            if (declared.kind() != Kind.EMPTY)
                delivered.add(actual.at(declared.rawIndex, declared.transportIndex, index, declared.kindOrdinal));
        }
        return new Payload(this, delivered);
    }

    boolean owns(Payload payload) {
        return payload != null && payload.layout == this;
    }

    static boolean sameItem(ItemStack left, ItemStack right) {
        return left != null && right != null
            && left.getItem() == right.getItem()
            && left.getItemDamage() == right.getItemDamage()
            && sameTag(left.getTagCompound(), right.getTagCompound());
    }

    private static boolean sameTag(NBTTagCompound left, NBTTagCompound right) {
        boolean leftEmpty = left == null || left.hasNoTags();
        boolean rightEmpty = right == null || right.hasNoTags();
        return leftEmpty && rightEmpty || !leftEmpty && !rightEmpty && left.equals(right);
    }

    private static boolean sameResource(Input left, Input right) {
        if (left.kind() != right.kind()) return false;
        if (left.kind() == Kind.EMPTY) return true;
        return left.kind() == Kind.ITEM ? sameItem(left.item, right.item) : left.fluid.isFluidEqual(right.fluid);
    }

    private static List<Input> select(List<Input> inputs, Kind kind) {
        List<Input> selected = new ArrayList<>();
        for (Input input : inputs) if (input.kind() == kind) selected.add(input);
        return Collections.unmodifiableList(selected);
    }

    private static void requirePatternItem(ItemStack pattern) {
        if (pattern == null || pattern.getItem() == null
            || (pattern.getItem()
                .getClass() != ItemEncodedPattern.class
                && pattern.getItem()
                    .getClass() != ItemEncodedUltimatePattern.class))
            throw rejected(UNKNOWN_PATTERN, -1);
    }

    private static NBTTagCompound requireProcessingNbt(ItemStack pattern) {
        NBTTagCompound encoded = pattern.getTagCompound();
        if (encoded == null || encoded.getBoolean("InvalidPattern")) throw rejected(INVALID_ENCODING, -1);
        if (encoded.getBoolean("crafting")) throw rejected(CRAFTING_PATTERN, -1);
        if (encoded.getBoolean("substitute")) throw rejected(SUBSTITUTE_PATTERN, -1);
        if (encoded.getBoolean("tunnel")) throw rejected(INPUT_ONLY_PATTERN, -1);
        return encoded;
    }

    private static void validateEncoded(NBTTagCompound encoded, IAEStack<?> decoded, int raw) {
        if (encoded.hasNoTags()) {
            if (decoded != null) throw rejected(INVALID_ENCODING, raw);
            return;
        }
        if (decoded == null) throw rejected(INVALID_ENCODING, raw);
        String type = encoded.getString("StackType");
        if (encoded.hasKey("StackType") && !encoded.hasKey("StackType", 1) && !encoded.hasKey("StackType", 8))
            throw rejected(UNKNOWN_RESOURCE, raw);
        if (encoded.hasKey("StackType", 8) && !type.isEmpty() && !type.equals("item") && !type.equals("fluid"))
            throw rejected(UNKNOWN_RESOURCE, raw);
        if (encoded.hasKey("StackType", 1)) {
            byte legacyType = encoded.getByte("StackType");
            if (legacyType < 0 || legacyType > 2) throw rejected(UNKNOWN_RESOURCE, raw);
        }
        long amount = encodedAmount(encoded, raw);
        if (decoded.getStackSize() != amount) throw rejected(INVALID_ENCODING, raw);
        boolean genericFluid = type.equals("fluid")
            || encoded.hasKey("StackType", 1) && encoded.getByte("StackType") == 2;
        NBTTagCompound tag = encoded.getCompoundTag("tag");
        if (genericFluid && !(decoded instanceof IAEFluidStack)) throw rejected(INVALID_ENCODING, raw);
        if (genericFluid && encoded.hasKey("tag") && (!encoded.hasKey("tag", 10) || !tag.hasNoTags()))
            throw rejected(SPECIAL_FLUID_TAG, raw);
        if (decoded instanceof IAEFluidStack && tag.hasKey("FluidTag")) throw rejected(SPECIAL_FLUID_TAG, raw);
        if (decoded instanceof IAEFluidStack && tag.getBoolean("DisplayOnly")) throw rejected(INVALID_ENCODING, raw);
    }

    private static long encodedAmount(NBTTagCompound encoded, int raw) {
        String key = encoded.hasKey("Cnt") ? "Cnt" : encoded.hasKey("Amount") ? "Amount" : "Count";
        if (!encoded.hasKey(key, 1) && !encoded.hasKey(key, 2) && !encoded.hasKey(key, 3) && !encoded.hasKey(key, 4))
            throw rejected(INVALID_ENCODING, raw);
        long amount = encoded.getLong(key);
        checkedAmount(amount, raw);
        return amount;
    }

    private static Input resource(IAEStack<?> stack, int raw) {
        if (stack == null) return new Input(raw, -1, -1, -1, null, null);
        checkedAmount(stack.getStackSize(), raw);
        if (stack.getUsedPercent() != 0) throw rejected(INVALID_ENCODING, raw);
        if (stack instanceof IAEFluidStack) {
            FluidStack fluid = ((IAEFluidStack) stack).getFluidStack();
            if (fluid == null) throw rejected(INVALID_ENCODING, raw);
            if (fluid.tag != null && !fluid.tag.hasNoTags()) throw rejected(SPECIAL_FLUID_TAG, raw);
            fluid = fluid.copy();
            fluid.amount = (int) stack.getStackSize();
            return new Input(raw, -1, -1, -1, null, fluid);
        }
        if (!(stack instanceof IAEItemStack)) throw rejected(UNKNOWN_RESOURCE, raw);
        ItemStack item = ((IAEItemStack) stack).getItemStack();
        if (ItemTunnelPattern.isTunnelPattern(item)) throw rejected(TUNNEL_INPUT, raw);
        String itemClass = item.getItem()
            .getClass()
            .getName();
        if (itemClass.equals(FLUID_DROP) || itemClass.equals(FLUID_PACKET)) {
            NBTTagCompound tag = item.getTagCompound();
            if (tag == null || tag.getBoolean("DisplayOnly")) throw rejected(INVALID_ENCODING, raw);
            FluidStack fluid = itemClass.equals(FLUID_DROP) ? decodeFluidDrop(tag, stack.getStackSize(), raw)
                : decodeFluidPacket(tag, stack.getStackSize(), raw);
            return new Input(raw, -1, -1, -1, null, fluid);
        }
        item = item.copy();
        item.stackSize = (int) stack.getStackSize();
        return new Input(raw, -1, -1, -1, item, null);
    }

    /** AE2FC 1.5.116: one drop is one mB; tags are inspected before AE can discard FluidTag. */
    static FluidStack decodeFluidDrop(NBTTagCompound tag, long amount, int raw) {
        checkedAmount(amount, raw);
        if (tag.hasKey("FluidTag")) throw rejected(SPECIAL_FLUID_TAG, raw);
        if (tag.getBoolean("DisplayOnly")) throw rejected(INVALID_ENCODING, raw);
        Fluid kind = FluidRegistry.getFluid(
            tag.getString("Fluid")
                .toLowerCase(Locale.ROOT));
        if (kind == null) throw rejected(INVALID_ENCODING, raw);
        return new FluidStack(kind, (int) amount);
    }

    /** AE2FC 1.5.116: the sole packet's inner Amount is mB, independent of the outer item count. */
    static FluidStack decodeFluidPacket(NBTTagCompound tag, long count, int raw) {
        if (count != 1) throw rejected(MULTIPLE_FLUID_PACKETS, raw);
        if (tag.getBoolean("DisplayOnly")) throw rejected(INVALID_ENCODING, raw);
        NBTTagCompound contents = tag.getCompoundTag("FluidStack");
        if (!contents.hasKey("Amount", 3) && !contents.hasKey("Amount", 4)) throw rejected(INVALID_ENCODING, raw);
        long amount = contents.getLong("Amount");
        checkedAmount(amount, raw);
        if (contents.hasKey("tag")) throw rejected(SPECIAL_FLUID_TAG, raw);
        FluidStack fluid = FluidStack.loadFluidStackFromNBT(contents);
        if (fluid == null) throw rejected(INVALID_ENCODING, raw);
        fluid.amount = (int) amount;
        return fluid;
    }

    private static void checkedAmount(long amount, int raw) {
        if (amount <= 0) throw rejected(INVALID_AMOUNT, raw);
        if (amount > Integer.MAX_VALUE) throw rejected(AMOUNT_OVERFLOW, raw);
    }

    static PatternValidationException rejected(PatternValidationException.Reason reason, int raw) {
        return new PatternValidationException(reason, raw);
    }
}
