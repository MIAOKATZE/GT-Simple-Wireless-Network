package com.miaokatze.gtswn.client.engineering;

import net.minecraft.client.Minecraft;

import com.miaokatze.gtswn.common.engineering.EngineeringGrid;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.Source;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.SourceOrigin;
import com.miaokatze.gtswn.network.EngineeringTypedSnapshot;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketEngineeringCompact;
import com.miaokatze.gtswn.network.PacketEngineeringControl;
import com.miaokatze.gtswn.network.PacketEngineeringSubscribe;
import com.miaokatze.gtswn.network.telemetry.MonitorCapabilities;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Client intent and server snapshot have the same bounded, expiring session. */
public final class EngineeringDashboardCache {

    private static long serial = System.currentTimeMillis(), session;
    private static Object world, connection, player;
    private static final DisplayContinuity<EngineeringSnapshot> DISPLAY = new DisplayContinuity<>();
    private static DisplayScope displayScope;
    private static Source source = Source.EMPTY;
    private static int slot = -1, dim, minX, minZ, maxX, maxZ, lastTick;
    private static boolean gtGrid, boundOnly, suspended;
    private static boolean patternKnown, axisPattern;
    private static EngineeringSnapshot snapshot;
    private static long received;
    private static long revision, acknowledged;
    private static byte[] compactBytes;

    private EngineeringDashboardCache() {}

    public static void subscribe(int selection, boolean gt, int x1, int z1, int x2, int z2) {
        subscribe(selection, gt, x1, z1, x2, z2, false);
    }

