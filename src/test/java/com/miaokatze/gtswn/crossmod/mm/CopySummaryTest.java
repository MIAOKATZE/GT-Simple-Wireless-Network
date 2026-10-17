package com.miaokatze.gtswn.crossmod.mm;

import static org.junit.Assert.*;

import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import org.junit.Test;

public class CopySummaryTest {

    @Test
    public void ordinaryCopiesStaySilentAndCompletionIsEmittedOnlyOnce() {
        CopySummary.Batch ordinary = new CopySummary.Batch();
        assertNull(ordinary.finish(true));
        assertNull(ordinary.finish(false));
        CopySummary.Batch batch = new CopySummary.Batch();
        batch.installed(target(1, 0), false, 2);
        assertEquals("gtswn.chat.mm.processed", translation(batch.finish(true)).getKey());
        assertNull(batch.finish(true));
        assertNull(batch.finish(false));
    }

    @Test
    public void repeatedAttemptsDoNotDoubleCountMaterialsAndSuccessfulRetryClearsFailure() {
        CopySummary.Batch batch = new CopySummary.Batch();
        CopySummary.Target link = target(1, 0);
        batch.failed(link, false, true);
        batch.failed(link, false, true);
        assertEquals(1, batch.counts().missingTubes);
        batch.installed(link, false, 1);
        batch.installed(link, false, 1);
        batch.failed(link, false, false);
        assertEquals(1, batch.counts().links);
        assertEquals(1, batch.counts().tubes);
        assertEquals(0, batch.counts().missingTubes);
        assertEquals(0, batch.counts().linkFailures);
        CopySummary.Target incorporation = target(2, -1);
        batch.failed(incorporation, true, false);
        batch.installed(incorporation, true, 1);
        batch.installed(incorporation, true, 1);
        assertEquals(1, batch.counts().incorporations);
        assertEquals(1, batch.counts().anchors);
        assertEquals(0, batch.counts().incorporationFailures);
    }

    @Test
    public void receiptSeparatesSuccessMissingMaterialInstallationFailureAndOptionalRefill() {
        CopySummary.Batch batch = new CopySummary.Batch();
        batch.installed(target(1, 0), false, 1);
        batch.installed(target(1, 1), false, 0);
        batch.installed(target(2, -1), true, 1);
        batch.failed(target(3, 0), false, true);
        batch.failed(target(4, 0), false, false);
        batch.failed(target(5, -1), true, true);
        batch.failed(target(6, -1), true, false);
        batch.deferred(target(1, 0));
        batch.deferred(target(1, 0));
        CopySummary.Counts counts = batch.counts();
        assertEquals(2, counts.links);
        assertEquals(1, counts.tubes);
        assertEquals(1, counts.incorporations);
        assertEquals(1, counts.anchors);
        assertEquals(1, counts.missingTubes);
        assertEquals(1, counts.linkFailures);
        assertEquals(1, counts.missingAnchors);
        assertEquals(1, counts.incorporationFailures);
        assertEquals(1, counts.deferred);
        IChatComponent receipt = batch.finish(false);
        assertEquals("gtswn.chat.mm.stopped", translation(receipt).getKey());
        assertEquals(
            7,
            receipt.getSiblings()
                .size());
        assertEquals(
            "gtswn.chat.mm.refill_deferred",
            translation(
                receipt.getSiblings()
                    .get(6)).getKey());
        batch.installed(target(9, 0), false, 5);
        batch.deferred(target(9, 0));
        assertEquals(2, batch.counts().links);
        assertEquals(1, batch.counts().deferred);
    }

    @Test
    public void dimensionAndSideArePartOfTheReceiptIdentityAndRefillAloneIsRelevant() {
        CopySummary.Batch batch = new CopySummary.Batch();
        batch.installed(new CopySummary.Target(0, 1, 2, 3, 0), false, 1);
        batch.installed(new CopySummary.Target(1, 1, 2, 3, 0), false, 1);
        batch.installed(new CopySummary.Target(0, 1, 2, 3, 1), false, 1);
        assertEquals(3, batch.counts().links);
        CopySummary.Batch refill = new CopySummary.Batch();
        refill.deferred(target(1, 0));
        IChatComponent receipt = refill.finish(true);
        assertNotNull(receipt);
        assertEquals(
            1,
            receipt.getSiblings()
                .size());
        assertEquals(
            "gtswn.chat.mm.refill_deferred",
            translation(
                receipt.getSiblings()
                    .get(0)).getKey());
        assertEquals(0, refill.counts().linkFailures);
    }

    @Test
    public void nodeRestoreIsRelevantWithoutMaterialConsumptionAndSuccessfulRefillClearsDowngrade() {
        CopySummary.Batch batch = new CopySummary.Batch();
        batch.nodeRestored(target(1, -1));
        batch.nodeRestored(target(1, -1));
        batch.deferred(target(2, 0));
        batch.refilled(target(2, 0));
        assertEquals(1, batch.counts().nodes);
        assertEquals(0, batch.counts().anchors);
        assertEquals(0, batch.counts().tubes);
        assertEquals(0, batch.counts().deferred);
        IChatComponent receipt = batch.finish(true);
        assertEquals(
            1,
            receipt.getSiblings()
                .size());
        assertEquals(
            "gtswn.chat.mm.nodes",
            translation(
                receipt.getSiblings()
                    .get(0)).getKey());
    }

    @Test
    public void realConfigurationChangesAreRelevantAndNewInstallationsDoNotCountThemTwice() {
        CopySummary.Batch batch = new CopySummary.Batch();
        batch.configured(target(1, 0));
        batch.configured(target(1, 0));
        batch.configured(target(2, 0));
        batch.installed(target(2, 0), false, 1);
        batch.installed(target(3, 0), false, 1);
        batch.configured(target(3, 0));
        assertEquals(1, batch.counts().configurations);
        assertEquals(2, batch.counts().links);
        IChatComponent receipt = batch.finish(true);
        assertEquals(
            2,
            receipt.getSiblings()
                .size());
        assertEquals(
            "gtswn.chat.mm.configured",
            translation(
                receipt.getSiblings()
                    .get(1)).getKey());

        CopySummary.Batch configurationOnly = new CopySummary.Batch();
        configurationOnly.configured(target(4, 0));
        assertNotNull(configurationOnly.finish(true));
    }

    private static ChatComponentTranslation translation(IChatComponent component) {
        return (ChatComponentTranslation) component;
    }

    private static CopySummary.Target target(int x, int side) {
        return new CopySummary.Target(0, x, 64, 0, side);
    }
}
