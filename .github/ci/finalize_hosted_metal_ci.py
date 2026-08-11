from pathlib import Path

path = Path("build.gradle")
text = path.read_text()
marker = 'def rawPredicateNeedle = "hostedCi" + " == false"'
if marker in text:
    raise SystemExit(0)

old = '''\t\tdef forbidden = [
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
\t\t\t'\\tisMacOSHost && (hostedCi == false || "true".equalsIgnoreCase(System.getenv("METALLUM_HOSTED_METAL_OFFSCREEN")))',
\t\t\t'\\tisMacOSHost && hostedCi == false'
\t\t]
\t\tdef unexpectedRaw = rawPhysicalLines.findAll { !allowedRawPhysicalLines.contains(it) }
\t\tif (!unexpectedRaw.isEmpty() || rawPhysicalLines.size() != allowedRawPhysicalLines.size()) {
\t\t\tthrow new GradleException("Raw hostedCi physical predicates must stay centralized in capability policy: ${rawPhysicalLines}")
\t\t}
'''
new = '''\t\t// Assemble forbidden spellings at runtime so this guard does not match its
\t\t// own implementation while scanning build.gradle.
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
\t\t\t'\\tisMacOSHost && (' + rawPredicateNeedle + ' || "true".equalsIgnoreCase(System.getenv("METALLUM_HOSTED_METAL_OFFSCREEN")))',
\t\t\t'\\tisMacOSHost && ' + rawPredicateNeedle
\t\t]
\t\tdef unexpectedRaw = rawPhysicalLines.findAll { !allowedRawPhysicalLines.contains(it) }
\t\tif (!unexpectedRaw.isEmpty() || rawPhysicalLines.size() != allowedRawPhysicalLines.size()) {
\t\t\tthrow new GradleException("Raw hostedCi physical predicates must stay centralized in capability policy: ${rawPhysicalLines}")
\t\t}
'''
if old not in text:
    raise SystemExit("capability audit block did not match")
path.write_text(text.replace(old, new, 1))
