
package com.miaokatze.gtswn.common.covers;

import static gregtech.common.misc.WirelessNetworkManager.addEUToGlobalEnergyMap;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;

import com.google.common.io.ByteArrayDataInput;
import com.miaokatze.gtswn.config.Config;

import gregtech.api.covers.CoverContext;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.common.covers.Cover;
import io.netty.buffer.ByteBuf;

/**
 * 链路终端（动力）—— 虚空覆盖板
 * <p>
 * 本质为一个"虚拟导线":每 tick 读取机器的输出电压/安培,按 V×A 从机器取电存入内部缓冲池(电容量 = 2^63-1)。
 * 每 600 tick 将缓冲池累积的 EU 送到无线电网(计算上行损耗)。
 * 卸载时将剩余电量发回网络(计算上行损耗)。
 * <p>
 * 通过 letsEnergyOut()=false 阻止机器向覆盖板所在面输出到真导线,避免双重消耗。
 * <p>
 * Link Terminal (Dynamo) — a void cover acting as a virtual cable.
 * Reads machine output V/A per tick, drains V×A EU into internal buffer (capacity = 2^63-1).
 * Uploads buffer to wireless network every 600 ticks (with uplink loss).
 * Returns remaining buffer to network on removal (with uplink loss).
 */
public class GTswn_Cover_DynamoWireless extends Cover {

    /** 电容量上限 = 太·终极电池容量 = 2^63-1 / Capacity = Long.MAX_VALUE (MAX Battery) */
    private static final long CAPACITY = Long.MAX_VALUE;

    private long storedEU = 0L; // 当前缓冲池 EU / Current buffer EU
    private long ticksSinceLastUpload = 0L; // 距上次上传网络的tick计数 / Ticks since last network upload
    private boolean configured = false;

    public GTswn_Cover_DynamoWireless(CoverContext context) {
        super(context, null);
    }

    @Override
    protected void readDataFromNbt(NBTBase nbt) {
        if (nbt instanceof NBTTagCompound tag) {
            if (tag.hasKey("storedEU")) this.storedEU = tag.getLong("storedEU");
            if (tag.hasKey("configured")) this.configured = tag.getBoolean("configured");
            if (tag.hasKey("ticksSinceLastUpload")) this.ticksSinceLastUpload = tag.getLong("ticksSinceLastUpload");
        }
    }

    @Override
    protected void readDataFromPacket(ByteArrayDataInput byteData) {
        storedEU = byteData.readLong();
        configured = byteData.readBoolean();
        ticksSinceLastUpload = byteData.readLong();
    }

