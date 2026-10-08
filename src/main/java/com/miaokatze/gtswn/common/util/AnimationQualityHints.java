package com.miaokatze.gtswn.common.util;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Success-only quality guidance, using Forge's death-persistent player NBT. */
public final class AnimationQualityHints {

    private static final DelayedPlayerMessages<List<IChatComponent>> PENDING = new DelayedPlayerMessages<>(4096);

    public static void show(EntityPlayer player, QualityHintCounter.Kind kind) {
        if (player.worldObj.isRemote) return;
        NBTTagCompound data = player.getEntityData();
        NBTTagCompound persisted = data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        int remaining = QualityHintCounter.consume(persisted, kind);
        data.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
        if (remaining >= 0) {
            player.addChatMessage(new ChatComponentTranslation("gtswn.chat.quality_hint." + kind.key, remaining));
        }
    }

    public static void linkSuccess(EntityPlayer player, List<IChatComponent> messages) {
        if (player.worldObj.isRemote) return;
        List<IChatComponent> copy = new ArrayList<>();
        for (IChatComponent message : messages) copy.add(message.createCopy());
        PENDING.schedule(player.getUniqueID(), copy, 40);
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        PENDING.advance((id, messages) -> {
            MinecraftServer server = MinecraftServer.getServer();
            if (server == null || server.getConfigurationManager() == null) return;
            for (Object online : server.getConfigurationManager().playerEntityList) {
                EntityPlayerMP player = (EntityPlayerMP) online;
                if (!id.equals(player.getUniqueID()) || player.playerNetServerHandler == null) continue;
                for (IChatComponent message : messages) player.addChatMessage(message);
                show(player, QualityHintCounter.Kind.LINK);
                return;
            }
        });
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.cancel(event.player.getUniqueID());
    }

    public static void clear() {
        PENDING.clear();
    }
}
