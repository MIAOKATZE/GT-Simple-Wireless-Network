package com.miaokatze.gtswn.common.assembly;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.UUID;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyAdapter;
import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblySession;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingProviderHelper;
import appeng.api.networking.events.MENetworkCraftingPatternChange;
import appeng.api.util.AECableType;
import appeng.api.util.DimensionalCoord;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

/** Shared AE lifecycle and durable identity for the two physical member types. */
public final class MatrixAssemblyPort {

    private final MatrixAssemblyMember member;
    private final MatrixPatternInventory patterns = new MatrixPatternInventory(this);
    private UUID memberId = UUID.randomUUID();
    private AENetworkProxy proxy;
    private OrderedAssemblyGroup group;
    private boolean patternChange = true;
    private boolean wasActive;
    private NBTTagCompound preservedRoot;
    private String fault = "";
    private int connectionMask;
    private NBTTagCompound restoredLedger;
    private boolean dropped;

    public MatrixAssemblyPort(MatrixAssemblyMember member) {
        this.member = member;
    }

    public MatrixAssemblyMember member() {
        return member;
    }

    public UUID memberId() {
        return memberId;
    }

    public MatrixPatternInventory patterns() {
        return patterns;
    }

    public OrderedAssemblyGroup group() {
        return group;
    }

    public void bind(OrderedAssemblyGroup next) {
        if (group == next) return;
        boolean wasMaster = group != null && group.master() == member;
        boolean nextMaster = next != null && next.master() == member;
        group = next;
        if (next != null) next.networkChanged();
        if (next != null) restoredLedger = null;
        patternChange = true;
        // AE classifies channel requirements when a node joins its path cache.
        // A newly elected master may already be connected, so recreate only on a real role change.
        boolean rebuild = wasMaster != nextMaster && proxy != null && proxy.isReady();
        if (rebuild) {
            // invalidate clears ready and destroys the node, including its native security identity.
            NBTTagCompound nodeData = new NBTTagCompound();
            proxy.writeToNBT(nodeData);
            proxy.invalidate();
            proxy.readFromNBT(nodeData);
        }
        getProxy().setFlags(nextMaster ? new GridFlags[] { GridFlags.REQUIRE_CHANNEL } : new GridFlags[0]);
        if (rebuild) getProxy().onReady();
    }

    public AENetworkProxy getProxy() {
        if (proxy == null) {
            proxy = new AENetworkProxy(
                (IGridProxyable) member,
                "matrixProxy",
                ((IMetaTileEntity) member).getStackForm(1),
                true);
            proxy.setValidSides(
                connectionSides(
                    member.getBaseMetaTileEntity()
                        .getFrontFacing(),
                    connectionMask));
            proxy.setIdlePowerUsage(1.0);
        }
        return proxy;
    }

    public IGridNode node() {
        return getProxy().getNode();
    }

    public DimensionalCoord location() {
        return new DimensionalCoord((TileEntity) member.getBaseMetaTileEntity());
    }

    public void ready() {
        getProxy().onReady();
        OrderedAssemblyGroup.refreshTopology(member);
    }

    public void removed() {
        if (group != null) group.invalidate("member_unloaded");
        if (proxy != null) proxy.invalidate();
    }

    public void structureChecked(NativeAssemblySession session, boolean formed) {
        OrderedAssemblyGroup.structureChecked(member, session, formed);
    }

    public void changed() {
        member.getBaseMetaTileEntity()
            .markDirty();
        patternChange = true;
    }

    public void networkChanged() {
        if (group != null) group.networkChanged();
        changed();
    }

    public void tick(long tick) {
        OrderedAssemblyGroup.topologyTick(member, tick);
        if (group != null && group.master() == member) group.tick();
        boolean active = getProxy().isActive();
        if (active != wasActive) {
            wasActive = active;
            patternChange = true;
        }
        if (patternChange && tick % 10 == 0 && getProxy().isReady()) {
            try {
                getProxy().getGrid()
                    .postEvent(new MENetworkCraftingPatternChange((ICraftingProvider) member, node()));
                patternChange = false;
            } catch (GridAccessException ignored) {
                // Disconnected grids have no crafting cache to notify. Retain the pending event.
            }
        }
    }

    public boolean busy() {
        return group == null || group.master() != member || !getProxy().isActive() || !group.accepting();
    }

    public void provideCrafting(ICraftingProviderHelper helper) {
        if (group != null && group.master() == member && getProxy().isActive()) group.provideCrafting(helper);
    }

    public boolean pushPattern(ICraftingPatternDetails details, InventoryCrafting table) {
        return !busy() && group.accept(details, table);
    }

    boolean permitsInputAccess() {
        return group != null && (NativeAssemblyAdapter.permitsNativeAccess(member) ? group.permitsMachineWork()
            : group.permitsOrdinaryInput());
    }

    public int connectionMask() {
        return connectionMask;
    }

    public void connectionMask(int mask) {
        if (connectionMask == mask) return;
        connectionMask = mask;
        if (group != null) group.networkChanged();
        refreshConnectionSides();
        member.getBaseMetaTileEntity()
            .issueTileUpdate();
    }

