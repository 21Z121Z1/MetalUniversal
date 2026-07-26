package com.metallum.client.metal.framegraph;

/**
 * A renderer extension declares resources and passes; the backend keeps
 * execution.
 *
 * <p>This is the shape a shader pack adapter wants: the pack's composite chain
 * is a list of passes over semantic resources, and declaring it is enough for
 * the compiler to order it, insert its barriers and allocate its intermediates.
 * The extension never receives a Metal handle, so a malformed pack is a compile
 * error rather than a driver abort.</p>
 */
public interface FrameGraphExtension {
    /** Stable identity, used in diagnostics and to keep pass names unique. */
    String id();

    /**
     * Whether this extension participates in the current frame. An extension
     * that is not enabled contributes nothing at all: no resources, no passes,
     * no slots.
     */
    default boolean isEnabled() {
        return true;
    }

    void declare(FrameGraphBuilder graph);
}
