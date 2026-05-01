
package com.miaokatze.gtswn.common.covers;

import static gregtech.common.misc.WirelessNetworkManager.addEUToGlobalEnergyMap;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;

import com.google.common.io.ByteArrayDataInput;

import gregtech.api.covers.CoverContext;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.common.covers.Cover;
import io.netty.buffer.ByteBuf;

/**
 * 链路锚点（动力模式）
 * <p>
 * 从机器获取能量发送到无线网络
 */
public class GTswn_Cover_DynamoWireless extends Cover {

    private int voltage = 0;
    private int amperage = 0;
    private int intervalTicks = 20;
    private long singleTransferEnergy = 0L;
    private boolean configured = false;

    public GTswn_Cover_DynamoWireless(CoverContext context) {
        super(context, null);
    }

    @Override
    protected void readDataFromNbt(NBTBase nbt) {
        if (nbt instanceof NBTTagCompound tag) {
            if (tag.hasKey("voltage")) this.voltage = tag.getInteger("voltage");
            if (tag.hasKey("amperage")) this.amperage = tag.getInteger("amperage");
            if (tag.hasKey("intervalTicks")) this.intervalTicks = tag.getInteger("intervalTicks");
            if (tag.hasKey("singleTransferEnergy")) this.singleTransferEnergy = tag.getLong("singleTransferEnergy");
            if (tag.hasKey("configured")) this.configured = tag.getBoolean("configured");
        }
    }

    @Override
    protected void readDataFromPacket(ByteArrayDataInput byteData) {
        voltage = byteData.readInt();
        amperage = byteData.readInt();
        intervalTicks = byteData.readInt();
        singleTransferEnergy = byteData.readLong();
        configured = byteData.readBoolean();
    }

    @Override
    protected NBTBase saveDataToNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("voltage", voltage);
        tag.setInteger("amperage", amperage);
        tag.setInteger("intervalTicks", intervalTicks);
        tag.setLong("singleTransferEnergy", singleTransferEnergy);
        tag.setBoolean("configured", configured);
        return tag;
    }

    @Override
    protected void writeDataToByteBuf(ByteBuf byteBuf) {
        byteBuf.writeInt(voltage);
        byteBuf.writeInt(amperage);
        byteBuf.writeInt(intervalTicks);
        byteBuf.writeLong(singleTransferEnergy);
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
        if (!this.configured || this.singleTransferEnergy <= 0 || this.intervalTicks <= 0) return;

        if (aTimer % this.intervalTicks == 0) {
            trySendingEnergy();
        }
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

    @Override
    public boolean onCoverRightClick(EntityPlayer aPlayer, float aX, float aY, float aZ) {
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
