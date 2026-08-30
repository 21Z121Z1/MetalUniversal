import java.lang.foreign.Arena;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Minimal JVM-background-thread render-PSO capability probe.
 *
 * Runs the exact shipping-dylib sequence (device -> MSL -> descriptor ->
 * render PSO) on a plain Java background thread — the same threading context
 * as a Gradle test executor worker. This is the machine-readable verdict that
 * decides METALLUM_JVM_PSO_SMOKE_MODE: a signal death here means this
 * environment cannot compile pipelines from a JVM background thread and the
 * JVM smoke is reported environment-blocked instead of failed. The Minecraft
 * E2E remains the authoritative JVM->FFM->Swift PSO proof either way.
 *
 * Usage: java --enable-native-access=ALL-UNNAMED JvmPsoThreadingProbe.java /path/libmetallum.dylib
 */
public final class JvmPsoThreadingProbe {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final AddressLayout ADDRESS = ValueLayout.ADDRESS;
    private static final String SHADER_SOURCE = """
            #include <metal_stdlib>
            using namespace metal;
            struct SmokeVertexOut { float4 position [[position]]; };
            vertex SmokeVertexOut abi_smoke_vs(uint vertexID [[vertex_id]]) {
                const float2 positions[3] = { float2(-1.0,-1.0), float2(3.0,-1.0), float2(-1.0,3.0) };
                SmokeVertexOut output;
                output.position = float4(positions[vertexID], 0.0, 1.0);
                return output;
            }
            fragment float4 abi_smoke_fs() { return float4(0.25, 0.50, 0.75, 1.0); }
            """;

    public static void main(final String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: JvmPsoThreadingProbe /path/to/libmetallum.dylib");
            System.exit(2);
        }
        final SymbolLookup lookup = SymbolLookup.libraryLookup(args[0], Arena.global());
        final MethodHandle createDevice = downcall(lookup, "metallum_create_system_default_device",
                FunctionDescriptor.of(ADDRESS));
        final MethodHandle createFunction = downcall(lookup, "metallum_create_shader_function",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        final MethodHandle createDescriptor = downcall(lookup, "metallum_MTLRenderPipelineDescriptor_create",
                FunctionDescriptor.of(ADDRESS));
        final MethodHandle setFunctions = downcall(lookup, "metallum_MTLRenderPipelineDescriptor_setCompiledFunctions",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
        final MethodHandle setColorFormat = downcall(lookup, "metallum_MTLRenderPipelineDescriptor_setColorAttachmentFormat",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
        final MethodHandle makePipeline = downcall(lookup, "metallum_MTLDevice_makeRenderPipelineState",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        final MethodHandle releaseObject = downcall(lookup, "metallum_release_object",
                FunctionDescriptor.ofVoid(ADDRESS));

        final boolean[] passed = { false };
        final String[] failure = { null };
        // Plain java.lang.Thread: the same threading context whose PSO compile
        // path must be proven before the JVM smoke may be required here.
        final Thread worker = new Thread(() -> {
            try (Arena arena = Arena.ofConfined()) {
                final MemorySegment device = (MemorySegment) createDevice.invokeExact();
                require(nonNull(device), "device creation returned null");
                final MemorySegment source = arena.allocateFrom(SHADER_SOURCE);
                final MemorySegment vsEntry = arena.allocateFrom("abi_smoke_vs");
                final MemorySegment fsEntry = arena.allocateFrom("abi_smoke_fs");
                final MemorySegment vertex = (MemorySegment) createFunction.invokeExact(device, source, vsEntry);
                require(nonNull(vertex), "vertex MSL compile returned null");
                final MemorySegment fragment = (MemorySegment) createFunction.invokeExact(device, source, fsEntry);
                require(nonNull(fragment), "fragment MSL compile returned null");
                final MemorySegment descriptor = (MemorySegment) createDescriptor.invokeExact();
                require(nonNull(descriptor), "descriptor creation returned null");
                setFunctions.invokeExact(descriptor, vertex, fragment);
                final int formatOk = (int) setColorFormat.invokeExact(descriptor, 0, 70L);
                require(formatOk != 0, "RGBA8 color attachment rejected");
                final MemorySegment pipeline = (MemorySegment) makePipeline.invokeExact(device, descriptor);
                require(nonNull(pipeline), "render PSO creation returned null");
                releaseObject.invokeExact(pipeline);
                releaseObject.invokeExact(fragment);
                releaseObject.invokeExact(vertex);
                releaseObject.invokeExact(descriptor);
                releaseObject.invokeExact(device);
                passed[0] = true;
            } catch (final Throwable throwable) {
                failure[0] = String.valueOf(throwable);
            }
        }, "JvmPsoThreadingProbe-worker");
        worker.start();
        worker.join();
        if (!passed[0]) {
            System.err.println("JVM_PSO_BACKGROUND_THREAD_FAIL: " + failure[0]);
            System.exit(1);
        }
        System.out.println("JVM_PSO_BACKGROUND_THREAD_PASS");
    }

    private static MethodHandle downcall(
            final SymbolLookup lookup,
            final String name,
            final FunctionDescriptor descriptor
    ) {
        return LINKER.downcallHandle(lookup.findOrThrow(name), descriptor);
    }

    private static boolean nonNull(final MemorySegment segment) {
        return segment != null && segment.address() != 0L;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