    public static void subscribe(int selection, boolean gt, int x1, int z1, int x2, int z2, boolean onlyBound) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null || selection < 0 || selection >= 8) {
            DISPLAY.clear();
            close();
            return;
        }
        Source actual = EngineeringTerminalSources.resolve(mc.thePlayer, selection);
        if (!actual.present()) {
            DISPLAY.clear();
            close();
            return;
        }
        int nextMinX = Math.min(x1, x2), nextMinZ = Math.min(z1, z2);
        int nextMaxX = Math.max(x1, x2), nextMaxZ = Math.max(z1, z2);
        if (session != 0 && world == mc.theWorld
            && connection == mc.getNetHandler()
            && player == mc.thePlayer
            && slot == selection
            && source.sameIdentity(actual)
            && gtGrid == gt
            && boundOnly == onlyBound
            && minX == nextMinX
            && minZ == nextMinZ
            && maxX == nextMaxX
            && maxZ == nextMaxZ) return;
        // A replacement session closes the previous server collector itself; send one new request.
        if (world != mc.theWorld || connection != mc.getNetHandler()) patternKnown = false;
        snapshot = null;
        acknowledged = 0;
        compactBytes = null;
        received = 0;
        revision++;
        world = mc.theWorld;
        player = mc.thePlayer;
        connection = mc.getNetHandler();
        slot = selection;
        source = actual;
        dim = mc.theWorld.provider.dimensionId;
        gtGrid = gt;
        boundOnly = onlyBound;
        minX = nextMinX;
        minZ = nextMinZ;
        maxX = nextMaxX;
        maxZ = nextMaxZ;
        displayScope = new DisplayScope(
            world,
            connection,
            player,
            source,
            slot,
            dim,
            gtGrid,
            boundOnly,
            minX,
            minZ,
            maxX,
            maxZ);
        DISPLAY.open(displayScope);
        suspended = false;
        session = ++serial;
        send(true, false);
    }

    public static void tick() {
        if (session == 0) {
            if (!currentScopeValid()) forgetScope();
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if ((!EngineeringModeController.isActive() && !EngineeringModeController.dashboardHostVisible())
            || world != mc.theWorld
            || connection != mc.getNetHandler()
            || player != mc.thePlayer
            || mc.thePlayer == null
            || mc.thePlayer.isDead) {
            close();
            return;
        }
        if (!sameSource(mc)) {
            close();
            return;
        }
        if (mc.isGamePaused()) return;
        boolean workbench = EngineeringModeController.isActive() && mc.currentScreen == null
            && !mc.gameSettings.hideGUI;
        boolean dashboard = EngineeringModeController.dashboardHostVisible();
        boolean overlay = workbench && EngineeringHud.mapVisible();
        if (!EngineeringTelemetryConsumers.visible(workbench, dashboard, overlay)) {
            if (!suspended) {
                send(false, true);
                suspended = true;
                DISPLAY.close();
            }
            return;
        }
        if (suspended) {
            suspended = false;
            session = ++serial;
            compactBytes = null;
            acknowledged = 0;
            snapshot = null;
            received = 0;
            revision++;
            send(true, false);
            return;
        }
        if (mc.thePlayer.ticksExisted - lastTick >= 20) send(false, false);
    }

    public static void receive(EngineeringSnapshot value) {
        Minecraft mc = Minecraft.getMinecraft();
        if (value == null || suspended
            || session == 0
            || value.sessionId != session
            || value.slot != slot
            || world != mc.theWorld
            || connection != mc.getNetHandler()
            || !sameSource(mc)
            || snapshot != null && value.serverTick < snapshot.serverTick) return;
        snapshot = value;
        if ("ok".equals(value.status)) DISPLAY.receive(displayScope, value, System.currentTimeMillis());
        else if ("denied".equals(value.status) || "revoked".equals(value.status) || "empty".equals(value.status))
            DISPLAY.clear();
        else DISPLAY.close();
        if (!"ok".equals(value.status)) {
            compactBytes = null;
            acknowledged = 0;
        }
        revision++;
        if (value.slot == 2 && value.patternResolved) {
            patternKnown = true;
            axisPattern = value.axisSymmetrical;
        }
        EngineeringGrid.applyPattern(value.axisSymmetrical);
        received = System.currentTimeMillis();
    }

    public static void receiveCompact(PacketEngineeringCompact packet) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!MonitorCapabilities.clientSupports(MonitorCapabilities.ENGINEERING) || !packet.valid
            || suspended
            || session == 0
            || packet.session != session
            || packet.slot != slot
            || world != mc.theWorld
            || connection != mc.getNetHandler()
            || !sameSource(mc)) return;
        if (snapshot != null && packet.serverTick < snapshot.serverTick) return;
        if (!packet.delta.full && packet.base != acknowledged) {
            acknowledged = 0;
            compactBytes = null;
            send(false, false);
            return;
        }
        byte[] next = packet.delta.apply(compactBytes);
        if (next == null) {
            acknowledged = 0;
            compactBytes = null;
            send(false, false);
            return;
        }
        EngineeringTypedSnapshot decoded = new EngineeringTypedSnapshot();
        ByteBuf input = Unpooled.wrappedBuffer(next);
        try {
            decoded.fromBytes(input);
        } finally {
            input.release();
        }
        EngineeringSnapshot value = decoded.snapshot;
        if (value == null || value.sessionId != session || value.slot != slot) {
            acknowledged = 0;
            compactBytes = null;
            send(false, false);
            return;
        }
        EngineeringSnapshot refreshed = new EngineeringSnapshot(
            value.sessionId,
            value.slot,
            packet.serverTick,
            value.occupiedMask,
            value.status,
            value.nodes,
            value.cells,
            value.before,
            value.current,
            value.monitored,
            value.unknown,
            value.totalBound,
            value.truncated,
            value.coverageChanged,
            value.coverageBoundOnly,
            value.quantum,
            value.axisSymmetrical,
            value.patternResolved,
            value.review);
        compactBytes = next;
        acknowledged = packet.target;
        receive(refreshed);
        send(false, false); // Complete decode and atomic cache replacement precede the ACK.
    }

    public static EngineeringSnapshot snapshot() {
        return currentScopeValid() ? snapshot : null;
    }

    /** Historical values are presentation only, never a delta ACK or action authorization. */
    public static EngineeringSnapshot displaySnapshot() {
        if (!currentScopeValid()) return null;
        EngineeringSnapshot historical = DISPLAY.display(displayScope);
        return historical == null ? snapshot : historical;
    }

    public static String displayStatus() {
        if (!currentScopeValid()) return "source";
        if (snapshot != null && !"ok".equals(snapshot.status)) return "source";
        return DISPLAY.status(
            displayScope,
            System.currentTimeMillis(),
            3500,
            suspended || Minecraft.getMinecraft()
                .isGamePaused());
    }

    private static boolean currentScopeValid() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && world == mc.theWorld
            && connection == mc.getNetHandler()
            && player == mc.thePlayer
            && sameSource(mc)) return true;
        DISPLAY.clear();
        return false;
    }

    static boolean patternResolved() {
        Minecraft mc = Minecraft.getMinecraft();
        return patternKnown && world == mc.theWorld && connection == mc.getNetHandler();
    }

    static boolean axisSymmetrical() {
        return patternResolved() && axisPattern;
    }

    public static boolean fresh() {
        return session != 0 && !suspended
            && currentScopeValid()
            && snapshot != null
            && "ok".equals(snapshot.status)
            && !Minecraft.getMinecraft()
                .isGamePaused()
            && System.currentTimeMillis() >= received
            && System.currentTimeMillis() - received < 3500;
    }

    /** Transport age and business status are independent; callers must also inspect snapshot.status. */
    public static long ageMillis() {
        return snapshot == null || received == 0 ? Long.MAX_VALUE : Math.max(0, System.currentTimeMillis() - received);
    }

    public static long revision() {
        return revision;
    }

    public static int slot() {
        return slot;
    }

    public static SourceOrigin sourceOrigin() {
        return source.origin;
    }

    public static void baseline() {
        if (session != 0 && fresh()) send(true, false);
    }

    public static void refresh() {
        if (session != 0 && currentScopeValid()
            && !Minecraft.getMinecraft()
                .isGamePaused())
            send(false, false);
    }

    public static void close() {
        if (session == 0 && snapshot == null) {
            currentScopeValid();
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (session != 0 && world == mc.theWorld && connection == mc.getNetHandler()) send(false, true);
        session = 0;
        suspended = false;
        DISPLAY.close();
        if (!currentScopeValid()) forgetScope();
        snapshot = null;
        acknowledged = 0;
        compactBytes = null;
        received = 0;
        revision++;
        patternKnown = false;
        // Keep only this last display identity. No subscription survives close().
    }

    /** Always-on client lifecycle invalidation, including while every view is closed. */
    public static void resetSession() {
        session = 0;
        suspended = false;
        snapshot = null;
        compactBytes = null;
        acknowledged = received = 0;
        revision++;
        forgetScope();
        FactoryDashboard.clear();
    }

    private static void forgetScope() {
        DISPLAY.clear();
        displayScope = null;
        world = connection = player = null;
        source = Source.EMPTY;
        slot = -1;
        patternKnown = false;
    }

    private static boolean sameSource(Minecraft mc) {
        if (mc.thePlayer == null || mc.theWorld == null
            || player != mc.thePlayer
            || mc.thePlayer.isDead
            || mc.thePlayer.getHealth() <= 0
            || mc.thePlayer.worldObj != mc.theWorld
            || mc.thePlayer.dimension != dim
            || slot < 0
            || slot >= 8) return false;
        return source.sameIdentity(EngineeringTerminalSources.resolve(mc.thePlayer, slot));
    }

    private static void send(boolean baseline, boolean close) {
        if (suspended && !close) return;
        PacketEngineeringSubscribe request = new PacketEngineeringSubscribe(
            session,
            slot,
            dim,
            minX,
            minZ,
            maxX,
            maxZ,
            gtGrid,
            baseline,
            close,
            boundOnly).source(source);
        if (MonitorCapabilities.clientSupports(MonitorCapabilities.ENGINEERING)) {
            int fields = slot == 2 ? PacketEngineeringControl.CELLS : PacketEngineeringControl.NODES;
            GTSWNPacketHandler.NETWORK.sendToServer(new PacketEngineeringControl(request, acknowledged, fields));
        } else GTSWNPacketHandler.NETWORK.sendToServer(request);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) lastTick = mc.thePlayer.ticksExisted;
    }

    private static final class DisplayScope {

        final Object world, connection, player;
        final Source source;
        final int slot, dim, minX, minZ, maxX, maxZ;
        final boolean gt, bound;

        DisplayScope(Object world, Object connection, Object player, Source source, int slot, int dim, boolean gt,
            boolean bound, int minX, int minZ, int maxX, int maxZ) {
            this.world = world;
            this.connection = connection;
            this.player = player;
            this.source = source;
            this.slot = slot;
            this.dim = dim;
            this.gt = gt;
            this.bound = bound;
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof DisplayScope)) return false;
            DisplayScope s = (DisplayScope) other;
            return world == s.world && connection == s.connection
                && player == s.player
                && source.sameIdentity(s.source)
                && slot == s.slot
                && dim == s.dim
                && gt == s.gt
                && bound == s.bound
                && minX == s.minX
                && minZ == s.minZ
                && maxX == s.maxX
                && maxZ == s.maxZ;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(world) * 31 + slot;
        }
    }

}
