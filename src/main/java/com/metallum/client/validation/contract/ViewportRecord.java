package com.metallum.client.validation.contract;

public record ViewportRecord(int x, int y, int width, int height) {
    public ViewportRecord {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Viewport dimensions must not be negative");
        }
    }
}
