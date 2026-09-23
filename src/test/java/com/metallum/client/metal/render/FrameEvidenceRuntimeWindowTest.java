package com.metallum.client.metal.render;

import com.google.gson.JsonObject;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class FrameEvidenceRuntimeWindowTest {
    @Test void runtimeUsesTheExactDriverAnchorAndDisabledCaptureHasNoRecorder() throws Exception {
        String[] keys = {"metallum.frameEvidence.enabled", "metallum.frameEvidence.windowed",
                "metallum.frameEvidence.mode", "metallum.frameEvidence.segmented"};
        var previous = new HashMap<String, String>();
        for (String key : keys) previous.put(key, System.getProperty(key));
        try {
            for (boolean enabled : new boolean[]{false, true}) {
                System.setProperty(keys[0], Boolean.toString(enabled));
                System.setProperty(keys[1], "true");
                System.setProperty(keys[2], "timing");
                System.setProperty(keys[3], "false");
                // Reload only the static runtime facade. Dependencies and the recorder
                // remain shared; no Minecraft window, native module, or GPU is started.
                String name = FrameEvidenceRuntime.class.getName();
                URL location = FrameEvidenceRuntime.class.getProtectionDomain().getCodeSource().getLocation();
                try (var isolated = new URLClassLoader(new URL[]{location}, getClass().getClassLoader()) {
                    @Override protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
                        synchronized (getClassLoadingLock(requested)) {
                            if (!requested.equals(name)) return super.loadClass(requested, resolve);
                            Class<?> type = findLoadedClass(requested);
                            if (type == null) type = findClass(requested);
                            if (resolve) resolveClass(type);
                            return type;
                        }
                    }
                }) {
                    Class<?> runtime = Class.forName(name, true, isolated);
                    var method = runtime.getMethod("armWindowAt", JsonObject.class, long.class, long.class, long.class);
                    var field = runtime.getDeclaredField("RECORDER");
                    field.setAccessible(true);
                    if (!enabled) {
                        method.invoke(null, null, -1L, -1L, Long.MAX_VALUE);
                        assertNull(field.get(null), "Disabled capture must not allocate a recorder");
                    } else {
                        var profile = new JsonObject(); profile.addProperty("route", "fixture");
                        method.invoke(null, profile, 10L, 20L, 100L);
                        profile.addProperty("route", "mutated");
                        var recorder = (FrameEvidenceRecorder) field.get(null);
                        var window = recorder.snapshot(new JsonObject()).getAsJsonObject("window");
                        assertEquals(110L, window.get("startNs").getAsLong());
                        assertEquals(130L, window.get("endNs").getAsLong());
                        assertEquals("fixture", window.getAsJsonObject("profile").get("route").getAsString());
                        var failure = assertThrows(InvocationTargetException.class,
                                () -> method.invoke(null, new JsonObject(), 0L, 1L, 200L));
                        assertInstanceOf(IllegalStateException.class, failure.getCause());
                        assertEquals(window, recorder.snapshot(new JsonObject()).getAsJsonObject("window"));
                    }
                }
            }
        } finally {
            for (String key : keys) {
                if (previous.get(key) == null) System.clearProperty(key);
                else System.setProperty(key, previous.get(key));
            }
        }
    }
}
