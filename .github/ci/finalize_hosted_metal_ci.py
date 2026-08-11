from pathlib import Path
import re

build = Path("build.gradle")
text = build.read_text()

capability_pattern = re.compile(
    r'def isMacOSHost = org\.gradle\.internal\.os\.OperatingSystem\.current\(\)\.isMacOsX\(\)\n'
    r'def hostedOffscreenMetalValidationAvailable = \{\n.*?\n\}\n'
    r'def physicalMetalValidationAvailable = \{\n.*?\n\}\n'
    r'def metalFxValidationAvailable = \{\n.*?\n\}\n'
    r'def presentationMetalValidationAvailable = \{\n.*?\n\}\n',
    re.S,
)
capability_replacement = '''def isMacOSHost = org.gradle.internal.os.OperatingSystem.current().isMacOsX()
def hostedOffscreenMetalValidationAvailable = {
\tisMacOSHost && (hostedCi == false || "true".equalsIgnoreCase(System.getenv("METALLUM_HOSTED_METAL_OFFSCREEN")))
}
// Physical-only validation is capability-driven, never a blanket CI exclusion.
// Every physical capability must document the concrete hardware/display reason.
def physicalMetalCapabilities = [
\t\t"metalfx-runtime": "MTLFXTemporalScaler/FrameInterpolator execution requires MetalFX device support; GitHub's Apple Paravirtual device currently reports the Temporal Scaler unsupported.",
\t\t"presentation-windowserver": "CAMetalLayer/CAMetalDisplayLink pacing, resize, fullscreen and drawable acceptance require an attended WindowServer/display session.",
\t\t"gpu-counter-sampling": "Encoder-stage GPU counter sampling is not supported by GitHub's Apple Paravirtual Metal device."
]
def physicalMetalCapabilityAvailable = { String capability ->
\tdef reason = physicalMetalCapabilities[capability]
\tif (reason == null || reason.isBlank()) {
\t\tthrow new GradleException("Physical Metal capability '${capability}' is missing a concrete reason")
\t}
\tisMacOSHost && hostedCi == false
}
def metalFxValidationAvailable = {
\tphysicalMetalCapabilityAvailable("metalfx-runtime")
}
def presentationMetalValidationAvailable = {
\tphysicalMetalCapabilityAvailable("presentation-windowserver")
}
'''
text, n = capability_pattern.subn(capability_replacement, text, count=1)
if n != 1:
    raise SystemExit("capability definition block did not match")

ci_exclude_pattern = re.compile(
    r'\tif \(hostedCi\) \{\n'
    r'\t\texclude "\*\*/IrisMetalPostChainCompilationTest\.class"\n'
    r'\t\texclude "\*\*/IrisMetalShadowPipelineTest\.class"\n'
    r'\t\texclude "\*\*/IrisMetalShadowComputeConformanceTest\.class"\n'
    r'\t\texclude "\*\*/MetalGenericVertexAttributeIntegrationTest\.class"\n'
    r'\t\texclude "\*\*/MetalIrisCustomTexturesIntegrationTest\.class"\n'
    r'\t\texclude "\*\*/MetalRenderContractGpuIntegrationTest\.class"\n'
    r'\t\}\n'
)
ci_exclude_replacement = '''\t// GPU integrations are routed through explicit capability tasks instead of
\t// changing the generic unit-test suite according to CI=true/false.
\texclude "**/IrisMetalPostChainCompilationTest.class"
\texclude "**/IrisMetalShadowPipelineTest.class"
\texclude "**/IrisMetalShadowComputeConformanceTest.class"
\texclude "**/MetalGenericVertexAttributeIntegrationTest.class"
\texclude "**/MetalIrisCustomTexturesIntegrationTest.class"
\texclude "**/MetalRenderContractGpuIntegrationTest.class"
'''
text, n = ci_exclude_pattern.subn(ci_exclude_replacement, text, count=1)
if n != 1:
    raise SystemExit("hosted CI test exclusion block did not match")

old = 'task.onlyIf { physicalMetalValidationAvailable() }'
if old not in text:
    raise SystemExit("render-contract physical gate did not match")
text = text.replace(old, 'task.onlyIf { hostedOffscreenMetalValidationAvailable() }', 1)

old = '''\t\tif (hostedCi) {
\t\t\tlogger.lifecycle("metalFrameGenerationPresentationValidation SKIPPED: hosted CI has no attended WindowServer surface")
\t\t\treturn false
\t\t}
'''
new = '''\t\tif (!presentationMetalValidationAvailable()) {
\t\t\tlogger.lifecycle("metalFrameGenerationPresentationValidation SKIPPED: requires physical capability presentation-windowserver")
\t\t\treturn false
\t\t}
'''
if old not in text:
    raise SystemExit("presentation hosted gate did not match")
