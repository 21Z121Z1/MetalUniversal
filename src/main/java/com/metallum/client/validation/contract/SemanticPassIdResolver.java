package com.metallum.client.validation.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts backend labels into stable logical pass ids without pack-name rules. */
public final class SemanticPassIdResolver {
    private static final Pattern INDEX = Pattern.compile("(?:^|[^0-9])([0-9]+)(?:$|[^0-9])");

    private SemanticPassIdResolver() {
    }

    public static String resolve(final String rawLabel, final PassType type) {
        String label = rawLabel == null ? "" : rawLabel.trim();
        if (label.isEmpty()) {
            return unclassified(type, label);
        }
        String normalized = label.replace('\\', '/').replaceAll("/+$", "");
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (hasKnownNamespace(lower)) {
            return normalizeKnownNamespace(normalized);
        }
        if (lower.matches("iris\\s+final(?:\\s*[:/_-].*)?")) {
            return "iris/final";
        }
        if (lower.startsWith("iris composite") || lower.startsWith("iris/composite")) {
            return indexed("iris/composite", normalized);
        }
        if (lower.startsWith("iris shadowcomp") || lower.startsWith("iris/shadowcomp")
                || lower.startsWith("iris shadow comp")) {
            return indexed("iris/shadow", normalized);
        }
        if (lower.startsWith("iris shadow")) {
            return indexed("iris/shadow", normalized);
        }
        if (lower.startsWith("iris gbuffer") || lower.startsWith("iris/gbuffer")) {
            String suffix = normalized.replaceFirst("(?i)^iris[ :/_-]+gbuffers?[ :/_-]*", "");
            suffix = normalizeToken(suffix);
            return suffix.isEmpty() ? "iris/gbuffers" : "iris/gbuffers/" + suffix;
        }
        if (lower.startsWith("metallum") || lower.startsWith("metallum/")) {
            return resolveMetallum(lower);
        }
        return unclassified(type, normalized);
    }

    public static String resolve(final String rawLabel) {
        return resolve(rawLabel, PassType.RENDER);
    }

    private static String resolveMetallum(final String lower) {
        if (lower.contains("object motion")) return "metallum/object-motion";
        if (lower.contains("camera motion")) return "metallum/camera-motion";
        if (lower.contains("motion merge")) return "metallum/motion-merge";
        if (lower.contains("reactive")) return "metallum/reactive-mask";
        if (lower.contains("temporal")) return "metallum/metalfx-temporal";
        if (lower.contains("frame generation") || lower.contains("framegen")) {
            return "metallum/frame-generation";
        }
        if (lower.contains("ui") && lower.contains("compose")) return "metallum/ui-compose";
        if (lower.contains("present")) return "metallum/present";
        return unclassified(PassType.RENDER, lower);
    }

    private static String indexed(final String prefix, final String label) {
        Matcher matcher = INDEX.matcher(label);
        return prefix + "/" + (matcher.find() ? matcher.group(1) : "0");
    }

    private static boolean hasKnownNamespace(final String lower) {
        return lower.startsWith("minecraft/") || lower.startsWith("iris/")
                || lower.startsWith("metallum/") || lower.startsWith("synthetic/");
    }

    private static String normalizeKnownNamespace(final String label) {
        String result = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._/-]+", "-");
        return result.replaceAll("/{2,}", "/").replaceAll("(^/|/$)", "");
    }

    private static String normalizeToken(final String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-").replaceAll("(^-|-$)", "");
    }

    private static String unclassified(final PassType type, final String label) {
        String input = (type == null ? "UNKNOWN" : type.name()) + "\u0000" + label;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("unclassified/");
            for (int index = 0; index < 8; index++) {
                result.append(String.format(Locale.ROOT, "%02x", digest[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
