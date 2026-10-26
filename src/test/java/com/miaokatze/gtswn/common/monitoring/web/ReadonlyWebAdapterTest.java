package com.miaokatze.gtswn.common.monitoring.web;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import org.junit.Test;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

/** Pure fake exchanges only: none of these tests bind a port, start a server or use a real credential. */
public class ReadonlyWebAdapterTest {

    private static final String TEST_TOKEN = "test-fixture-not-a-real-credential-0000";
    private static final UUID ID = new UUID(1, 2);

    private static ReadonlyWebAdapter adapter() throws IOException {
        return new ReadonlyWebAdapter(
            ReadonlyWebAdapter.digest(TEST_TOKEN.getBytes(StandardCharsets.UTF_8)),
            1,
            1024 * 1024,
            null,
            null);
    }

    private static WebDashboardSnapshot snapshot(UUID id) {
        return new WebDashboardSnapshot(
            id,
            "Authorized <title>",
            1,
            "CACHED",
            20,
            Collections
                .singletonList(new WebDashboardSnapshot.Card("EU", "10", "EU/t", new double[] { 1, Double.NaN, 3 })));
    }

    private static FakeExchange request(String method, String path, boolean authenticated) {
        FakeExchange exchange = new FakeExchange(method, path);
        if (authenticated) exchange.request.set("Authorization", "Bearer " + TEST_TOKEN);
        return exchange;
    }

    @Test
    public void privateDataRequiresAuthenticationEvenWithMatchingEtag() throws Exception {
        ReadonlyWebAdapter adapter = adapter();
        adapter.publish(Collections.singletonList(snapshot(ID)));
        FakeExchange valid = request("GET", "/api/snapshots", true);
        adapter.handle(valid);
        assertEquals(200, valid.code);
        assertTrue(
            valid.body()
                .contains("Authorized"));
        FakeExchange unauthenticated = request("GET", "/api/snapshots", false);
        unauthenticated.request.set("If-None-Match", valid.response.getFirst("ETag"));
        adapter.handle(unauthenticated);
        assertEquals(401, unauthenticated.code);
        assertEquals("", unauthenticated.body());
    }

    @Test
    public void unchangedContentUses304AndHeadHasNoBody() throws Exception {
        ReadonlyWebAdapter adapter = adapter();
        adapter.publish(Collections.singletonList(snapshot(ID)));
        FakeExchange first = request("GET", "/api/snapshots", true);
        adapter.handle(first);
        adapter.publish(Collections.singletonList(snapshot(ID)));
        FakeExchange conditional = request("GET", "/api/snapshots", true);
        conditional.request.set("If-None-Match", first.response.getFirst("ETag"));
        adapter.handle(conditional);
        assertEquals(304, conditional.code);
        assertEquals("", conditional.body());
        FakeExchange head = request("HEAD", "/api/snapshots", true);
        adapter.handle(head);
        assertEquals(200, head.code);
        assertEquals("", head.body());
        assertEquals(first.response.getFirst("ETag"), head.response.getFirst("ETag"));
    }

    @Test
    public void revokeAndStopRemoveSensitiveSnapshots() throws Exception {
        ReadonlyWebAdapter adapter = adapter();
        adapter.publish(Collections.singletonList(snapshot(ID)));
        adapter.revoke(ID);
        FakeExchange revoked = request("GET", "/api/snapshots", true);
        adapter.handle(revoked);
        assertEquals(200, revoked.code);
        assertFalse(
            revoked.body()
                .contains("Authorized"));
        adapter.close();
        FakeExchange stopped = request("GET", "/api/snapshots", true);
        adapter.handle(stopped);
        assertEquals(503, stopped.code);
        assertEquals("", stopped.body());
    }

    @Test
    public void onlyBoundedReadOnlyRoutesAreAccepted() throws Exception {
        ReadonlyWebAdapter adapter = adapter();
        for (String method : Arrays.asList("POST", "PUT", "DELETE", "OPTIONS")) {
            FakeExchange exchange = request(method, "/api/snapshots", true);
            adapter.handle(exchange);
            assertEquals(405, exchange.code);
        }
        for (String path : Arrays.asList("/api/snapshots?source=arbitrary", "/api/snapshots?")) {
            FakeExchange exchange = request("GET", path, true);
            adapter.handle(exchange);
            assertEquals(400, exchange.code);
        }
        FakeExchange body = request("GET", "/api/snapshots", true);
        body.request.set("Content-Length", "1");
        adapter.handle(body);
        assertEquals(400, body.code);
        FakeExchange encodedPath = request("GET", "/%61pi/snapshots", true);
        adapter.handle(encodedPath);
        assertEquals(404, encodedPath.code);
        FakeExchange huge = request("GET", "/", false);
        huge.request.set("X-Large", String.join("", Collections.nCopies(9000, "x")));
        adapter.handle(huge);
        assertEquals(431, huge.code);
    }

