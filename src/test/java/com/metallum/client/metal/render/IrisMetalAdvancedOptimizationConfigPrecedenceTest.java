package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalAdvancedOptimizationConfigPrecedenceTest {
    @Test
    void stablePropertiesTakePrecedenceInConfigAndPlanInFreshJvm() throws Exception {
        assertScenario(List.of(), false);
        assertScenario(properties(null, "true"), true);
        assertScenario(properties("true", null), true);
        assertScenario(properties("false", "true"), false);
    }

    private static void assertScenario(
            final List<String> properties,
            final boolean expected
    ) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(properties);
        command.add("-cp");
        command.add(Path.of(Probe.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator
                + Path.of(IrisMetalAdvancedOptimizationConfig.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()));
        command.add(Probe.class.getName());

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Property probe timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            assertEquals(0, process.exitValue(), output);
            String value = Boolean.toString(expected);
            assertEquals(value + "/" + value + ","
                            + value + "/" + value + ","
                            + value + "/" + value,
                    output);
        } finally {
            process.destroyForcibly();
        }
    }

    private static List<String> properties(final String stable, final String legacy) {
        List<String> values = new ArrayList<>();
        if (stable != null) {
            values.add("-Dmetallum.iris.passFusion=" + stable);
            values.add("-Dmetallum.iris.computeGrouping=" + stable);
            values.add("-Dmetallum.iris.depthLiveness=" + stable);
        }
        if (legacy != null) {
            values.add("-Dmetallum.iris.experimental.passFusion=" + legacy);
            values.add("-Dmetallum.iris.experimental.computeGrouping=" + legacy);
            values.add("-Dmetallum.iris.experimental.resourcePruning=" + legacy);
        }
        return values;
    }

    public static final class Probe {
        public static void main(final String[] args) {
            System.out.print(pair(
                    IrisMetalAdvancedOptimizationConfig.RENDER_PASS_FUSION,
                    IrisMetalOptimizationPlan.ENABLE_PASS_FUSION
            ));
            System.out.print(",");
            System.out.print(pair(
                    IrisMetalAdvancedOptimizationConfig.COMPUTE_GROUPING,
                    IrisMetalOptimizationPlan.ENABLE_COMPUTE_GROUPING
            ));
            System.out.print(",");
            System.out.print(pair(
                    IrisMetalAdvancedOptimizationConfig.DEPTH_LIVENESS,
                    IrisMetalOptimizationPlan.ENABLE_RESOURCE_PRUNING
            ));
        }

        private static String pair(final boolean config, final boolean plan) {
            return config + "/" + plan;
        }
    }
}
