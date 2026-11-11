// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.device;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.miaokatze.gtswn.network.PacketRequestDeviceTerminalDelta;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalDelta;

/** One acknowledged dictionary and one frozen target; intervening samples coalesce in the live store. */
public final class DeviceTerminalDeltaDelivery {

    private static final AtomicLong EPOCHS = new AtomicLong();
    private final UUID terminal;
    private UUID session;
    private String source;
    private long epoch, baseVersion = -1, version = -1;
    private List<Entry> base, target;
    private int nextPage, wireVersion = 1;
    private PacketSyncDeviceTerminalDelta pendingPacket;
    private long lastSent = Long.MIN_VALUE;

    public DeviceTerminalDeltaDelivery(UUID terminal) {
        this.terminal = terminal;
    }

    /** Must only be invoked after current possession, profile and native source authorization succeeds. */
    public void prepare(PacketRequestDeviceTerminalDelta request, String sourceRevision, long liveVersion,
        Supplier<List<Entry>> capture) {
        boolean reset = session == null || !session.equals(request.getSession())
            || !sourceRevision.equals(source)
            || request.getEpoch() != epoch
            || liveVersion < version
            || wireVersion != request.getWireVersion();
        if (!reset && request.getKnownVersion() != baseVersion) {
            // A claimed ACK cannot establish a baseline that this session did not completely send.
            if (target != null && nextPage == pageTotal() && request.getKnownVersion() == version) {
                base = target;
                baseVersion = version;
                target = null;
            } else reset = true;
        }
        if (reset) {
            session = request.getSession();
            wireVersion = request.getWireVersion();
            source = sourceRevision;
            epoch = EPOCHS.incrementAndGet();
            base = null;
            baseVersion = -1;
            target = null;
            version = -1;
            nextPage = 0;
            pendingPacket = null;
        }
        if (target == null && (base == null || liveVersion != baseVersion)) {
            List<Entry> rows = capture.get();
            if (rows.size() > (wireVersion == 2 ? PacketSyncDeviceTerminalDelta.MAX_PAGED_ENTRIES : 1024))
                throw new IllegalArgumentException("Too many device rows");
            target = Collections.unmodifiableList(new ArrayList<>(rows));
            version = liveVersion;
            nextPage = 0;
            pendingPacket = null;
        } else if (target != null
            && (nextPage == pageTotal() || request.getPendingVersion() != version && nextPage != 0)) {
                // Missing pages or lost cache: replay the same frozen target, never the moving live sample.
                nextPage = 0;
                pendingPacket = null;
            }
    }

    public boolean hasPage() {
        return target != null && nextPage < pageTotal();
    }

    public int pageTotal() {
        List<Entry> rows = target == null ? base : target;
        return Math.max(1, ((rows == null ? 0 : rows.size()) + 127) / 128);
    }

    public PacketSyncDeviceTerminalDelta next() {
        if (!hasPage()) return null;
        if (pendingPacket != null) return pendingPacket;
        int from = nextPage * 128;
        int to = Math.min(target.size(), from + 128);
        DeviceTerminalDeltaCodec.Encoded encoded = DeviceTerminalDeltaCodec
            .encode(base != null && base.size() <= 1024 ? base : null, target.subList(from, to));
        pendingPacket = new PacketSyncDeviceTerminalDelta(
            terminal,
            session,
            epoch,
            baseVersion,
            version,
            encoded.delta ? PacketSyncDeviceTerminalDelta.DELTA : PacketSyncDeviceTerminalDelta.FULL,
            nextPage,
            pageTotal(),
            target.size(),
            encoded.bytes,
            wireVersion);
        return pendingPacket;
    }

    /** Called only after byte-budget admission and actual send; retries never skip a page. */
    public void sent(PacketSyncDeviceTerminalDelta packet, long now) {
        if (packet.getEpoch() == epoch && packet.getVersion() == version && packet.getPageIndex() == nextPage) {
            nextPage++;
            pendingPacket = null;
            lastSent = now;
        }
    }

    public PacketSyncDeviceTerminalDelta keepAlive(long now, long interval) {
        if (target != null || base == null
            || lastSent != Long.MIN_VALUE && now >= lastSent && now - lastSent < interval) return null;
        return new PacketSyncDeviceTerminalDelta(
            terminal,
            session,
            epoch,
            baseVersion,
            baseVersion,
            PacketSyncDeviceTerminalDelta.KEEPALIVE,
            0,
            pageTotal(),
            base.size(),
            new byte[0],
            wireVersion);
    }

    public void keepAliveSent(long now) {
        lastSent = now;
    }

    public long epoch() {
        return epoch;
    }

    public long acknowledgedVersion() {
        return baseVersion;
    }

    public long targetVersion() {
        return version;
    }

    public int nextPageIndex() {
        return nextPage;
    }

    public PacketSyncDeviceTerminalDelta revoked(UUID clientSession) {
        return new PacketSyncDeviceTerminalDelta(
            terminal,
            clientSession,
            EPOCHS.incrementAndGet(),
            -1,
            0,
            PacketSyncDeviceTerminalDelta.REVOKED,
            0,
            1,
            0,
            new byte[0],
            wireVersion);
    }
}
