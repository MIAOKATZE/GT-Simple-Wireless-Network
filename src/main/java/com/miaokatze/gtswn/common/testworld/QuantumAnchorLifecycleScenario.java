package com.miaokatze.gtswn.common.testworld;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumControllerIdentity;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumMigrationJournal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkColorRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkSecurityRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkStatsCache;
import com.miaokatze.gtswn.common.quantum.QuantumPolicyEpoch;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.pathing.ControllerState;
import appeng.api.util.AEColor;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.parts.networking.PartCable;
import appeng.tile.AEBaseTile;
import appeng.tile.networking.TileCableBus;
import appeng.tile.networking.TileController;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;

/** Real placement/rollback and mixin lifecycle, observed only across ordinary server END callbacks. */
final class QuantumAnchorLifecycleScenario implements TestScenario {

    private static final int X = -16, Y = TestWorldFixtures.Y, Z = 16;
    private FakePlayer owner;
    private ItemStack terminal, previousTerminal;
    private TileEntity rejectedCandidate;
    private String generation;
    private int stage;
    private long stageTick, placementWorldTick;
    private PlacementObserver observer;
    private boolean observerRegistered;

    @Override
    public String id() {
        return "quantum.anchor_lifecycle";
    }

    @Override
    public String description() {
        return "Native first-anchor/legacy alias, controller placement rollback, generation and policy-mixin lifecycle";
    }

    @Override
    public int timeoutTicks() {
        return 1200;
    }

    @Override
    public void start(TestContext ctx) {
        TestWorldFixtures.requireTestWorld(ctx.world());
        owner = TestActors.player(ctx.world(), "owner");
        owner.capabilities.allowEdit = true;
        owner.capabilities.isCreativeMode = false;
        owner.setSneaking(false);
        owner.setPosition(X + 1.5, Y, Z + 2.5);
        terminal = null;
        previousTerminal = null;
        rejectedCandidate = null;
        generation = null;
        stage = 0;
        stageTick = ctx.tick();
        observerRegistered = false;
        clear(ctx);
        placeInitial(ctx);
        observer = new PlacementObserver(ctx, owner);
        MinecraftForge.EVENT_BUS.register(observer);
        observerRegistered = true;
        ctx.event(
            "fixture",
            "Real hash actor and AE controllers at (-16..-13,65,16); no human inventory or mode changes");
    }

