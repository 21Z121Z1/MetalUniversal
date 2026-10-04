package com.metallum.client.startup;

import com.mojang.text2speech.Narrator;

/**
 * Defers creation of the platform text-to-speech backend until Minecraft
 * actually uses narration.
 */
public final class DeferredNarrator implements Narrator {
    private Narrator delegate;

    private synchronized Narrator delegate() {
        if (this.delegate == null) {
            this.delegate = Narrator.getNarrator();
        }
        return this.delegate;
    }

    @Override
    public void say(String message, boolean interrupt, float volume) {
        this.delegate().say(message, interrupt, volume);
    }

    @Override
    public void clear() {
        this.delegate().clear();
    }

    @Override
    public boolean active() {
        return this.delegate().active();
    }

    @Override
    public synchronized void destroy() {
        if (this.delegate != null) {
            this.delegate.destroy();
        }
    }
}
