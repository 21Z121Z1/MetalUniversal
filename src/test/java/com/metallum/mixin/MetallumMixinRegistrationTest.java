package com.metallum.mixin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the mixin config and the actual mixin sources in step.
 *
 * <p>Both directions fail silently otherwise. A mixin class that is never listed
 * compiles, ships and simply does nothing, which looks exactly like a hook that
 * is present but ineffective — the hardest kind of gap to notice, because the code
 * reads as if the behaviour exists. A listed class with no source is the reverse
 * and takes the whole config down at load time.</p>
 *
 * <p>The render/sodium/iris packages also contain package-private helper classes used by
 * mixins. Those helpers are deliberately not entries in metallum.mixins.json, so
 * source discovery must identify {@code @Mixin} classes rather than treating every
 * Java source in the package as a mixin.</p>
 */
final class MetallumMixinRegistrationTest {
    private static final Path CONFIG = Path.of("src/main/resources/metallum.mixins.json");
    private static final Path MIXIN_ROOT = Path.of("src/main/java/com/metallum/mixin");
    private static final List<String> MIXIN_SUBPACKAGES = List.of("render", "sodium", "iris");
    private static final Pattern ENTRY = Pattern.compile("\"((?:render|sodium|iris)\\.[A-Za-z0-9_]+)\"");
    private static final Pattern MIXIN_ANNOTATION = Pattern.compile("@Mixin\\s*\\(");

    private static List<String> registeredEntries() throws IOException {
        String config = Files.readString(CONFIG);
        Matcher matcher = ENTRY.matcher(config);
        List<String> entries = new ArrayList<>();
        while (matcher.find()) {
            entries.add(matcher.group(1));
        }
        assertTrue(entries.size() > 10, "the mixin config parsed to only " + entries.size()
                + " entries, so this test is no longer reading it correctly");
        return entries;
    }

    @Test
    void everyRegisteredMixinHasASource() throws IOException {
        for (String entry : registeredEntries()) {
            Path source = MIXIN_ROOT.resolve(entry.replace('.', '/') + ".java");
            assertTrue(Files.isRegularFile(source),
                    entry + " is registered but " + source + " does not exist; the mixin config would fail"
                            + " to load");
            assertTrue(isMixinSource(source),
                    entry + " is registered but " + source + " is not annotated with @Mixin");
        }
    }

    @Test
    void everyMixinSourceIsRegistered() throws IOException {
        List<String> registered = registeredEntries();
        for (String subpackage : MIXIN_SUBPACKAGES) {
            Path directory = MIXIN_ROOT.resolve(subpackage);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> sources = Files.list(directory)) {
                List<String> unregistered = sources
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .filter(MetallumMixinRegistrationTest::isMixinSourceUnchecked)
                        .map(path -> subpackage + "." + path.getFileName().toString().replace(".java", ""))
                        .filter(name -> !registered.contains(name))
                        .toList();
                assertEquals(List.of(), unregistered,
                        "these @Mixin classes exist but are not listed in " + CONFIG + ", so they are compiled and"
                                + " shipped while doing nothing at runtime");
            }
        }
    }

    private static boolean isMixinSource(Path source) throws IOException {
        return MIXIN_ANNOTATION.matcher(Files.readString(source)).find();
    }

    private static boolean isMixinSourceUnchecked(Path source) {
        try {
            return isMixinSource(source);
        } catch (IOException exception) {
            throw new IllegalStateException("could not read mixin source " + source, exception);
        }
    }
}
