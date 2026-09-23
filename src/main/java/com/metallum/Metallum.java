package com.metallum;

import com.metallum.client.metal.render.bridge.IOSRuntimePreflight;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Metallum implements ModInitializer, PreLaunchEntrypoint {
    public static final String MOD_ID = "metallum";

    // This logger is used to write text to the console and the log file.
    // It is considered best practice to use your mod id as the logger's name.
    // That way, it's clear which mod wrote info, warnings, and errors.
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onPreLaunch() {
        // Select SPVC before Minecraft initializes its static library handle.
        // Do not initialize the GPU/FFM bridge during Fabric prelaunch.
        IOSRuntimePreflight.prepare();
    }

    @Override
    public void onInitialize() {
    }
}