    @Override
    public void tick(TestContext ctx) throws Exception {
        power(ctx);
        require(ctx, ctx.tick() - stageTick <= 300, "Natural AE transition within 300 ticks, stage=" + stage);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(ctx.world());
        if (stage == 0) {
            if (!ready(ctx, 2)) return;
            legacyAlias(ctx);
            terminal = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
            clickTerminal(ctx);
            require(ctx, ItemNetworkQuantumTerminal.isBound(terminal), "Real terminal gesture binds active structure");
            generation = registry.canonicalAnchor(pos(0)).generation;
            require(
                ctx,
                registry.canonicalAnchor(pos(0)).position == pos(1),
                "First successful click fixes canonical member");
            require(
                ctx,
                generation.equals(
                    terminal.getTagCompound()
                        .getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION)),
                "New terminal carries generation");
            next(ctx, 1);
        } else if (stage == 1) {
            ordinaryCablePlacement(ctx);
            observer.expectedX = X + 2;
            observer.cancel = false;
            int observations = observer.observed;
            placementWorldTick = ctx.world()
                .getTotalWorldTime();
            ItemStack stack = controllerStack();
            require(
                ctx,
                placeThroughManager(ctx, X + 1, stack),
                "ItemInWorldManager permits controller on quantum controller face");
            require(
                ctx,
                stack.stackSize == 0 && observer.observed == observations + 1,
                "Forge placement consumed one real controller and delivered PlaceEvent");
            require(ctx, !registry.isQuantized(pos(2)), "PlaceEvent isolates candidate before natural END commit");
            next(ctx, 2);
        } else if (stage == 2) {
            require(
                ctx,
                ctx.world()
                    .getTotalWorldTime() - placementWorldTick <= 1,
                "Expansion commits within the following natural server END");
            require(ctx, registry.isQuantized(pos(2)), "Natural END enrolled placed controller");
            require(
                ctx,
                registry.canonicalAnchor(pos(2)).position == pos(1)
                    && generation.equals(registry.canonicalAnchor(pos(2)).generation),
                "Expansion retains first point and generation");
            next(ctx, 3);
        } else if (stage == 3) {
            if (!ready(ctx, 3)) return;
            policyMixin(ctx);
            observer.expectedX = X + 3;
            observer.cancel = true;
            ItemStack stack = controllerStack();
            try {
                require(
                    ctx,
                    !placeThroughManager(ctx, X + 2, stack),
                    "LOWEST protection listener cancels real Forge placement");
            } finally {
                unregisterObserver();
            }
            require(
                ctx,
                observer.cancellations == 1 && stack.stackSize == 1,
                "Canceled Forge placement restores original item count");
            require(
                ctx,
                ctx.world()
                    .isAirBlock(X + 3, Y, Z),
                "Forge restores canceled block snapshot");
            next(ctx, 4);
        } else if (stage == 4) {
            require(
                ctx,
                registry.isQuantized(pos(0)) && registry.isQuantized(pos(1))
                    && registry.isQuantized(pos(2))
                    && !registry.isQuantized(pos(3))
                    && generation.equals(registry.canonicalAnchor(pos(0)).generation),
                "Canceled candidate invalidation leaves original generation intact after natural END");
            previousTerminal = terminal.copy();
            require(
                ctx,
                ctx.world()
                    .setBlockToAir(X + 1, Y, Z),
                "Real removal breaks canonical controller");
            next(ctx, 5);
        } else if (stage == 5) {
            require(
                ctx,
                !registry.isQuantized(pos(0)) && !registry.isQuantized(pos(1)) && !registry.isQuantized(pos(2)),
                "Actual controller removal retires whole old structure");
            require(
                ctx,
                !QuantumTerminalAccess.validate(owner, previousTerminal),
                "Retired generation rejects old terminal");
            clear(ctx);
            placeInitial(ctx);
            next(ctx, 6);
        } else if (stage == 6) {
            if (!ready(ctx, 2)) return;
            terminal = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
            clickTerminal(ctx);
            QuantumControllerRegistry.CanonicalAnchor rebuilt = registry.canonicalAnchor(pos(1));
            require(
                ctx,
                rebuilt != null && rebuilt.position == pos(1) && !generation.equals(rebuilt.generation),
                "Rebuild at identical first point gets another generation");
            require(
                ctx,
                !QuantumTerminalAccess.validate(owner, previousTerminal) && generation.equals(
                    previousTerminal.getTagCompound()
                        .getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION)),
                "Old terminal cannot adopt rebuilt generation during ordinary synchronization");
            TileEntity root = tile(ctx, 1);
            long epoch = QuantumPolicyEpoch.current();
            ((QuantumControllerIdentity) root).gtswn$setControllerIdentity(
                UUID.randomUUID()
                    .toString());
            require(ctx, QuantumPolicyEpoch.current() > epoch, "Real in-place identity setter emits policy epoch");
            ItemStack candidateStack = controllerStack();
            require(
                ctx,
                placeThroughManager(ctx, X + 1, candidateStack),
                "Real candidate placement reaches expansion guard");
            rejectedCandidate = tile(ctx, 2);
            require(
                ctx,
                candidateStack.stackSize == 0 && rejectedCandidate instanceof TileController
                    && !rejectedCandidate.isInvalid(),
                "Guard candidate consumed one controller and remains a real live tile");
            next(ctx, 7);
        } else if (stage == 7) {
            require(
                ctx,
                tile(ctx, 2) == rejectedCandidate && !rejectedCandidate.isInvalid(),
                "Natural END retains the exact candidate tile; rejection is not deletion or replacement");
            require(
                ctx,
                !registry.isQuantized(pos(2)),
                "Expansion rejects old-member identity mutation before adopting candidate");
            require(
                ctx,
                !registry.isQuantized(pos(1)) || !registry.isCurrentController(ctx.world(), pos(1)),
                "Expansion never overwrites original expected identity to adopt the mutated object");
            ctx.pass(
                "Native first point, legacy alias, natural END expansion/rollback, retirement/rebuild and tracked policy NBT passed");
        }
    }

    private void clickTerminal(TestContext ctx) {
        owner.inventory.currentItem = 0;
        owner.inventory.setInventorySlotContents(0, terminal);
        require(
            ctx,
            owner.theItemInWorldManager.activateBlockOrUseItem(
                owner,
                ctx.world(),
                terminal,
                X + 1,
                Y,
                Z,
                ForgeDirection.UP.ordinal(),
                .5f,
                .5f,
                .5f),
            "Real ItemInWorldManager terminal controller gesture");
    }

    private boolean placeThroughManager(TestContext ctx, int clickedX, ItemStack stack) {
        owner.inventory.currentItem = 0;
        owner.inventory.setInventorySlotContents(0, stack);
        owner.setSneaking(false);
        return owner.theItemInWorldManager.activateBlockOrUseItem(
            owner,
            ctx.world(),
            stack,
            clickedX,
            Y,
            Z,
            ForgeDirection.EAST.ordinal(),
            .5f,
            .5f,
            .5f);
    }

    private void ordinaryCablePlacement(TestContext ctx) {
        TestWorldFixtures.place(ctx.world(), X + 6, Y, Z, Blocks.stone, 0);
        TestWorldProbes.addPosition(X + 7, Y, Z);
        owner.setPosition(X + 6.5, Y, Z + 2.5);
        ItemStack positive = AEApi.instance()
            .definitions()
            .parts()
            .cableGlass()
            .stack(AEColor.Transparent, 1);
        require(ctx, placeThroughManager(ctx, X + 6, positive), "Real AE glass cable places on an ordinary stone face");
        TileEntity cable = ctx.world()
            .getTileEntity(X + 7, Y, Z);
        require(
            ctx,
            positive.stackSize == 0 && cable instanceof TileCableBus
                && ((TileCableBus) cable).getPart(ForgeDirection.UNKNOWN) instanceof PartCable,
            "Ordinary-face positive control consumes one glass cable and creates the real cable part");
        owner.setPosition(X + 1.5, Y, Z + 2.5);
        require(
            ctx,
            ctx.world()
                .isAirBlock(X + 2, Y, Z),
            "Quantum-face cable target starts empty");
        ItemStack negative = AEApi.instance()
            .definitions()
            .parts()
            .cableGlass()
            .stack(AEColor.Transparent, 1);
        int canceledBefore = observer.cableCancellations;
        require(
            ctx,
            !placeThroughManager(ctx, X + 1, negative),
            "Real ItemInWorldManager rejects ordinary cable on a quantum controller face");
        require(
            ctx,
            observer.cableCancellations == canceledBefore + 1 && negative.stackSize == 1
                && ctx.world()
                    .isAirBlock(X + 2, Y, Z),
            "Production PlayerInteract cancellation leaves cable unconsumed and quantum-face target empty");
    }

    private void legacyAlias(TestContext ctx) throws IllegalAccessException {
        // Prime these normal stores before the tightly scoped map splice. The temporary registry never enters
        // saveAllData's list.
        QuantumMigrationJournal.get(ctx.world());
        QuantumNetworkColorRegistry.get(ctx.world());
        QuantumNetworkSecurityRegistry.get(ctx.world());
        MapStorage storage = ctx.world().perWorldStorage;
        Field mapField = ReflectionHelper.findField(MapStorage.class, "loadedDataMap", "field_75749_b");
        Field listField = ReflectionHelper.findField(MapStorage.class, "loadedDataList", "field_75750_c");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) mapField.get(storage);
        List<?> list = (List<?>) listField.get(storage);
        Object original = map.get(QuantumControllerRegistry.DATA_NAME);
        int originalIndex = list.indexOf(original);
        require(
            ctx,
            original != null && originalIndex >= 0,
            "Original registry is present in exact MapStorage map/list");
        QuantumControllerRegistry legacy = new QuantumControllerRegistry(QuantumControllerRegistry.DATA_NAME);
        NBTTagCompound old = new NBTTagCompound();
        old.setIntArray(
            "controllers",
            new int[] { ctx.world().provider.dimensionId, X, Y, Z, ctx.world().provider.dimensionId, X + 1, Y, Z });
        legacy.readFromNBT(old);
        try {
            map.put(QuantumControllerRegistry.DATA_NAME, legacy);
            QuantumControllerRegistry live = QuantumControllerRegistry.get(ctx.world());
            ItemStack oldTerminal = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
            NBTTagCompound tag = new NBTTagCompound();
            tag.setByte(ItemNetworkQuantumTerminal.NBT_BOUND, (byte) 1);
            tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM, ctx.world().provider.dimensionId);
            tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_X, X + 1);
            tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Y, Y);
            tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Z, Z);
            // This offline old item predates the current journal head; the captured cursor excludes unrelated history.
            tag.setLong(
                QuantumTerminalAccess.NBT_MIGRATION_CURSOR,
                QuantumMigrationJournal.get(ctx.world())
                    .currentSequence());
            oldTerminal.setTagCompound(tag);
            QuantumTerminalAccess.synchronize(owner, oldTerminal);
            QuantumControllerRegistry.CanonicalAnchor selected = live.resolveAnchor(pos(1), "");
            require(
                ctx,
                selected != null
                    && selected.generation.equals(tag.getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION))
                    && QuantumControllerRegistry.pack(tag.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_X), Y, Z)
                        == selected.position,
                "Real old terminal NBT lazily adopts migrated canonical alias");
            NBTTagCompound saved = new NBTTagCompound();
            live.writeToNBT(saved);
            QuantumControllerRegistry reloaded = new QuantumControllerRegistry("test_roundtrip");
            reloaded.readFromNBT(saved);
            require(
                ctx,
                saved.getInteger("anchorSchema") == 2
                    && reloaded.resolveAnchor(pos(0), "").position == selected.position
                    && reloaded.resolveAnchor(pos(0), "").generation.equals(selected.generation),
                "Old registry migration fixes one persistent first point across NBT reload");
        } finally {
            map.put(QuantumControllerRegistry.DATA_NAME, original);
            legacy.setDirty(false);
            QuantumNetworkStatsCache.clear();
            QuantumNetworkData.clearCache();
            QuantumNetworkSecurityRegistry.get(ctx.world())
                .clearPolicyCache();
            require(
                ctx,
                mapField.get(storage) == map && listField.get(storage) == list
                    && map.get(QuantumControllerRegistry.DATA_NAME) == original
                    && list.indexOf(original) == originalIndex
                    && !list.contains(legacy),
                "Temporary old-NBT registry restores exact storage objects without dirty save-list entries");
        }
    }

    private void policyMixin(TestContext ctx) {
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(ctx.world());
        QuantumNetworkSecurityRegistry policy = QuantumNetworkSecurityRegistry.get(ctx.world());
        java.util.Set<Long> members = registry.registeredStructure(pos(1));
        TileEntity root = tile(ctx, 1);
        for (long position : members) require(
            ctx,
            ctx.world()
                .getTileEntity(
                    QuantumControllerRegistry.unpackX(position),
                    Y,
                    Z) instanceof QuantumPolicyEpoch.TrackedController,
            "Real controller carries tracked-policy mixin");
        policy.setSafety(ctx.world(), members, false);
        require(ctx, !policy.isSafetyEnabled(ctx.world(), members), "Warm real policy cache OFF");
        long epoch = QuantumPolicyEpoch.current();
        ((QuantumControllerIdentity) root).gtswn$setNetworkSafetyEnabled(true);
        require(
            ctx,
            QuantumPolicyEpoch.current() > epoch && policy.isSafetyEnabled(ctx.world(), members),
            "Real setter invalidates warm OFF cache without manual epoch notification");
        policy.setSafety(ctx.world(), members, false);
        require(
            ctx,
            !policy.isSafetyEnabled(ctx.world(), members),
            "Real network restored OFF before fresh NBT fixtures");
        IGridNode node = ((IGridHost) root).getGridNode(ForgeDirection.UNKNOWN);
        int ownerId = node.getPlayerID();
        String identity = ((QuantumControllerIdentity) root).gtswn$getControllerIdentity();
        NBTTagCompound saved = new NBTTagCompound();
        controllerNbt(ctx, root, saved, false, "live_policy_snapshot");
        saved.setBoolean("GTSWN_NetworkSafetyEnabled", true);
        freshPolicyRead(ctx, saved, identity, false);
        freshPolicyRead(ctx, saved, identity, true);
        require(
            ctx,
            identity.equals(((QuantumControllerIdentity) root).gtswn$getControllerIdentity())
                && ((IGridHost) root).getGridNode(ForgeDirection.UNKNOWN)
                    .getPlayerID() == ownerId
                && !policy.isSafetyEnabled(ctx.world(), members),
            "Fresh unattached NBT reads leave real network OFF and its identity/native owner unchanged");
    }

    /** AE forbids loading full NBT into an existing grid node; exercise actual pre-onReady deserialization. */
    private static void freshPolicyRead(TestContext ctx, NBTTagCompound saved, String identity, boolean worldBound) {
        TileEntity fresh = ctx.world()
            .getBlock(X + 1, Y, Z)
            .createTileEntity(
                ctx.world(),
                ctx.world()
                    .getBlockMetadata(X + 1, Y, Z));
        try {
            fresh.setWorldObj(worldBound ? ctx.world() : null);
            require(
                ctx,
                !((IGridProxyable) fresh).getProxy()
                    .isReady(),
                "Fresh controller has not joined an AE grid");
            long epoch = QuantumPolicyEpoch.current();
            controllerNbt(ctx, fresh, saved, true, worldBound ? "world_bound_unattached_policy" : "world_null_policy");
            require(
                ctx,
                fresh instanceof QuantumPolicyEpoch.TrackedController && QuantumPolicyEpoch.current() > epoch
                    && ((QuantumControllerIdentity) fresh).gtswn$isNetworkSafetyEnabled()
                    && identity.equals(((QuantumControllerIdentity) fresh).gtswn$getControllerIdentity()),
                "Real fresh " + (worldBound ? "world-bound unattached" : "world-null")
                    + " NBT read emits invalidation and preserves saved safety/identity");
            require(
                ctx,
                ctx.world()
                    .getTileEntity(X + 1, Y, Z) != fresh,
                "Fresh deserialization fixture never replaces live controller");
            epoch = QuantumPolicyEpoch.current();
            fresh.validate();
            require(ctx, QuantumPolicyEpoch.current() > epoch, "Real controller validate emits invalidation");
        } finally {
            // AE queues validate() for onReady; invalidating here makes that ordinary queue skip this unattached
            // fixture.
            fresh.setWorldObj(null);
            fresh.invalidate();
        }
    }

    /** Keep each real AE entry point visible; TestContext records any nested handler failure. */
    private static void controllerNbt(TestContext ctx, TileEntity tile, NBTTagCompound tag, boolean read,
        String phase) {
        String operation = phase + (read ? ":readFromNBT" : ":writeToNBT");
        ctx.event(
            "native_nbt",
            operation + ":begin;tile="
                + tile.getClass()
                    .getName());
        if (read) tile.readFromNBT(tag);
        else tile.writeToNBT(tag);
        ctx.event("native_nbt", operation + ":complete");
    }

    private void placeInitial(TestContext ctx) {
        for (int offset = 0; offset < 2; offset++)
            TestWorldFixtures.aeOwnedBlock(ctx.world(), X + offset, Y, Z, controllerStack(), owner);
    }

    private static ItemStack controllerStack() {
        return AEApi.instance()
            .definitions()
            .blocks()
            .controller()
            .maybeStack(1)
            .get();
    }

    private static long pos(int offset) {
        return QuantumControllerRegistry.pack(X + offset, Y, Z);
    }

    private static TileEntity tile(TestContext ctx, int offset) {
        return ctx.world()
            .getTileEntity(X + offset, Y, Z);
    }

    private static boolean ready(TestContext ctx, int count) {
        for (int offset = 0; offset < count; offset++) {
            TileEntity tile = tile(ctx, offset);
            if (!(tile instanceof TileController) || tile.isInvalid()) return false;
            AENetworkProxy proxy = ((IGridProxyable) tile).getProxy();
            if (!proxy.isReady()) return false;
            try {
                if (proxy.getPath()
                    .getControllerState() != ControllerState.CONTROLLER_ONLINE
                    || !proxy.getEnergy()
                        .isNetworkPowered())
                    return false;
            } catch (GridAccessException exception) {
                return false;
            }
        }
        return true;
    }

    private static void power(TestContext ctx) {
        for (int offset = 0; offset < 4; offset++) {
            TileEntity tile = tile(ctx, offset);
            if (!(tile instanceof TileController) || tile.isInvalid()) continue;
            AENetworkProxy proxy = ((IGridProxyable) tile).getProxy();
            if (!proxy.isReady()) continue;
            try {
                proxy.getEnergy()
                    .injectPower(200, Actionable.MODULATE);
            } catch (GridAccessException ignored) { /* Wait for natural AE readiness. */ }
        }
    }

    private void next(TestContext ctx, int value) {
        stage = value;
        stageTick = ctx.tick();
        ctx.event("stage", id() + ":" + value);
    }

    private static void require(TestContext ctx, boolean condition, String detail) {
        TestWorldFixtures.require(ctx, condition, detail);
    }

    private void unregisterObserver() {
        if (observerRegistered) MinecraftForge.EVENT_BUS.unregister(observer);
        observerRegistered = false;
    }

    private static void clear(TestContext ctx) {
        TestWorldFixtures.requireTestWorld(ctx.world());
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(ctx.world());
        for (int offset = 0; offset < 4; offset++) {
            registry.dequantize(pos(offset));
            TileEntity tile = tile(ctx, offset);
            if (tile instanceof AEBaseTile) ((AEBaseTile) tile).disableDrops();
            TestWorldProbes.addPosition(X + offset, Y, Z);
            ctx.world()
                .setBlockToAir(X + offset, Y, Z);
        }
        for (int offset = 6; offset <= 7; offset++) {
            TileEntity tile = tile(ctx, offset);
            if (tile instanceof AEBaseTile) ((AEBaseTile) tile).disableDrops();
            TestWorldProbes.addPosition(X + offset, Y, Z);
            ctx.world()
                .setBlockToAir(X + offset, Y, Z);
        }
    }

    @Override
    public void cleanup(TestContext ctx) {
        unregisterObserver();
        try {
            clear(ctx);
        } finally {
            if (owner != null) owner.inventory.clearInventory(null, -1);
            owner = null;
            terminal = null;
            previousTerminal = null;
            rejectedCandidate = null;
            observer = null;
        }
    }

    public static final class PlacementObserver {

        private final TestContext context;
        private final FakePlayer actor;
        private int expectedX, observed, cancellations, cableCancellations;
        private boolean cancel;

        PlacementObserver(TestContext context, FakePlayer actor) {
            this.context = context;
            this.actor = actor;
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void placed(BlockEvent.PlaceEvent event) {
            if (event.world != context.world() || event.player != actor
                || event.x != expectedX
                || event.y != Y
                || event.z != Z) return;
            observed++;
            context.event(
                "native_place",
                "Forge PlaceEvent at x=" + event.x + ", canceledBeforeObserver=" + event.isCanceled());
            if (cancel) {
                cancellations++;
                event.setCanceled(true);
            }
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void interacted(PlayerInteractEvent event) {
            if (event.world != context.world() || event.entityPlayer != actor
                || event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK
                || event.x != X + 1
                || event.y != Y
                || event.z != Z
                || !AEApi.instance()
                    .definitions()
                    .parts()
                    .cableGlass()
                    .sameAs(AEColor.Transparent, actor.getHeldItem()))
                return;
            context
                .event("native_cable_interact", "Real quantum-face cable interaction canceled=" + event.isCanceled());
            if (event.isCanceled()) cableCancellations++;
        }
    }
}
