package com.miaokatze.gtswn.common.quantum;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;

import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import appeng.api.implementations.parts.IPartCable;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.parts.BusSupport;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartHost;
import appeng.api.parts.IPartRenderHelper;
import appeng.api.parts.PartItemStack;
import appeng.api.util.AECableType;
import appeng.api.util.AEColor;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.netty.buffer.ByteBuf;

/** The quantum block itself occupies the cable slot, without another item or GridNode. */
public final class QuantumNodeCablePart implements IPartCable {

    private final TileEntityNetworkQuantumNode node;

    public QuantumNodeCablePart(TileEntityNetworkQuantumNode node) {
        this.node = node;
    }

    @Override
    public BusSupport supportsBuses() {
        return BusSupport.CABLE;
    }

    @Override
    public AEColor getCableColor() {
        return AEColor.Transparent;
    }

    @Override
    public AECableType getCableConnectionType() {
        return AECableType.DENSE;
    }

    @Override
    public AECableType getCableConnectionType(ForgeDirection side) {
        return getCableConnectionType();
    }

    @Override
    public boolean changeColor(AEColor color, EntityPlayer player) {
        return false;
    }

    @Override
    public void setValidSides(EnumSet<ForgeDirection> sides) {
        node.setCableValidSides(sides);
    }

    @Override
    public IGridNode getGridNode() {
        return node.getCoreGridNode();
    }

    @Override
    public IGridNode getGridNode(ForgeDirection side) {
        return getGridNode();
    }

    @Override
    public boolean isConnected(ForgeDirection side) {
        IGridNode gridNode = getGridNode();
        if (gridNode != null) {
            for (IGridConnection connection : gridNode.getConnections()) {
                if (connection.getDirection(gridNode) == side) return true;
            }
        }
        return false;
    }

    // The block owns these lifecycles, persistence, collision and rendering. The virtual
    // cable never supplies an item to AE2's wrench/drop/save/network serialization loops.
    @Override
    public ItemStack getItemStack(PartItemStack type) {
        return null;
    }

    @Override
    public void addToWorld() {}

    @Override
    public void removeFromWorld() {}

    @Override
    public void securityBreak() {}

    @Override
    public void setPartHostInfo(ForgeDirection side, IPartHost host, TileEntity tile) {}

    @Override
    public void getBoxes(IPartCollisionHelper helper) {}

    @Override
    public void getDrops(List<ItemStack> drops, boolean wrenched) {}

    @Override
    public void writeToNBT(NBTTagCompound tag) {}

    @Override
    public void readFromNBT(NBTTagCompound tag) {}

    @Override
    public void writeToStream(ByteBuf stream) {}

    @Override
    public boolean readFromStream(ByteBuf stream) {
        return false;
    }

    @Override
    public IGridNode getExternalFacingNode() {
        return null;
    }

    @Override
    public boolean onActivate(EntityPlayer player, Vec3 pos) {
        return false;
    }

    @Override
    public boolean onShiftActivate(EntityPlayer player, Vec3 pos) {
        return false;
    }

    @Override
    public void onPlacement(EntityPlayer player, ItemStack held, ForgeDirection side) {}

    @Override
    public boolean canBePlacedOn(BusSupport support) {
        return false;
    }

    @Override
    public boolean requireDynamicRender() {
        return false;
    }

    @Override
    public boolean isSolid() {
        return false;
    }

    @Override
    public boolean canConnectRedstone() {
        return false;
    }

    @Override
    public boolean isLadder(EntityLivingBase entity) {
        return false;
    }

    @Override
    public int getLightLevel() {
        return 0;
    }

    @Override
    public int isProvidingStrongPower() {
        return 0;
    }

    @Override
    public int isProvidingWeakPower() {
        return 0;
    }

    @Override
    public int cableConnectionRenderTo() {
        return 3;
    }

    @Override
    public void onNeighborChanged() {}

    @Override
    public void onEntityCollision(Entity entity) {}

    @Override
    @SideOnly(Side.CLIENT)
    public void renderInventory(IPartRenderHelper helper, RenderBlocks renderer) {}

    @Override
    @SideOnly(Side.CLIENT)
    public void renderStatic(int x, int y, int z, IPartRenderHelper helper, RenderBlocks renderer) {}

    @Override
    @SideOnly(Side.CLIENT)
    public void renderDynamic(double x, double y, double z, IPartRenderHelper helper, RenderBlocks renderer) {}

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getBreakingTexture() {
        return null;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void randomDisplayTick(World world, int x, int y, int z, Random random) {}
}
