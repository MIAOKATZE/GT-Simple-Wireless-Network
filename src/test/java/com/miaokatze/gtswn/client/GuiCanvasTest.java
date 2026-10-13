package com.miaokatze.gtswn.client;

import static org.junit.Assert.*;

import org.junit.Test;

import com.miaokatze.gtswn.client.gui.GuiCanvas;

public class GuiCanvasTest {

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
