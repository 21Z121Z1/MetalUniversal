package com.metallum.e2e;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Versioned test actions in the existing client/input loop; never a renderer or simulation scheduler. */
final class FrameWorkloads {
    static final String ID = System.getProperty("metallum.ci.workload", "");
    static final boolean ENABLED = !ID.isEmpty();
    static final String PRODUCER = System.getProperty("metallum.ci.producer", "vanilla");
    static final boolean PREPARE = Boolean.getBoolean("metallum.ci.prepareScene");
    static final boolean TRANSITIONS = ID.equals("X0");
    static final Set<String> IDS = Set.of("P0", "T0", "C0", "G0", "I0", "I1", "X0");
    static final long WARMUP_NS = durationNanos("metallum.ci.warmupNs", 30_000_000_000L, 0, 3_600_000_000_000L);
    static final long SAMPLE_NS = durationNanos("metallum.ci.sampleNs", 120_000_000_000L, 1_000_000_000L, 86_400_000_000_000L);
    private static final String CPU_TAG = "metallum_cpu_fixture_v1";

    private FrameWorkloads() { }

    static long durationNanos(String property, long fallback, long minimum, long maximum) {
        String raw = System.getProperty(property);
        long value = raw == null ? fallback : Long.parseLong(raw.trim());
        if (value < minimum || value > maximum) throw new IllegalArgumentException(property + " is outside the declared trial bounds");
        return value;
    }

    static void validate() {
        require(IDS.contains(ID), "Unknown versioned workload: " + ID);
        require(Set.of("vanilla", "sodium", "iris").contains(PRODUCER), "Unknown producer");
        require(Set.of("metal3", "metal4").contains(System.getProperty("metallum.ci.gameplayBackend", "metal3")), "Unknown Metal lowering");
        require(!ID.equals("T0") || SAMPLE_NS >= 50_000_000_000L, "T0 requires its complete declared route");
        require(!ID.equals("X0") || SAMPLE_NS >= 60_000_000_000L, "X0 requires its complete declared transition window");
        require(!ID.startsWith("I") || PRODUCER.equals("iris"), "I0/I1 need the actual Iris adapter");
        require(!PREPARE || ID.equals("C0") || ID.equals("G0"), "Only C0/G0 have generated scene recipes");
    }

    /** Opt-in bootstrap only. Subsequent trials restore the resulting saved snapshot, not this recipe. */
    static void prepare(ClientGameTestContext context, TestSingleplayerContext world) {
        if (!ENABLED) return;
        validate();
        if (PREPARE && ID.equals("C0")) {
            require(cpuEntities(world) == 0, "CPU fixture already present; do not add it twice");
            world.getServer().runCommand("fill 164 134 162 192 140 183 minecraft:glass hollow");
            world.getServer().runCommand("fill 165 134 163 191 134 182 minecraft:sea_lantern");
            for (int i = 0; i < 128; i++) {
                double x = 166 + (i % 16) * 1.5;
                double z = 164 + (i / 16) * 2.0;
                world.getServer().runCommand("summon minecraft:villager " + x + " 135 " + z
                        + " {PersistenceRequired:1b,Tags:[\"" + CPU_TAG + "\"]}");
            }
            // Normal AI, entity tick and game rules remain active. This is a disposable test scene.
            context.waitTicks(20);
        }
        if (PREPARE && ID.equals("G0")) {
            for (int layer = 0; layer < 9; layer++) {
                int x = 172 + layer * 2;
                String block = layer % 2 == 0 ? "red_stained_glass" : "blue_stained_glass";
                world.getServer().runCommand("fill " + x + " 127 155 " + x + " 150 181 minecraft:" + block);
            }
            context.waitTicks(20);
        }
        validateScene(world);
    }

    static void validateScene(TestSingleplayerContext world) {
        if (ID.equals("C0")) require(cpuEntities(world) == 128, "C0 requires the prepared 128-entity snapshot; AI must remain active");
        if (ID.equals("G0")) {
            boolean intact = world.getServer().computeOnServer(server -> {
                var level = server.overworld();
                for (int layer = 0; layer < 9; layer++) for (int y = 127; y <= 150; y++) for (int z = 155; z <= 181; z++) {
                    var expected = layer % 2 == 0 ? Blocks.STAINED_GLASS.red() : Blocks.STAINED_GLASS.blue();
                    if (!level.getBlockState(new BlockPos(172 + 2 * layer, y, z)).is(expected)) return false;
                }
                return true;
            });
            require(intact, "G0 requires the complete prepared nine-layer glass snapshot");
        }
    }

