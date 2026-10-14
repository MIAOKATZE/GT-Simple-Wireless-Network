package com.miaokatze.gtswn.client;

import static org.junit.Assert.*;

import org.junit.Test;

import com.miaokatze.gtswn.client.gui.GuiCanvas;

public class GuiCanvasTest {

    @Test
    public void portablePanelShrinksByFifteenPercentAndAddsMarginsAtEveryGuiScale() {
        for (int[] screen : new int[][] { { 320, 240 }, { 427, 240 }, { 640, 360 }, { 854, 480 }, { 1280, 720 },
            { 2560, 1440 } }) {
            int panelWidth = Math.max(430, Math.min(640, screen[0] - 16));
            int panelHeight = Math.max(300, Math.min(420, screen[1] - 16));
            GuiCanvas previous = new GuiCanvas(screen[0], screen[1], panelWidth, panelHeight);
            GuiCanvas smaller = new GuiCanvas(screen[0], screen[1], panelWidth, panelHeight, .85f);
            assertEquals(previous.scale * .85f, smaller.scale, .00001f);
            assertTrue(panelWidth * smaller.scale <= 544.01f);
            assertTrue(panelHeight * smaller.scale <= 357.01f);
            int left = (smaller.width - panelWidth) / 2, top = (smaller.height - panelHeight) / 2;
            assertTrue(left * smaller.scale >= (screen[0] - panelWidth * previous.scale) / 2 + 10);
            assertTrue(top * smaller.scale >= (screen[1] - panelHeight * previous.scale) / 2 + 10);
            assertTrue((left + panelWidth) * smaller.scale <= screen[0] - 10);
            assertTrue((top + panelHeight) * smaller.scale <= screen[1] - 10);
        }
    }

    @Test
    public void reducedPortableCanvasKeepsSettingsFieldChoicesAndPagingInputAligned() {
        for (int[] screen : new int[][] { { 320, 240, 4 }, { 427, 240, 6 }, { 640, 360, 4 }, { 1280, 720, 2 } }) {
            int panelWidth = Math.max(430, Math.min(640, screen[0] - 16));
            int panelHeight = Math.max(300, Math.min(420, screen[1] - 16));
            GuiCanvas canvas = new GuiCanvas(screen[0], screen[1], panelWidth, panelHeight, .85f);
            int left = (canvas.width - panelWidth) / 2, top = (canvas.height - panelHeight) / 2;
            int fieldWidth = (panelWidth - 28) / 2;
            int visibleRows = Math.max(1, Math.min(8, (panelHeight - 174) / 20));
            // Settings save field, average checkbox on the last page row, then next-page button.
            for (int[] button : new int[][] { { panelWidth / 2 + 12, panelHeight - 28, panelWidth / 2 - 24, 18 },
                { 16 + fieldWidth, 137 + (visibleRows - 1) * 20, fieldWidth, 18 },
                { panelWidth - 48, panelHeight - 28, 32, 18 } }) {
                int x = left + button[0] + button[2] / 2, y = top + button[1] + button[3] / 2;
                assertTrue(button[0] >= 12 && button[0] + button[2] <= panelWidth - 12);
                assertTrue(button[1] >= 7 && button[1] + button[3] <= panelHeight - 10);
                assertEquals(x, canvas.mouse(Math.round(x * canvas.scale)), 2);
                assertEquals(y, canvas.mouse(Math.round(y * canvas.scale)), 2);
                int rawX = Math.round(x * canvas.scale * screen[2]);
                int inputX = rawX * canvas.width / (screen[0] * screen[2]);
                assertEquals(x, inputX, 2);
            }
            int[] clip = canvas.scissor(
                left + 12,
                top + 107,
                panelWidth - 24,
                panelHeight - 156,
                screen[2],
                screen[0] * screen[2],
                screen[1] * screen[2]);
            assertTrue(clip[0] >= 0 && clip[1] >= 0 && clip[2] > 0 && clip[3] > 0);
            assertTrue(clip[0] + clip[2] <= screen[0] * screen[2]);
            assertTrue(clip[1] + clip[3] <= screen[1] * screen[2]);
            assertEquals(Math.ceil((panelWidth - 24) * canvas.scale * screen[2]), clip[2], 1);
            assertEquals(Math.ceil((panelHeight - 156) * canvas.scale * screen[2]), clip[3], 1);
        }
    }

    @Test
    public void highGuiScalePanelsButtonsAndClipRemainInsideFramebuffer() {
        for (int[] screen : new int[][] { { 320, 240, 4 }, { 427, 240, 6 }, { 1280, 720, 2 } }) {
            for (int[] panel : new int[][] { { 430, 300 }, { 430, 285 }, { 640, 420 } }) {
                GuiCanvas canvas = new GuiCanvas(screen[0], screen[1], panel[0], panel[1]);
                int left = (canvas.width - panel[0]) / 2, top = (canvas.height - panel[1]) / 2;
                assertTrue(left * canvas.scale >= 7);
                assertTrue(top * canvas.scale >= 7);
                assertTrue((left + panel[0]) * canvas.scale <= screen[0] - 7);
                assertTrue((top + panel[1]) * canvas.scale <= screen[1] - 7);
                int x = left + panel[0] - 40, y = top + panel[1] - 20;
                assertEquals(x, canvas.mouse(Math.round(x * canvas.scale)), 1);
                assertEquals(y, canvas.mouse(Math.round(y * canvas.scale)), 1);
                // Native GuiScreen input derives its logical coordinates from width/height.
                int rawX = Math.round(x * canvas.scale * screen[2]);
                int inputX = rawX * canvas.width / (screen[0] * screen[2]);
                assertEquals(x, inputX, 1);
                int[] clip = canvas.scissor(
                    left + 12,
                    top + 107,
                    panel[0] - 24,
                    panel[1] - 156,
                    screen[2],
                    screen[0] * screen[2],
                    screen[1] * screen[2]);
                assertTrue(clip[0] >= 0 && clip[1] >= 0);
                assertTrue(clip[2] > 0 && clip[3] > 0);
                assertTrue(clip[0] + clip[2] <= screen[0] * screen[2]);
                assertTrue(clip[1] + clip[3] <= screen[1] * screen[2]);
                assertEquals(Math.ceil((panel[0] - 24) * canvas.scale * screen[2]), clip[2], 1);
            }
        }
    }
}
