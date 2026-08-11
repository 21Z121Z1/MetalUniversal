from pathlib import Path

path = Path("build.gradle")
text = path.read_text()
start_marker = "// ---- Hosted Metal capability-governed integration suite ----"
start = text.find(start_marker)
if start < 0:
    raise SystemExit("formal hosted capability block not found")

correct = '''// ---- Hosted Metal capability-governed integration suite ----
// Repository-owned, redistributable tests proven to execute offscreen Metal
// command submission/readback on GitHub's Apple Silicon macOS runner.
tasks.register("hostedMetalExtendedIntegrationTest", Test) {
\tgroup = "verification"
\tdescription = "Runs redistributable offscreen Metal/Iris integrations on a proven hosted Metal device."
\tonlyIf { hostedOffscreenMetalValidationAvailable() }
\tdependsOn tasks.named("buildMacNative")
\ttestClassesDirs = sourceSets.test.output.classesDirs
\tclasspath = sourceSets.test.runtimeClasspath
\tuseJUnitPlatform()
\tfilter {
\t\tincludeTestsMatching "com.metallum.client.metal.render.IrisMetalBundledPostChainCompilationTest"
\t\tincludeTestsMatching "com.metallum.client.metal.render.IrisMetalBundledTerrainPipelineTest"
\t\tincludeTestsMatching "com.metallum.client.metal.render.IrisMetalShadowPipelineTest"
\t\tincludeTestsMatching "com.metallum.client.metal.render.IrisMetalShadowComputeConformanceTest"
\t\tincludeTestsMatching "com.metallum.client.metal.render.IrisMetalCenterDepthSamplerTest"
\t\tincludeTestsMatching "com.metallum.client.metal.render.IrisMetalComputeConformanceTest"
\t\tincludeTestsMatching "com.metallum.client.metal.render.IrisMetalExternalLevelSamplerTest"
\t\tincludeTestsMatching "com.metallum.client.metal.render.MetalGenericVertexAttributeIntegrationTest"
\t\tincludeTestsMatching "com.metallum.client.metal.render.MetalIrisCustomTexturesIntegrationTest"
\t\tincludeTestsMatching "com.metallum.client.metal.render.MetalIrisNoiseTextureIntegrationTest"
\t\tincludeTestsMatching "com.metallum.client.metal.render.MetalRenderContractGpuIntegrationTest"
\t\tfailOnNoMatchingTests = true
\t}
\tjvmArgs "--enable-native-access=ALL-UNNAMED"
\tenvironment "MTL_DEBUG_LAYER", "0"
\tenvironment "MTL_SHADER_VALIDATION", "0"
\tenvironment "MTL_HUD_ENABLED", "0"
\tenvironment "MTLFX_HUD_ENABLED", "0"
\tsystemProperty "metallum.metal.hud", "false"
\tsystemProperty "metallum.validation.gpuPassTiming", "false"
}

// Mechanical guardrail: new blanket CI exclusions or unnamed physical-only
// gates fail verification. New physical requirements must be centralized in
// physicalMetalCapabilities with a concrete capability reason.
tasks.register("verifyMetalCapabilityPolicy") {
\tgroup = "verification"
\tdescription = "Rejects blanket CI GPU exclusions and undocumented physical-only Metal gates."
\tinputs.file("build.gradle")
\tdoLast {
\t\tdef policyText = file("build.gradle").getText("UTF-8")
\t\t// Assemble forbidden spellings at runtime so this guard does not match
\t\t// its own implementation while scanning build.gradle.
\t\tdef forbidden = [
\t\t\t"physicalMetal" + "ValidationAvailable",
\t\t\t"if (" + "hostedCi)",
\t\t\t"onlyIf { " + "!hostedCi",
\t\t\t"onlyIf {" + "!hostedCi"
\t\t]
\t\tdef violations = forbidden.findAll { policyText.contains(it) }
\t\tif (!violations.isEmpty()) {
\t\t\tthrow new GradleException("Blanket CI/physical Metal gating is forbidden: ${violations}")
\t\t}
\t\tdef rawPredicateNeedle = "hostedCi" + " == false"
\t\tdef rawPhysicalLines = policyText.readLines().findAll { it.contains(rawPredicateNeedle) }
\t\tdef allowedRawPhysicalLines = [
\t\t\t'\tisMacOSHost && (' + rawPredicateNeedle + ' || "true".equalsIgnoreCase(System.getenv("METALLUM_HOSTED_METAL_OFFSCREEN")))',
\t\t\t'\tisMacOSHost && ' + rawPredicateNeedle
\t\t]
\t\tdef unexpectedRaw = rawPhysicalLines.findAll { !allowedRawPhysicalLines.contains(it) }
\t\tif (!unexpectedRaw.isEmpty() || rawPhysicalLines.size() != allowedRawPhysicalLines.size()) {
\t\t\tthrow new GradleException("Raw hostedCi physical predicates must stay centralized in capability policy: ${rawPhysicalLines}")
\t\t}
\t\tdef undocumented = physicalMetalCapabilities.findAll { key, value -> value == null || value.trim().isEmpty() }
\t\tif (!undocumented.isEmpty()) {
\t\t\tthrow new GradleException("Physical Metal capabilities require concrete reasons: ${undocumented.keySet()}")
\t\t}
\t\tlogger.lifecycle("Metal capability policy: PASS (${physicalMetalCapabilities.size()} documented physical capabilities)")
\t}
}
'''

current_tail = text[start:]
if current_tail == correct:
    raise SystemExit(0)
path.write_text(text[:start] + correct)
