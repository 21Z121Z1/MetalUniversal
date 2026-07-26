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
 * Keeps the mixin config and the mixin sources in step.
 *
 * <p>Both directions fail silently otherwise. A mixin class that is never listed
 * compiles, ships and simply does nothing, which looks exactly like a hook that
 * is present but ineffective — the hardest kind of gap to notice, because the code
 * reads as if the behaviour exists. A listed class with no source is the reverse
 * and takes the whole config down at load time.</p>
 */
final class MetallumMixinRegistrationTest {
    private static final Path CONFIG = Path.of("src/main/resources/metallum.mixins.json");
    private static final Path MIXIN_ROOT = Path.of("src/main/java/com/metallum/mixin");
    private static final Pattern ENTRY = Pattern.compile("\"((?:render|sodium)\\.[A-Za-z0-9_]+)\"");

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
        }
    }

    @Test
    void everyMixinSourceIsRegistered() throws IOException {
        List<String> registered = registeredEntries();
        for (String subpackage : new String[] { "render", "sodium" }) {
            Path directory = MIXIN_ROOT.resolve(subpackage);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> sources = Files.list(directory)) {
                List<String> unregistered = sources
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .map(path -> subpackage + "." + path.getFileName().toString().replace(".java", ""))
                        .filter(name -> !registered.contains(name))
                        .toList();
                assertEquals(List.of(), unregistered,
                        "these mixins exist but are not listed in " + CONFIG + ", so they are compiled and"
                                + " shipped while doing nothing at runtime");
            }
        }
    }
}
