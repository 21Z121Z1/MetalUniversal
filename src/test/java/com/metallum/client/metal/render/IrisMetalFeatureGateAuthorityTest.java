package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

final class IrisMetalFeatureGateAuthorityTest {
    private static final String[] STABLE = {
            "passFusion", "computeGrouping", "attachmentLiveness", "depthLiveness",
            "finalColorFusion", "argumentTables", "indirectSubmission"
    };
    private static final String[] LEGACY = {
            "passFusion", "computeGrouping", "loadStoreLiveness", "resourcePruning",
            "finalColorFusion", "argumentTables", "icb"
    };

    @Test
    void precedenceAndIndependentAdmissionAreResolvedOnceInFreshProcesses(@TempDir Path directory) throws Exception {
        probe(directory, "defaults", null, null, "0000000");
        probe(directory, "legacy", null, "true", "1111111");
        probe(directory, "stable", "true", null, "1111111");
        probe(directory, "explicit-off", "false", "true", "0000000");
        probe(directory, "explicit-on", "true", "false", "1111111");
        probe(directory, "empty-stable", "", "true", "0000000");
        probe(directory, "invalid-stable", "invalid", "true", "0000000");
        probe(directory, "case-insensitive", "TRUE", null, "1111111");
        for (int enabled = 0; enabled < STABLE.length; enabled++) {
            String bits = "0".repeat(enabled) + "1" + "0".repeat(STABLE.length - enabled - 1);
            run(directory.resolve("independent-" + enabled + ".log"),
                    List.of("-Dmetallum.iris." + STABLE[enabled] + "=true"), bits);
        }
    }

    @Test
    void consumersCannotReparseProtectedPropertiesOutsideTheirOwner() throws Exception {
        // This is an architectural boundary, not an assertion about constant names:
        // a second parser could turn an explicit stable=false into legacy=true.
        var protectedKeys = new ArrayList<String>();
        for (String suffix : STABLE) protectedKeys.add("metallum.iris." + suffix);
        for (String suffix : LEGACY) protectedKeys.add("metallum.iris.experimental." + suffix);
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (path.getFileName().toString().equals("IrisMetalAdvancedOptimizationConfig.java")) continue;
                String source = Files.readString(path);
                for (String key : protectedKeys) {
                    Pattern read = Pattern.compile("(?:getBoolean|getProperty)\\s*\\(\\s*\"" + Pattern.quote(key) + "\"");
                    assertFalse(read.matcher(source).find(), () -> path + " independently reads " + key);
                }
            }
        }
    }

    private static void probe(Path directory, String name, String stable, String legacy, String bits) throws Exception {
        var properties = new ArrayList<String>();
        for (String suffix : STABLE) {
            if (stable != null) properties.add("-Dmetallum.iris." + suffix + "=" + stable);
        }
        for (String suffix : LEGACY) {
            if (legacy != null) properties.add("-Dmetallum.iris.experimental." + suffix + "=" + legacy);
        }
        run(directory.resolve(name + ".log"), properties, bits);
    }

    private static void run(Path output, List<String> properties, String bits) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(properties);
        command.add("-cp");
        command.add(Path.of(Probe.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator + Path.of(IrisMetalAdvancedOptimizationConfig.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()));
        command.add(Probe.class.getName());
        command.add(bits);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Feature-gate probe timed out");
            assertEquals(0, process.exitValue(), () -> readOutput(output));
            assertEquals("PASS", Files.readString(output).trim());
        } finally {
            process.destroyForcibly();
        }
    }

    private static String readOutput(Path output) {
        try { return Files.readString(output); }
        catch (java.io.IOException failure) { return failure.toString(); }
    }

    public static final class Probe {
        public static void main(String[] args) {
            var snapshot = IrisMetalAdvancedOptimizationConfig.snapshot();
            boolean[] actual = {snapshot.renderPassFusion(), snapshot.computeGrouping(), snapshot.attachmentLiveness(),
                    snapshot.depthLiveness(), snapshot.finalColorFusion(), snapshot.argumentTables(), snapshot.indirectSubmission()};
            for (int index = 0; index < actual.length; index++) {
                if (actual[index] != (args[0].charAt(index) == '1')) throw new AssertionError("gate " + index);
            }
            if (!snapshot.hazardGraph()) throw new AssertionError("Hazard analysis must remain enabled by default");

            // Exercise a real transformation consumer, including its independent semantic admission.
            var candidate = new IrisMetalFinalColorFusion.Candidate(
                    "color = /* METALLUM_FINAL_COLOR_OUTPUT */ sourceColor;",
                    "vec4 convert(vec4 c) { return c; }", "convert", true, true, true);
            var result = IrisMetalFinalColorFusion.fuse(candidate);
            if (result.fused() != actual[4]) throw new AssertionError("Transformation ignored feature policy");
            if (result.fused() && !result.source().contains("convert(sourceColor)")) throw new AssertionError("Missing fused expression");
            var unsafe = new IrisMetalFinalColorFusion.Candidate(candidate.finalFragmentSource(),
                    candidate.colorFunctionSource(), candidate.colorFunctionName(), true, false, true);
            if (IrisMetalFinalColorFusion.fuse(unsafe).fused()) throw new AssertionError("Feature enabled unsafe transform");

            System.setProperty("metallum.iris.finalColorFusion", Boolean.toString(!actual[4]));
            if (!snapshot.equals(IrisMetalAdvancedOptimizationConfig.snapshot())) throw new AssertionError("Policy changed after initialization");
            if (IrisMetalFinalColorFusion.fuse(candidate).fused() != actual[4]) throw new AssertionError("Consumer reparsed policy");
            System.out.println("PASS");
        }
    }
}
