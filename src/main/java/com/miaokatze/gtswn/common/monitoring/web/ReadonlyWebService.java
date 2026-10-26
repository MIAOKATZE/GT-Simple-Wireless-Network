package com.miaokatze.gtswn.common.monitoring.web;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

/** Explicit operator-controlled lifecycle. configure() and disabled start() never create a listener. */
public final class ReadonlyWebService {

    private static Settings settings = Settings.disabled();
    private static HttpServer server;
    private static volatile ReadonlyWebAdapter adapter;
    private static volatile Map<UUID, Set<UUID>> profileDashboards = Collections.emptyMap();
    private static ThreadPoolExecutor workers;
    private static ScheduledThreadPoolExecutor deadlines;

    private ReadonlyWebService() {}

    public static final class Settings {

        public final boolean enabled;
        public final String bindAddress;
        public final int port;
        public final int maxDashboards;
        public final String tokenHashEnvironment;
        public final int bytesPerSecond;

        public Settings(boolean enabled, String bindAddress, int port, int maxDashboards, String tokenHashEnvironment,
            int bytesPerSecond) {
            if (port < 1024 || port > 65535
                || maxDashboards < 1
                || maxDashboards > 16
                || bytesPerSecond < 1024
                || bytesPerSecond > 16 * 1024 * 1024) throw new IllegalArgumentException("Invalid dashboard limits");
            if (!"127.0.0.1".equals(bindAddress) && !"::1".equals(bindAddress))
                throw new IllegalArgumentException("Web must bind loopback; use an operator-managed TLS reverse proxy");
            if (tokenHashEnvironment == null || !tokenHashEnvironment.matches("[A-Z][A-Z0-9_]{0,63}"))
                throw new IllegalArgumentException("Invalid credential environment variable name");
            this.enabled = enabled;
            this.bindAddress = bindAddress;
            this.port = port;
            this.maxDashboards = maxDashboards;
            this.tokenHashEnvironment = tokenHashEnvironment;
            this.bytesPerSecond = bytesPerSecond;
        }

        public static Settings disabled() {
            return new Settings(false, "127.0.0.1", 8765, 1, "GTSWN_WEB_TOKEN_SHA256", 131072);
        }
    }

    public static synchronized void configure(Settings next) {
        if (next == null) throw new IllegalArgumentException("Missing web configuration");
        stop();
        settings = next;
    }

    public static synchronized int maxDashboards() {
        return settings.maxDashboards;
    }

    public static boolean isRunning() {
        return adapter != null;
    }

    /** Runtime hook only: tests use the handler directly and must never call enabled start(). */
    public static synchronized void start() throws IOException {
        if (!settings.enabled || server != null) return;
        byte[] hash = parseHash(System.getenv(settings.tokenHashEnvironment));
        // JDK 8 HttpServer bounds pre-handler slow headers/idle sockets. These are JVM-wide server properties;
        // set before first HttpServer initialization; operators with another embedded server must set at JVM launch.
        System.setProperty("sun.net.httpserver.maxReqTime", "5");
        System.setProperty("sun.net.httpserver.maxRspTime", "5");
        System.setProperty("sun.net.httpserver.idleInterval", "5");
        System.setProperty("sun.net.httpserver.maxIdleConnections", "16");
        System.setProperty("sun.net.httpserver.maxReqHeaders", "32");
        System.setProperty("sun.net.httpserver.timerMillis", "1000");
        System.setProperty("sun.net.httpserver.clockTick", "1000");
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "GTSWN-readonly-web");
            thread.setDaemon(true);
            return thread;
        };
        deadlines = new ScheduledThreadPoolExecutor(1, factory);
        deadlines.setRemoveOnCancelPolicy(true);
        workers = new ThreadPoolExecutor(
            2,
            4,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(16),
            factory,
            new ThreadPoolExecutor.AbortPolicy());
        try {
            ReadonlyWebAdapter next = new ReadonlyWebAdapter(
                hash,
                settings.maxDashboards,
                settings.bytesPerSecond,
                null,
                deadlines);
            HttpServer listener = HttpServer
                .create(new InetSocketAddress(InetAddress.getByName(settings.bindAddress), settings.port), 16);
            server = listener;
            listener.setExecutor(workers);
            listener.createContext("/", next);
            adapter = next;
            listener.start();
        } catch (IOException | RuntimeException failure) {
            stop();
            throw failure;
        }
    }

    public static synchronized void stop() {
        ReadonlyWebAdapter previous = adapter;
        adapter = null;
        profileDashboards = Collections.emptyMap();
        if (previous != null) previous.close();
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
        if (deadlines != null) {
            deadlines.shutdownNow();
            deadlines = null;
        }
    }

    /** Main-thread caller must already have revalidated owner, fixed sources, opt-in and profile limits. */
    public static void publish(List<WebDashboardSnapshot> snapshots) {
        ReadonlyWebAdapter current = adapter;
        if (current != null) current.publish(snapshots);
    }

    /** Main-thread registry attests membership; HTTP never reads profile/world state. */
    public static void setProfileMembership(Map<UUID, Set<UUID>> memberships) {
        Map<UUID, Set<UUID>> copy = new LinkedHashMap<>();
        if (memberships.size() > 128) throw new IllegalArgumentException("Web membership quota exceeded");
        for (Map.Entry<UUID, Set<UUID>> entry : memberships.entrySet()) {
            if (entry.getKey() == null || entry.getValue()
                .size() > 16) throw new IllegalArgumentException("Invalid dashboard membership");
            copy.put(entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
        }
        profileDashboards = Collections.unmodifiableMap(copy);
    }

    /** Unknown/unlisted profiles are a no-op; owners cannot blank unrelated OP dashboards. */
    public static void revoke(UUID profileId) {
        ReadonlyWebAdapter current = adapter;
        Set<UUID> affected = profileDashboards.get(profileId);
        if (current != null && affected != null) current.revokeDashboards(affected);
    }

    public static void revokeDashboard(UUID dashboardId) {
        ReadonlyWebAdapter current = adapter;
        if (current != null && dashboardId != null) current.revoke(dashboardId);
    }

    public static void clearPublished() {
        ReadonlyWebAdapter current = adapter;
        if (current != null) current.clearPublished();
    }

    static byte[] parseHash(String value) {
        if (value == null || !value.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException(
            "Web disabled: operator must provision a SHA-256 bearer-token hash in the configured environment variable");
        byte[] hash = new byte[32];
        for (int i = 0; i < hash.length; i++) hash[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return hash;
    }
}
