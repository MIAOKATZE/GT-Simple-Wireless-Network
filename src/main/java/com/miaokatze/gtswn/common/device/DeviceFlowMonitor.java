package com.miaokatze.gtswn.common.device;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Server-thread-only observation of actual controller energy interactions.
 * The twenty-tick window contains changes made while the controller executes, not hatch network traffic.
 * No hatch ownership is inferred: two controllers sharing a hatch retain separate observations.
 */
public final class DeviceFlowMonitor {

    public static final int MAX_TRACKED = 8192;
    private static final int WINDOW = 20;
    private static final Map<Object, Reading> TRACKED = new IdentityHashMap<>();
    private static long now;
    private static Object serverIdentity;
    private static Thread serverThread;
    private static Reading current;

    public static final class Reading {

        private final long[][] values = new long[4][WINDOW];
        private final long[] ticks = new long[WINDOW];
        private long expires;
        private final double[] totals = new double[4];
        private Object owner;
        private boolean fusionInternalBuffer;
        private boolean sawSteam;
        private double selfIn, selfOut, externalIn, externalOut;
        private long observedAt;

        private Reading(long expires) {
            this.expires = expires;
            observedAt = now;
            java.util.Arrays.fill(ticks, Long.MIN_VALUE);
        }

        private void add(int channel, double amount) {
            if (amount <= 0) return;
            if (channel >= 2) sawSteam = true;
            int slot = (int) Math.floorMod(now, (long) WINDOW);
            if (ticks[slot] != now) {
                ticks[slot] = now;
                for (long[] value : values) value[slot] = 0;
            }
            long bucketAmount = amount >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) amount;
            values[channel][slot] = saturatingAdd(values[channel][slot], bucketAmount);
            totals[channel] += amount;
        }

        /** Since-last-sample volume divided by elapsed ticks, retaining periodic bursts in long-term averages. */
        public double[] consumeInterval() {
            long elapsed = now - observedAt;
            double[] rates = new double[4];
            if (elapsed > 0) {
                for (int i = 0; i < rates.length; i++) rates[i] = totals[i] / elapsed;
                java.util.Arrays.fill(totals, 0);
                observedAt = now;
            }
            return rates;
        }

        public boolean hasSteamActivity() {
            return sawSteam;
        }

        public boolean warmedUp() {
            return now > observedAt;
        }

        public long average(int channel) {
            long quotient = 0;
            long remainder = 0;
            for (int i = 0; i < WINDOW; i++) {
                if (ticks[i] <= now && ticks[i] > now - WINDOW) {
                    quotient += values[channel][i] / WINDOW;
                    remainder += values[channel][i] % WINDOW;
                }
            }
            return quotient + remainder / WINDOW;
        }
    }

    private DeviceFlowMonitor() {}

    /** A changed server identity or backwards clock starts a fresh session. TTL cleanup is once per second. */
    public static void tick(Object server, long tick) {
        if (serverIdentity != server || tick < now) {
            TRACKED.clear();
            DeviceScanManager.clear();
            current = null;
            serverIdentity = server;
            serverThread = Thread.currentThread();
        }
        now = tick;
        if (tick % 20 == 0) {
            Iterator<Reading> iterator = TRACKED.values()
                .iterator();
            while (iterator.hasNext()) if (iterator.next().expires < tick) iterator.remove();
        }
    }

    /** Only the sampler enrolls machines; interactions never create entries. */
    public static Reading watch(Object controller, long ttlTicks) {
        if (Thread.currentThread() != serverThread) return null;
        Reading reading = TRACKED.get(controller);
        if (reading == null) {
            if (TRACKED.size() >= MAX_TRACKED) return null;
            reading = new Reading(now + ttlTicks);
            reading.owner = controller;
            for (Class<?> type = controller.getClass(); type != null; type = type.getSuperclass()) {
                String name = type.getSimpleName();
                if (name.equals("MTEFusionComputer") || name.equals("MTELargeFusionComputer")) {
                    reading.fusionInternalBuffer = true;
                    break;
                }
            }
            TRACKED.put(controller, reading);
        } else reading.expires = now + ttlTicks;
        return reading;
    }

    public static void clear() {
        TRACKED.clear();
        current = null;
        serverIdentity = null;
        serverThread = null;
        com.miaokatze.gtswn.network.DeviceTerminalActionQueue.clear();
        DeviceTerminalDataStore.clearActiveTerminals();
        DeviceScanManager.clear();
    }

    public static void forget(Object controller) {
        TRACKED.remove(controller);
    }

    public static Reading enter(Object controller) {
        if (Thread.currentThread() != serverThread) return null;
        Reading previous = current;
        Reading next = TRACKED.get(controller);
        current = next != null && next.expires >= now ? next : null;
        return previous;
    }

    public static void leave(Reading previous) {
        if (Thread.currentThread() != serverThread) return;
        if (current != null && current != previous) {
            // GT205 fusion families explicitly refill their own buffer from input hatches, then consume it.
            // For these verified families the recipe/startup consumption is solely the own-buffer decrease.
            // Other controllers retain both legs; arbitrary equal amounts are never assumed to be transfers.
            if (current.fusionInternalBuffer) current.add(0, current.selfIn);
            else {
                current.add(0, current.selfIn + current.externalIn);
                current.add(1, current.selfOut + current.externalOut);
            }
            current.selfIn = current.selfOut = current.externalIn = current.externalOut = 0;
        }
        current = previous;
    }

    public static boolean active() {
        return Thread.currentThread() == serverThread && current != null;
    }

    public static boolean isWatched(Object owner) {
        if (Thread.currentThread() != serverThread) return false;
        Reading reading = TRACKED.get(owner);
        return reading != null && reading.expires >= now;
    }

    public static void steamCurrent(long amount, boolean output) {
        if (active()) current.add(output ? 3 : 2, amount);
    }

    /** EU buffer mutations during a monitored controller call: decrease = consumption, increase = production. */
    public static void energyChange(long before, long after, boolean steamStorage) {
        energyChange(null, before, after, steamStorage);
    }

    public static void energyChange(Object storage, long before, long after, boolean steamStorage) {
        if (!active()) return;
        before = Math.max(0, before);
        after = Math.max(0, after);
        if (before == after) return;
        long amount = after > before ? after - before : before - after;
        if (amount < 0) amount = Long.MAX_VALUE;
        if (steamStorage) amount = amount > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : amount * 2;
        if (steamStorage) current.add(after > before ? 3 : 2, amount);
        else if (storage == current.owner) {
            if (after > before) current.selfOut = current.selfOut + amount;
            else current.selfIn = current.selfIn + amount;
        } else {
            if (after > before) current.externalOut = current.externalOut + amount;
            else current.externalIn = current.externalIn + amount;
        }
    }

    public static void steam(Object controller, long amount, boolean output) {
        if (Thread.currentThread() != serverThread) return;
        Reading reading = TRACKED.get(controller);
        if (reading != null && reading.expires >= now) reading.add(output ? 3 : 2, amount);
    }

    static long saturatingAdd(long a, long b) {
        return b > Long.MAX_VALUE - a ? Long.MAX_VALUE : a + b;
    }
}