    private static int cpuEntities(TestSingleplayerContext world) {
        return world.getServer().computeOnServer(server -> {
            int count = 0;
            for (var entity : server.overworld().getAllEntities()) if (entity.entityTags().contains(CPU_TAG)) count++;
            return count;
        });
    }

    static void validateProducer(String requested, boolean sodium, boolean iris) {
        require(Set.of("vanilla", "sodium", "iris").contains(requested), "Unknown producer");
        require(sodium == !requested.equals("vanilla") && iris == requested.equals("iris"),
                "Loaded producer differs from the trial");
    }

    static JsonObject producerReceipt() {
        var result = new JsonObject();
        var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
        boolean sodium = loader.isModLoaded("sodium"), iris = loader.isModLoaded("iris");
        validateProducer(PRODUCER, sodium, iris);
        result.addProperty("producer", PRODUCER);
        result.addProperty("sodiumInstalled", sodium); result.addProperty("irisInstalled", iris);
        if (iris) {
            // Only enter the pinned optional API when Iris is actually present.
            try {
                Class<?> adapter = Class.forName("com.metallum.client.metal.render.IrisMetalPipelineOverrides");
                int generation = (Integer) adapter.getMethod("activeGenerationForDiagnostics").invoke(null);
                String pack = (String) Class.forName("net.irisshaders.iris.Iris").getMethod("getCurrentPackName").invoke(null);
                String expected = System.getProperty("metallum.ci.shaderPackName", "");
                require(generation >= 0 && !expected.isEmpty() && expected.equals(pack), "Selected shader pack did not activate through the Metal adapter");
                result.addProperty("shaderPack", pack); result.addProperty("generation", generation);
                result.addProperty("motionSemantics", "unproven; frame interpolation remains independently fail-closed");
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Pinned Iris activation receipt is unavailable", failure);
            }
        }
        return result;
    }

