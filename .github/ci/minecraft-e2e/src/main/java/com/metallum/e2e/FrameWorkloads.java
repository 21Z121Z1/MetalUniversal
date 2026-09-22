package com.metallum.e2e;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
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
    private static final String CPU_TAG = "metallum_cpu_fixture_v1";

    private FrameWorkloads() { }

    static void validate() {
        require(IDS.contains(ID), "Unknown versioned workload: " + ID);
        require(Set.of("vanilla", "sodium", "iris").contains(PRODUCER), "Unknown producer");
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
                    var expected = layer % 2 == 0 ? Blocks.RED_STAINED_GLASS : Blocks.BLUE_STAINED_GLASS;
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
            for (var entity : server.overworld().getAllEntities()) if (entity.getTags().contains(CPU_TAG)) count++;
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

    static JsonObject run(ClientGameTestContext context, TestSingleplayerContext world,
                          long sampleStart, long sampleEnd, int width, int height) {
        JsonObject receipt = new JsonObject();
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
            context.runOnClient(client -> {
                client.options.fullscreen().set(false);
                // Window.setWindowed owns the exact 26.3 SDL size/mode change.
                // Fabric's input size below updates its independent virtual framebuffer.
                client.getWindow().setWindowed(Math.max(320, client.getWindow().getScreenWidth() / 2),
                        Math.max(240, client.getWindow().getScreenHeight() / 2));
            });
            input.resizeWindow(Math.max(320, width / 2), Math.max(240, height / 2));
            context.runOnClient(Minecraft::invalidateSurfaceConfiguration);
            context.waitFor(client -> !client.options.fullscreen().get()
                    && client.getWindow().getWidth() == Math.max(320, width / 2)
                    && client.getWindow().getHeight() == Math.max(240, height / 2), 1200);
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
            context.waitFor(client -> client.getWindow().getWidth() == width && client.getWindow().getHeight() == height, 1200);
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
        receipt.addProperty("serverTickAtCompletion", world.getServer().computeOnServer(server -> server.getTickCount()));
        receipt.add("producerAfter", context.computeOnClient(client -> producerReceipt()));
        validateScene(world);
        receipt.add("jvmAfter", jvmObservation());
        receipt.addProperty("completed", true);
        receipt.addProperty("completionNs", System.nanoTime());
        return receipt;
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
