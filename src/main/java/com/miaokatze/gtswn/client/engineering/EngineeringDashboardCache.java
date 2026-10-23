package com.miaokatze.gtswn.client.engineering;

import net.minecraft.client.Minecraft;

import com.miaokatze.gtswn.common.engineering.EngineeringGrid;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.Source;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.SourceOrigin;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketEngineeringSubscribe;

/** Client intent and server snapshot have the same bounded, expiring session. */
public final class EngineeringDashboardCache {

    private static long serial = System.currentTimeMillis(), session;
    private static Object world, connection;
    private static Source source = Source.EMPTY;
    private static int slot = -1, dim, minX, minZ, maxX, maxZ, lastTick;
    private static boolean gtGrid, boundOnly;
    private static boolean patternKnown, axisPattern;
    private static EngineeringSnapshot snapshot;
    private static long received;

    private EngineeringDashboardCache() {}

    public static void subscribe(int selection, boolean gt, int x1, int z1, int x2, int z2) {
        subscribe(selection, gt, x1, z1, x2, z2, false);
    }

    public static void subscribe(int selection, boolean gt, int x1, int z1, int x2, int z2, boolean onlyBound) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null || selection < 0 || selection >= 8) return;
        Source actual = EngineeringTerminalSources.resolve(mc.thePlayer, selection);
        if (!actual.present()) return;
        int nextMinX = Math.min(x1, x2), nextMinZ = Math.min(z1, z2);
        int nextMaxX = Math.max(x1, x2), nextMaxZ = Math.max(z1, z2);
        if (session != 0 && world == mc.theWorld
            && connection == mc.getNetHandler()
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
        received = 0;
        world = mc.theWorld;
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
        session = ++serial;
        send(true, false);
    }

    public static void tick() {
        if (session == 0) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (!EngineeringModeController.isActive() || world != mc.theWorld
            || connection != mc.getNetHandler()
            || mc.thePlayer == null
            || mc.thePlayer.isDead) {
            close();
            return;
        }
        if (!sameSource(mc)) {
            close();
            return;
        }
        if (mc.thePlayer.ticksExisted - lastTick >= 20) send(false, false);
    }

    public static void receive(EngineeringSnapshot value) {
        Minecraft mc = Minecraft.getMinecraft();
        if (value == null || session == 0
            || value.sessionId != session
            || value.slot != slot
            || world != mc.theWorld
            || connection != mc.getNetHandler()
            || !sameSource(mc)) return;
        snapshot = value;
        if (value.slot == 2 && value.patternResolved) {
            patternKnown = true;
            axisPattern = value.axisSymmetrical;
        }
        EngineeringGrid.applyPattern(value.axisSymmetrical);
        received = System.currentTimeMillis();
    }

    public static EngineeringSnapshot snapshot() {
        return snapshot;
    }

    static boolean patternResolved() {
        Minecraft mc = Minecraft.getMinecraft();
        return patternKnown && world == mc.theWorld && connection == mc.getNetHandler();
    }

    static boolean axisSymmetrical() {
        return patternResolved() && axisPattern;
    }

    public static boolean fresh() {
        return snapshot != null && System.currentTimeMillis() - received < 3500;
    }

    public static int slot() {
        return slot;
    }

    public static SourceOrigin sourceOrigin() {
        return source.origin;
    }

    public static void baseline() {
        if (session != 0) send(true, false);
    }

    public static void refresh() {
        if (session != 0) send(false, false);
    }

    public static void close() {
        Minecraft mc = Minecraft.getMinecraft();
        if (session != 0 && world == mc.theWorld && connection == mc.getNetHandler()) send(false, true);
        session = 0;
        slot = -1;
        snapshot = null;
        patternKnown = false;
        world = connection = null;
        source = Source.EMPTY;
    }

    private static boolean sameSource(Minecraft mc) {
        if (mc.thePlayer == null || slot < 0 || slot >= 8) return false;
        return source.sameIdentity(EngineeringTerminalSources.resolve(mc.thePlayer, slot));
    }

    private static void send(boolean baseline, boolean close) {
        GTSWNPacketHandler.NETWORK.sendToServer(
            new PacketEngineeringSubscribe(
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
                boundOnly).source(source));
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) lastTick = mc.thePlayer.ticksExisted;
    }
}
