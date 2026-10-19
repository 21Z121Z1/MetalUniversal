package com.metallum.e2e;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Independent RGB coverage oracle for three opaque concrete walls in the real world. */
final class TerrainColorProbe {
    private TerrainColorProbe() { }

    static JsonObject inspect(byte[] rgba, int width, int height) {
        if (width < 20 || height < 20 || (long) width * height * 4 != rgba.length) {
            throw new IllegalArgumentException("Invalid terrain probe framebuffer extent");
        }
        JsonObject result = new JsonObject();
        result.addProperty("scenario", "opaque-concrete-walls-v1");
        result.addProperty("width", width);
        result.addProperty("height", height);
        JsonArray bands = new JsonArray();
        boolean passed = true;
        for (int band = 0; band < 3; band++) {
            int center = (int) (width * (0.2 + 0.3 * band));
            int halfWidth = Math.max(1, width / 25);
            int matches = 0;
            int total = 0;
            for (int y = height * 2 / 5; y < height * 3 / 5; y++) {
                for (int x = center - halfWidth; x < center + halfWidth; x++) {
                    int offset = (y * width + x) * 4;
                    int r = rgba[offset] & 255;
                    int g = rgba[offset + 1] & 255;
                    int b = rgba[offset + 2] & 255;
                    boolean matchesColor = switch (band) {
                        case 0 -> r >= 16 && r > g * 1.5 && r > b * 1.5;
                        case 1 -> g >= 16 && g > r * 1.3 && g > b * 1.5;
                        default -> b >= 16 && b > r * 1.5 && b > g * 1.5;
                    };
                    if (matchesColor) matches++;
                    total++;
                }
            }
            boolean bandPassed = matches >= total * 0.99;
            JsonObject evidence = new JsonObject();
            evidence.addProperty("expected", new String[]{"red", "lime", "blue"}[band]);
            evidence.addProperty("matchingPixels", matches);
            evidence.addProperty("sampledPixels", total);
            evidence.addProperty("passed", bandPassed);
            bands.add(evidence);
            passed &= bandPassed;
        }
        result.add("bands", bands);
        result.addProperty("passed", passed);
        return result;
    }
}
