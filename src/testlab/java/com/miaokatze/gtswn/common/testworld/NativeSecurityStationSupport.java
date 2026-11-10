package com.miaokatze.gtswn.common.testworld;

import java.util.function.LongConsumer;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkSecurityRegistry;

import appeng.api.AEApi;
import appeng.api.config.SecurityPermissions;
import appeng.api.implementations.items.IBiometricCard;
import appeng.api.networking.security.ISecurityProvider;
import appeng.api.storage.ITerminalHost;
import appeng.container.slot.SlotOutput;
import appeng.container.slot.SlotRestrictedInput;

/** Shared native card and encoder fixture steps. No mock provider and no client/Netty claim. */
public final class NativeSecurityStationSupport {

    private NativeSecurityStationSupport() {}

    public static ItemStack card(EntityPlayer actor, SecurityPermissions permission) {
        ItemStack result = AEApi.instance()
            .definitions()
            .items()
            .biometricCard()
            .maybeStack(1)
            .get();
        IBiometricCard item = (IBiometricCard) result.getItem();
        item.setProfile(result, actor.getGameProfile());
        for (SecurityPermissions existing : SecurityPermissions.values()) item.removePermission(result, existing);
        item.addPermission(result, permission);
        return result;
    }

    /** Caller owns the exact actor inventory backup and decides where the returned terminal is held. */
    public static ItemStack encodeAt(TestContext ctx, ItemStack input, TileEntity station, EntityPlayerMP owner,
        LongConsumer introducedKey) {
        TestWorldFixtures.requireTestWorld(ctx.world());
        if (station == null || station.getWorldObj() != ctx.world() || owner.worldObj != ctx.world())
            throw new IllegalArgumentException("Native encoder requires the same isolated test world");
        long key = ((ISecurityProvider) station).getSecurityKey();
        if (!QuantumNetworkSecurityRegistry.providerRegistry(ctx.world())
            .isProviderEnrolled(key)) introducedKey.accept(key);
        Container container = container(station, owner);
        Slot encoder = null, output = null;
        try {
            for (Object entry : container.inventorySlots) {
                Slot slot = (Slot) entry;
                if (slot instanceof SlotRestrictedInput && ((SlotRestrictedInput) slot).getItemType()
                    == SlotRestrictedInput.PlacableItemType.ENCODABLE_ITEM) encoder = slot;
                if (slot instanceof SlotOutput) output = slot;
            }
            require(
                ctx,
                encoder != null && output != null,
                "Native security container exposes encoder input/output slots");
            require(ctx, encoder.isItemValid(input), "Native encoder accepts GTSWN INetworkEncodable terminal");
            encoder.putStack(input.copy());
            ItemStack encoded = output.getStack();
            require(
                ctx,
                encoded != null && !encoder.getHasStack(),
                "Native container moves terminal from encoder input to output");
            require(
                ctx,
                Long.toString(key)
                    .equals(((ItemNetworkQuantumTerminal) encoded.getItem()).getEncryptionKey(encoded)),
                "Native encoder writes actual station key");
            ItemStack result = encoded.copy();
            output.putStack(null);
            ctx.event("pairing", "Native ContainerSecurity encoded fixture terminal");
            return result;
        } finally {
            if (encoder != null) encoder.putStack(null);
            if (output != null) output.putStack(null);
            container.onContainerClosed(owner);
        }
    }

    private static Container container(TileEntity station, EntityPlayerMP owner) {
        try {
            return (Container) Class.forName("appeng.container.implementations.ContainerSecurity")
                .getConstructor(InventoryPlayer.class, ITerminalHost.class)
                .newInstance(owner.inventory, (ITerminalHost) station);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Required native AE security container is unavailable", failure);
        }
    }

    private static void require(TestContext ctx, boolean value, String detail) {
        TestWorldFixtures.require(ctx, value, detail);
    }
}
