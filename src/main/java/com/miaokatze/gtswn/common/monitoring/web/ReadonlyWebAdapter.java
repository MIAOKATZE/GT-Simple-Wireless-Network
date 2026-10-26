package com.miaokatze.gtswn.common.monitoring.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.zip.GZIPOutputStream;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/** HTTP-only boundary: reads immutable encoded projections, never world state or source registries. */
public final class ReadonlyWebAdapter implements HttpHandler {

    static final int MAX_RESPONSE = 262144;
    static final long PUBLICATION_LEASE_NANOS = TimeUnit.SECONDS.toNanos(10);
    private final byte[] tokenHash;
    private final int maxDashboards;
    private final String allowedProxy;
    private final ScheduledExecutorService deadlines;
    private final LongSupplier clock;
    private volatile long lastPublicationNanos;
    private final Semaphore concurrent = new Semaphore(8);
    private final Set<HttpExchange> active = Collections.newSetFromMap(new ConcurrentHashMap<HttpExchange, Boolean>());
    private final Budget egress;
    private final Budget requests = new Budget(40, 80);
    private final Budget authenticationFailures = new Budget(1, 20);
    private final Map<String, Encoded> assets = new LinkedHashMap<>();
    private volatile Published published = new Published(Collections.<WebDashboardSnapshot>emptyList());
    private volatile boolean enabled = true;

    ReadonlyWebAdapter(byte[] tokenHash, int maxDashboards, int bytesPerSecond, String allowedProxy,
        ScheduledExecutorService deadlines) throws IOException {
        this(tokenHash, maxDashboards, bytesPerSecond, allowedProxy, deadlines, System::nanoTime);
    }

    ReadonlyWebAdapter(byte[] tokenHash, int maxDashboards, int bytesPerSecond, String allowedProxy,
        ScheduledExecutorService deadlines, LongSupplier clock) throws IOException {
        if (tokenHash == null || tokenHash.length != 32) throw new IllegalArgumentException("Authentication required");
        this.tokenHash = tokenHash.clone();
        this.maxDashboards = Math.max(1, Math.min(16, maxDashboards));
        this.allowedProxy = allowedProxy;
        this.deadlines = deadlines;
        this.clock = java.util.Objects.requireNonNull(clock);
        this.lastPublicationNanos = clock.getAsLong() - PUBLICATION_LEASE_NANOS;
        this.egress = new Budget(bytesPerSecond, Math.max(MAX_RESPONSE + 1024, bytesPerSecond));
        assets.put("/", asset("index.html", "text/html; charset=utf-8"));
        assets.put("/dashboard.js", asset("dashboard.js", "application/javascript; charset=utf-8"));
        assets.put("/dashboard.css", asset("dashboard.css", "text/css; charset=utf-8"));
    }

    /** Called only by the main-thread lifecycle bridge; encoding is cached until content actually changes. */
    public synchronized void publish(List<WebDashboardSnapshot> snapshots) {
        if (!enabled) return;
        List<WebDashboardSnapshot> bounded = new ArrayList<>();
        for (WebDashboardSnapshot snapshot : snapshots) {
            if (bounded.size() >= maxDashboards) break;
            if (snapshot != null) bounded.add(snapshot);
        }
        Published candidate = new Published(bounded, published.encoded);
        if (!candidate.encoded.etag.equals(published.encoded.etag)) {
            boolean removed = false;
            for (WebDashboardSnapshot old : published.snapshots) {
                boolean retained = false;
                for (WebDashboardSnapshot next : candidate.snapshots) if (old.id.equals(next.id)) retained = true;
                if (!retained) removed = true;
            }
            published = candidate;
            if (removed) for (HttpExchange exchange : active) exchange.close();
        }
        // A successful main-thread publication renews liveness even when the encoded content is unchanged.
        lastPublicationNanos = clock.getAsLong();
    }

    private boolean currentPublication(Published current) {
        long age = clock.getAsLong() - lastPublicationNanos;
        return enabled && current == published && age >= 0 && age < PUBLICATION_LEASE_NANOS;
    }

