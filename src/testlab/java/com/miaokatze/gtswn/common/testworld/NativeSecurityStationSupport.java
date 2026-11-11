// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongConsumer;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkSecurityRegistry;
import com.mojang.authlib.GameProfile;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.SecurityPermissions;
import appeng.api.implementations.items.IBiometricCard;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.pathing.ControllerState;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.ISecurityProvider;
import appeng.api.networking.security.PlayerSource;
import appeng.api.storage.ITerminalHost;
import appeng.api.storage.data.IAEItemStack;
import appeng.container.slot.SlotOutput;
import appeng.container.slot.SlotRestrictedInput;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;

/** Shared native card and encoder fixture steps. No mock provider and no client/Netty claim. */
public final class NativeSecurityStationSupport {

    private NativeSecurityStationSupport() {}

    /** A synthetic server actor with ordinary native permissions; messages are observed, never sent over TCP. */
    public static RecordingActor actor(TestContext ctx, String role) {
        TestWorldFixtures.requireTestWorld(ctx.world());
        RecordingActor actor = new RecordingActor(ctx, role);
        TestWorldFixtures.ensureActorConnection(actor);
        actor.inventory.currentItem = 0;
        return actor;
    }

    public static final class RecordingActor extends FakePlayer {

        private final List<String> messages = new ArrayList<>();

        private RecordingActor(TestContext ctx, String role) {
            super(ctx.world(), new GameProfile(TestActors.id(role), TestWorldFixtures.actorName(TestActors.id(role))));
        }

        @Override
        public void addChatMessage(IChatComponent message) {
            if (messages.size() == 32) messages.remove(0);
            messages.add(IChatComponent.Serializer.func_150696_a(message) + " | " + message.getUnformattedText());
        }

        @Override
        public void addChatComponentMessage(IChatComponent message) {
            addChatMessage(message);
        }

        public List<String> messages() {
            return Collections.unmodifiableList(new ArrayList<>(messages));
        }

        public void clearMessages() {
            messages.clear();
        }
    }

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

    public static ItemStack allPermissionsCard(EntityPlayer actor) {
        ItemStack result = card(actor, SecurityPermissions.BUILD);
        IBiometricCard item = (IBiometricCard) result.getItem();
        for (SecurityPermissions permission : SecurityPermissions.values()) item.addPermission(result, permission);
        return result;
    }

    public static IAEItemStack insertCard(TestContext ctx, TileEntity station, EntityPlayer owner, ItemStack card) {
        TestWorldFixtures.requireTestWorld(ctx.world());
        if (station.getWorldObj() != ctx.world() || owner.worldObj != ctx.world())
            throw new IllegalArgumentException("Native card insertion requires the isolated test world");
        return ((ITerminalHost) station).getItemInventory()
            .injectItems(
                AEApi.instance()
                    .storage()
                    .createItemStack(card.copy()),
                Actionable.MODULATE,
                new PlayerSource(owner, (IActionHost) station));
    }

    public static IAEItemStack extractCard(TestContext ctx, TileEntity station, EntityPlayer owner, ItemStack card) {
        TestWorldFixtures.requireTestWorld(ctx.world());
        if (station.getWorldObj() != ctx.world() || owner.worldObj != ctx.world())
            throw new IllegalArgumentException("Native card extraction requires the isolated test world");
        return ((ITerminalHost) station).getItemInventory()
            .extractItems(
                AEApi.instance()
                    .storage()
                    .createItemStack(card.copy()),
                Actionable.MODULATE,
                new PlayerSource(owner, (IActionHost) station));
    }

    public static IGridNode node(TileEntity tile) {
        return tile instanceof IGridHost ? ((IGridHost) tile).getGridNode(ForgeDirection.UNKNOWN) : null;
    }

    public static boolean ready(TileEntity controller, TileEntity station) {
        if (controller == null || station == null || controller.isInvalid() || station.isInvalid()) return false;
        IGridNode control = node(controller), provider = node(station);
        if (control == null || provider == null || control.getGrid() != provider.getGrid()) return false;
        AENetworkProxy proxy = ((IGridProxyable) controller).getProxy();
        if (!proxy.isReady()) return false;
        try {
            ISecurityGrid security = control.getGrid()
                .getCache(ISecurityGrid.class);
            return proxy.getPath()
                .getControllerState() == ControllerState.CONTROLLER_ONLINE && proxy.getEnergy()
                    .isNetworkPowered()
                && security != null
                && security.isAvailable();
        } catch (GridAccessException ignored) {
            return false;
        }
    }

    /** Fixture energy only; grid membership, permissions and maintenance are left to native ticks. */
    public static void power(TileEntity tile) {
        if (!(tile instanceof IGridProxyable) || tile.isInvalid()) return;
        AENetworkProxy proxy = ((IGridProxyable) tile).getProxy();
        if (!proxy.isReady()) return;
        try {
            proxy.getEnergy()
                .injectPower(200, Actionable.MODULATE);
        } catch (GridAccessException ignored) { /* Native grid boot-up is observed on later ticks. */ }
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
