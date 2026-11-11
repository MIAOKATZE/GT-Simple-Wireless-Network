package com.miaokatze.gtswn.common.testworld;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.FakePlayer;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumMigrationJournal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkMigration;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkSecurityRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.config.Config;

import appeng.api.AEApi;
import appeng.api.config.SecurityPermissions;
import appeng.api.features.ILocatable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.ISecurityProvider;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.AEBaseTile;

/** Native key/grid and identity history. Same-process tile recreation is never called a JVM restart. */
final class QuantumSecurityHistoryScenario implements TestScenario {

    enum Mode {
        MIGRATION,
        IDENTITY_RELOAD
    }

    private static final int SOURCE_X = -4, TARGET_X = 8, Y = TestWorldFixtures.Y, Z = 16;
    private static final int NODE_X = -4, NODE_Z = 20;
    private final Mode mode;
    private final Set<Long> introducedProviderKeys = new HashSet<>();
    private FakePlayer owner, member;
    private TileEntity source, sourceStation, target, targetStation;
    private TileEntityNetworkQuantumNode node;
    private ItemStack terminal, memberCard;
    private boolean sourcePlaced, sourceStationPlaced, targetPlaced, targetStationPlaced, nodeReserved;
    private int stage;
    private long stageTick, sourceKey;
    private String generation, safetyIdentity;
    private IGrid sourceGridBeforeMigration;

    QuantumSecurityHistoryScenario(Mode mode) {
        this.mode = mode;
    }

    @Override
    public String id() {
        return mode == Mode.MIGRATION ? "quantum.native_security_migration" : "quantum.native_security_identity_reload";
    }

    @Override
    public String description() {
        return mode == Mode.MIGRATION
            ? "All-permissions non-OP teammate: committed migration, old source-key denial and native target re-pairing"
            : "All-permissions non-OP teammate: same-process controller/station NBT recreation, changed-key denial and recovery";
    }

    @Override
    public int timeoutTicks() {
        return 1800;
    }

    @Override
    public boolean includeInAll() {
        return false;
    }

    @Override
    public void start(TestContext ctx) {
        stage = 0;
        stageTick = ctx.tick();
        sourcePlaced = sourceStationPlaced = targetPlaced = targetStationPlaced = nodeReserved = false;
        source = sourceStation = target = targetStation = null;
        node = null;
        owner = member = null;
        terminal = memberCard = null;
        generation = safetyIdentity = null;
        sourceGridBeforeMigration = null;
        introducedProviderKeys.clear();
        TestWorldFixtures.requireTestWorld(ctx.world());
        if (!Config.quantumSafetyEnabled) {
            ctx.skip("Global quantum safety is disabled; history case leaves Config unchanged");
            return;
        }
        int[] fixtureXs = mode == Mode.MIGRATION ? new int[] { SOURCE_X, SOURCE_X + 1, TARGET_X, TARGET_X + 1 }
            : new int[] { SOURCE_X, SOURCE_X + 1 };
        for (int x : fixtureXs) require(
            ctx,
            ctx.world()
                .isAirBlock(x, Y, Z),
            "History controller/station position starts empty at x=" + x);
        require(
            ctx,
            ctx.world()
                .isAirBlock(NODE_X, Y, NODE_Z),
            "History node target starts empty");
        require(
            ctx,
            ctx.world()
                .isAirBlock(NODE_X, Y - 1, NODE_Z),
            "History node support starts empty");
        owner = NativeSecurityStationSupport.actor(ctx, "owner");
        member = NativeSecurityStationSupport.actor(ctx, "member");
        NativeSecurityMatrix.assertOrdinaryActors(ctx, "history-start", owner, member);
        owner.setPosition(SOURCE_X + .5, Y, Z + 2.5);
        member.setPosition(SOURCE_X + .5, Y, Z + 2.5);
        sourcePlaced = true;
        source = place(ctx, SOURCE_X, false);
        sourceStationPlaced = true;
        sourceStation = place(ctx, SOURCE_X + 1, true);
        if (mode == Mode.MIGRATION) {
            targetPlaced = true;
            target = place(ctx, TARGET_X, false);
            targetStationPlaced = true;
            targetStation = place(ctx, TARGET_X + 1, true);
        }
        nodeReserved = true;
        TestWorldProbes.addPosition(NODE_X, Y, NODE_Z);
        TestWorldFixtures.place(ctx.world(), NODE_X, Y - 1, NODE_Z, Blocks.stone, 0);
        ctx.event("history_scope", "Natural server ticks; real native stations/cards; no cache reset; same JVM only");
    }

