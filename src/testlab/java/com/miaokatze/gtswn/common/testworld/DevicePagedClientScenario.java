// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.util.FakePlayer;

import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore;
import com.miaokatze.gtswn.common.device.DeviceTerminalDeltaCodec;
import com.miaokatze.gtswn.common.device.DeviceTerminalDeltaDelivery;
import com.miaokatze.gtswn.common.device.DeviceTerminalRequestQueue;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalDelta;

/** Explicit real-client fixture. Server state is authoritative; client evidence is reported independently. */
final class DevicePagedClientScenario implements TestScenario {

    static final long STALE_VERSION = Long.MAX_VALUE - 17;
    private static DevicePagedClientScenario active;
    private TestContext context;
    private EntityPlayerMP actor;
    private Object connection;
    private UUID terminal, session;
    private long firstEpoch;
    private int stage;

    public String id() {
        return "device.paged_client";
    }

    public boolean includeInAll() {
        return false;
    }

    public int timeoutTicks() {
        return 1000;
    }

    public String description() {
        return "Real online OP2 actor: 1025 run-owned rows over native GUI/Netty, renewed epoch and stale-page injection";
    }

    public void start(TestContext ctx) {
        context = ctx;
        TestWorldFixtures.requireTestWorld(ctx.world());
        for (Object candidate : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) candidate;
            if (!(player instanceof FakePlayer) && player.worldObj == ctx.world()
                && player.canCommandSenderUseCommand(2, "gtswn")) {
                if (actor != null) throw new IllegalStateException("Exactly one actual OP2 test actor is required");
                actor = player;
            }
        }
        if (actor == null) throw new IllegalStateException("Actual client actor must enter the test dimension first");
        connection = actor.playerNetServerHandler;
        ctx.result.actor = "actual online player authorized at OP2; fixed connection";
        ctx.result.actorLevel2 = actor.canCommandSenderUseCommand(2, "gtswn");
        ctx.result.actorLevel4 = actor.canCommandSenderUseCommand(4, "gtswn");
        ItemStack item = TestUiKit.grantPaged(actor, ctx.runId());
        terminal = ItemDeviceInfoTerminal.readTerminalId(item);
        DeviceTerminalDataStore store = DeviceTerminalDataStore.get(ctx.world());
        DeviceTerminalDataStore.TerminalData data = store.getOrCreateTerminal(terminal);
        for (int index = 0; index < 1025; index++) {
            DeviceTerminalDataStore.MachineRecord row = new DeviceTerminalDataStore.MachineRecord();
            row.dim = ctx.world().provider.dimensionId;
            row.x = 20000000 + index;
            row.y = 64;
            row.z = 20000000;
            row.name = index == 1024 ? "QA_TAIL_" + ctx.runId() : "QA row " + index;
            row.capabilityMask = 0;
            row.evidenceKind = 3;
            String key = row.dim + ":" + row.x + ":64:" + row.z;
            data.boundKeys.add(key);
            data.records.put(key, row);
        }
        data.version++;
        store.markDirty();
        active = this;
        ctx.event(
            "fixture",
            "run=" + ctx.runId()
                + " actor="
                + actor.getUniqueID()
                + " terminal="
                + terminal
                + " rows=1025 tail="
                + ctx.world().provider.dimensionId
                + ":20001024:64:20000000; no blocks or EU created");
    }

    public void tick(TestContext ctx) {
        if (!DeviceTerminalRequestQueue.isOnline(actor) || actor.playerNetServerHandler != connection
            || actor.worldObj != ctx.world())
            throw new IllegalStateException("Actual actor/connection/world changed; fixture recovery required");
        if (ItemDeviceInfoTerminal.readTerminalId(actor.inventory.mainInventory[0]) == null
            || !terminal.equals(ItemDeviceInfoTerminal.readTerminalId(actor.inventory.mainInventory[0])))
            throw new IllegalStateException("Run-owned fixture moved from its single reserved slot");
    }

    static void control(EntityPlayerMP sender, String action, String runId) throws Exception {
        DevicePagedClientScenario run = active;
        if (run == null || run.actor != sender
            || sender.playerNetServerHandler != run.connection
            || !run.context.runId()
                .equals(runId)
            || !TestDimension.isTest(sender.worldObj))
            throw new IllegalStateException("No matching actor/connection/run capability");
        if ("renew".equals(action) && run.stage == 0) {
            DeviceTerminalDeltaDelivery delivery = run.delivery();
            if (delivery.acknowledgedVersion() < 0)
                throw new IllegalStateException("First complete snapshot has not reached server ACK");
            run.context.result.transport = "real Netty delta request/page/server ACK observed; GUI verification is recorded separately by the client";
            run.firstEpoch = delivery.epoch();
            run.session = (UUID) field(delivery, "session");
            DeviceTerminalRequestQueue.invalidate(sender);
            run.stage = 1;
            run.context
                .event("epoch", "first complete ACK observed; native sender invalidated; oldEpoch=" + run.firstEpoch);
        } else if ("stale".equals(action) && run.stage == 1) {
            DeviceTerminalDeltaDelivery delivery = run.delivery();
            if (delivery.epoch() <= run.firstEpoch || delivery.acknowledgedVersion() < 0)
                throw new IllegalStateException("Renewed complete snapshot has not reached server ACK");
            List<Entry> poison = new ArrayList<>();
            for (int index = 0; index < 128; index++) poison.add(
                new Entry(
                    "19871:" + (10000000 + index) + ":64:10000000",
                    "STALE_QA_POISON",
                    (byte) 0,
                    (byte) 0,
                    0,
                    0,
                    0,
                    0,
                    19871,
                    10000000 + index,
                    64,
                    10000000,
                    "",
                    ""));
            byte[] payload = DeviceTerminalDeltaCodec.encode(null, poison).bytes;
            GTSWNPacketHandler.NETWORK.sendTo(
                new PacketSyncDeviceTerminalDelta(
                    run.terminal,
                    run.session,
                    run.firstEpoch,
                    -1,
                    STALE_VERSION,
                    PacketSyncDeviceTerminalDelta.FULL,
                    0,
                    9,
                    1025,
                    payload,
                    2),
                sender);
            run.stage = 2;
            run.context
                .event("stale_input", "Actual NETWORK.sendTo oldEpoch=" + run.firstEpoch + " version=" + STALE_VERSION);
        } else if ("complete".equals(action) && run.stage == 2) {
            run.stage = 3;
            run.context.check(
                DeviceTerminalDataStore.get(sender.worldObj)
                    .getTerminal(run.terminal).boundKeys.size() == 1025,
                "Server retains all 1025 run-owned rows before cleanup");
            run.context.pass(
                "Real actor requested both acknowledged epochs and stale input; require independent client result.json PASS and restoration evidence");
        } else if ("abort".equals(action)) run.context.fail("Client observer aborted; restore run-owned fixture");
        else throw new IllegalStateException("Invalid paged-client control transition");
    }

    private DeviceTerminalDeltaDelivery delivery() throws Exception {
        Map<?, ?> players = (Map<?, ?>) field(null, DeviceTerminalRequestQueue.class, "SENT");
        Object player = players.get(actor.getUniqueID());
        if (player == null) throw new IllegalStateException("Native sender not established");
        Object state = ((Map<?, ?>) field(player, "terminals")).get(terminal);
        if (state == null) throw new IllegalStateException("Native terminal delivery not established");
        return (DeviceTerminalDeltaDelivery) field(state, "delta");
    }

    private static Object field(Object target, String name) throws Exception {
        return field(target, target.getClass(), name);
    }

    private static Object field(Object target, Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    public void cleanup(TestContext ctx) {
        active = null;
        if (actor != null && TestUiKit.hasBackup(actor)) TestUiKit.restore(actor);
        if (actor != null && terminal != null) {
            ctx.check(
                DeviceTerminalDataStore.get(ctx.world())
                    .getTerminal(terminal) == null,
                "Exact run terminal data removed");
            ctx.check(!TestUiKit.hasBackup(actor), "Persistent original slot restored and backup retired");
            ctx.event("cleanup", "actor=" + actor.getUniqueID() + " run=" + ctx.runId() + " exact fixture absent=true");
        }
        actor = null;
        context = null;
        connection = null;
    }
}
