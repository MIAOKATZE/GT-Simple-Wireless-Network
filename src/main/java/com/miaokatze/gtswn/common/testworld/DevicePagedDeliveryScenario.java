package com.miaokatze.gtswn.common.testworld;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.miaokatze.gtswn.common.device.DeviceTerminalDeltaCodec;
import com.miaokatze.gtswn.common.device.DeviceTerminalDeltaDelivery;
import com.miaokatze.gtswn.network.PacketRequestDeviceTerminalDelta;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalDelta;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Real server ticks and Netty wire serialization; no client classes, live TE scan or performance claim. */
final class DevicePagedDeliveryScenario implements TestScenario {

    private DeviceTerminalDeltaDelivery delivery;
    private UUID terminal, session;
    private int received, pages;
    private String tail;

    public String id() {
        return "device.paged_capacity";
    }

    public boolean includeInAll() {
        return false;
    }

    public int timeoutTicks() {
        return 400;
    }

    public String description() {
        return "65536 frozen device rows, 128 per page, two pages per natural tick, exact tail and ACK";
    }

    public void start(TestContext context) {
        TestWorldFixtures.requireTestWorld(context.world());
        terminal = UUID.randomUUID();
        session = UUID.randomUUID();
        List<Entry> rows = new ArrayList<>();
        for (int i = 0; i < PacketSyncDeviceTerminalDelta.MAX_PAGED_ENTRIES; i++) rows
            .add(new Entry("0:" + i + ":64:0", "Machine " + i, (byte) 1, (byte) 0, i, 0, i, 0, 0, i, 64, 0, "", ""));
        delivery = new DeviceTerminalDeltaDelivery(terminal);
        delivery.prepare(
            new PacketRequestDeviceTerminalDelta(terminal, session, 0, -1, -1).paged(),
            "capacity-fixture",
            1,
            () -> rows);
        context.event("capacity", "65536 rows / 512 pages; transport fixture only, no block scan");
    }

    public void tick(TestContext context) {
        delivery.prepare(
            new PacketRequestDeviceTerminalDelta(terminal, session, delivery.epoch(), -1, 1).paged(),
            "capacity-fixture",
            100 + context.tick(),
            () -> { throw new AssertionError("Frozen target recaptured"); });
        for (int burst = 0; burst < 2 && delivery.hasPage(); burst++) {
            PacketSyncDeviceTerminalDelta packet = delivery.next();
            ByteBuf bytes = Unpooled.buffer();
            try {
                packet.toBytes(bytes);
                PacketSyncDeviceTerminalDelta decoded = new PacketSyncDeviceTerminalDelta();
                decoded.fromBytes(bytes);
                if (!terminal.equals(decoded.getTerminalId()) || decoded.getVersion() != 1
                    || decoded.getPageIndex() != pages
                    || decoded.getEntryTotal() != 65536)
                    throw new AssertionError("Capacity wire identity/order mismatch");
                List<Entry> rows = DeviceTerminalDeltaCodec.decode(null, false, decoded.getPayload());
                received += rows.size();
                tail = rows.get(rows.size() - 1).key;
                pages++;
            } finally {
                bytes.release();
            }
            delivery.sent(packet, context.tick());
        }
        if (!delivery.hasPage()) {
            delivery.prepare(
                new PacketRequestDeviceTerminalDelta(terminal, session, delivery.epoch(), 1, -1).paged(),
                "capacity-fixture",
                1,
                () -> { throw new AssertionError("ACK recaptured"); });
            context.check(
                received == 65536 && pages == 512 && "0:65535:64:0".equals(tail),
                "All configured rows and final target delivered");
            context.check(
                delivery.acknowledgedVersion() == 1 && !delivery.hasPage(),
                "Only complete frozen target becomes ACK baseline");
            context.pass("Native server wire fixture completed: 65536 rows / 512 pages / final row visible to decoder");
        }
    }

    public void cleanup(TestContext context) {
        delivery = null;
    }
}
