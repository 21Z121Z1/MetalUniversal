package com.metallum.e2e;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class TerrainColorProbeTest {
    private static final int WIDTH = 120;
    private static final int HEIGHT = 80;

    private static byte[] panels(boolean mirrored) {
        byte[] image = new byte[WIDTH * HEIGHT * 4];
        int[][] colors = {{120, 28, 28}, {72, 136, 20}, {28, 30, 120}};
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int band = x * 3 / WIDTH;
                int[] color = colors[mirrored ? 2 - band : band];
                for (int c = 0; c < 3; c++) image[(y * WIDTH + x) * 4 + c] = (byte) color[c];
            }
        }
        return image;
    }

    @Test void acceptsOpaqueWallColorsUnderOrdinaryDirectionalShading() {
        assertTrue(TerrainColorProbe.inspect(panels(false), WIDTH, HEIGHT).get("passed").getAsBoolean());
    }

    @Test void rejectsMirroredWorldGeometry() {
        assertFalse(TerrainColorProbe.inspect(panels(true), WIDTH, HEIGHT).get("passed").getAsBoolean());
    }

    @Test void rejectsMissingTrianglesEvenThoughTheFrameIsNonBlackAndMulticolored() {
        byte[] image = panels(false);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if ((x + y) % 3 == 0) {
                    for (int c = 0; c < 3; c++) image[(y * WIDTH + x) * 4 + c] = 0;
                }
            }
        }
        assertFalse(TerrainColorProbe.inspect(image, WIDTH, HEIGHT).get("passed").getAsBoolean());
    }

    @Test void rejectsBlankOrMalformedReadback() {
        assertFalse(TerrainColorProbe.inspect(new byte[WIDTH * HEIGHT * 4], WIDTH, HEIGHT).get("passed").getAsBoolean());
        assertThrows(IllegalArgumentException.class, () -> TerrainColorProbe.inspect(new byte[4], WIDTH, HEIGHT));
    }
}
