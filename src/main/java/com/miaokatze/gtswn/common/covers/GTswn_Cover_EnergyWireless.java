
package com.miaokatze.gtswn.common.covers;

import static gregtech.common.misc.WirelessNetworkManager.addEUToGlobalEnergyMap;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;

import com.google.common.io.ByteArrayDataInput;
import com.miaokatze.gtswn.common.performance.PerformanceAudit;
import com.miaokatze.gtswn.common.util.CoverMaths;
import com.miaokatze.gtswn.config.Config;

import gregtech.api.covers.CoverContext;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import io.netty.buffer.ByteBuf;

/**
 * 链路节点（能源）—— 虚空覆盖板
 * <p>
 * 本质为一个"虚拟电源":内部维护电容量缓冲池,像导线一样每 tick 向机器持续输入 V×A 的 EU。
 * 每基础值 tick(默认 600,Config.interactionRateTicks)从无线电网补满缓冲池(计算下行损耗)。
 * 卸载时将剩余电量发回网络(计算上行损耗)。
 * <p>
 * Link Node (Energy) — a void cover acting as a virtual power source.
 * Maintains an internal capacity buffer, continuously injects V×A EU per tick like a cable.
 * Refills from wireless network every base-value ticks (default 600, Config.interactionRateTicks,
 * with downlink loss).
 * Returns remaining buffer to network on removal (with uplink loss).
 */
public class GTswn_Cover_EnergyWireless extends GTswnCoverWirelessBase {

    private int voltage = 0;
    private int amperage = 0;
    private long capacity = 0L; // 电容量上限 = V × A ×（基础值+冗余值,默认 800）/ Capacity = V × A × (base + redundancy ticks, default
                                // 800)
    private long ticksSinceLastRefill = 0L; // 距上次网络补满的tick计数 / Ticks since last network refill

    public GTswn_Cover_EnergyWireless(CoverContext context) {
        super(context);
    }

    /** 能源无线节点：显形协议 type = {@link WirelessNodeRegistry#TYPE_ENERGY}（0） */
    @Override
    public byte nodeTypeId() {
        return WirelessNodeRegistry.TYPE_ENERGY;
    }

    @Override
    protected void readDataFromNbt(NBTBase nbt) {
        if (nbt instanceof NBTTagCompound tag) {
            // storedEU / configured 已由基类字段持有，这里直接读写（NBT 顺序无关）
            if (tag.hasKey("voltage")) this.voltage = tag.getInteger("voltage");
            if (tag.hasKey("amperage")) this.amperage = tag.getInteger("amperage");
            if (tag.hasKey("capacity")) this.capacity = tag.getLong("capacity");
            if (tag.hasKey("storedEU")) this.storedEU = tag.getLong("storedEU");
            if (tag.hasKey("configured")) this.configured = tag.getBoolean("configured");
            if (tag.hasKey("ticksSinceLastRefill")) this.ticksSinceLastRefill = tag.getLong("ticksSinceLastRefill");
        }
    }

    @Override
    protected void readDataFromPacket(ByteArrayDataInput byteData) {
        // 顺序必须与 writeDataToByteBuf 一致：voltage, amperage, capacity, storedEU, configured, ticksSinceLastRefill
        voltage = byteData.readInt();
        amperage = byteData.readInt();
        capacity = byteData.readLong();
        storedEU = byteData.readLong();
        configured = byteData.readBoolean();
        ticksSinceLastRefill = byteData.readLong();
    }

    @Override
    protected NBTBase saveDataToNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("voltage", voltage);
        tag.setInteger("amperage", amperage);
        tag.setLong("capacity", capacity);
        tag.setLong("storedEU", storedEU);
        tag.setBoolean("configured", configured);
        tag.setLong("ticksSinceLastRefill", ticksSinceLastRefill);
        return tag;
    }

    @Override
    protected void writeDataToByteBuf(ByteBuf byteBuf) {
        // 顺序必须与 readDataFromPacket 一致
        byteBuf.writeInt(voltage);
        byteBuf.writeInt(amperage);
        byteBuf.writeLong(capacity);
        byteBuf.writeLong(storedEU);
        byteBuf.writeBoolean(configured);
        byteBuf.writeLong(ticksSinceLastRefill);
    }

    @Override
    public void doCoverThings(byte aInputRedstone, long aTimer) {
        if (!this.configured || this.voltage <= 0 || this.amperage <= 0 || this.capacity <= 0) return;

        // v1.6.19：性能审计——覆盖板 tick 计数 + 本 tick 耗时采样（开关关闭时零开销）
        PerformanceAudit.recordWirelessTick();
        long auditT0 = PerformanceAudit.start();

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

        // 每基础值 tick(默认 600):从电网补满到电容量上限
        // Every base-value ticks (default 600): refill buffer to capacity from network
        ticksSinceLastRefill++;
        if (ticksSinceLastRefill >= Config.interactionRateTicks) {
            ticksSinceLastRefill = 0L;
            refillFromNetwork(bmte);
        }
        // v1.6.19：性能审计——本 tick 覆盖板耗时采样终点
        PerformanceAudit.record(auditT0);
        // v1.6.23：性能审计——覆盖板切片（gt.cover，GT cover API 宿主归属）
        PerformanceAudit.endSlice(PerformanceAudit.SLICE_GT_COVER, auditT0);
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
            // v1.6.19：性能审计——下行补满成功计数
            PerformanceAudit.recordWirelessDraw();
        }
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
            aPlayer.addChatMessage(
                new ChatComponentText(
                    net.minecraft.util.StatCollector.translateToLocal("gtswn.chat.cover.next_refill")
                        + (Config.interactionRateTicks - ticksSinceLastRefill)
                        + " ticks"));
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
        // 电容量 = V × A ×（基础值 + 冗余值）tick / Capacity = V × A × (base + redundancy) ticks
        // SWN-BUG-06+SWN-OPT-17：公式收敛至 CoverMaths.bufferCapacity 单源（与链路终端预告共享）
        this.capacity = CoverMaths.bufferCapacity(voltage, amperage);
        this.configured = true;
        // 配置时立即从电网补满到电容量上限
        // Refill to capacity immediately upon configuration
        ICoverable tileEntity = coveredTile.get();
        if (tileEntity instanceof BaseMetaTileEntity bmte) {
            refillFromNetwork(bmte);
        }
    }
}
