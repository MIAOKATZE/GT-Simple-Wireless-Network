package com.miaokatze.gtswn.common.testworld;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkSecurityRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.miaokatze.gtswn.config.Config;

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
import appeng.tile.AEBaseTile;

/** Real station, cards and native encoder slots; every grid transition waits for ordinary server ticks. */
final class NativeSecurityScenario implements TestScenario {

    enum Mode {
        PERMISSIONS,
        RECOVERY
    }

    // Separate from the display fixtures and the other quantum scenarios.
    private static final int X = 8, Y = TestWorldFixtures.Y, Z = 8, STATION_X = X + 1;
    private final Mode mode;
    private final Set<Long> introducedProviderKeys = new HashSet<>();
    private FakePlayer owner, member, outsider;
    private TileEntity controller, station;
    private ItemStack terminal, memberCard;
    private boolean controllerPlaced, stationPlaced;
    private int stage;
    private long stageTick, encodedKey;

    NativeSecurityScenario(Mode mode) {
        this.mode = mode;
    }

    @Override
    public String id() {
        return mode == Mode.PERMISSIONS ? "quantum.native_security_permissions" : "quantum.native_security_recovery";
    }

    @Override
    public String description() {
        return mode == Mode.PERMISSIONS
            ? "Native AE station/card/encoder: owner, SECURITY-only member, outsider and card revocation"
            : "Native AE security station NBT reload, removal, changed key and terminal re-pairing";
    }

    @Override
    public int timeoutTicks() {
        return 1200;
    }

    @Override
    public void start(TestContext ctx) {
        owner = null;
        member = null;
        outsider = null;
        controller = null;
        station = null;
        terminal = null;
        memberCard = null;
        controllerPlaced = false;
        stationPlaced = false;
        stage = 0;
        stageTick = ctx.tick();
        encodedKey = 0;
        introducedProviderKeys.clear();
        TestWorldFixtures.requireTestWorld(ctx.world());
        if (!Config.quantumSafetyEnabled) {
            ctx.skip("Global quantum safety is disabled by current Config; native safety case leaves Config unchanged");
            return;
        }
        owner = TestActors.player(ctx.world(), "owner");
        member = TestActors.player(ctx.world(), "member");
        outsider = TestActors.player(ctx.world(), "outsider");
        owner.setPosition(X + .5, Y, Z + 2.5);
        member.setPosition(X + .5, Y, Z + 2.5);
        outsider.setPosition(X + .5, Y, Z + 2.5);
        terminal = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
        controllerPlaced = true;
        controller = placeOwned(
            ctx,
            X,
            AEApi.instance()
                .definitions()
                .blocks()
                .controller()
                .maybeStack(1)
                .get());
        placeStation(ctx);
        ctx.event("actors", "Native owner/member/outsider use only TestActors hash profiles");
        ctx.event("fixture", "Real adjacent AE controller/security station at test-dimension (8,65,8)/(9,65,8)");
    }

    private void placeStation(TestContext ctx) {
        stationPlaced = true;
        station = placeOwned(
            ctx,
            STATION_X,
            AEApi.instance()
                .definitions()
                .blocks()
                .security()
                .maybeStack(1)
                .get());
    }

    private TileEntity placeOwned(TestContext ctx, int x, ItemStack stack) {
        TestWorldFixtures.requireTestWorld(ctx.world());
        if (x != X && x != STATION_X) throw new IllegalArgumentException("Unexpected native-security fixture position");
        TestWorldProbes.addPosition(x, Y, Z);
        ctx.world()
            .setBlockToAir(x, Y, Z);
        require(
            ctx,
            ((ItemBlock) stack.getItem()).placeBlockAt(
                stack,
                owner,
                ctx.world(),
                x,
                Y,
                Z,
                ForgeDirection.UP.ordinal(),
                .5f,
                .5f,
                .5f,
                stack.getItemDamage()),
            "Native AE item placement accepts synthetic owner");
        return ctx.world()
            .getTileEntity(x, Y, Z);
    }

