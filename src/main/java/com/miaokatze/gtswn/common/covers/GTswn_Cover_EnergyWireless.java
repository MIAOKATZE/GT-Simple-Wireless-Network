
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
import gregtech.common.covers.Cover;
import io.netty.buffer.ByteBuf;

/**
 * 链路终端（能源）—— 虚空覆盖板
 * <p>
 * 本质为一个"虚拟电源":内部维护电容量缓冲池,像导线一样每 tick 向机器持续输入 V×A 的 EU。
 * 每 600 tick 从无线电网补满缓冲池(计算下行损耗)。
 * 卸载时将剩余电量发回网络(计算上行损耗)。
 * <p>
 * Link Terminal (Energy) — a void cover acting as a virtual power source.
 * Maintains an internal capacity buffer, continuously injects V×A EU per tick like a cable.
 * Refills from wireless network every 600 ticks (with downlink loss).
 * Returns remaining buffer to network on removal (with uplink loss).
 */
public class GTswn_Cover_EnergyWireless extends Cover {

    private int voltage = 0;
    private int amperage = 0;
    private long capacity = 0L; // 电容量上限 = V × A × 800 / Capacity upper bound = V × A × 800
    private long storedEU = 0L; // 当前缓冲池 EU / Current buffer EU
    private boolean configured = false;

    public GTswn_Cover_EnergyWireless(CoverContext context) {
        super(context, null);
    }

    @Override
    protected void readDataFromNbt(NBTBase nbt) {
        if (nbt instanceof NBTTagCompound tag) {
            if (tag.hasKey("voltage")) this.voltage = tag.getInteger("voltage");
            if (tag.hasKey("amperage")) this.amperage = tag.getInteger("amperage");
            if (tag.hasKey("capacity")) this.capacity = tag.getLong("capacity");
            if (tag.hasKey("storedEU")) this.storedEU = tag.getLong("storedEU");
            if (tag.hasKey("configured")) this.configured = tag.getBoolean("configured");
        }
    }

    @Override
    protected void readDataFromPacket(ByteArrayDataInput byteData) {
        voltage = byteData.readInt();
        amperage = byteData.readInt();
        capacity = byteData.readLong();
        storedEU = byteData.readLong();
        configured = byteData.readBoolean();
    }

    @Override
    protected NBTBase saveDataToNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("voltage", voltage);
        tag.setInteger("amperage", amperage);
        tag.setLong("capacity", capacity);
        tag.setLong("storedEU", storedEU);
        tag.setBoolean("configured", configured);
        return tag;
    }

    @Override
    protected void writeDataToByteBuf(ByteBuf byteBuf) {
        byteBuf.writeInt(voltage);
        byteBuf.writeInt(amperage);
        byteBuf.writeLong(capacity);
        byteBuf.writeLong(storedEU);
        byteBuf.writeBoolean(configured);
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
        if (!this.configured || this.voltage <= 0 || this.amperage <= 0 || this.capacity <= 0) return;

        ICoverable tileEntity = coveredTile.get();
        if (!(tileEntity instanceof BaseMetaTileEntity bmte)) return;

        // 每 tick:像导线一样持续输入 V×A(从缓冲池扣)
        // Per tick: continuously inject V×A like a cable (deduct from buffer)
        long euPerTick = (long) this.voltage * this.amperage;
        if (this.storedEU > 0 && euPerTick > 0) {
            long currentEU = bmte.getStoredEUuncapped();
            long machineCapacity = bmte.getEUCapacity();
            long neededEU = machineCapacity - currentEU;
            if (neededEU > 0) {
                long euToInput = Math.min(neededEU, Math.min(euPerTick, this.storedEU));
                bmte.increaseStoredEnergyUnits(euToInput, true);
                this.storedEU -= euToInput;
            }
        }

        // 每 600 tick:从电网补满到电容量上限
        // Every 600 ticks: refill buffer to capacity from network
        if (aTimer % 600L == 0L) {
            refillFromNetwork(bmte);
        }
    }

    /**
     * 从无线电网补满缓冲池到电容量上限
     * 电网扣除量 = (capacity - storedEU) × (1 + 下行损耗)
     * Refill buffer to capacity from wireless network, with downlink loss
     */
    private void refillFromNetwork(BaseMetaTileEntity bmte) {
        if (this.capacity <= 0) return;
        long needed = this.capacity - this.storedEU;
        if (needed <= 0) return;
        // 计算下行损耗:电网额外扣除 downlinkLossEU 倍
        // Downlink loss: network deducts (1 + downlinkLossEU) × needed
        long lossEU = (long) (needed * Config.downlinkLossEU);
        long totalDeducted = needed + lossEU;
        UUID owner = getOwner(bmte);
        if (owner == null) return;
        if (addEUToGlobalEnergyMap(owner, -totalDeducted)) {
            this.storedEU = this.capacity;
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

    @Override
    public boolean alwaysLookConnected() {
        return true;
    }

    @Override
    public int getMinimumTickRate() {
        return 1; // 每 tick 执行,像导线一样 / Run every tick, like a cable
    }

    @Override
    public boolean hasCoverGUI() {
        return true;
    }

    @Override
    public boolean onCoverRightClick(EntityPlayer aPlayer, float aX, float aY, float aZ) {
        aPlayer.addChatMessage(
            new ChatComponentText(net.minecraft.util.StatCollector.translateToLocal("gtswn.chat.cover.energy_config")));
        if (this.configured) {
            aPlayer.addChatMessage(
                new ChatComponentText(
                    net.minecraft.util.StatCollector.translateToLocal("gtswn.chat.cover.voltage_tier") + this.voltage
                        + " EU/t"));
            aPlayer.addChatMessage(
                new ChatComponentText(
                    net.minecraft.util.StatCollector.translateToLocal("gtswn.chat.cover.amperage_tier") + this.amperage
                        + " A"));
            aPlayer.addChatMessage(
                new ChatComponentText(
                    net.minecraft.util.StatCollector.translateToLocal("gtswn.chat.cover.capacity") + this.capacity
                        + " EU"));
            aPlayer.addChatMessage(
                new ChatComponentText(
                    net.minecraft.util.StatCollector.translateToLocal("gtswn.chat.cover.stored_eu") + this.storedEU
                        + " EU"));
        } else {
            aPlayer.addChatMessage(
                new ChatComponentText(
                    net.minecraft.util.StatCollector.translateToLocal("gtswn.chat.cover.not_configured")));
        }
        return true;
    }

    /**
     * 配置覆盖板:设置电压、安培,计算电容量,并立即从电网补满
     * Configure cover: set voltage/amperage, compute capacity, and refill from network immediately
     *
     * @param voltage  电压 (EU/t)
     * @param amperage 安培数 (A)
     */
    public void configure(int voltage, int amperage) {
        this.voltage = voltage;
        this.amperage = amperage;
        this.capacity = (long) voltage * amperage * 800L; // 电容量 = V × A × 800 tick / Capacity = V × A × 800 ticks
        this.configured = true;
        // 配置时立即从电网补满到电容量上限
        // Refill to capacity immediately upon configuration
        ICoverable tileEntity = coveredTile.get();
        if (tileEntity instanceof BaseMetaTileEntity bmte) {
            refillFromNetwork(bmte);
        }
    }
}
