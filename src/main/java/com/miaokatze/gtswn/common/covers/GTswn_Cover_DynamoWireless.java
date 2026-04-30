package com.miaokatze.gtswn.common.covers;

import static gregtech.common.misc.WirelessNetworkManager.addEUToGlobalEnergyMap;

import java.util.UUID;

import javax.annotation.Nonnull;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

import org.jetbrains.annotations.NotNull;

import com.google.common.io.ByteArrayDataInput;
import gregtech.api.covers.CoverContext;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.util.GTUtility;
import gregtech.common.covers.Cover;
import io.netty.buffer.ByteBuf;

/**
 * GTswn Wireless Dynamo Cover (To Grid)
 * Uses NBT to store configuration: voltageTier, intervalTicks
 */
public class GTswn_Cover_DynamoWireless extends Cover {

    // NBT keys
    private static final String NBT_VOLTAGE_TIER = "vt";
    private static final String NBT_INTERVAL_TICKS = "it";
    private static final String NBT_CONFIGURED = "cfg";

    // Configuration
    private int voltageTier = 0;
    private int intervalTicks = 2000;
    private boolean configured = false;

    public GTswn_Cover_DynamoWireless(@NotNull CoverContext context) {
        super(context, null);
    }

    @Override
    protected void readDataFromNbt(@Nonnull NBTBase nbt) {
        if (nbt instanceof NBTTagCompound tag) {
            this.voltageTier = tag.getInteger(NBT_VOLTAGE_TIER);
            this.intervalTicks = tag.getInteger(NBT_INTERVAL_TICKS);
            this.configured = tag.getBoolean(NBT_CONFIGURED);
        }
    }

    @Override
    protected @Nonnull NBTBase saveDataToNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger(NBT_VOLTAGE_TIER, this.voltageTier);
        tag.setInteger(NBT_INTERVAL_TICKS, this.intervalTicks);
        tag.setBoolean(NBT_CONFIGURED, this.configured);
        return tag;
    }

    @Override
    protected void readDataFromPacket(@Nonnull ByteArrayDataInput byteData) {
        this.voltageTier = byteData.readInt();
        this.intervalTicks = byteData.readInt();
        this.configured = byteData.readBoolean();
    }

    @Override
    protected void writeDataToByteBuf(@Nonnull ByteBuf byteBuf) {
        byteBuf.writeInt(voltageTier);
        byteBuf.writeInt(intervalTicks);
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
        if (!this.configured) {
            return;
        }
        if (aTimer % this.intervalTicks == 0) {
            trySendingEnergy(coveredTile.get());
        }
    }

    private static UUID getOwner(Object te) {
        if (te instanceof BaseMetaTileEntity igte) {
            return igte.getOwnerUuid();
        } else {
            return null;
        }
    }

    private void trySendingEnergy(ICoverable tileEntity) {
        if (tileEntity instanceof BaseMetaTileEntity bmte) {
            long currentEU = bmte.getStoredEUuncapped();
            if (currentEU <= 0) return; // nothing to send
            // Send all stored energy to grid
            if (addEUToGlobalEnergyMap(getOwner(tileEntity), currentEU)) {
                bmte.setEUVar(0L); // Set to zero after sending
            }
        }
    }

    @Override
    public boolean alwaysLookConnected() {
        return true;
    }

    @Override
    public int getMinimumTickRate() {
        return 20;
    }

    // UI support
    @Override
    public boolean hasCoverGUI() {
        return true;
    }

    @Override
    public boolean onCoverShiftRightClick(EntityPlayer aPlayer) {
        // Send chat message with current config
        GTUtility.sendChatTrans(aPlayer, "GTswn无线动力覆盖板配置:");
        if (this.configured) {
            GTUtility.sendChatTrans(aPlayer, "电压等级: " + this.voltageTier);
            GTUtility.sendChatTrans(aPlayer, "交互间隔: " + this.intervalTicks + " ticks");
        } else {
            GTUtility.sendChatTrans(aPlayer, "尚未配置!");
        }
        return true;
    }

    // Setters for configuration (called by WirelessEnergyTap)
    public void configure(int voltageTier, int intervalTicks) {
        this.voltageTier = voltageTier;
        this.intervalTicks = intervalTicks;
        this.configured = true;
    }
}