    @Override
    public void tick(TestContext ctx) {
        power();
        if (ctx.tick() - stageTick > 300) {
            ctx.fail("Native AE transition timed out at stage=" + stage + ", stationReady=" + ready());
            return;
        }
        if (stage == 0) {
            if (!ready()) return;
            ISecurityGrid security = security();
            int ownerId = AEApi.instance()
                .registries()
                .players()
                .getID(owner);
            require(
                ctx,
                ((IGridHost) controller).getGridNode(ForgeDirection.UNKNOWN)
                    .getPlayerID() == ownerId,
                "Native item placement assigns controller owner to hash actor");
            require(
                ctx,
                ((ISecurityProvider) station).getOwner() == ownerId,
                "Native item placement assigns security station owner to hash actor");
            require(
                ctx,
                security.hasPermission(owner, SecurityPermissions.SECURITY),
                "Native station owner has SECURITY");
            require(
                ctx,
                !security.hasPermission(outsider, SecurityPermissions.SECURITY),
                "Native outsider lacks SECURITY");
            memberCard = card(member, SecurityPermissions.SECURITY);
            require(
                ctx,
                insertCard(memberCard) == null,
                "Owner inserts real SECURITY-only biometric card through native inventory");
            owner.inventory.setInventorySlotContents(0, terminal);
            owner.setSneaking(false);
            ((ItemNetworkQuantumTerminal) terminal.getItem())
                .onItemUseFirst(terminal, owner, ctx.world(), X, Y, Z, 1, .5f, .5f, .5f);
            require(ctx, ItemNetworkQuantumTerminal.isBound(terminal), "Real controller click binds terminal");
            require(
                ctx,
                QuantumControllerRegistry.get(ctx.world())
                    .isQuantized(X, Y, Z),
                "Real controller click quantizes fixture");
            require(
                ctx,
                !QuantumTerminalAccess.isNetworkSafetyEnabled(ctx.world(), X, Y, Z),
                "New controller local safety defaults OFF");
            require(
                ctx,
                QuantumTerminalAccess.validate(outsider, terminal.copy()),
                "Local OFF permits unrelated synthetic actor");
            terminal = encode(ctx, terminal);
            encodedKey = ((ISecurityProvider) station).getSecurityKey();
            require(
                ctx,
                !QuantumTerminalAccess.isNetworkSafetyEnabled(ctx.world(), X, Y, Z),
                "Native pairing alone leaves local safety OFF");
            next(ctx, 1);
            return;
        }
        if (stage == 1) {
            if (!ready()) return;
            ISecurityGrid security = security();
            require(
                ctx,
                security.hasPermission(member, SecurityPermissions.SECURITY),
                "Inserted card grants native member SECURITY");
            require(
                ctx,
                !security.hasPermission(member, SecurityPermissions.BUILD),
                "SECURITY-only card does not grant native BUILD");
            require(
                ctx,
                QuantumTerminalAccess.canChangeNetworkSafety(member, terminal.copy()),
                "SECURITY-only member can manage local OFF switch");
            require(
                ctx,
                !QuantumTerminalAccess.setNetworkSafety(outsider, terminal.copy(), true),
                "Outsider cannot enable local safety while OFF");
            require(
                ctx,
                QuantumTerminalAccess.setNetworkSafety(member, terminal.copy(), true),
                "SECURITY-only member enables local safety");
            require(
                ctx,
                QuantumTerminalAccess.isSecurityRequired(ctx.world(), X, Y, Z),
                "Global enabled and local ON enforce native authorization");
            assertProtected(ctx);
            if (mode == Mode.PERMISSIONS) {
                IAEItemStack removed = ((ITerminalHost) station).getItemInventory()
                    .extractItems(
                        AEApi.instance()
                            .storage()
                            .createItemStack(memberCard),
                        Actionable.MODULATE,
                        new PlayerSource(owner, (IActionHost) station));
                require(
                    ctx,
                    removed != null && removed.getStackSize() == 1,
                    "Owner revokes real member card through native extraction");
                next(ctx, 2);
            } else {
                station = TestWorldFixtures.reload(ctx.world(), station);
                require(
                    ctx,
                    ((ISecurityProvider) station).getSecurityKey() == encodedKey,
                    "Station tile NBT preserves native security key");
                next(ctx, 3);
            }
            return;
        }
        if (stage == 2) {
            if (!ready()) return;
            require(
                ctx,
                !security().hasPermission(member, SecurityPermissions.SECURITY),
                "Native security cache observes card revocation");
            require(
                ctx,
                !QuantumTerminalAccess.canManage(member, terminal.copy()),
                "Revoked member loses quantum management");
            require(
                ctx,
                !QuantumTerminalAccess.setNetworkSafety(member, terminal.copy(), false),
                "Revoked member cannot disable safety");
            require(
                ctx,
                QuantumTerminalAccess.setNetworkSafety(owner, terminal, false),
                "Owner can disable safety after member revocation");
            require(
                ctx,
                QuantumTerminalAccess.validate(outsider, terminal.copy()),
                "Local OFF immediately reopens ordinary quantum access");
            require(
                ctx,
                !QuantumTerminalAccess.setNetworkSafety(outsider, terminal.copy(), true),
                "Local OFF still protects enable switch from outsider");
            require(
                ctx,
                QuantumTerminalAccess.setNetworkSafety(owner, terminal, true),
                "Native owner can re-enable safety");
            ctx.pass("Real native card/encoder permissions, local OFF/ON and revocation passed");
        } else if (stage == 3) {
            if (!ready()) return;
            assertProtected(ctx);
            require(
                ctx,
                ((ISecurityProvider) station).getSecurityKey() == encodedKey,
                "Natural reload re-registers original station key");
            removeStation(ctx);
            station = null;
            next(ctx, 4);
        } else if (stage == 4) {
            if (ctx.tick() - stageTick < 25) return;
            require(
                ctx,
                QuantumTerminalAccess.isSecurityRequired(ctx.world(), X, Y, Z),
                "Removing station leaves controller safety ON");
            require(
                ctx,
                !QuantumTerminalAccess.validate(owner, terminal.copy()),
                "Removed station makes old native key unusable even for owner");
            require(
                ctx,
                !QuantumTerminalAccess.canManage(member, terminal.copy()),
                "Removed station denies former card member management");
            placeStation(ctx);
            next(ctx, 5);
        } else if (stage == 5) {
            if (!ready()) return;
            require(
                ctx,
                ((ISecurityProvider) station).getSecurityKey() != encodedKey,
                "Replacement native station has a different key");
            require(
                ctx,
                !QuantumTerminalAccess.validate(owner, terminal.copy()),
                "Old key cannot authorize replacement station grid");
            require(
                ctx,
                !QuantumTerminalAccess.canManage(member, terminal.copy()),
                "Old card grant does not transfer to replacement station");
            require(ctx, insertCard(memberCard) == null, "Owner provisions replacement station with real member card");
            terminal = encode(ctx, terminal);
            next(ctx, 6);
        } else if (stage == 6) {
            if (!ready()) return;
            assertProtected(ctx);
            ctx.pass("Real station NBT reload, removal rejection, changed-key rejection and native re-pairing passed");
        }
    }