    static void run(ClientGameTestContext context, TestSingleplayerContext world,
                    long sampleStart, long sampleEnd, int width, int height, JsonObject receipt) {
        JsonArray actions = new JsonArray();
        receipt.addProperty("workloadId", ID); receipt.addProperty("protocolVersion", 1);
        receipt.addProperty("sourceSampleStartNs", sampleStart); receipt.addProperty("sourceSampleEndNs", sampleEnd);
        receipt.add("actions", actions);
        receipt.add("producerBefore", context.computeOnClient(client -> producerReceipt()));
        context.waitFor(client -> System.nanoTime() >= sampleStart, timeoutTicks(sampleEnd - System.nanoTime()));
        receipt.add("jvmBefore", jvmObservation());
        int serverStart = world.getServer().computeOnServer(server -> server.getTickCount());
        receipt.addProperty("serverTickAtActionsStart", serverStart);
        var input = context.getInput();
        if (ID.equals("T0")) {
            double x = context.computeOnClient(client -> client.player.getX());
            double z = context.computeOnClient(client -> client.player.getZ());
            input.holdKey(options -> options.keyUp);
            input.holdKey(options -> options.keySprint);
            try {
                for (int leg = 0; leg < 6; leg++) {
                    input.lookAt(-65 + leg * 12, 15);
                    context.runOnClient(client -> client.getFramerateLimitTracker().onInputReceived());
                    action(actions, "flight-leg-" + leg);
                    context.waitTicks(160);
                    require(System.nanoTime() < sampleEnd, "T0 sample ended before its fixed route; keep failed trial, choose a longer future protocol");
                }
            } finally {
                input.releaseKey(options -> options.keyUp); input.releaseKey(options -> options.keySprint);
            }
            double traveled = context.computeOnClient(client -> Math.hypot(client.player.getX() - x, client.player.getZ() - z));
            require(traveled > 100, "T0 input route did not move through the world");
            receipt.addProperty("horizontalDisplacementBlocks", traveled);
        } else if (TRANSITIONS) {
            action(actions, "windowed-resize-half-output");
            receipt.add("beforeWindowedResize", context.computeOnClient(FrameWorkloads::windowObservation));
            context.runOnClient(client -> {
                client.options.fullscreen().set(false);
                client.getWindow().setFullscreen(false);
                client.getWindow().updateFullscreenIfChanged();
            });
            input.resizeWindow(Math.max(320, width / 2), Math.max(240, height / 2));
            // Fabric intercepts setWindowed and treats its virtual framebuffer pixels
            // as logical window units. Resize SDL separately using its actual scale.
            context.runOnClient(client -> resizeActualWindow(client, Math.max(320, width / 2), Math.max(240, height / 2)));
            context.runOnClient(Minecraft::invalidateSurfaceConfiguration);
            waitForWindowDimensions(context, receipt, "windowed", false, Math.max(320, width / 2), Math.max(240, height / 2));
            var resized = context.computeOnClient(client -> client.getWindow().queryFramebufferSize());
            receipt.addProperty("windowedPixelWidth", resized.width());
            receipt.addProperty("windowedPixelHeight", resized.height());
            require(resized.width() > 0 && resized.height() > 0 && resized.width() < width,
                    "The actual SDL window did not resize; virtual framebuffer changes alone are insufficient");
            action(actions, "resource-reload");
            CompletableFuture<?> reload = context.computeOnClient(FrameWorkloads::reloadResources);
            context.waitFor(client -> reload.isDone(), 1200);
            reload.join();
            if (PRODUCER.equals("iris")) {
                action(actions, "iris-reload");
                context.runOnClient(client -> {
                    try { Class.forName("net.irisshaders.iris.Iris").getMethod("reload").invoke(null); }
                    catch (ReflectiveOperationException failure) { throw new IllegalStateException("Iris reload failed", failure); }
                });
            }
            action(actions, "restore-fullscreen-output");
            context.runOnClient(client -> { client.options.fullscreen().set(true); client.getWindow().setFullscreen(true); });
            input.resizeWindow(width, height);
            context.runOnClient(Minecraft::invalidateSurfaceConfiguration);
            waitForWindowDimensions(context, receipt, "restored", true, width, height);
            var restored = context.computeOnClient(client -> client.getWindow().queryFramebufferSize());
            receipt.addProperty("restoredPixelWidth", restored.width());
            receipt.addProperty("restoredPixelHeight", restored.height());
            world.getConnection().waitForChunksRender(false, 1200);
            require(System.nanoTime() < sampleEnd, "X0 exceeded its declared window; the failed transition remains evidence");
            receipt.addProperty("displayMigration", "physical-validation-required: move the same SDL window between real displays");
            receipt.addProperty("occlusion", "physical-validation-required: no hosted synthetic focus event proves WindowServer occlusion");
        } else action(actions, "fixed-view");
        context.waitFor(client -> {
            // This is a real active workload, not Vanilla's unrelated idle/AFK limiter test.
            client.getFramerateLimitTracker().onInputReceived();
            return System.nanoTime() >= sampleEnd;
        }, timeoutTicks(sampleEnd - System.nanoTime()));
        context.waitTick();
        int serverTickAtCompletion = world.getServer().computeOnServer(server -> server.getTickCount());
        receipt.addProperty("serverTickAtCompletion", serverTickAtCompletion);
        receipt.add("producerAfter", context.computeOnClient(client -> producerReceipt()));
        validateScene(world);
        receipt.add("jvmAfter", jvmObservation());
        receipt.addProperty("completed", true);
        receipt.addProperty("completionNs", System.nanoTime());
    }

    private static void waitForWindowDimensions(ClientGameTestContext context, JsonObject receipt, String phase,
                                               boolean fullscreen, int width, int height) {
        receipt.add(phase + "BeforeWait", context.computeOnClient(FrameWorkloads::windowObservation));
        try {
            context.waitFor(client -> {
                var actual = client.getWindow().queryFramebufferSize();
                return client.options.fullscreen().get() == fullscreen
                        && client.getWindow().getWidth() == width && client.getWindow().getHeight() == height
                        && actual.width() == width && actual.height() == height;
            }, 1200);
        } finally {
            // Keep the native/virtual mismatch even when the strict wait fails.
            receipt.add(phase + "AfterWait", context.computeOnClient(FrameWorkloads::windowObservation));
        }
    }

