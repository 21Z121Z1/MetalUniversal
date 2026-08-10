package com.metallum;

import com.metallum.client.metal.render.bridge.IOSRuntimePreflight;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Metallum implements ModInitializer, PreLaunchEntrypoint {
    public static final String MOD_ID = "metallum";

    // This logger is used to write text to the console and the log file.
    // It is considered best practice to use your mod id as its logger's name.
    // That way, it's clear which mod wrote info, warnings, and errors.
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onPreLaunch() {
        // Fabric's pre-launch entrypoint runs before Minecraft initializes its
        // native libraries. On iOS this is the last safe point to make the
        // launcher-provided LWJGL runtime and MetalUniversal's full
        // SPIRV-Cross/MSL native agree. Fail here with a precise diagnostic
        // instead of letting NativeLibrariesBootstrap or the first shader
        // compilation fail later with an unrelated linkage/backend error.
        IOSRuntimePreflight.prepare();
    }

    @Override
    public void onInitialize() {
    }
}