    @Override
    protected NBTBase saveDataToNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setLong("storedEU", storedEU);
        tag.setBoolean("configured", configured);
        tag.setLong("ticksSinceLastUpload", ticksSinceLastUpload);
        return tag;
    }

    @Override
    protected void writeDataToByteBuf(ByteBuf byteBuf) {
        byteBuf.writeLong(storedEU);
        byteBuf.writeBoolean(configured);
        byteBuf.writeLong(ticksSinceLastUpload);
    }

    @Override
    public boolean isRedstoneSensitive(long aTimer) {
        return false;
    }

    @Override
    public boolean allowsCopyPasteTool() {
        return false;
    }

    @Override
    public boolean allowsTickRateAddition() {
        return false;
    }

    @Override
    public void doCoverThings(byte aInputRedstone, long aTimer) {
        if (!this.configured) return;

        ICoverable tileEntity = coveredTile.get();
        if (!(tileEntity instanceof BaseMetaTileEntity bmte)) return;

        // 每 tick:读取机器输出,按 V×A 从机器取电存入缓冲池
        // Per tick: read machine output, drain V×A EU into buffer
        // 只对会输出能量的机器取电(getOutputVoltage > 0),避免误取非输出机器
        // Only drain from output-capable machines (getOutputVoltage > 0)
        long outputV = bmte.getOutputVoltage();
        long outputA = bmte.getOutputAmperage();
        if (outputV > 0 && outputA > 0) {
            long currentEU = bmte.getStoredEUuncapped();
            long minStoredEU = 0L;
            if (bmte.getMetaTileEntity() instanceof MetaTileEntity mte) {
                minStoredEU = mte.getMinimumStoredEU();
            }
            long availableEU = currentEU - minStoredEU;
            if (availableEU > 0) {
                long euToTake = Math.min(availableEU, outputV * outputA);
                if (bmte.decreaseStoredEU(euToTake, true)) {
                    this.storedEU += euToTake;
                }
            }
        }

        // 每 600 tick:把缓冲池累积的 EU 送到电网(计算上行损耗)
        // Every 600 ticks: upload buffer to network (with uplink loss)
        ticksSinceLastUpload++;
        if (ticksSinceLastUpload >= 600L) {
            ticksSinceLastUpload = 0L;
            if (this.storedEU > 0) {
                UUID owner = getOwner(bmte);
                if (owner != null) {
                    long actualAdded = (long) (this.storedEU * (1.0 - Config.uplinkLossEU));
                    if (actualAdded > 0 && addEUToGlobalEnergyMap(owner, actualAdded)) {
                        this.storedEU = 0;
                    }
                }
            }
        }
    }

    private static UUID getOwner(Object te) {
        if (te instanceof BaseMetaTileEntity igte) {
            return igte.getOwnerUuid();
        } else {
            return null;
        }
    }

    @Override
    public void onCoverRemoval() {
        // 卸载时:将缓冲池剩余 EU 发回网络(计算上行损耗)
        // On removal: return remaining buffer to network (with uplink loss)
        if (this.storedEU > 0) {
            ICoverable tileEntity = coveredTile.get();
            UUID owner = getOwner(tileEntity);
            if (owner != null) {
                long actualAdded = (long) (this.storedEU * (1.0 - Config.uplinkLossEU));
                if (actualAdded > 0) {
                    addEUToGlobalEnergyMap(owner, actualAdded);
                }
            }
            this.storedEU = 0;
        }
    }

    /**
     * 阻止机器向覆盖板所在面输出到真导线,避免双重消耗
     * Block machine from outputting to real cables on this side, preventing double consumption
     */
    @Override
    public boolean letsEnergyOut() {
        return false;
    }

    @Override
    public boolean alwaysLookConnected() {
        return true;
    }

    @Override
    public int getMinimumTickRate() {
        return 1; // 每 tick 执行 / Run every tick
    }

    @Override
    public boolean hasCoverGUI() {
        return true;
    }

    @Override
    public boolean onCoverRightClick(EntityPlayer aPlayer, float aX, float aY, float aZ) {
        aPlayer.addChatMessage(
            new ChatComponentText(net.minecraft.util.StatCollector.translateToLocal("gtswn.chat.cover.dynamo_config")));
        if (this.configured) {
            aPlayer.addChatMessage(
                new ChatComponentText(
                    net.minecraft.util.StatCollector.translateToLocal("gtswn.chat.cover.capacity") + CAPACITY + " EU"));
            aPlayer.addChatMessage(
                new ChatComponentText(
                    net.minecraft.util.StatCollector.translateToLocal("gtswn.chat.cover.stored_eu") + this.storedEU
                        + " EU"));
            aPlayer.addChatMessage(
                new ChatComponentText(
                    net.minecraft.util.StatCollector.translateToLocal("gtswn.chat.cover.next_upload")
                        + (600 - ticksSinceLastUpload)
                        + " ticks"));
        } else {
            aPlayer.addChatMessage(
                new ChatComponentText(
                    net.minecraft.util.StatCollector.translateToLocal("gtswn.chat.cover.not_configured")));
        }
        return true;
    }

    /**
     * 配置覆盖板:设置 configured=true,缓冲池初始为空
     * Configure cover: set configured=true, buffer starts empty
     */
    public void configure() {
        this.configured = true;
        this.storedEU = 0L;
    }
}