    private void assertProtected(TestContext ctx) {
        require(ctx, QuantumTerminalAccess.validate(owner, terminal.copy()), "Paired native owner passes BUILD access");
        require(
            ctx,
            QuantumTerminalAccess.canManage(owner, terminal.copy()),
            "Paired native owner can manage quantum network");
        require(
            ctx,
            QuantumTerminalAccess.canManage(member, terminal.copy()),
            "Paired SECURITY-only member can manage without BUILD");
        require(
            ctx,
            !QuantumTerminalAccess.validate(member, terminal.copy()),
            "SECURITY-only member cannot perform ordinary BUILD actions");
        require(
            ctx,
            !QuantumTerminalAccess.validate(outsider, terminal.copy()),
            "Paired terminal does not grant outsider BUILD");
        require(
            ctx,
            !QuantumTerminalAccess.canManage(outsider, terminal.copy()),
            "Paired terminal does not grant outsider SECURITY");
        ItemStack unpaired = terminal.copy();
        unpaired.getTagCompound()
            .removeTag(QuantumTerminalAccess.NBT_ENCRYPTION_KEY);
        require(
            ctx,
            !QuantumTerminalAccess.validate(owner, unpaired),
            "Unpaired terminal is rejected by local ON despite native ownership");
        require(
            ctx,
            QuantumTerminalAccess.canChangeNetworkSafety(owner, unpaired),
            "Owner retains native safety-switch recovery authority");
    }

    private ItemStack card(FakePlayer actor, SecurityPermissions permission) {
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

    private IAEItemStack insertCard(ItemStack card) {
        return ((ITerminalHost) station).getItemInventory()
            .injectItems(
                AEApi.instance()
                    .storage()
                    .createItemStack(card.copy()),
                Actionable.MODULATE,
                new PlayerSource(owner, (IActionHost) station));
    }

    /** Exercise AE's public input/output slots, which invoke INetworkEncodable and GTSWN enrollment naturally. */
    private ItemStack encode(TestContext ctx, ItemStack input) {
        long key = ((ISecurityProvider) station).getSecurityKey();
        QuantumNetworkSecurityRegistry providers = QuantumNetworkSecurityRegistry.providerRegistry(ctx.world());
        if (!providers.isProviderEnrolled(key)) introducedProviderKeys.add(key);
        Container container = nativeSecurityContainer();
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
            owner.inventory.setInventorySlotContents(0, result);
            ctx.event("pairing", "Native ContainerSecurity encoded fixture terminal");
            return result;
        } finally {
            if (encoder != null) encoder.putStack(null);
            if (output != null) output.putStack(null);
            container.onContainerClosed(owner);
        }
    }