    @Test
    public void assetIsPublicButCannotContainPublishedData() throws Exception {
        ReadonlyWebAdapter adapter = adapter();
        adapter.publish(Collections.singletonList(snapshot(ID)));
        FakeExchange exchange = request("GET", "/", false);
        adapter.handle(exchange);
        assertEquals(200, exchange.code);
        assertTrue(
            exchange.body()
                .contains("Access token"));
        assertFalse(
            exchange.body()
                .contains("Authorized <title>"));
        assertTrue(
            exchange.response.getFirst("Content-Security-Policy")
                .contains("frame-ancestors 'none'"));
    }

    @Test
    public void gzipIsCachedRepresentationAndNegotiationHonorsZeroQuality() throws Exception {
        ReadonlyWebAdapter adapter = adapter();
        adapter.publish(Collections.singletonList(snapshot(ID)));
        FakeExchange exchange = request("GET", "/api/snapshots", true);
        exchange.request.set("Accept-Encoding", "gzip");
        adapter.handle(exchange);
        assertEquals("gzip", exchange.response.getFirst("Content-Encoding"));
        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(exchange.output.toByteArray()))) {
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            byte[] bytes = new byte[1024];
            int count;
            while ((count = input.read(bytes)) != -1) decoded.write(bytes, 0, count);
            assertTrue(new String(decoded.toByteArray(), StandardCharsets.UTF_8).contains("[1.0,null,3.0]"));
        }
        assertFalse(ReadonlyWebAdapter.acceptsGzip("gzip;q=0"));
        assertFalse(ReadonlyWebAdapter.acceptsGzip("gzip;q=0.0"));
        assertTrue(ReadonlyWebAdapter.acceptsGzip("br, gzip;q=0.5"));
    }

    @Test
    public void dashboardQuotaIsNotViewerQuotaAndDtoCopiesHistory() throws Exception {
        ReadonlyWebAdapter adapter = adapter();
        adapter.publish(Arrays.asList(snapshot(ID), snapshot(new UUID(3, 4))));
        for (int i = 0; i < 3; i++) {
            FakeExchange exchange = request("GET", "/api/snapshots", true);
            adapter.handle(exchange);
            assertEquals(200, exchange.code);
            assertTrue(
                exchange.body()
                    .contains(ID.toString()));
            assertFalse(
                exchange.body()
                    .contains(new UUID(3, 4).toString()));
        }
        double[] history = { 1, 2 };
        WebDashboardSnapshot.Card card = new WebDashboardSnapshot.Card("a", "b", "c", history);
        history[0] = 9;
        card.history()[1] = 9;
        assertArrayEquals(new double[] { 1, 2 }, card.history(), 0);
    }

    @Test
    public void giantSnapshotIsBoundedAndClearlyTruncated() throws Exception {
        double[] history = new double[120];
        Arrays.fill(history, Double.MAX_VALUE);
        List<WebDashboardSnapshot.Card> cards = Collections
            .nCopies(128, new WebDashboardSnapshot.Card("x", "y", "z", history));
        ReadonlyWebAdapter adapter = adapter();
        adapter.publish(Collections.singletonList(new WebDashboardSnapshot(ID, "bounded", 1, "CACHED", 1, cards)));
        FakeExchange exchange = request("GET", "/api/snapshots", true);
        adapter.handle(exchange);
        assertEquals(200, exchange.code);
        assertTrue(
            exchange.body()
                .contains("\"cardsTruncated\":true"));
        assertTrue(exchange.output.size() < ReadonlyWebAdapter.MAX_RESPONSE);
    }

    @Test
    public void manyDashboardsKeepMetadataAndShareCardBudget() throws Exception {
        ReadonlyWebAdapter adapter = new ReadonlyWebAdapter(
            ReadonlyWebAdapter.digest(TEST_TOKEN.getBytes(StandardCharsets.UTF_8)),
            16,
            1024 * 1024,
            null,
            null);
        double[] history = new double[120];
        Arrays.fill(history, Double.MAX_VALUE);
        List<WebDashboardSnapshot.Card> cards = Collections
            .nCopies(128, new WebDashboardSnapshot.Card("series", "last", "EU/t", history));
        List<WebDashboardSnapshot> dashboards = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++)
            dashboards.add(new WebDashboardSnapshot(new UUID(0, i + 1), "View " + i, 1, "CACHED", 1, cards));
        adapter.publish(dashboards);
        FakeExchange exchange = request("GET", "/api/snapshots", true);
        adapter.handle(exchange);
        assertEquals(200, exchange.code);
        for (WebDashboardSnapshot dashboard : dashboards) assertTrue(
            exchange.body()
                .contains(dashboard.id.toString()));
        assertTrue(
            exchange.body()
                .contains("\"cardsTruncated\":true"));
        assertTrue(
            exchange.body()
                .contains("\"cardsOmitted\":"));
        assertTrue(
            exchange.body()
                .split("\"label\"", -1).length - 1 <= 128);
        assertTrue(exchange.output.size() < ReadonlyWebAdapter.MAX_RESPONSE);
    }

    @Test
    public void bearerDuplicatesAndWrongTokenFailClosed() throws Exception {
        ReadonlyWebAdapter adapter = adapter();
        FakeExchange duplicate = request("GET", "/api/snapshots", true);
        duplicate.request.add("Authorization", "Bearer " + TEST_TOKEN);
        adapter.handle(duplicate);
        assertEquals(401, duplicate.code);
        FakeExchange invalid = request("GET", "/api/snapshots", true);
        invalid.request.set("Authorization", "Bearer " + TEST_TOKEN + "x");
        adapter.handle(invalid);
        assertEquals(401, invalid.code);
    }

    @Test
    public void budgetsRejectOverdrawAndNegativeAmounts() {
        ReadonlyWebAdapter.Budget budget = new ReadonlyWebAdapter.Budget(1, 10);
        assertTrue(budget.take(10));
        assertFalse(budget.take(1));
        assertFalse(budget.take(-1));
        assertFalse(budget.take(11));
    }

    @Test
    public void disabledLifecycleNeedsNoCredentialAndNeverListens() throws Exception {
        ReadonlyWebService.configure(ReadonlyWebService.Settings.disabled());
        ReadonlyWebService.start();
        assertFalse(ReadonlyWebService.isRunning());
        ReadonlyWebService.stop();
        try {
            ReadonlyWebService.parseHash(null);
            fail("Missing hash accepted");
        } catch (IllegalArgumentException expected) { /* fail closed before any socket */ }
        try {
            new ReadonlyWebService.Settings(true, "0.0.0.0", 8765, 1, "HASH", 1024);
            fail("Public bind accepted");
        } catch (IllegalArgumentException expected) { /* TLS proxy must own public listener */ }
    }

    @org.junit.Test
    public void oversizedDisplayValuesAreExplicitlyMarkedRatherThanSilentlyChangingMagnitude() {
        char[] digits = new char[300];
        java.util.Arrays.fill(digits, '9');
        WebDashboardSnapshot.Card card = new WebDashboardSnapshot.Card("EU", new String(digits), "EU", null);
        org.junit.Assert.assertEquals(256, card.value.length());
        org.junit.Assert.assertTrue(card.value.endsWith("…"));
        org.junit.Assert.assertEquals("123", new WebDashboardSnapshot.Card("EU", "123", "EU", null).value);
    }

    static final class FakeExchange extends HttpExchange {

        final Headers request = new Headers(), response = new Headers();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final String method;
        final URI uri;
        int code;

        FakeExchange(String method, String path) {
            this.method = method;
            uri = URI.create(path);
        }

        String body() {
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }

        @Override
        public Headers getRequestHeaders() {
            return request;
        }

        @Override
        public Headers getResponseHeaders() {
            return response;
        }

        @Override
        public URI getRequestURI() {
            return uri;
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {}

        @Override
        public InputStream getRequestBody() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream getResponseBody() {
            return output;
        }

        @Override
        public void sendResponseHeaders(int code, long length) {
            this.code = code;
        }

        // Newer JDKs make this abstract; no annotation keeps the fixture compilable against JDK 8 too.
        public int getResponseCode() {
            return code;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 20000);
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8765);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {}

        @Override
        public void setStreams(InputStream input, OutputStream output) {}

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }
    }
}
