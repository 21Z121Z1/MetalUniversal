package com.metallum.client.metal.render;

/** Logical surface lease. Drawable acquisition remains in the one native present path. */
final class MetalSurfaceFrameState {
    private enum State { NEW, READY, ACQUIRED, ENCODED, CLOSED }
    private State state = State.NEW;
    private long epoch;

    void requireConfigurable() {
        if (state != State.NEW && state != State.READY) throw new IllegalStateException("Surface has a live frame or is closed: " + state);
    }
    void configured() { requireConfigurable(); epoch++; state = State.READY; }
    void acquire() {
        if (state != State.READY) throw new IllegalStateException("Surface is not ready to acquire: " + state);
        state = State.ACQUIRED;
    }
    void requireAcquired() {
        if (state != State.ACQUIRED) throw new IllegalStateException("Surface has no unencoded frame: " + state);
    }
    void encoded() { requireAcquired(); state = State.ENCODED; }
    void present() {
        if (state != State.ENCODED) throw new IllegalStateException("Surface has no encoded frame: " + state);
        state = State.READY;
    }
    void close() { state = State.CLOSED; }
    long epoch() { return epoch; }
}
