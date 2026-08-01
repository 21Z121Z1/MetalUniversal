package com.metallum.client.validation.contract;

public record ScissorRecord(boolean enabled, int x, int y, int width, int height) {
    public ScissorRecord {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Scissor dimensions must not be negative");
        }
    }

    public static ScissorRecord disabled() {
        return new ScissorRecord(false, 0, 0, 0, 0);
    }
}