    /** Resolve the real native container without javac loading AE's optional powered-tile interfaces. */
    private Container nativeSecurityContainer() {
        try {
            return (Container) Class.forName("appeng.container.implementations.ContainerSecurity")
                .getConstructor(InventoryPlayer.class, ITerminalHost.class)
                .newInstance(owner.inventory, (ITerminalHost) station);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Required native AE security container is unavailable", exception);
        }
    }

    private boolean controllerActive() {
        AENetworkProxy proxy = ((IGridProxyable) controller).getProxy();
        if (!proxy.isReady()) return false;
        try {
            return proxy.getPath()
                .getControllerState() == ControllerState.CONTROLLER_ONLINE && proxy.getEnergy()
                    .isNetworkPowered();
        } catch (GridAccessException ignored) {
            return false;
        }
    }

    private ISecurityGrid security() {
        IGridNode node = ((IGridHost) controller).getGridNode(ForgeDirection.UNKNOWN);
        return node == null ? null
            : node.getGrid()
                .getCache(ISecurityGrid.class);
    }

    private boolean ready() {
        if (controller == null || station == null || controller.isInvalid() || station.isInvalid()) return false;
        IGridNode controlNode = ((IGridHost) controller).getGridNode(ForgeDirection.UNKNOWN);
        IGridNode stationNode = ((IGridHost) station).getGridNode(ForgeDirection.UNKNOWN);
        return controlNode != null && stationNode != null
            && controlNode.getGrid() == stationNode.getGrid()
            && controllerActive()
            && security().isAvailable();
    }

    private void power() {
        if (controller == null || controller.isInvalid()
            || !((IGridProxyable) controller).getProxy()
                .isReady())
            return;
        try {
            ((IGridProxyable) controller).getProxy()
                .getEnergy()
                .injectPower(200, Actionable.MODULATE);
        } catch (GridAccessException ignored) { /* Ordinary AE boot-up is observed on later server ticks. */ }
    }

    private void next(TestContext ctx, int value) {
        stage = value;
        stageTick = ctx.tick();
        ctx.event("stage", id() + ":" + value);
    }

    private static void require(TestContext ctx, boolean value, String detail) {
        TestWorldFixtures.require(ctx, value, detail);
    }

    @Override
    public void cleanup(TestContext ctx) {
        TestWorldFixtures.requireTestWorld(ctx.world());
        try {
            try {
                if (stationPlaced) removeStation(ctx);
            } finally {
                if (controllerPlaced) {
                    QuantumControllerRegistry.get(ctx.world())
                        .dequantize(X, Y, Z);
                    ctx.world()
                        .setBlockToAir(X, Y, Z);
                }
            }
        } finally {
            if (owner != null) owner.inventory.clearInventory(null, -1);
            if (member != null) member.inventory.clearInventory(null, -1);
            if (outsider != null) outsider.inventory.clearInventory(null, -1);
            removeIntroducedProviderKeys(ctx);
            owner = null;
            member = null;
            outsider = null;
            controller = null;
            station = null;
            terminal = null;
            memberCard = null;
            controllerPlaced = false;
            stationPlaced = false;
        }
    }

    private void removeStation(TestContext ctx) {
        TileEntity current = ctx.world()
            .getTileEntity(STATION_X, Y, Z);
        // Real break/invalidation still executes; synthetic cards must not become stray item entities.
        if (current instanceof AEBaseTile) ((AEBaseTile) current).disableDrops();
        ctx.world()
            .setBlockToAir(STATION_X, Y, Z);
    }

    /** Remove only keys introduced by these synthetic stations, preserving all other registry state. */
    private void removeIntroducedProviderKeys(TestContext ctx) {
        if (introducedProviderKeys.isEmpty()) return;
        QuantumNetworkSecurityRegistry providers = QuantumNetworkSecurityRegistry.providerRegistry(ctx.world());
        for (long key : introducedProviderKeys) providers.forgetTestProvider(ctx.world(), key);
        introducedProviderKeys.clear();
    }
}
