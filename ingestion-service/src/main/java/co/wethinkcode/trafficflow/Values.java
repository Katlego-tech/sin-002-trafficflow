package co.wethinkcode.trafficflow;

import java.util.Locale;
import java.util.Set;

/**
 * Normalisation rules that apply to every column of the legacy export.
 */
final class Values {

    private static final Set<String> PLACEHOLDERS =
            Set.of("", "n/a", "na", "tbd", "unknown", "-", "nan", "null", "none");
    private static final Set<String> TRUE_FLAGS = Set.of("y", "yes", "1", "true");
    private static final Set<String> FALSE_FLAGS = Set.of("n", "no", "0", "false");

    private Values() {
    }

    /**
     * Trims, collapses inner runs of whitespace to one space, and turns placeholders ({@code N/A},
     * {@code unknown}, blank, ...) into {@code null}, so "missing" has exactly one representation.
     */
    static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String collapsed = raw.strip().replaceAll("\\s+", " ");
        return PLACEHOLDERS.contains(collapsed.toLowerCase(Locale.ROOT)) ? null : collapsed;
    }

    /**
     * {@code Y/yes/1/true} in any casing is true, {@code N/no/0/false} is false. Anything else,
     * including a missing value, is {@code null}: unknown, not guessed.
     */
    static Boolean flag(String cleaned) {
        if (cleaned == null) {
            return null;
        }
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (TRUE_FLAGS.contains(lower)) {
            return true;
        }
        if (FALSE_FLAGS.contains(lower)) {
            return false;
        }
        return null;
    }

    /** {@code "north-east side"} becomes {@code "North-East Side"}. */
    static String titleCase(String cleaned) {
        StringBuilder out = new StringBuilder(cleaned.length());
        boolean startOfWord = true;
        for (char c : cleaned.toCharArray()) {
            out.append(startOfWord ? Character.toUpperCase(c) : Character.toLowerCase(c));
            startOfWord = c == ' ' || c == '-';
        }
        return out.toString();
    }

    /**
     * Letters and digits only, lower-cased. Spellings of one name that differ only in casing,
     * spacing or punctuation ({@code Stop Sign} / {@code stop-sign}) share a key.
     */
    static String matchKey(String cleaned) {
        return cleaned.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