    public void readConnectionMask(int mask) {
        connectionMask = mask;
    }

    static EnumSet<ForgeDirection> connectionSides(ForgeDirection front, int verifiedMask) {
        EnumSet<ForgeDirection> sides = EnumSet.of(front);
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS)
            if ((verifiedMask & side.flag) != 0) sides.add(side);
        return sides;
    }

    public AECableType cableType(ForgeDirection side) {
        return connectionSides(
            member.getBaseMetaTileEntity()
                .getFrontFacing(),
            connectionMask).contains(side) ? AECableType.SMART : AECableType.NONE;
    }

    public void facingChanged() {
        if (group != null) group.invalidate("member_facing");
        OrderedAssemblyGroup.refreshTopology(member);
        refreshConnectionSides();
    }

    private void refreshConnectionSides() {
        if (proxy == null) return;
        proxy.setValidSides(
            connectionSides(
                member.getBaseMetaTileEntity()
                    .getFrontFacing(),
                connectionMask));
    }

    public void open(EntityPlayer player) {
        if (member.getBaseMetaTileEntity()
            .isClientSide()) return;
        OrderedAssemblyGroup.refreshTopology(member);
        ((gregtech.api.metatileentity.CommonMetaTileEntity) member).openGui(player);
    }

    public boolean canDismantle() {
        if (quarantined() || group != null && !group.editable()) return false;
        if (member.matrixItem() != null || member.matrixFluidStack() != null) return false;
        for (int i = 0; i < MatrixPatternInventory.SIZE; i++) if (patterns.getStackInSlot(i) != null) return false;
        return true;
    }

    public void dropContents() {
        if (dropped || member.getBaseMetaTileEntity()
            .isClientSide()) return;
        dropped = true;
        ItemStack item = member.matrixItem();
        member.matrixSetItem(null);
        if (item != null) drop(item);
        for (int i = 0; i < MatrixPatternInventory.SIZE; i++) {
            ItemStack pattern = patterns.getStackInSlot(i);
            patterns.clearForDestruction(i);
            if (pattern != null) drop(pattern);
        }
        removed();
    }

    private void drop(ItemStack stack) {
        var tile = member.getBaseMetaTileEntity();
        tile.getWorld()
            .spawnEntityInWorld(
                new EntityItem(
                    tile.getWorld(),
                    tile.getXCoord() + .5,
                    tile.getYCoord() + .5,
                    tile.getZCoord() + .5,
                    stack.copy()));
    }

    public boolean quarantined() {
        return !fault.isEmpty();
    }

    public String fault() {
        return fault;
    }

    public void quarantine(String reason) {
        fault = reason;
        changed();
    }

    public NBTTagCompound restoredLedger() {
        return restoredLedger;
    }

    public void load(NBTTagCompound root) {
        preservedRoot = (NBTTagCompound) root.copy();
        restoredLedger = null;
        fault = "";
        if (root.hasKey("gtswnMatrix") && !root.hasKey("gtswnMatrix", 10)) {
            fault = "unknown_schema";
            return;
        }
        NBTTagCompound data = root.getCompoundTag("gtswnMatrix");
        if (root.hasKey("gtswnMatrix") && (!data.hasKey("schema", 3) || data.getInteger("schema") != 1)) {
            fault = "unknown_schema";
            return;
        }
        try {
            ItemStack legacy = loadLegacyItem(root);
            ItemStack item = data.hasKey("item") ? loadItem(requireCompound(data, "item")) : legacy;
            if (legacy != null && data.hasKey("item") && !ItemStack.areItemStacksEqual(legacy, item))
                throw new IllegalArgumentException("conflicting_item_payload");
            FluidStack fluid = loadFluid(root);
            if (member.matrixFluid() && item != null || !member.matrixFluid() && fluid != null)
                throw new IllegalArgumentException("wrong_member_payload");
            if (root.hasKey("gtswnMatrix")) {
                if (!data.hasKey("member", 8)) throw new IllegalArgumentException("member_identity");
                memberId = UUID.fromString(data.getString("member"));
                patterns.load(compoundList(data, "patterns"));
                if (data.hasKey("ledger")) {
                    NBTTagCompound ledger = requireCompound(data, "ledger");
                    if (!ledger.hasKey("stage", 8)) throw new IllegalArgumentException("ledger_stage");
                    OrderedAssemblyGroup.Stage.valueOf(ledger.getString("stage"));
                    restoredLedger = (NBTTagCompound) ledger.copy();
                }
                if (data.hasKey("fault") && !data.hasKey("fault", 8)) throw new IllegalArgumentException("fault_type");
                fault = data.getString("fault");
            }
            if (member.matrixFluid()) member.matrixSetFluid(fluid);
            else member.matrixSetItem(item);
            if (fault.isEmpty()) preservedRoot = null;
        } catch (IllegalArgumentException ex) {
            fault = "invalid_saved_data";
        }
        if (preservedRoot == null && root.hasKey("matrixProxy", 10)) getProxy().readFromNBT(root);
    }

    public void save(NBTTagCompound root) {
        if (preservedRoot != null) {
            for (Object key : new ArrayList<>(root.func_150296_c())) root.removeTag((String) key);
            for (Object key : preservedRoot.func_150296_c()) {
                String name = (String) key;
                root.setTag(
                    name,
                    preservedRoot.getTag(name)
                        .copy());
            }
            return;
        }
        NBTTagCompound data = new NBTTagCompound();
        data.setInteger("schema", 1);
        data.setString("member", memberId.toString());
        data.setString("fault", fault);
        data.setTag("patterns", patterns.save());
        ItemStack item = member.matrixItem();
        if (item != null) data.setTag("item", saveItem(item));
        // Retire the legacy view rather than leaving a second stale source on a reused NBT root.
        root.setTag("Inventory", new NBTTagList());
        FluidStack fluid = member.matrixFluidStack();
        if (fluid == null) root.removeTag("mFluid");
        else root.setTag("mFluid", fluid.writeToNBT(new NBTTagCompound()));
        NBTTagCompound ledger = group != null && group.master() == member ? group.saveLedger() : restoredLedger;
        if (ledger != null) data.setTag("ledger", ledger.copy());
        root.setTag("gtswnMatrix", data);
        if (proxy != null) proxy.writeToNBT(root);
    }

    /** The real slot is durable independently of native inventory access and byte-sized legacy counts. */
    private static NBTTagCompound saveItem(ItemStack stack) {
        String name = Item.itemRegistry.getNameForObject(stack.getItem());
        if (name == null || stack.stackSize <= 0) throw new IllegalArgumentException("invalid_item_payload");
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("registry", name);
        tag.setInteger("damage", stack.getItemDamage());
        tag.setInteger("count", stack.stackSize);
        if (stack.hasTagCompound()) tag.setTag(
            "tag",
            stack.getTagCompound()
                .copy());
        return tag;
    }

    private static ItemStack loadItem(NBTTagCompound tag) {
        if (!tag.hasKey("registry", 8) || !tag.hasKey("damage", 3)
            || !tag.hasKey("count", 3)
            || tag.getInteger("count") <= 0
            || tag.hasKey("tag") && !tag.hasKey("tag", 10)) throw new IllegalArgumentException("invalid_item_payload");
        String name = tag.getString("registry");
        Item item = (Item) Item.itemRegistry.getObject(name);
        if (item == null || !name.equals(Item.itemRegistry.getNameForObject(item)))
            throw new IllegalArgumentException("unknown_item");
        ItemStack stack = new ItemStack(item, tag.getInteger("count"), tag.getInteger("damage"));
        if (tag.hasKey("tag")) stack.setTagCompound(
            (NBTTagCompound) tag.getCompoundTag("tag")
                .copy());
        return stack;
    }

    private static ItemStack loadLegacyItem(NBTTagCompound root) {
        NBTTagList inventory = compoundList(root, "Inventory");
        if (inventory.tagCount() == 0) return null;
        if (inventory.tagCount() != 1) throw new IllegalArgumentException("legacy_inventory_size");
        NBTTagCompound tag = inventory.getCompoundTagAt(0);
        if (!tag.hasKey("IntSlot", 3) || tag.getInteger("IntSlot") != 0
            || !tag.hasKey("id", 2)
            || !tag.hasKey("Damage", 2)
            || !tag.hasKey("Count", 1) && !tag.hasKey("Count", 3)
            || tag.hasKey("tag") && !tag.hasKey("tag", 10)) throw new IllegalArgumentException("legacy_inventory");
        int count = tag.getInteger("Count");
        ItemStack stack = ItemStack.loadItemStackFromNBT(tag);
        if (stack == null || count <= 0) throw new IllegalArgumentException("legacy_item");
        stack.stackSize = count;
        return stack;
    }

    private static FluidStack loadFluid(NBTTagCompound root) {
        if (!root.hasKey("mFluid")) return null;
        NBTTagCompound tag = requireCompound(root, "mFluid");
        if (!tag.hasKey("FluidName", 8) || !tag.hasKey("Amount", 3)
            || tag.getInteger("Amount") <= 0
            || tag.hasKey("Tag") && !tag.hasKey("Tag", 10)) throw new IllegalArgumentException("invalid_fluid_payload");
        FluidStack fluid = FluidStack.loadFluidStackFromNBT(tag);
        if (fluid == null) throw new IllegalArgumentException("unknown_fluid");
        return fluid;
    }

    private static NBTTagCompound requireCompound(NBTTagCompound root, String key) {
        if (!root.hasKey(key, 10)) throw new IllegalArgumentException("invalid_compound");
        return root.getCompoundTag(key);
    }

    private static NBTTagList compoundList(NBTTagCompound root, String key) {
        if (!root.hasKey(key)) return new NBTTagList();
        if (!root.hasKey(key, 9)) throw new IllegalArgumentException("invalid_list");
        NBTTagList list = (NBTTagList) root.getTag(key);
        if (list.tagCount() > 0 && list.func_150303_d() != 10)
            throw new IllegalArgumentException("invalid_list_element");
        return list;
    }
}