    private TileEntity place(TestContext ctx, int x, boolean station) {
        ItemStack stack = station ? AEApi.instance()
            .definitions()
            .blocks()
            .security()
            .maybeStack(1)
            .get()
            : AEApi.instance()
                .definitions()
                .blocks()
                .controller()
                .maybeStack(1)
                .get();
        return TestWorldFixtures.aeOwnedBlock(ctx.world(), x, Y, Z, stack, owner);
    }

    @Override
    public void tick(TestContext ctx) {
        NativeSecurityStationSupport.power(source);
        NativeSecurityStationSupport.power(target);
        if (ctx.tick() - stageTick > 300) {
            ctx.fail("History transition timed out: mode=" + mode + "; stage=" + stage + "; " + evidence(ctx));
            return;
        }
        require(ctx, Config.quantumSafetyEnabled, "Global safety stays enabled throughout history case");
        if (stage == 0) {
            if (!ready()) return;
            assertOwners(ctx);
            memberCard = NativeSecurityStationSupport.allPermissionsCard(member);
            require(
                ctx,
                NativeSecurityStationSupport.insertCard(ctx, sourceStation, owner, memberCard) == null,
                "Owner A inserts real all-permissions card for ordinary teammate B at source");
            if (targetStation != null) require(
                ctx,
                NativeSecurityStationSupport.insertCard(ctx, targetStation, owner, memberCard) == null,
                "Owner A separately provisions B with all native permissions at destination");
            terminal = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
            owner.inventory.setInventorySlotContents(0, terminal);
            click(ctx, owner, terminal, SOURCE_X, Y, Z);
            requireAnchor(ctx, SOURCE_X);
            terminal = encode(ctx, sourceStation);
            require(
                ctx,
                QuantumTerminalAccess.setNetworkSafety(owner, terminal, true),
                "Native owner enables source safety through production authority");
            sourceKey = ((ISecurityProvider) sourceStation).getSecurityKey();
            generation = terminal.getTagCompound()
                .getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION);
            safetyIdentity = QuantumTerminalAccess.networkSafetyIdentity(ctx.world(), SOURCE_X, Y, Z);
            member.inventory.setInventorySlotContents(0, terminal);
            next(ctx, 1);
            return;
        }
        if (stage == 1) {
            if (!ready() || !allGrants(source) || target != null && !allGrants(target)) return;
            assertOwners(ctx);
            click(ctx, member, terminal, SOURCE_X, Y, Z);
            requireAnchor(ctx, SOURCE_X);
            requireFull(ctx, "Before history transition");
            if (mode == Mode.MIGRATION) {
                sourceGridBeforeMigration = grid(source);
                require(ctx, grid(source) != grid(target), "Source and destination are genuinely distinct AE grids");
                require(
                    ctx,
                    QuantumNetworkMigration.migrate(member, terminal, ctx.world(), TARGET_X, Y, Z),
                    "All-permissions teammate commits production migration transaction");
                require(
                    ctx,
                    !QuantumControllerRegistry.get(ctx.world())
                        .isQuantized(SOURCE_X, Y, Z) && QuantumControllerRegistry.get(ctx.world())
                            .isQuantized(TARGET_X, Y, Z),
                    "Migration actually retires source and registers destination");
                requireAnchor(ctx, TARGET_X);
                next(ctx, 2);
            } else {
                TileEntity previous = source;
                source = TestWorldFixtures.reload(ctx.world(), source);
                require(ctx, source != previous && previous.isInvalid(), "Controller NBT recreates a new live tile");
                previous = sourceStation;
                sourceStation = TestWorldFixtures.reload(ctx.world(), sourceStation);
                require(
                    ctx,
                    sourceStation != previous && previous.isInvalid(),
                    "Station NBT recreates a new live tile");
                ctx.event(
                    "reload_scope",
                    "Same-process NBT serialization/unload/new TE/validation; no cross-JVM claim");
                next(ctx, 4);
            }
            return;
        }
        if (stage == 2) {
            if (!ready() || !allGrants(source) || !allGrants(target)) return;
            requireAnchor(ctx, TARGET_X);
            assertOwners(ctx);
            require(
                ctx,
                grid(source) == sourceGridBeforeMigration && grid(sourceStation) == grid(source),
                "Original native security station remains on original source grid after migration");
            require(
                ctx,
                grid(targetStation) == grid(target) && grid(source) != grid(target),
                "Destination has its own separate real security-provider grid");
            require(
                ctx,
                encodedGrid() == grid(source) && encodedGrid() != grid(target),
                "Untouched terminal native key still resolves source provider rather than current anchor grid");
            require(
                ctx,
                Long.toString(sourceKey)
                    .equals(key()),
                "Migration retains actual source native key");
            denyNode(ctx, "Migrated anchor with source native key");
            ctx.event(
                "REPRODUCED_DENIAL_AFTER_MIGRATION",
                "seamlessUsability=false; rePairRequired=true; " + evidence(ctx));
            terminal = encode(ctx, targetStation);
            member.inventory.setInventorySlotContents(0, terminal);
            next(ctx, 3);
            return;
        }
        if (stage == 3) {
            if (!ready() || !allGrants(target)) return;
            require(
                ctx,
                encodedGrid() == grid(target),
                "Native destination encoder repairs key/current-anchor grid match");
            requireAnchor(ctx, TARGET_X);
            requireFull(ctx, "After destination native re-pairing");
            placeNode(ctx);
            next(ctx, 7);
            return;
        }
        if (stage == 4) {
            if (!ready() || !allGrants(source)) return;
            requireAnchor(ctx, SOURCE_X);
            assertOwners(ctx);
            require(
                ctx,
                ((ISecurityProvider) sourceStation).getSecurityKey() == sourceKey && Long.toString(sourceKey)
                    .equals(key()) && encodedGrid() == grid(source),
                "Recreated station re-registers same native key and current provider grid; expectedKey=" + sourceKey
                    + "; "
                    + evidence(ctx));
            require(
                ctx,
                safetyIdentity.equals(QuantumTerminalAccess.networkSafetyIdentity(ctx.world(), SOURCE_X, Y, Z)),
                "Controller NBT recreation preserves authoritative safety identity; expected=" + safetyIdentity
                    + "; actual="
                    + QuantumTerminalAccess.networkSafetyIdentity(ctx.world(), SOURCE_X, Y, Z));
            requireFull(ctx, "Same-key NBT recreation");
            removeStation(ctx, SOURCE_X + 1);
            sourceStation = null;
            next(ctx, 5);
            return;
        }
        if (stage == 5) {
            if (ctx.tick() - stageTick < 25) return;
            require(ctx, !QuantumTerminalAccess.validate(member, terminal), "Removed provider denies old encoded key");
            sourceStation = place(ctx, SOURCE_X + 1, true);
            next(ctx, 6);
            return;
        }
        if (stage == 6) {
            if (!ready()) return;
            require(
                ctx,
                ((ISecurityProvider) sourceStation).getSecurityKey() != sourceKey,
                "Replacement station generates different native key");
            if (!allGrants(source)) {
                if (NativeSecurityStationSupport.insertCard(ctx, sourceStation, owner, memberCard) != null)
                    require(ctx, false, "Owner provisions replacement station with original all-permissions card");
                next(ctx, 8);
                return;
            }
            next(ctx, 8);
            return;
        }
        if (stage == 8) {
            if (!ready() || !allGrants(source)) return;
            assertOwners(ctx);
            denyNode(ctx, "All native grants with old key after station replacement");
            ctx.event("REPRODUCED_DENIAL_AFTER_KEY_CHANGE", evidence(ctx));
            terminal = encode(ctx, sourceStation);
            member.inventory.setInventorySlotContents(0, terminal);
            requireFull(ctx, "After replacement native re-pairing");
            placeNode(ctx);
            next(ctx, 7);
            return;
        }
        if (stage == 7) {
            if (!ready() || node == null || !node.isLinked()) return;
            TileEntity anchor = mode == Mode.MIGRATION ? target : source;
            require(
                ctx,
                node.getCoreGridNode()
                    .getGrid() == grid(anchor) && node.getNativeOwnerID() == actorId(member),
                "Production item-placed node naturally joins actual current anchor grid as teammate B");
            require(
                ctx,
                node.getAnchorX() == anchor.xCoord && generation.equals(node.getAnchorGeneration()),
                "Connected node retains canonical generation and current destination");
            assertOwners(ctx);
            requireFull(ctx, "Final repaired native access");
            NativeSecurityMatrix.assertOrdinaryActors(ctx, "history-complete", owner, member);
            ctx.pass(
                mode == Mode.MIGRATION
                    ? "Reproduced all-grants old-key denial after committed migration; native destination re-pairing restores full data and natural node connection; seamless migration NOT established"
                    : "Same-process native owner/key/controller identity NBT recovery, changed-key denial and native re-pairing restore full data and natural node connection");
        }
    }

    private boolean ready() {
        return NativeSecurityStationSupport.ready(source, sourceStation)
            && (target == null || NativeSecurityStationSupport.ready(target, targetStation));
    }

    private static IGrid grid(TileEntity tile) {
        return tile == null || tile.isInvalid() || NativeSecurityStationSupport.node(tile) == null ? null
            : NativeSecurityStationSupport.node(tile)
                .getGrid();
    }

    private static int actorId(FakePlayer actor) {
        return AEApi.instance()
            .registries()
            .players()
            .getID(actor);
    }

    private boolean allGrants(TileEntity controller) {
        ISecurityGrid security = grid(controller).getCache(ISecurityGrid.class);
        for (SecurityPermissions permission : SecurityPermissions.values())
            if (!security.hasPermission(member, permission)) return false;
        return true;
    }

    private void assertOwners(TestContext ctx) {
        int ownerId = actorId(owner);
        require(ctx, ownerId != actorId(member), "Native owner A differs from ordinary teammate B");
        for (TileEntity controller : new TileEntity[] { source, target }) if (controller != null) require(
            ctx,
            NativeSecurityStationSupport.node(controller)
                .getPlayerID() == ownerId,
            "Native controller owner remains A at x=" + controller.xCoord);
        for (TileEntity station : new TileEntity[] { sourceStation, targetStation }) if (station != null) require(
            ctx,
            ((ISecurityProvider) station).getOwner() == ownerId,
            "Native station owner remains A at x=" + station.xCoord);
    }

    private ItemStack encode(TestContext ctx, TileEntity station) {
        return NativeSecurityStationSupport.encodeAt(ctx, terminal, station, owner, introducedProviderKeys::add);
    }

    private String key() {
        return ((ItemNetworkQuantumTerminal) terminal.getItem()).getEncryptionKey(terminal);
    }

    private IGrid encodedGrid() {
        ILocatable location = AEApi.instance()
            .registries()
            .locatable()
            .getLocatableBy(Long.parseLong(key()));
        if (!(location instanceof ISecurityProvider) || !(location instanceof TileEntity)
            || ((ISecurityProvider) location).getSecurityKey() != location.getLocatableSerial()) return null;
        return grid((TileEntity) location);
    }

    private void requireAnchor(TestContext ctx, int x) {
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(terminal);
        require(
            ctx,
            anchor != null && anchor[0] == ctx.world().provider.dimensionId
                && anchor[1] == x
                && anchor[2] == Y
                && anchor[3] == Z,
            "Real terminal anchor resolves expected controller x=" + x);
        if (generation != null) require(
            ctx,
            generation.equals(
                terminal.getTagCompound()
                    .getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION)),
            "History retains original logical network generation");
        if (stage != 0) require(
            ctx,
            QuantumTerminalAccess.isSecurityRequired(ctx.world(), x, Y, Z),
            "Global/local safety remains ON at current anchor");
    }

    private void requireFull(TestContext ctx, String label) {
        QuantumNetworkData data = QuantumNetworkData.assemble(member, terminal);
        require(
            ctx,
            QuantumTerminalAccess.validate(member, terminal) && QuantumTerminalAccess.canManage(member, terminal)
                && data != null
                && data.online
                && !data.managementOnly
                && data.networkSafetyEnabled
                && (data.channelsInfinite || data.totalChannels > 0),
            label + " permits actual full business response; " + evidence(ctx));
    }

    private void denyNode(TestContext ctx, String label) {
        QuantumTerminalAccess.synchronize(member, terminal);
        NativeSecurityStationSupport.RecordingActor recording = (NativeSecurityStationSupport.RecordingActor) member;
        recording.clearMessages();
        NBTTagCompound before = denialSnapshot(ctx);
        require(
            ctx,
            !QuantumTerminalAccess.validate(member, terminal) && !QuantumTerminalAccess.canManage(member, terminal),
            label + " rejects BUILD and management despite all actual native permissions");
        QuantumNetworkData data = QuantumNetworkData.assemble(member, terminal);
        require(ctx, data == null || data.managementOnly, label + " cannot obtain full business data");
        if (data != null) NativeSecurityMatrix.assertManagementOnly(ctx, member, terminal);
        click(ctx, member, terminal, NODE_X, Y - 1, NODE_Z);
        require(
            ctx,
            ctx.world()
                .isAirBlock(NODE_X, Y, NODE_Z),
            label + " produces no unauthorized node");
        String denied = StatCollector.translateToLocalFormatted("gtswn.chat.quantum.no_permission");
        require(
            ctx,
            recording.messages()
                .stream()
                .anyMatch(message -> message.contains(denied)),
            label + " emits actual production no-permission chat");
        ctx.event(
            "native_denied_chat",
            recording.messages()
                .toString());
        require(
            ctx,
            before.equals(denialSnapshot(ctx)),
            label
                + " leaves typed inventory/persistent resources, policy, registries and same-callback AE power unchanged");
    }

    private NBTTagCompound denialSnapshot(TestContext ctx) {
        NBTTagCompound state = new NBTTagCompound(), registry = new NBTTagCompound();
        QuantumControllerRegistry.get(ctx.world())
            .writeToNBT(registry);
        state.setTag("controllers", registry);
        state.setLong(
            "journal",
            QuantumMigrationJournal.get(ctx.world())
                .currentSequence());
        NBTTagList actors = new NBTTagList();
        for (FakePlayer actor : new FakePlayer[] { owner, member }) {
            NBTTagCompound resources = new NBTTagCompound();
            resources.setTag("inventory", actor.inventory.writeToNBT(new NBTTagList()));
            resources.setInteger("selected", actor.inventory.currentItem);
            resources.setTag(
                "persistent",
                actor.getEntityData()
                    .copy());
            actors.appendTag(resources);
        }
        state.setTag("actors", actors);
        NBTTagCompound policy = new NBTTagCompound();
        QuantumNetworkSecurityRegistry.get(ctx.world())
            .writeToNBT(policy);
        state.setTag("policy", policy);
        state.setTag("terminal", terminal.writeToNBT(new NBTTagCompound()));
        TileEntity anchor = mode == Mode.MIGRATION ? target : source;
        state.setBoolean("safety", QuantumTerminalAccess.isNetworkSafetyEnabled(ctx.world(), anchor.xCoord, Y, Z));
        for (TileEntity controller : new TileEntity[] { source, target }) if (controller != null) {
            try {
                state.setDouble(
                    "power" + controller.xCoord,
                    ((IGridProxyable) controller).getProxy()
                        .getEnergy()
                        .getStoredPower());
            } catch (appeng.me.GridAccessException failure) {
                throw new IllegalStateException("Ready fixture lost native energy cache", failure);
            }
        }
        return (NBTTagCompound) state.copy();
    }

    private void placeNode(TestContext ctx) {
        require(
            ctx,
            ctx.world()
                .isAirBlock(NODE_X, Y, NODE_Z),
            "Successful node target is still empty");
        click(ctx, member, terminal, NODE_X, Y - 1, NODE_Z);
        TileEntity placed = ctx.world()
            .getTileEntity(NODE_X, Y, NODE_Z);
        require(
            ctx,
            placed instanceof TileEntityNetworkQuantumNode,
            "Re-paired teammate actually places node via production item interaction");
        node = (TileEntityNetworkQuantumNode) placed;
    }

    private static void click(TestContext ctx, FakePlayer actor, ItemStack stack, int x, int y, int z) {
        actor.inventory.setInventorySlotContents(0, stack);
        actor.inventory.currentItem = 0;
        actor.setSneaking(false);
        ((ItemNetworkQuantumTerminal) stack.getItem())
            .onItemUseFirst(stack, actor, ctx.world(), x, y, z, 1, .5f, .5f, .5f);
    }

    private String evidence(TestContext ctx) {
        return "actor=" + (member == null ? -1 : actorId(member))
            + "; key="
            + (terminal == null ? "null" : key())
            + "; anchor="
            + (terminal == null ? "null" : Arrays.toString(ItemNetworkQuantumTerminal.getAnchor(terminal)))
            + "; sourceStationKey="
            + stationKey(sourceStation)
            + "; targetStationKey="
            + stationKey(targetStation)
            + "; sourceGrid="
            + identity(grid(source))
            + "; targetGrid="
            + identity(grid(target))
            + "; encodedGrid="
            + (terminal == null ? "null" : identity(encodedGrid()))
            + "; sourceAllGrants="
            + (grid(source) != null && allGrants(source))
            + "; targetAllGrants="
            + (grid(target) != null && allGrants(target))
            + "; stage="
            + stage
            + "; tick="
            + ctx.tick();
    }

    private static String identity(Object object) {
        return object == null ? "null" : Integer.toHexString(System.identityHashCode(object));
    }

    private static String stationKey(TileEntity tile) {
        return tile instanceof ISecurityProvider ? Long.toString(((ISecurityProvider) tile).getSecurityKey()) : "null";
    }

    private void next(TestContext ctx, int value) {
        stage = value;
        stageTick = ctx.tick();
        ctx.event("stage", id() + ":" + value);
    }

    private static void require(TestContext ctx, boolean condition, String detail) {
        TestWorldFixtures.require(ctx, condition, detail);
    }

    private void removeStation(TestContext ctx, int x) {
        TileEntity current = ctx.world()
            .getTileEntity(x, Y, Z);
        if (current instanceof AEBaseTile) ((AEBaseTile) current).disableDrops();
        ctx.world()
            .setBlockToAir(x, Y, Z);
    }

    @Override
    public void cleanup(TestContext ctx) {
        TestWorldFixtures.requireTestWorld(ctx.world());
        try {
            if (nodeReserved) ctx.world()
                .setBlockToAir(NODE_X, Y, NODE_Z);
            if (sourcePlaced) QuantumControllerRegistry.get(ctx.world())
                .dequantize(SOURCE_X, Y, Z);
            if (targetPlaced) QuantumControllerRegistry.get(ctx.world())
                .dequantize(TARGET_X, Y, Z);
            QuantumNetworkSecurityRegistry policies = QuantumNetworkSecurityRegistry.get(ctx.world());
            Set<Long> owned = new HashSet<>();
            if (sourcePlaced) owned.add(QuantumControllerRegistry.pack(SOURCE_X, Y, Z));
            if (targetPlaced) owned.add(QuantumControllerRegistry.pack(TARGET_X, Y, Z));
            policies.remove(owned);
            if (sourceStationPlaced) removeStation(ctx, SOURCE_X + 1);
            if (targetStationPlaced) removeStation(ctx, TARGET_X + 1);
            for (long key : introducedProviderKeys) TestFixtureAccess
                .forgetProvider(QuantumNetworkSecurityRegistry.providerRegistry(ctx.world()), ctx.world(), key);
            introducedProviderKeys.clear();
            if (sourcePlaced) ctx.world()
                .setBlockToAir(SOURCE_X, Y, Z);
            if (targetPlaced) ctx.world()
                .setBlockToAir(TARGET_X, Y, Z);
            if (nodeReserved) ctx.world()
                .setBlockToAir(NODE_X, Y - 1, NODE_Z);
        } finally {
            if (owner != null) owner.inventory.clearInventory(null, -1);
            if (member != null) member.inventory.clearInventory(null, -1);
            owner = member = null;
            terminal = memberCard = null;
            source = sourceStation = target = targetStation = null;
            node = null;
            sourceGridBeforeMigration = null;
        }
    }
}
