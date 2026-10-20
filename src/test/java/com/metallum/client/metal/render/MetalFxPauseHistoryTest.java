package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MetalFxPauseHistoryTest {
    @Test
    void repeatedPausedFramesDoNotContinuouslyResetHistory() {
        var state = new MetalFxManager.PauseHistory();
        assertFalse(state.observe(false)); // First source already starts with reset.
        assertFalse(state.observe(false));
        assertTrue(state.observe(true));
        assertTrue(state.paused());
        assertFalse(state.observe(true));
        assertFalse(state.observe(true));
        assertTrue(state.observe(false));
        assertFalse(state.paused());
        assertFalse(state.observe(false));
    }

    @Test
    void firstObservedFrameCanBePausedWithoutInventingPriorHistory() {
        var state = new MetalFxManager.PauseHistory();
        assertFalse(state.observe(true));
        assertTrue(state.paused());
        assertTrue(state.observe(false));
        assertFalse(state.observe(false));
    }
}
