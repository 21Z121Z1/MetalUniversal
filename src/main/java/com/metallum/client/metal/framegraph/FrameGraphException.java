package com.metallum.client.metal.framegraph;

/**
 * Thrown when a declared frame graph cannot be compiled into a valid execution
 * plan. Every such failure is a programming error in a pass declaration, so it
 * surfaces at compile time rather than as a Metal validation abort or a silent
 * read of uninitialised memory.
 */
public final class FrameGraphException extends IllegalStateException {
    public FrameGraphException(final String message) {
        super(message);
    }
}
