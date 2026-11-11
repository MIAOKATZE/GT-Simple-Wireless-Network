// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

import static appeng.util.item.AEFluidStackType.FLUID_STACK_TYPE;
import static appeng.util.item.AEItemStackType.ITEM_STACK_TYPE;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.util.DebugTestGate;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.networking.events.MENetworkCellArrayUpdate;
import appeng.api.networking.events.MENetworkChannelsChanged;
import appeng.api.networking.events.MENetworkEventSubscribe;
import appeng.api.networking.events.MENetworkPowerStatusChange;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.MachineSource;
import appeng.api.storage.ICellContainer;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.storage.data.IItemList;
import appeng.api.util.AECableType;
import appeng.api.util.DimensionalCoord;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;
import cpw.mods.fml.common.registry.GameRegistry;
import gregtech.common.misc.WirelessNetworkManager;
import gregtech.common.misc.spaceprojects.SpaceProjectManager;

/** Persistent test-world-only creative source/sink. Machines still transact through the native ME grid. */
public final class StressSupplyTile extends TileEntity implements IGridProxyable, ICellContainer {

    static final BigInteger EU_LOW = BigInteger.TEN.pow(90);
    static final BigInteger EU_TARGET = BigInteger.TEN.pow(100);
    static final BigInteger EU_HIGH = BigInteger.TEN.pow(110);
    private static final long AVAILABLE = (1L << 52) - 1;
    static Block block;

    interface Observer {

        void transferred(Object source, boolean fluid, boolean inserted, long amount);
    }

    private final Handler<?> items = new Handler<>(ITEM_STACK_TYPE, false);
    private final Handler<?> fluids = new Handler<>(FLUID_STACK_TYPE, true);
    private final List<ItemStack> itemInputs = new ArrayList<>();
    private final List<FluidStack> fluidInputs = new ArrayList<>();
    private final List<UUID> terminals = new ArrayList<>();
    private final Map<Long, RequestLimit> limits = new HashMap<>();
    private AENetworkProxy proxy;
    private NBTTagCompound savedProxy;
    private UUID owner;
    private boolean ready, wasActive, changed;
    private Observer observer;
    long itemExtracted, itemInserted, fluidExtracted, fluidInserted, lowerClamps, upperClamps;

    public static void register() {
        DebugTestGate.requireEnabled();
        block = new SupplyBlock();
        GameRegistry.registerBlock(block, "NativeStressSupply");
        GameRegistry.registerTileEntity(StressSupplyTile.class, "gtswn.test.native_stress_supply");
    }

    void configure(UUID fixtureOwner, List<ItemStack> inputs, List<FluidStack> liquids, List<UUID> terminalIds) {
        TestWorldFixtures.requireTestWorld((WorldServer) worldObj);
        UUID leader = SpaceProjectManager.spaceTeams.get(fixtureOwner);
        if (leader != null && !fixtureOwner.equals(leader))
            throw new IllegalStateException("Stress owner belongs to a foreign wireless team");
        owner = fixtureOwner;
        itemInputs.clear();
        fluidInputs.clear();
        for (ItemStack stack : inputs) itemInputs.add(stack.copy());
        for (FluidStack stack : liquids) fluidInputs.add(stack.copy());
        terminals.clear();
        terminals.addAll(terminalIds);
        rebuildPalette();
        getProxy().setOwner(TestWorldFixtures.player((WorldServer) worldObj, owner));
        maintain();
        postCellUpdate();
        markDirty();
    }

    void observe(Observer next) {
        observer = next;
    }

    /** Per-native-ME-hatch stock visibility; the native recipe engine still chooses and consumes its own batch. */
    void limit(TileEntity hatch, long itemLimit, Map<String, Long> fluidLimits) {
        TestWorldFixtures.requireTestWorld((WorldServer) worldObj);
        if (hatch.getWorldObj() != worldObj) throw new IllegalArgumentException("Foreign stress ME hatch");
        limits.put(
            QuantumControllerRegistry.pack(hatch.xCoord, hatch.yCoord, hatch.zCoord),
            new RequestLimit(itemLimit, fluidLimits));
        markDirty();
    }

    private boolean permitted() {
        return DebugTestGate.isEnabled() && worldObj != null && !worldObj.isRemote && TestDimension.isTest(worldObj);
    }

