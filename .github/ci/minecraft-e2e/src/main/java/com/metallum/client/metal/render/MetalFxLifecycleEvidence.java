package com.metallum.client.metal.render;

import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exercises real frame-boundary settings and pause transitions in the loaded CI world. */
final class MetalFxLifecycleEvidence {
    private MetalFxLifecycleEvidence() {}

    static void verify(ClientGameTestContext context, Path output) {
        Map<String, MetalFxManager.ScalingStatus> states = new LinkedHashMap<>();
        var initial = snapshot(context);
        states.put("initial", initial);
        try {
            context.runOnClient(client -> {
                // Align the persisted owner with the launch override before toggling it.
                MetalFxConfig.setModeFromSodium(MetalFxConfig.Mode.TEMPORAL);
                System.setProperty(MetalFxConfig.MODE_PROPERTY, "OFF");
                MetalFxConfig.setModeFromSodium(MetalFxConfig.Mode.OFF);
            });
            context.waitFor(client -> {
                var s = MetalFxManager.scalingStatus();
                return s.requestedMode().equals("OFF") && s.effectiveMode().equals("OFF")
                        && s.historyEpoch() > initial.historyEpoch();
            }, 100);
            var off = snapshot(context);
            require(off.renderWidth() == off.displayWidth() && off.renderHeight() == off.displayHeight(),
                    "Disabling MetalFX did not restore native scene dimensions: " + off);
            states.put("disabled", off);

            context.runOnClient(client -> {
                System.setProperty(MetalFxConfig.MODE_PROPERTY, "TEMPORAL");
                MetalFxConfig.setModeFromSodium(MetalFxConfig.Mode.TEMPORAL);
            });
            context.waitFor(client -> {
                var s = MetalFxManager.scalingStatus();
                return s.effectiveMode().equals(initial.effectiveMode()) && s.historyEpoch() > off.historyEpoch()
                        && s.spatialEncodes() + s.temporalEncodes() > off.spatialEncodes() + off.temporalEncodes();
            }, 100);
            var enabled = snapshot(context);
            states.put("reenabled", enabled);

            context.runOnClient(client -> {
                System.setProperty(MetalFxConfig.SCALE_PROPERTY, "0.5");
                MetalFxConfig.setScaleFromSodium(MetalFxConfig.Scale.HALF);
            });
            context.waitFor(client -> {
                var s = MetalFxManager.scalingStatus();
                return s.historyEpoch() > enabled.historyEpoch() && s.renderWidth() < enabled.renderWidth()
                        && s.renderHeight() < enabled.renderHeight();
            }, 100);
            var half = snapshot(context);
            states.put("scale50", half);
            context.runOnClient(client -> {
                System.setProperty(MetalFxConfig.SCALE_PROPERTY, "0.67");
                MetalFxConfig.setScaleFromSodium(MetalFxConfig.Scale.QUALITY);
            });
            context.waitFor(client -> {
                var s = MetalFxManager.scalingStatus();
                return s.historyEpoch() > half.historyEpoch() && s.renderWidth() == enabled.renderWidth()
                        && s.renderHeight() == enabled.renderHeight();
            }, 100);
            var restored = snapshot(context);
            states.put("scale67", restored);

            context.runOnClient(client -> client.pauseGame(false));
            context.waitFor(client -> client.isPaused()
                    && MetalFxManager.scalingStatus().historyEpoch() > restored.historyEpoch(), 100);
            var paused = snapshot(context);
            states.put("paused", paused);
            // LoadingOverlay pauses singleplayer too. Reload while already paused
            // so pause/resume invalidation cannot accidentally hide a missing reload hook.
            var reload = context.computeOnClient(client -> client.reloadResourcePacks());
            context.waitFor(client -> reload.isDone(), 600);
            reload.join();
            context.waitFor(client -> client.gui.overlay() == null && client.isPaused(), 100);
            var pausedReload = snapshot(context);
            states.put("reloadedWhilePaused", pausedReload);
            require(pausedReload.session() != paused.session() || pausedReload.historyEpoch() > paused.historyEpoch(),
                    "Resource reload reused history while pause state stayed unchanged: " + pausedReload);
            context.runOnClient(client -> client.gui.setScreen(null));
            context.waitFor(client -> !client.isPaused()
                    && MetalFxManager.scalingStatus().historyEpoch() > pausedReload.historyEpoch(), 100);
            states.put("resumed", snapshot(context));
            for (var entry : states.entrySet()) {
                var s = entry.getValue();
                require(!s.runtimeDisabled() && !s.frameGenerationEnabled() && s.frameGenerationQueued() == 0,
                        "Unsafe or disabled MetalFX transition " + entry);
            }
            try {
                Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                        "verified", true, "states", states,
                        "boundary", "real client settings/pause/history transitions; Temporal/FG quality requires supported hardware")) + "\n");
            } catch (IOException failure) { throw new IllegalStateException("Could not write lifecycle evidence", failure); }
        } finally {
            context.runOnClient(client -> {
                client.gui.setScreen(null);
                System.setProperty(MetalFxConfig.MODE_PROPERTY, "TEMPORAL");
                System.setProperty(MetalFxConfig.SCALE_PROPERTY, "0.67");
                MetalFxConfig.setModeFromSodium(MetalFxConfig.Mode.TEMPORAL);
                MetalFxConfig.setScaleFromSodium(MetalFxConfig.Scale.QUALITY);
            });
        }
    }

    private static MetalFxManager.ScalingStatus snapshot(ClientGameTestContext context) {
        return context.computeOnClient(client -> MetalFxManager.scalingStatus());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
