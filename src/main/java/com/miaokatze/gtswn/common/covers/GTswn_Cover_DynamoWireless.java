
package com.miaokatze.gtswn.common.covers;

import static gregtech.common.misc.WirelessNetworkManager.addEUToGlobalEnergyMap;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

import gregtech.api.covers.CoverContext;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.common.covers.CoverLegacyData;

/**
 * 链路锚点（动力模式）
 * <p>
 * 从机器获取能量发送到无线网络
 */
public class GTswn_Cover_DynamoWireless extends CoverLegacyData {

    private int voltage = 0;
    private int amperage = 0;
    private int intervalTicks = 20;
    private long singleTransferEnergy = 0L;
    private boolean configured = false;

    public GTswn_Cover_DynamoWireless(CoverContext context) {
        super(context);
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
        if (!this.configured || this.singleTransferEnergy <= 0 || this.intervalTicks <= 0) return;

        if (coverData == 0 || aTimer % this.intervalTicks == 0) {
            trySendingEnergy();
        }
        coverData = 1;
    }

    private static UUID getOwner(Object te) {
        if (te instanceof BaseMetaTileEntity igte) {
            return igte.getOwnerUuid();
        } else {
            return null;
        }
    }

    private void trySendingEnergy() {
        ICoverable tileEntity = coveredTile.get();
        if (tileEntity instanceof BaseMetaTileEntity bmte) {
            long currentEU = bmte.getStoredEUuncapped();
            if (currentEU <= 0) return; // nothing to send
            long euToTransfer = Math.min(currentEU, this.singleTransferEnergy);
            if (!addEUToGlobalEnergyMap(getOwner(tileEntity), euToTransfer)) return;
            bmte.decreaseStoredEnergyUnits(euToTransfer, true);
        }
    }

    @Override
    public boolean alwaysLookConnected() {
        return true;
    }

    @Override
    public int getMinimumTickRate() {
        return 5;
    }

    @Override
    public boolean hasCoverGUI() {
        return true;
    }

    public boolean onCoverRightClick(EntityPlayer aPlayer) {
        aPlayer.addChatMessage(
            new ChatComponentText(net.minecraft.util.StatCollector.translateToLocal("gtswn.chat.cover.dynamo_config")));
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
                    net.minecraft.util.StatCollector.translateToLocal("gtswn.chat.cover.interval") + this.intervalTicks
                        + " tick"));
            aPlayer.addChatMessage(
                new ChatComponentText(
                    net.minecraft.util.StatCollector.translateToLocal("gtswn.chat.cover.single_transfer")
                        + this.singleTransferEnergy
                        + " EU"));
        } else {
            aPlayer.addChatMessage(
                new ChatComponentText(
                    net.minecraft.util.StatCollector.translateToLocal("gtswn.chat.cover.not_configured")));
        }
        return true;
    }

    public void configure(int voltage, int amperage, int intervalTicks, long singleTransferEnergy) {
        this.voltage = voltage;
        this.amperage = amperage;
        this.intervalTicks = intervalTicks;
        this.singleTransferEnergy = singleTransferEnergy;
        this.configured = true;
    }
}