    @Override
    public void updateEntity() {
        if (!permitted()) return;
        if (!ready) {
            ready = true;
            getProxy().onReady();
            maintain();
        }
        if (worldObj.getTotalWorldTime() % 100 == 0) {
            maintain();
            if (changed) {
                changed = false;
                markDirty();
            }
        }
    }

    /** Only the dedicated synthetic test owner is adjusted; no machine buffers or recipe state are changed. */
    private void maintain() {
        if (owner == null) return;
        UUID leader = SpaceProjectManager.spaceTeams.get(owner);
        if (leader != null && !owner.equals(leader))
            throw new IllegalStateException("Stress owner moved to a foreign wireless team");
        BigInteger current = WirelessNetworkManager.getUserEU(owner);
        int direction = clampDirection(current);
        if (direction != 0) {
            if (!WirelessNetworkManager.addEUToGlobalEnergyMap(owner, EU_TARGET.subtract(current)))
                throw new IllegalStateException("Stress wireless balance adjustment rejected");
            if (direction < 0) lowerClamps++;
            else upperClamps++;
            markDirty();
        }
        // A persisted fixture actor keeps the real DIT sampler enrolled after a server restart.
        for (UUID id : terminals) DeviceTerminalDataStore.ensureActiveTerminal(owner, id);
    }

    static int clampDirection(BigInteger balance) {
        return balance.compareTo(EU_LOW) < 0 ? -1 : balance.compareTo(EU_HIGH) > 0 ? 1 : 0;
    }