    private static JsonObject windowObservation(Minecraft client) {
        var result = new JsonObject();
        var window = client.getWindow();
        var actual = window.queryFramebufferSize();
        result.addProperty("sourceClockNs", System.nanoTime());
        result.addProperty("fullscreenOption", client.options.fullscreen().get());
        result.addProperty("virtualWidth", window.getWidth());
        result.addProperty("virtualHeight", window.getHeight());
        result.addProperty("pixelWidth", actual.width());
        result.addProperty("pixelHeight", actual.height());
        long flags = SDLVideo.SDL_GetWindowFlags(window.handle());
        result.addProperty("sdlFlags", flags);
        result.addProperty("nativeFullscreen", (flags & SDLVideo.SDL_WINDOW_FULLSCREEN) != 0);
        result.addProperty("nativeMaximized", (flags & SDLVideo.SDL_WINDOW_MAXIMIZED) != 0);
        result.addProperty("nativeMinimized", (flags & SDLVideo.SDL_WINDOW_MINIMIZED) != 0);
        try (var stack = MemoryStack.stackPush()) {
            var width = stack.mallocInt(1);
            var height = stack.mallocInt(1);
            require(SDLVideo.SDL_GetWindowSize(window.handle(), width, height),
                    "SDL logical window query failed: " + SDLError.SDL_GetError());
            result.addProperty("logicalWidth", width.get(0));
            result.addProperty("logicalHeight", height.get(0));
        }
        return result;
    }

    private static void resizeActualWindow(Minecraft client, int pixelWidth, int pixelHeight) {
        var window = client.getWindow();
        // Cocoa can leave fullscreen as a maximized window; size requests then
        // only change its future restored size. Restore before measuring scale.
        require(SDLVideo.SDL_RestoreWindow(window.handle()),
                "SDL window restore failed: " + SDLError.SDL_GetError());
        require(SDLVideo.SDL_SyncWindow(window.handle()),
                "SDL window restore did not synchronize: " + SDLError.SDL_GetError());
        try (var stack = MemoryStack.stackPush()) {
            var logicalWidth = stack.mallocInt(1);
            var logicalHeight = stack.mallocInt(1);
            require(SDLVideo.SDL_GetWindowSize(window.handle(), logicalWidth, logicalHeight),
                    "SDL logical window query failed: " + SDLError.SDL_GetError());
            var actual = window.queryFramebufferSize();
            require(logicalWidth.get(0) > 0 && logicalHeight.get(0) > 0, "SDL returned an empty logical window");
            int width = Math.max(320, (int) Math.round((double) pixelWidth * logicalWidth.get(0) / actual.width()));
            int height = Math.max(240, (int) Math.round((double) pixelHeight * logicalHeight.get(0) / actual.height()));
            require(SDLVideo.SDL_SetWindowSize(window.handle(), width, height),
                    "SDL physical window resize failed: " + SDLError.SDL_GetError());
            require(SDLVideo.SDL_SyncWindow(window.handle()),
                    "SDL physical window resize did not synchronize: " + SDLError.SDL_GetError());
        }
    }

    private static JsonObject jvmObservation() {
        var result = new JsonObject();
        result.addProperty("sourceClockNs", System.nanoTime());
        result.addProperty("scope", "observed action endpoints; not an exact native presentation window");
        var heap = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        result.addProperty("heapUsedBytes", heap.getUsed()); result.addProperty("heapCommittedBytes", heap.getCommitted());
        JsonArray collectors = new JsonArray();
        for (var collector : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            var row = new JsonObject(); row.addProperty("name", collector.getName());
            long count = collector.getCollectionCount(), time = collector.getCollectionTime();
            if (count < 0) { row.add("count", com.google.gson.JsonNull.INSTANCE); row.addProperty("countUnavailableReason", "MXBean-unavailable"); }
            else row.addProperty("count", count);
            if (time < 0) { row.add("timeMillis", com.google.gson.JsonNull.INSTANCE); row.addProperty("timeUnavailableReason", "MXBean-unavailable"); }
            else row.addProperty("timeMillis", time);
            collectors.add(row);
        }
        result.add("collectors", collectors);
        result.addProperty("residentMemory", "unavailable: heap usage is not process RSS");
        return result;
    }

    private static CompletableFuture<?> reloadResources(Minecraft client) {
        try {
            Object result = client.getClass().getMethod("reloadResourcePacks").invoke(client);
            if (result instanceof CompletableFuture<?> future) return future;
            throw new IllegalStateException("26.3 reload method did not return its completion future");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("26.3 resource reload unavailable", failure); }
    }

    private static void action(JsonArray actions, String id) {
        var action = new JsonObject();
        action.addProperty("id", id); action.addProperty("sourceClockNs", System.nanoTime()); actions.add(action);
    }

    private static int timeoutTicks(long remainingNs) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1200L, Math.max(0L, remainingNs) / 1_000_000_000L * 40L + 1200L));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
