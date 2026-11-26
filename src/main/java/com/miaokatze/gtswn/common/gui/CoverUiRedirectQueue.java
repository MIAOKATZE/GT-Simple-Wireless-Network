// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.gui;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtswn.common.covers.GTswnCoverWirelessBase;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.modularui2.CoverUIFactory;
import gregtech.api.modularui2.MetaTileEntityGuiHandler;

/**
 * Defers the legacy cover-tab handoff until MUI1 has finished installing its synced window/container.
 * This is a GUI lifecycle bridge; wireless accounts, payment and cover persistence stay with the cover.
 */
public final class CoverUiRedirectQueue {

    private static final Map<UUID, Request> PENDING = new ConcurrentHashMap<>();
    private static boolean registered;

    private static final class Request {

        private final EntityPlayerMP player;
        private final GTswnCoverWirelessBase cover;
        private final Container expectedContainer;

        private Request(EntityPlayerMP player, GTswnCoverWirelessBase cover, Container expectedContainer) {
            this.player = player;
            this.cover = cover;
            this.expectedContainer = expectedContainer;
        }
    }

    private CoverUiRedirectQueue() {}

    public static synchronized void init() {
        if (registered) return;
        FMLCommonHandler.instance()
            .bus()
            .register(new CoverUiRedirectQueue());
        registered = true;
    }

    public static void enqueue(EntityPlayer player, GTswnCoverWirelessBase cover, Container expectedContainer) {
        // One pending request per player: a newer tab selection replaces the previous destination.
        if (player instanceof EntityPlayerMP serverPlayer && !player.worldObj.isRemote)
            PENDING.put(player.getUniqueID(), new Request(serverPlayer, cover, expectedContainer));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        // END runs after the synchronous MUI1 open path; an immediate MUI2 open would lose its container.
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) return;
        for (Map.Entry<UUID, Request> entry : PENDING.entrySet()) {
            Request request = entry.getValue();
            // Consume once before validation/opening. A replaced request is left for its own turn;
            // invalidated requests are cancelled rather than retried after the player/context changes.
            if (!PENDING.remove(entry.getKey(), request)) continue;
            EntityPlayerMP player = request.player;
            GTswnCoverWirelessBase cover = request.cover;
            ICoverable tile = cover.getTile();
            // Require the original container and live host/side identity in the same world and range.
            // Closing/changing GUI, disconnecting, removing/replacing the host or cover, or leaving range
            // cancels the handoff without opening a stale editor.
            if (player.playerNetServerHandler == null || !player.isEntityAlive()
                || player.openContainer != request.expectedContainer
                || !cover.isValid()
                || !(tile instanceof TileEntity tileEntity)
                || tileEntity.isInvalid()
                || tile.getWorld() != player.worldObj
                || player.worldObj.getTileEntity(tile.getXCoord(), tile.getYCoord(), tile.getZCoord()) != tileEntity
                || tile.getCoverAtSide(cover.getSide()) != cover
                || player.getDistanceSq(tile.getXCoord() + 0.5, tile.getYCoord() + 0.5, tile.getZCoord() + 0.5)
                    > MetaTileEntityGuiHandler.MAX_INTERACTION_DISTANCE)
                continue;
            CoverUIFactory.INSTANCE.open(player, cover.getCoverID(), tile, cover.getSide());
        }
    }
}