    /** Revocation clears sensitive cache immediately and interrupts in-flight responses. */
    public synchronized void revoke(UUID id) {
        revokeDashboards(Collections.singleton(id));
    }

    public synchronized void revokeDashboards(Set<UUID> ids) {
        List<WebDashboardSnapshot> remaining = new ArrayList<>();
        for (WebDashboardSnapshot snapshot : published.snapshots)
            if (!ids.contains(snapshot.id)) remaining.add(snapshot);
        if (remaining.size() == published.snapshots.size()) return;
        published = new Published(remaining);
        for (HttpExchange exchange : active) exchange.close();
    }

    public synchronized void clearPublished() {
        published = new Published(Collections.<WebDashboardSnapshot>emptyList());
        for (HttpExchange exchange : active) exchange.close();
    }

    public synchronized void close() {
        enabled = false;
        published = new Published(Collections.<WebDashboardSnapshot>emptyList());
        for (HttpExchange exchange : active) exchange.close();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!concurrent.tryAcquire()) {
            exchange.close();
            return;
        }
        ScheduledFuture<?> timeout = null;
        active.add(exchange);
        try {
            if (deadlines != null) timeout = deadlines.schedule(exchange::close, 5, TimeUnit.SECONDS);
            if (!enabled) {
                error(exchange, 503);
                return;
            }
            if (allowedProxy != null && !allowedProxy.equals(
                exchange.getRemoteAddress()
                    .getAddress()
                    .getHostAddress())) {
                error(exchange, 403);
                return;
            }
            if (!requests.take(1)) {
                error(exchange, 429);
                return;
            }
            if (!validHeaders(exchange.getRequestHeaders())) {
                error(exchange, 431);
                return;
            }
            String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                exchange.getResponseHeaders()
                    .set("Allow", "GET, HEAD");
                error(exchange, 405);
                return;
            }
            if (exchange.getRequestURI()
                .getRawQuery() != null
                || exchange.getRequestURI()
                    .getRawPath()
                    .length() > 128
                || exchange.getRequestHeaders()
                    .containsKey("Transfer-Encoding")
                || !zeroLength(exchange.getRequestHeaders())) {
                error(exchange, 400);
                return;
            }
            String path = exchange.getRequestURI()
                .getRawPath();
            boolean api = "/api/snapshots".equals(path);
            Published current = published;
            Encoded encoded = api ? current.encoded : assets.get(path);
            if (encoded == null) {
                error(exchange, 404);
                return;
            }
            if (api && !authenticated(exchange.getRequestHeaders())) {
                if (!authenticationFailures.take(1)) {
                    error(exchange, 429);
                    return;
                }
                exchange.getResponseHeaders()
                    .set("WWW-Authenticate", "Bearer realm=\"GTSWN\"");
                error(exchange, 401);
                return;
            }
            if (api && !currentPublication(current)) {
                error(exchange, 503);
                return;
            }
            Headers out = exchange.getResponseHeaders();
            secureHeaders(out);
            out.set("Content-Type", encoded.type);
            out.set("Cache-Control", api ? "private, no-cache, no-store" : "public, max-age=3600, must-revalidate");
            out.set("ETag", encoded.etag);
            out.set("Vary", api ? "Authorization, Accept-Encoding" : "Accept-Encoding");
            if (encoded.etag.equals(
                exchange.getRequestHeaders()
                    .getFirst("If-None-Match"))) {
                if (!egress.take(1024)) {
                    exchange.close();
                    return;
                }
                if (api && !currentPublication(current)) {
                    exchange.close();
                    return;
                }
                exchange.sendResponseHeaders(304, -1);
                return;
            }
            boolean gzip = acceptsGzip(
                exchange.getRequestHeaders()
                    .getFirst("Accept-Encoding"));
            byte[] body = gzip ? encoded.gzip : encoded.plain;
            boolean head = "HEAD".equals(method);
            if (!egress.take((head ? 0 : body.length) + 1024)) {
                error(exchange, 429);
                return;
            }
            if (gzip) out.set("Content-Encoding", "gzip");
            out.set("Content-Length", Integer.toString(body.length));
            if (api && !currentPublication(current)) {
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, head ? -1 : body.length);
            if (!head) exchange.getResponseBody()
                .write(body);
        } finally {
            if (timeout != null) timeout.cancel(false);
            active.remove(exchange);
            exchange.close();
            concurrent.release();
        }
    }

    private boolean authenticated(Headers headers) {
        List<String> values = headers.get("Authorization");
        if (values == null || values.size() != 1) return false;
        String value = values.get(0);
        if (!value.startsWith("Bearer ")) return false;
        String token = value.substring(7);
        if (token.length() < 32 || token.length() > 256 || !token.matches("[A-Za-z0-9_~.+/=-]+")) return false;
        return MessageDigest.isEqual(tokenHash, digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean zeroLength(Headers headers) {
        List<String> length = headers.get("Content-Length");
        return length == null || length.size() == 1 && "0".equals(length.get(0));
    }

    static boolean validHeaders(Headers headers) {
        if (headers.size() > 32) return false;
        int bytes = 0, count = 0;
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            bytes += header.getKey()
                .length();
            for (String value : header.getValue()) {
                bytes += value.length();
                count++;
            }
            if (bytes > 8192 || count > 48) return false;
        }
        return true;
    }

    static boolean acceptsGzip(String value) {
        if (value == null) return false;
        for (String encoding : value.split(",")) {
            String[] parts = encoding.trim()
                .split(";");
            if (!"gzip".equalsIgnoreCase(parts[0].trim())) continue;
            for (int i = 1; i < parts.length; i++) {
                String part = parts[i].trim();
                if (part.startsWith("q=")) {
                    try {
                        return Double.parseDouble(part.substring(2)) > 0;
                    } catch (NumberFormatException invalid) {
                        return false;
                    }
                }
            }
            return true;
        }
        return false;
    }

    private void error(HttpExchange exchange, int code) throws IOException {
        if (!egress.take(1024)) {
            exchange.close();
            return;
        }
        secureHeaders(exchange.getResponseHeaders());
        exchange.getResponseHeaders()
            .set("Cache-Control", "no-store");
        if (code == 429) exchange.getResponseHeaders()
            .set("Retry-After", "2");
        exchange.sendResponseHeaders(code, -1);
    }

    private static void secureHeaders(Headers headers) {
        headers.set(
            "Content-Security-Policy",
            "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Frame-Options", "DENY");
    }

    private static Encoded asset(String name, String type) throws IOException {
        try (InputStream input = ReadonlyWebAdapter.class.getResourceAsStream("/assets/gtswn/web/" + name)) {
            if (input == null) throw new IOException("Dashboard asset missing");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int length;
            while ((length = input.read(buffer)) != -1) {
                if (output.size() + length > MAX_RESPONSE) throw new IOException("Dashboard asset exceeds budget");
                output.write(buffer, 0, length);
            }
            return new Encoded(output.toByteArray(), type);
        }
    }

    static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static final class Encoded {

        final byte[] plain;
        final byte[] gzip;
        final String type;
        final String etag;

        Encoded(byte[] plain, String type) {
            if (plain.length > MAX_RESPONSE) throw new IllegalArgumentException("Dashboard exceeds response budget");
            this.plain = plain;
            this.type = type;
            StringBuilder hash = new StringBuilder("W/\"");
            for (byte value : digest(plain)) hash.append(String.format("%02x", value & 255));
            etag = hash.append('"')
                .toString();
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (GZIPOutputStream compressor = new GZIPOutputStream(output)) {
                    compressor.write(plain);
                }
                gzip = output.toByteArray();
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    private static final class Published {

        final List<WebDashboardSnapshot> snapshots;
        final Encoded encoded;

        Published(List<WebDashboardSnapshot> snapshots) {
            this(snapshots, null);
        }

        Published(List<WebDashboardSnapshot> snapshots, Encoded previous) {
            // Whole-response cap: omit excess cards/dashboards rather than allocating an unbounded JSON document.
            List<WebDashboardSnapshot> accepted = new ArrayList<>();
            StringBuilder json = new StringBuilder("{\"dashboards\":[");
            boolean truncated = false;
            int perDashboardCards = Math.max(1, 128 / Math.max(1, snapshots.size()));
            int perDashboardChars = Math.min(20000, (MAX_RESPONSE - 1024) / (3 * Math.max(1, snapshots.size())) - 128);
            for (WebDashboardSnapshot snapshot : snapshots) {
                String serialized = serialize(snapshot, perDashboardCards, perDashboardChars);
                if ((json.length() + serialized.length()) * 3L > MAX_RESPONSE - 128) {
                    truncated = true;
                    break;
                }
                if (!accepted.isEmpty()) json.append(',');
                json.append(serialized);
                accepted.add(snapshot);
            }
            json.append("],\"truncated\":")
                .append(truncated)
                .append('}');
            this.snapshots = Collections.unmodifiableList(accepted);
            byte[] bytes = json.toString()
                .getBytes(StandardCharsets.UTF_8);
            encoded = previous != null && java.util.Arrays.equals(previous.plain, bytes) ? previous
                : new Encoded(bytes, "application/json; charset=utf-8");
        }
    }

    static String serialize(WebDashboardSnapshot snapshot) {
        return serialize(snapshot, 128, 20000);
    }

    private static String serialize(WebDashboardSnapshot snapshot, int cardLimit, int charLimit) {
        StringBuilder result = new StringBuilder("{\"id\":").append(quote(snapshot.id.toString()))
            .append(",\"title\":")
            .append(quote(snapshot.title))
            .append(",\"revision\":")
            .append(snapshot.revision)
            .append(",\"status\":")
            .append(quote(snapshot.status))
            .append(",\"sampleTick\":")
            .append(snapshot.sampleTick)
            .append(",\"cards\":[");
        boolean first = true;
        boolean truncated = false;
        int emitted = 0;
        for (WebDashboardSnapshot.Card card : snapshot.cards) {
            if (card == null) continue;
            if (emitted >= cardLimit) {
                truncated = true;
                break;
            }
            StringBuilder entry = new StringBuilder("{\"label\":").append(quote(card.label))
                .append(",\"value\":")
                .append(quote(card.value))
                .append(",\"unit\":")
                .append(quote(card.unit))
                .append(",\"history\":[");
            boolean firstPoint = true;
            for (double point : card.history()) {
                if (!firstPoint) entry.append(',');
                firstPoint = false;
                entry.append(Double.isNaN(point) || Double.isInfinite(point) ? "null" : Double.toString(point));
            }
            entry.append("]}");
            if (result.length() + entry.length() > charLimit) {
                truncated = true;
                break;
            }
            if (!first) result.append(',');
            first = false;
            result.append(entry);
            emitted++;
        }
        return result.append("],\"cardsTruncated\":")
            .append(truncated || snapshot.omittedCards > 0)
            .append(",\"cardsOmitted\":")
            .append(snapshot.omittedCards + Math.max(0, snapshot.cards.size() - emitted))
            .append('}')
            .toString();
    }

    private static String quote(String text) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '"' || character == '\\') result.append('\\')
                .append(character);
            else if (character < 32 || character == '\u2028' || character == '\u2029')
                result.append(String.format("\\u%04x", (int) character));
            else result.append(character);
        }
        return result.append('"')
            .toString();
    }

    /** Process-wide HTTP egress/request budgets, independent of the in-game uplink accounting. */
    static final class Budget {

        final long perSecond;
        final long capacity;
        double remaining;
        long last = System.nanoTime();

        Budget(long perSecond, long capacity) {
            this.perSecond = Math.max(1, perSecond);
            this.capacity = Math.max(1, capacity);
            remaining = this.capacity;
        }

        synchronized boolean take(long amount) {
            long now = System.nanoTime();
            remaining = Math.min(capacity, remaining + Math.max(0, now - last) / 1.0e9 * perSecond);
            last = now;
            if (amount < 0 || amount > remaining) return false;
            remaining -= amount;
            return true;
        }
    }
}