text = text.replace(old, new, 1)

old = 'onlyIf { !hostedCi && presentationValidationSkipReason() == null }'
if old not in text:
    raise SystemExit("MetalFX performance hosted gate did not match")
text = text.replace(old, 'onlyIf { metalFxValidationAvailable() && presentationValidationSkipReason() == null }', 1)

marker = '// ---- Hosted Metal capability-governed integration suite ----'
if marker in text:
    raise SystemExit("formal hosted suite already present")
text += r'''

// ---- Hosted Metal capability-governed integration suite ----
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
\t\tdef forbidden = [
\t\t\t"physicalMetalValidationAvailable",
\t\t\t"if (hostedCi)",
\t\t\t"onlyIf { !hostedCi",
\t\t\t"onlyIf {!hostedCi"
\t\t]
\t\tdef violations = forbidden.findAll { policyText.contains(it) }
\t\tif (!violations.isEmpty()) {
\t\t\tthrow new GradleException("Blanket CI/physical Metal gating is forbidden: ${violations}")
\t\t}
\t\tdef rawPhysicalLines = policyText.readLines().findAll { it.contains("hostedCi == false") }
\t\tdef allowedRawPhysicalLines = [
\t\t\t'\tisMacOSHost && (hostedCi == false || "true".equalsIgnoreCase(System.getenv("METALLUM_HOSTED_METAL_OFFSCREEN")))',
\t\t\t'\tisMacOSHost && hostedCi == false'
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
build.write_text(text)

workflow = Path('.github/workflows/hosted-metal-gpu-probe.yml')
wf = workflow.read_text()
wf = wf.replace('            -I .github/ci/hosted-metal-required.gradle \\\n', '')
wf = wf.replace(
    '            compileMetalFrameGenerationPresentationValidation\n',
    '            compileMetalFrameGenerationPresentationValidation \\\n            verifyMetalCapabilityPolicy\n',
    1,
)
wf = wf.replace(
    '  ios-simulator-metal-runtime-candidate:\n    name: iOS Simulator host-GPU Metal runtime candidate\n    runs-on: macos-26\n    continue-on-error: true\n',
    '  ios-simulator-metal-runtime-required:\n    name: iOS Simulator host-GPU Metal runtime required\n    runs-on: macos-26\n',
    1,
)

anchor = '''      - name: Validate Metal 4 fallback and compile physical-only harnesses
        shell: bash
        run: |
          set -euo pipefail
          chmod +x ./gradlew
          ./gradlew --stacktrace \\
            metal4PipelineSmokeTest \\
            metal4PipelinePathTest \\
            compileMetalFxOffscreenValidation \\
            compileMetalFxPerformanceValidation \\
            compileMetalFrameGenerationPresentationValidation \\
            verifyMetalCapabilityPolicy
'''
if anchor not in wf:
    raise SystemExit("capability compile workflow anchor not found")
wf = wf.replace(anchor, anchor + '''
      - name: Execute hosted HUD layer and selector contract
        shell: bash
        env:
          MTL_HUD_ENABLED: '1'
          MTLFX_HUD_ENABLED: '1'
        run: |
          set -euo pipefail
          ./gradlew --stacktrace metalHudRuntimeTest

      - name: Classify hosted MetalFX runtime capability
        shell: bash
        run: |
          set -uo pipefail
          binary="build/metal-tests/MetalFXOffscreenValidation"
          test -x "$binary"
          out="$RUNNER_TEMP/metalfx-hosted"
          rm -rf "$out"
          set +e
          "$binary" "$out" 2>&1 | tee "$RUNNER_TEMP/metalfx-hosted.log"
          rc=${PIPESTATUS[0]}
          set -e
          if [ "$rc" -eq 0 ]; then
            echo 'HOSTED_METALFX_TEMPORAL_SUPPORTED=true' >> "$GITHUB_STEP_SUMMARY"
          elif grep -Fq 'MTLFXTemporalScaler is unsupported on this device' "$RUNNER_TEMP/metalfx-hosted.log"; then
            echo 'HOSTED_METALFX_TEMPORAL_SUPPORTED=false' >> "$GITHUB_STEP_SUMMARY"
            echo 'MetalFX runtime remains physical-capability gated; core Metal execution is covered separately.' >> "$GITHUB_STEP_SUMMARY"
          else
            echo "Unexpected MetalFX runtime failure: $rc" >&2
            exit "$rc"
          fi
''', 1)
workflow.write_text(wf)
