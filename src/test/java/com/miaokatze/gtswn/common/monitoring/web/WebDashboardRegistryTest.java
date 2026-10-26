package com.miaokatze.gtswn.common.monitoring.web;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class WebDashboardRegistryTest {

    static final class Source implements WebDashboardRegistry.ProfileExport {

        final Map<UUID, WebDashboardSnapshot> allowed = new LinkedHashMap<>();
        final Map<UUID, Integer> counts = new LinkedHashMap<>();
        int authorizationChecks;

        UUID add(String title) {
            UUID id = UUID.randomUUID();
            allowed.put(
                id,
                new WebDashboardSnapshot(
                    id,
                    title,
                    1,
                    "CACHED",
                    20,
                    Collections
                        .singletonList(new WebDashboardSnapshot.Card(title, "5", "EU/t", new double[] { 1, 5 }))));
            return id;
        }

        @Override
        public boolean authorized(UUID id) {
            authorizationChecks++;
            return allowed.containsKey(id);
        }

        @Override
        public int sourceCount(UUID id) {
            return counts.getOrDefault(id, 1);
        }

        @Override
        public WebDashboardSnapshot snapshot(UUID id) {
            return allowed.get(id);
        }
    }

    @Test
    public void ownerConsentAloneNeverCreatesOrEnablesPage() {
        Source source = new Source();
        source.add("owner consent");
        WebDashboardRegistry registry = new WebDashboardRegistry("test");
        assertTrue(
            registry.exportSnapshots(source, 1)
                .isEmpty());
        assertTrue(
            registry.list(true)
                .isEmpty());
        try {
            registry.create(false, "not OP");
            fail("Non-OP created dashboard");
        } catch (IllegalArgumentException expected) { /* Explicit OP authority required. */ }
    }

    @Test
    public void opGroupNeedsExplicitEnableAndConsentedFixedReferences() {
        Source source = new Source();
        UUID first = source.add("first"), second = source.add("second");
        WebDashboardRegistry registry = new WebDashboardRegistry("test");
        WebDashboardRegistry.Dashboard page = registry.create(true, "OP page");
        registry.add(true, page.id, first, source);
        registry.add(true, page.id, second, source);
        assertTrue(
            registry.exportSnapshots(source, 1)
                .isEmpty());
        registry.setEnabled(true, page.id, true, 1, source);
        List<WebDashboardSnapshot> published = registry.exportSnapshots(source, 1);
        assertEquals(1, published.size());
        assertEquals(page.id, published.get(0).id);
        assertEquals("OP page", published.get(0).title);
        assertEquals(2, published.get(0).cards.size());
        try {
            registry.add(true, page.id, UUID.randomUUID(), source);
            fail("Unconsented profile admitted");
        } catch (IllegalArgumentException expected) {
            assertEquals(
                2,
                page.profiles()
                    .size());
        }
        try {
            registry.remove(false, page.id, first);
            fail("Non-OP changed page");
        } catch (IllegalArgumentException expected) {
            assertEquals(
                2,
                page.profiles()
                    .size());
        }
    }

    @Test
    public void enabledDashboardQuotaDoesNotLimitConsentingOwnersOrViewers() {
        Source source = new Source();
        UUID id = source.add("consent");
        WebDashboardRegistry registry = new WebDashboardRegistry("test");
        WebDashboardRegistry.Dashboard first = registry.create(true, "first"), second = registry.create(true, "second");
        registry.add(true, first.id, id, source);
        registry.add(true, second.id, id, source);
        registry.setEnabled(true, first.id, true, 1, source);
        try {
            registry.setEnabled(true, second.id, true, 1, source);
            fail("Enabled quota ignored");
        } catch (IllegalArgumentException expected) {
            assertFalse(second.enabled());
        }
        registry.setEnabled(true, first.id, false, 1, source);
        registry.setEnabled(true, second.id, true, 1, source);
        assertEquals(
            second.id,
            registry.exportSnapshots(source, 1)
                .get(0).id);
    }

    @Test
    public void lossOfOneConsentBlocksEntireGroupWithoutDeletingDefinition() {
        Source source = new Source();
        UUID first = source.add("first"), second = source.add("second");
        WebDashboardRegistry registry = new WebDashboardRegistry("test");
        WebDashboardRegistry.Dashboard page = registry.create(true, "group");
        registry.add(true, page.id, first, source);
        registry.add(true, page.id, second, source);
        registry.setEnabled(true, page.id, true, 1, source);
        source.allowed.remove(first);
        registry.revalidate(source, 1);
        assertTrue(page.blocked());
        assertTrue(
            registry.exportSnapshots(source, 1)
                .isEmpty());
        assertEquals(
            2,
            page.profiles()
                .size());
        registry.remove(true, page.id, first);
        assertEquals(
            1,
            registry.exportSnapshots(source, 1)
                .size());
    }

    @Test
    public void definitionsPersistButAuthorizationIsRecheckedAfterLoad() {
        Source source = new Source();
        UUID id = source.add("persisted");
        WebDashboardRegistry registry = new WebDashboardRegistry("test");
        WebDashboardRegistry.Dashboard page = registry.create(true, "Saved name");
        registry.add(true, page.id, id, source);
        registry.setEnabled(true, page.id, true, 1, source);
        NBTTagCompound tag = new NBTTagCompound();
        registry.writeToNBT(tag);
        WebDashboardRegistry restored = new WebDashboardRegistry("restored");
        restored.readFromNBT(tag);
        assertTrue(
            restored.list(true)
                .get(0)
                .blocked());
        assertEquals(
            page.id,
            restored.exportSnapshots(source, 1)
                .get(0).id);
        source.allowed.clear();
        assertTrue(
            restored.exportSnapshots(source, 1)
                .isEmpty());
    }

    @Test
    public void groupedCardProjectionIsFairAndMarksOmittedCards() {
        Source source = new Source();
        WebDashboardRegistry registry = new WebDashboardRegistry("test");
        WebDashboardRegistry.Dashboard page = registry.create(true, "eight profiles");
        for (int i = 0; i < 8; i++) {
            UUID id = source.add("profile " + i);
            source.allowed.put(
                id,
                new WebDashboardSnapshot(
                    id,
                    "source",
                    1,
                    "CACHED",
                    1,
                    Collections
                        .nCopies(128, new WebDashboardSnapshot.Card("profile " + i, "1", "EU/t", new double[] { 1 }))));
            registry.add(true, page.id, id, source);
        }
        registry.setEnabled(true, page.id, true, 1, source);
        WebDashboardSnapshot snapshot = registry.exportSnapshots(source, 1)
            .get(0);
        assertEquals(128, snapshot.cards.size());
        assertEquals(896, snapshot.omittedCards);
        for (int i = 0; i < 8; i++) assertEquals("profile " + i, snapshot.cards.get(i * 16).label);
    }

    @Test
    public void sourceBudgetCountsDistinctProfileWalksAndMemoizesRepeatedReferences() {
        Source source = new Source();
        WebDashboardRegistry registry = new WebDashboardRegistry("test");
        WebDashboardRegistry.Dashboard first = registry.create(true, "full budget"),
            second = registry.create(true, "shared");
        UUID shared = null;
        for (int i = 0; i < 8; i++) {
            UUID id = source.add("large profile " + i);
            source.counts.put(id, 512);
            registry.add(true, first.id, id, source);
            shared = id;
        }
        registry.setEnabled(true, first.id, true, 2, source);
        registry.add(true, second.id, shared, source);
        registry.setEnabled(true, second.id, true, 2, source);
        source.authorizationChecks = 0;
        registry.revalidate(source, 2);
        assertEquals(8, source.authorizationChecks);
        UUID excessive = source.add("one more source");
        try {
            registry.add(true, second.id, excessive, source);
            fail("Source budget exceeded");
        } catch (IllegalArgumentException expected) {
            assertEquals(
                1,
                second.profiles()
                    .size());
        }
        registry.setEnabled(true, second.id, false, 2, source);
        registry.add(true, second.id, excessive, source);
        try {
            registry.setEnabled(true, second.id, true, 2, source);
            fail("Over-budget definition activated");
        } catch (IllegalArgumentException expected) {
            assertFalse(second.enabled());
        }
        NBTTagCompound tag = new NBTTagCompound();
        registry.writeToNBT(tag);
        tag.getTagList("dashboards", 10)
            .getCompoundTagAt(1)
            .setBoolean("enabled", true);
        WebDashboardRegistry restored = new WebDashboardRegistry("restored");
        restored.readFromNBT(tag);
        assertEquals(
            1,
            restored.exportSnapshots(source, 2)
                .size());
        assertFalse(
            restored.list(true)
                .get(1)
                .enabled());
    }

    @Test
    public void unchangedBlockedGroupDoesNotRepeatedlyClearValidPublishedPage() throws Exception {
        Source source = new Source();
        UUID valid = source.add("valid profile"), invalid = source.add("will revoke");
        WebDashboardRegistry registry = new WebDashboardRegistry("test");
        WebDashboardRegistry.Dashboard first = registry.create(true, "Valid page"),
            second = registry.create(true, "Blocked page");
        registry.add(true, first.id, valid, source);
        registry.add(true, second.id, invalid, source);
        registry.setEnabled(true, first.id, true, 2, source);
        registry.setEnabled(true, second.id, true, 2, source);
        String fixture = "test-fixture-not-a-real-credential-0000";
        ReadonlyWebAdapter adapter = new ReadonlyWebAdapter(
            ReadonlyWebAdapter.digest(fixture.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            2,
            1024 * 1024,
            null,
            null);
        java.lang.reflect.Field field = ReadonlyWebService.class.getDeclaredField("adapter");
        field.setAccessible(true);
        field.set(null, adapter); // Fake HTTP adapter only; no HttpServer, socket, executor or real token.
        try {
            ReadonlyWebService.publish(registry.exportSnapshots(source, 2));
            source.allowed.remove(invalid);
            registry.revalidate(source, 2);
            ReadonlyWebService.publish(registry.exportSnapshots(source, 2));
            registry.revalidate(source, 2);
            registry.revalidate(source, 2);
            ReadonlyWebAdapterTest.FakeExchange exchange = new ReadonlyWebAdapterTest.FakeExchange(
                "GET",
                "/api/snapshots");
            exchange.request.set("Authorization", "Bearer " + fixture);
            adapter.handle(exchange);
            assertEquals(200, exchange.code);
            assertTrue(
                exchange.body()
                    .contains("Valid page"));
            assertFalse(
                exchange.body()
                    .contains("Blocked page"));
        } finally {
            ReadonlyWebService.stop();
        }
    }

    @Test
    public void profileRevocationOnlyRemovesAssociatedPagesAndUnlistedIdsAreNoOp() throws Exception {
        Source source = new Source();
        UUID shared = source.add("shared"), independent = source.add("independent");
        WebDashboardRegistry registry = new WebDashboardRegistry("test");
        WebDashboardRegistry.Dashboard first = registry.create(true, "Shared one"),
            second = registry.create(true, "Shared two"), third = registry.create(true, "Independent page");
        registry.add(true, first.id, shared, source);
        registry.add(true, second.id, shared, source);
        registry.add(true, third.id, independent, source);
        registry.setEnabled(true, first.id, true, 3, source);
        registry.setEnabled(true, second.id, true, 3, source);
        registry.setEnabled(true, third.id, true, 3, source);
        String fixture = "test-fixture-not-a-real-credential-0000";
        ReadonlyWebAdapter adapter = new ReadonlyWebAdapter(
            ReadonlyWebAdapter.digest(fixture.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            3,
            1024 * 1024,
            null,
            null);
        java.lang.reflect.Field field = ReadonlyWebService.class.getDeclaredField("adapter");
        field.setAccessible(true);
        field.set(null, adapter); // No listener is constructed.
        try {
            ReadonlyWebService.publish(registry.exportSnapshots(source, 3));
            UUID unrelated = UUID.randomUUID();
            ReadonlyWebService.revoke(unrelated);
            ReadonlyWebService.revoke(unrelated);
            ReadonlyWebAdapterTest.FakeExchange initial = new ReadonlyWebAdapterTest.FakeExchange(
                "GET",
                "/api/snapshots");
            initial.request.set("Authorization", "Bearer " + fixture);
            adapter.handle(initial);
            assertTrue(
                initial.body()
                    .contains("Shared one"));
            assertTrue(
                initial.body()
                    .contains("Independent page"));
            ReadonlyWebService.revoke(shared);
            ReadonlyWebService.revoke(shared);
            ReadonlyWebService.revoke(unrelated);
            ReadonlyWebAdapterTest.FakeExchange revoked = new ReadonlyWebAdapterTest.FakeExchange(
                "GET",
                "/api/snapshots");
            revoked.request.set("Authorization", "Bearer " + fixture);
            adapter.handle(revoked);
            assertEquals(200, revoked.code);
            assertTrue(
                revoked.body()
                    .contains("Independent page"));
            assertFalse(
                revoked.body()
                    .contains("Shared one"));
            assertFalse(
                revoked.body()
                    .contains("Shared two"));
        } finally {
            ReadonlyWebService.stop();
        }
    }

}