    private void rebuildPalette() {
        items.clearPalette();
        fluids.clearPalette();
        for (ItemStack stack : itemInputs) add(items, AEItemStack.create(stack));
        for (FluidStack stack : fluidInputs) add(fluids, AEFluidStack.create(stack));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void add(Handler handler, IAEStack stack) {
        if (stack == null) throw new IllegalArgumentException("Unregistered stress source ingredient");
        stack.setStackSize(AVAILABLE);
        if (handler.palette.findPrecise(stack) == null) handler.palette.add(stack);
    }

    @Override
    public AENetworkProxy getProxy() {
        if (proxy == null) {
            proxy = new AENetworkProxy(this, "proxy", new ItemStack(block), true);
            proxy.setFlags(GridFlags.REQUIRE_CHANNEL);
            proxy.setValidSides(EnumSet.allOf(ForgeDirection.class));
            proxy.setIdlePowerUsage(1);
            if (savedProxy != null) {
                proxy.readFromNBT(savedProxy);
                savedProxy = null;
            }
        }
        return proxy;
    }

    @Override
    public IGridNode getGridNode(ForgeDirection side) {
        return permitted() ? getProxy().getNode() : null;
    }

    @Override
    public IGridNode getActionableNode() {
        return getGridNode(ForgeDirection.UNKNOWN);
    }

    @Override
    public AECableType getCableConnectionType(ForgeDirection side) {
        return AECableType.SMART;
    }

    @Override
    public DimensionalCoord getLocation() {
        return new DimensionalCoord(this);
    }

    @Override
    public void gridChanged() {}

    @Override
    public void securityBreak() {
        worldObj.setBlockToAir(xCoord, yCoord, zCoord);
    }

    @Override
    public void validate() {
        super.validate();
        ready = false;
        if (proxy != null) proxy.validate();
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (proxy != null) proxy.invalidate();
        releaseActor();
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        if (proxy != null) proxy.onChunkUnload();
        ready = false;
        releaseActor();
    }

    private void releaseActor() {
        if (owner != null && worldObj != null && !worldObj.isRemote) DeviceTerminalDataStore.onPlayerLoggedOut(owner);
    }

    @MENetworkEventSubscribe
    public void channelsChanged(MENetworkChannelsChanged event) {
        notifyCells();
    }

    @MENetworkEventSubscribe
    public void powerChanged(MENetworkPowerStatusChange event) {
        notifyCells();
    }

    private void notifyCells() {
        if (proxy == null) return;
        boolean active = permitted() && proxy.isActive();
        if (active == wasActive) return;
        wasActive = active;
        postCellUpdate();
    }

    private void postCellUpdate() {
        if (proxy == null) return;
        IGridNode node = proxy.getNode();
        if (node != null) node.getGrid()
            .postEvent(new MENetworkCellArrayUpdate());
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List<IMEInventoryHandler> getCellArray(IAEStackType<?> type) {
        if (!permitted() || !getProxy().isActive()) return Collections.emptyList();
        if (type == ITEM_STACK_TYPE) return Collections.singletonList(items);
        if (type == FLUID_STACK_TYPE) return Collections.singletonList(fluids);
        return Collections.emptyList();
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public void saveChanges(IMEInventory inventory) {
        changed = true;
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        if (proxy != null) proxy.writeToNBT(tag);
        else if (savedProxy != null) tag.setTag("proxy", savedProxy.getCompoundTag("proxy"));
        if (owner != null) tag.setString("owner", owner.toString());
        NBTTagList itemTags = new NBTTagList(), fluidTags = new NBTTagList(), terminalTags = new NBTTagList();
        for (ItemStack stack : itemInputs) itemTags.appendTag(stack.writeToNBT(new NBTTagCompound()));
        for (FluidStack stack : fluidInputs) fluidTags.appendTag(stack.writeToNBT(new NBTTagCompound()));
        for (UUID id : terminals) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString("id", id.toString());
            terminalTags.appendTag(entry);
        }
        tag.setTag("items", itemTags);
        tag.setTag("fluids", fluidTags);
        tag.setTag("terminals", terminalTags);
        NBTTagList limitTags = new NBTTagList();
        for (Map.Entry<Long, RequestLimit> entry : limits.entrySet()) {
            NBTTagCompound limit = new NBTTagCompound();
            limit.setLong("position", entry.getKey());
            limit.setLong("items", entry.getValue().items);
            NBTTagList fluidLimits = new NBTTagList();
            for (Map.Entry<String, Long> fluid : entry.getValue().fluids.entrySet()) {
                NBTTagCompound fluidLimit = new NBTTagCompound();
                fluidLimit.setString("name", fluid.getKey());
                fluidLimit.setLong("amount", fluid.getValue());
                fluidLimits.appendTag(fluidLimit);
            }
            limit.setTag("fluids", fluidLimits);
            limitTags.appendTag(limit);
        }
        tag.setTag("limits", limitTags);
        tag.setLong("itemExtracted", itemExtracted);
        tag.setLong("itemInserted", itemInserted);
        tag.setLong("fluidExtracted", fluidExtracted);
        tag.setLong("fluidInserted", fluidInserted);
        tag.setLong("lowerClamps", lowerClamps);
        tag.setLong("upperClamps", upperClamps);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        savedProxy = (NBTTagCompound) tag.copy();
        owner = tag.hasKey("owner") ? UUID.fromString(tag.getString("owner")) : null;
        itemInputs.clear();
        fluidInputs.clear();
        terminals.clear();
        limits.clear();
        NBTTagList itemTags = tag.getTagList("items", 10), fluidTags = tag.getTagList("fluids", 10);
        for (int i = 0; i < itemTags.tagCount(); i++) {
            ItemStack stack = ItemStack.loadItemStackFromNBT(itemTags.getCompoundTagAt(i));
            if (stack == null) throw new IllegalStateException("Missing persisted stress source item");
            itemInputs.add(stack);
        }
        for (int i = 0; i < fluidTags.tagCount(); i++) {
            FluidStack stack = FluidStack.loadFluidStackFromNBT(fluidTags.getCompoundTagAt(i));
            if (stack == null) throw new IllegalStateException("Missing persisted stress source fluid");
            fluidInputs.add(stack);
        }
        NBTTagList terminalTags = tag.getTagList("terminals", 10);
        for (int i = 0; i < terminalTags.tagCount(); i++) terminals.add(
            UUID.fromString(
                terminalTags.getCompoundTagAt(i)
                    .getString("id")));
        NBTTagList limitTags = tag.getTagList("limits", 10);
        for (int i = 0; i < limitTags.tagCount(); i++) {
            NBTTagCompound entry = limitTags.getCompoundTagAt(i);
            Map<String, Long> fluidLimits = new HashMap<>();
            NBTTagList fluidTagsForLimit = entry.getTagList("fluids", 10);
            for (int j = 0; j < fluidTagsForLimit.tagCount(); j++) {
                NBTTagCompound fluid = fluidTagsForLimit.getCompoundTagAt(j);
                fluidLimits.put(fluid.getString("name"), fluid.getLong("amount"));
            }
            limits.put(entry.getLong("position"), new RequestLimit(entry.getLong("items"), fluidLimits));
        }
        rebuildPalette();
        itemExtracted = tag.getLong("itemExtracted");
        itemInserted = tag.getLong("itemInserted");
        fluidExtracted = tag.getLong("fluidExtracted");
        fluidInserted = tag.getLong("fluidInserted");
        lowerClamps = tag.getLong("lowerClamps");
        upperClamps = tag.getLong("upperClamps");
    }

    private final class Handler<S extends IAEStack<S>> implements IMEInventoryHandler<S> {

        final IAEStackType<S> type;
        IItemList<S> palette;
        final boolean fluid;

        Handler(IAEStackType<S> type, boolean fluid) {
            this.type = type;
            this.fluid = fluid;
            palette = type.createPrimitiveList();
        }

        void clearPalette() {
            palette = type.createPrimitiveList();
        }

        @Override
        public S injectItems(S input, Actionable mode, BaseActionSource source) {
            if (!permitted()) return input;
            if (mode == Actionable.MODULATE) record(source, true, input.getStackSize());
            return null;
        }

        @Override
        public S extractItems(S request, Actionable mode, BaseActionSource source) {
            if (!permitted() || palette.findPrecise(request) == null) return null;
            long amount = request.getStackSize();
            if (source instanceof MachineSource && ((MachineSource) source).via instanceof TileEntity) {
                TileEntity hatch = (TileEntity) ((MachineSource) source).via;
                RequestLimit limit = hatch.getWorldObj() == worldObj
                    ? limits.get(QuantumControllerRegistry.pack(hatch.xCoord, hatch.yCoord, hatch.zCoord))
                    : null;
                if (limit != null) amount = Math.min(
                    amount,
                    fluid ? limit.fluids.getOrDefault(
                        ((IAEFluidStack) request).getFluid()
                            .getName(),
                        AVAILABLE) : limit.items);
            }
            if (amount <= 0) return null;
            if (mode == Actionable.MODULATE) record(source, false, amount);
            return request.copy()
                .setStackSize(amount);
        }

        private void record(BaseActionSource source, boolean inserted, long amount) {
            if (fluid) {
                if (inserted) fluidInserted += amount;
                else fluidExtracted += amount;
            } else {
                if (inserted) itemInserted += amount;
                else itemExtracted += amount;
            }
            changed = true;
            if (observer != null) observer.transferred(
                source instanceof MachineSource ? ((MachineSource) source).via : source,
                fluid,
                inserted,
                amount);
        }

        @Override
        public IItemList<S> getAvailableItems(IItemList<S> out, int iteration) {
            if (permitted()) for (S stack : palette) out.add(stack.copy());
            return out;
        }

        @Override
        public S getAvailableItem(S request, int iteration) {
            S found = permitted() ? palette.findPrecise(request) : null;
            return found == null ? null : found.copy();
        }

        @Override
        public IAEStackType<S> getStackType() {
            return type;
        }

        @Override
        public AccessRestriction getAccess() {
            return AccessRestriction.READ_WRITE;
        }

        @Override
        public boolean isPrioritized(S input) {
            return true;
        }

        @Override
        public boolean canAccept(S input) {
            return permitted();
        }

        @Override
        public int getPriority() {
            return 0;
        }

        @Override
        public int getSlot() {
            return 0;
        }

        @Override
        public boolean validForPass(int pass) {
            return true;
        }
    }

    private static final class RequestLimit {

        final long items;
        final Map<String, Long> fluids;

        RequestLimit(long items, Map<String, Long> fluids) {
            this.items = items;
            this.fluids = new HashMap<>(fluids);
        }
    }

    private static final class SupplyBlock extends BlockContainer {

        SupplyBlock() {
            super(Material.iron);
            setBlockName("gtswn.nativeStressSupply");
            setBlockTextureName("minecraft:lapis_block");
            setHardness(3);
        }

        @Override
        public TileEntity createNewTileEntity(World world, int meta) {
            return new StressSupplyTile();
        }
    }
}
