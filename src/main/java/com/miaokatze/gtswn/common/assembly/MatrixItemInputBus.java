package com.miaokatze.gtswn.common.assembly;

import static appeng.util.item.AEFluidStackType.FLUID_STACK_TYPE;
import static appeng.util.item.AEItemStackType.ITEM_STACK_TYPE;

import java.util.UUID;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyBinding;
import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblySession;

import appeng.api.implementations.IPowerChannelState;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingProviderHelper;
import appeng.api.storage.data.IAEStackType;
import appeng.api.util.AECableType;
import appeng.api.util.DimensionalCoord;
import appeng.api.util.IInterfaceViewable;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import io.netty.buffer.ByteBuf;

/** One physical ordered item position; machines consume the normal input inventory. */
@IMetaTileEntity.SkipGenerateDescription
public final class MatrixItemInputBus extends MTEHatchInputBus
    implements MatrixAssemblyMember, IGridProxyable, ICraftingProvider, IPowerChannelState, IInterfaceViewable {

    private final MatrixAssemblyPort matrix = new MatrixAssemblyPort(this);
    private final IInventory interfacePatterns = new MatrixPatternInventoryAdapter(matrix);

    public MatrixItemInputBus(int id, String name, String localName) {
        super(id, name, localName, 6, 1);
        inventoryHandler.setSize(0);
        disableSort = true;
    }

    private MatrixItemInputBus(String name, String[] description, ITexture[][][] textures) {
        super(name, 6, 1, description, textures);
        inventoryHandler.setSize(0);
        disableSort = true;
    }

    @Override
    public MatrixItemInputBus newMetaEntity(IGregTechTileEntity tile) {
        return new MatrixItemInputBus(mName, mDescriptionArray, mTextures);
    }

    @Override
    public String getLocalName() {
        return StatCollector.translateToLocal("gtswn.matrix.item.name");
    }

    @Override
    public String[] getDescription() {
        return MatrixAssemblyUi.description(false);
    }

    @Override
    public MatrixAssemblyPort matrixPort() {
        return matrix;
    }

    @Override
    public UUID orderedMemberId() {
        return matrix.memberId();
    }

    @Override
    public NativeAssemblyBinding orderedAssemblyBinding() {
        return matrix.group();
    }

    @Override
    public void orderedAssemblyStructureChecked(NativeAssemblySession session, boolean formed) {
        matrix.structureChecked(session, formed);
    }

    @Override
    public boolean matrixFluid() {
        return false;
    }

    @Override
    public ItemStack matrixItem() {
        return mInventory[0] == null ? null : mInventory[0].copy();
    }

    @Override
    public FluidStack matrixFluidStack() {
        return null;
    }

    @Override
    public void matrixSetItem(ItemStack stack) {
        mInventory[0] = stack;
    }

    @Override
    public void matrixSetFluid(FluidStack fluid) {
        if (fluid != null) throw new IllegalArgumentException("Item member cannot own fluid");
    }

    @Override
    public ItemStack getFirstStack() {
        return matrix.permitsInputAccess() ? mInventory[0] : null;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return slot == 0 && matrix.permitsInputAccess() ? mInventory[0] : null;
    }

    @Override
    public ItemStack[] getRealInventory() {
        return matrix.permitsInputAccess() ? mInventory : new ItemStack[0];
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        if (slot == 0 && matrix.permitsInputAccess()) mInventory[0] = stack;
    }

    @Override
    public ItemStack decrStackSize(int slot, int amount) {
        if (slot != 0 || amount <= 0 || !matrix.permitsInputAccess() || mInventory[0] == null) return null;
        ItemStack removed = mInventory[0].splitStack(Math.min(amount, mInventory[0].stackSize));
        updateSlots();
        matrix.changed();
        return removed;
    }

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        return false;
    }

    @Override
    public boolean allowPutStack(IGregTechTileEntity tile, int slot, ForgeDirection side, ItemStack stack) {
        return false;
    }

    @Override
    public boolean allowPullStack(IGregTechTileEntity tile, int slot, ForgeDirection side, ItemStack stack) {
        return false;
    }

    @Override
    public int[] getAccessibleSlotsFromSide(int side) {
        return new int[0];
    }

    @Override
    public int getCircuitSlot() {
        return -1;
    }

    @Override
    public boolean allowSelectCircuit() {
        return false;
    }

    @Override
    public int getInventoryStackLimit() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void updateSlots() {
        disableSort = true;
        if (mInventory[0] != null && mInventory[0].stackSize <= 0) mInventory[0] = null;
    }

    @Override
    public void onScrewdriverRightClick(ForgeDirection side, EntityPlayer player, float x, float y, float z,
        ItemStack tool) {
        matrix.open(player);
    }

    @Override
    public boolean onWrenchRightClick(ForgeDirection side, ForgeDirection wrenchSide, EntityPlayer player, float x,
        float y, float z, ItemStack tool) {
        if (!matrix.canDismantle()) {
            player.addChatComponentMessage(new ChatComponentTranslation("gtswn.matrix.dismantle_blocked"));
            return false;
        }
        return super.onWrenchRightClick(side, wrenchSide, player, x, y, z, tool);
    }

    @Override
    public boolean onRightclick(IGregTechTileEntity tile, EntityPlayer player) {
        matrix.open(player);
        return true;
    }

    @Override
    protected boolean useMui2() {
        return true;
    }

    @Override
    protected boolean forceUseMui2() {
        return true;
    }

    @Override
    public ModularPanel buildUI(PosGuiData data, PanelSyncManager sync, UISettings settings) {
        return new MatrixAssemblyUi(this).build(data, sync, settings);
    }

    @Override
    public void onFirstTick(IGregTechTileEntity tile) {
        super.onFirstTick(tile);
        matrix.ready();
    }

    @Override
    public void onPostTick(IGregTechTileEntity tile, long timer) {
        if (tile.isServerSide()) {
            updateSlots();
            matrix.tick(timer);
        }
    }

    @Override
    public void onRemoval() {
        matrix.removed();
        super.onRemoval();
    }

    @Override
    public void onUnload() {
        matrix.removed();
        super.onUnload();
    }

    @Override
    public void onBlockDestroyed() {
        matrix.dropContents();
        super.onBlockDestroyed();
    }

    @Override
    public void saveNBTData(NBTTagCompound nbt) {
        super.saveNBTData(nbt);
        matrix.save(nbt);
    }

    @Override
    public void loadNBTData(NBTTagCompound nbt) {
        super.loadNBTData(nbt);
        matrix.load(nbt);
        disableSort = true;
    }

    @Override
    public void writeToStream(ByteBuf buffer) {
        super.writeToStream(buffer);
        buffer.writeByte(matrix.connectionMask());
    }

    @Override
    public void readFromStream(ByteBuf buffer) {
        super.readFromStream(buffer);
        matrix.readConnectionMask(buffer.readUnsignedByte());
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister register) {
        super.registerIcons(register);
        MatrixAssemblyTextures.init();
    }

    @Override
    public ITexture[] getTexturesActive(ITexture base) {
        return MatrixAssemblyTextures.front(this, base);
    }

    @Override
    public ITexture[] getTexturesInactive(ITexture base) {
        return MatrixAssemblyTextures.front(this, base);
    }

    @Override
    public AENetworkProxy getProxy() {
        return matrix.getProxy();
    }

    @Override
    public IGridNode getGridNode(ForgeDirection side) {
        return matrix.node();
    }

    @Override
    public DimensionalCoord getLocation() {
        return matrix.location();
    }

    @Override
    public int rows() {
        return 4;
    }

    @Override
    public int rowSize() {
        return 9;
    }

    @Override
    public IInventory getPatterns() {
        return interfacePatterns;
    }

    @Override
    public String getName() {
        return getLocalName();
    }

    @Override
    public TileEntity getTileEntity() {
        return (TileEntity) getBaseMetaTileEntity();
    }

    @Override
    public boolean shouldDisplay() {
        return matrix.group() != null && matrix.group()
            .master() == this;
    }

    @Override
    public boolean allowsPatternOptimization() {
        return false;
    }

    @Override
    public IAEStackType<?>[] getSupportedStackTypes() {
        return new IAEStackType<?>[] { ITEM_STACK_TYPE, FLUID_STACK_TYPE };
    }

    @Override
    public ItemStack getSelfRep() {
        return getStackForm(1);
    }

    @Override
    public void gridChanged() {
        matrix.networkChanged();
    }

    @Override
    public void securityBreak() {}

    @Override
    public AECableType getCableConnectionType(ForgeDirection side) {
        return matrix.cableType(side);
    }

    @Override
    public void onFacingChange() {
        super.onFacingChange();
        matrix.facingChanged();
    }

    @Override
    public boolean isPowered() {
        return getProxy().isPowered();
    }

    @Override
    public boolean isActive() {
        return getProxy().isActive();
    }

    @Override
    public void provideCrafting(ICraftingProviderHelper helper) {
        matrix.provideCrafting(helper);
    }

    @Override
    public boolean pushPattern(ICraftingPatternDetails details, InventoryCrafting table) {
        return matrix.pushPattern(details, table);
    }

    @Override
    public boolean isBusy() {
        return matrix.busy();
    }
}
