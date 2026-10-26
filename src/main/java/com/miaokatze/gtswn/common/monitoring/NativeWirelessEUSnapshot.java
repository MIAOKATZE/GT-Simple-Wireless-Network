package com.miaokatze.gtswn.common.monitoring;

import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.panel.NetworkInfoDataStore;

import gregtech.common.misc.WirelessNetworkManager;

/** Exact native balance memo, not a sampler: each consumer keeps its existing cadence and control semantics. */
public final class NativeWirelessEUSnapshot {

    public final UUID epoch, account;
    public final long tick;
    public final BigInteger amount;
    private static final Cache CACHE = new Cache();

    private NativeWirelessEUSnapshot(UUID epoch, UUID account, long tick, BigInteger amount) {
        this.epoch = epoch;
        this.account = account;
        this.tick = tick;
        this.amount = amount;
    }

    public String sourceToken() {
        return epoch + ":" + account;
    }

    public static NativeWirelessEUSnapshot read(World requestedWorld, UUID owner) {
        return read(requestedWorld, owner, true);
    }

    /** Control must observe native transactions later in the same tick; never consume or replace the telemetry memo. */
    public static NativeWirelessEUSnapshot readForControl(World requestedWorld, UUID owner) {
        return read(requestedWorld, owner, false);
    }

    private static NativeWirelessEUSnapshot read(World requestedWorld, UUID owner, boolean memoized) {
        if (requestedWorld == null || requestedWorld.isRemote || owner == null) return null;
        MinecraftServer server = MinecraftServer.getServer();
        World root = server == null ? requestedWorld : server.worldServerForDimension(0);
        if (root == null) return null;
        return CACHE.read(
            root,
            root.getTotalWorldTime(),
            owner,
            NetworkInfoDataStore::resolveAccount,
            WirelessNetworkManager::getUserEU,
            memoized);
    }

    public static BigInteger balance(World world, UUID owner) {
        NativeWirelessEUSnapshot snapshot = read(world, owner);
        return snapshot == null ? null : snapshot.amount;
    }

    /** Server-main-thread cache; reference identity and rollback delimit the session epoch. */
    static final class Cache {

        static final int MAX_ACCOUNTS = 4096;
        private WeakReference<Object> world = new WeakReference<>(null);
        private long tick = Long.MIN_VALUE;
        private UUID epoch = UUID.randomUUID();
        private final Map<UUID, NativeWirelessEUSnapshot> balances = new HashMap<>();

        NativeWirelessEUSnapshot read(Object currentWorld, long currentTick, UUID owner, Function<UUID, UUID> resolver,
            Function<UUID, BigInteger> reader) {
            return read(currentWorld, currentTick, owner, resolver, reader, true);
        }

        NativeWirelessEUSnapshot readForControl(Object currentWorld, long currentTick, UUID owner,
            Function<UUID, UUID> resolver, Function<UUID, BigInteger> reader) {
            return read(currentWorld, currentTick, owner, resolver, reader, false);
        }

        private NativeWirelessEUSnapshot read(Object currentWorld, long currentTick, UUID owner,
            Function<UUID, UUID> resolver, Function<UUID, BigInteger> reader, boolean memoized) {
            if (currentWorld == null || owner == null || currentTick < 0) return null;
            if (world.get() != currentWorld || currentTick < tick) {
                world = new WeakReference<>(currentWorld);
                epoch = UUID.randomUUID();
                balances.clear();
            }
            if (currentTick != tick) balances.clear();
            tick = currentTick;
            UUID account = resolver.apply(owner);
            if (account == null || !account.equals(resolver.apply(account))) return null;
            if (memoized && balances.containsKey(account)) return balances.get(account);
            // Fail closed on excessive same-tick fanout, never evict and reread an already observed account.
            if (memoized && balances.size() >= MAX_ACCOUNTS) return null;
            BigInteger amount;
            try {
                amount = reader.apply(account);
            } catch (RuntimeException unavailable) {
                if (memoized) balances.put(account, null);
                return null;
            }
            NativeWirelessEUSnapshot result = amount != null && account.equals(resolver.apply(owner))
                && account.equals(resolver.apply(account))
                    ? new NativeWirelessEUSnapshot(epoch, account, currentTick, amount)
                    : null;
            if (memoized) balances.put(account, result);
            return result;
        }
    }
}
