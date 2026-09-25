package co.wethinkcode.trafficflow;

import co.wethinkcode.trafficflow.CleaningReport.RejectedRow;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.RFC4180ParserBuilder;
import com.opencsv.exceptions.CsvValidationException;

import java.io.IOException;
import java.io.Reader;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns the legacy {@code intersections-legacy.csv} export into one record per intersection.
 *
 * <p>Two passes. First every row is cleaned on its own (padding, casing, placeholders, flags,
 * signal-type spellings). Then rows with the same ID once normalised ({@code INT-1005} /
 * {@code int-1005}) are merged into one record.
 *
 * <p>A bad row is rejected and reported; it never stops the rest of the file being cleaned.
 */
public final class IntersectionCsvCleaner {

    private static final Pattern INTERSECTION_ID = Pattern.compile("INT-(\\d+)");
    private static final List<String> COLUMNS = List.of("intersection_id", "district", "signal_type", "active_flag");
    private static final Map<String, String> SIGNAL_TYPES = Map.of(
            "4way", "4-way",
            "pedestrian", "pedestrian",
            "roundabout", "roundabout",
            "stopsign", "stop-sign");

    /** A row after its fields are cleaned, before duplicates are merged. */
    private record Row(long line, String id, String district, String signalType, Boolean active, List<String> notes) {
    }

    private static final class RowRejected extends Exception {
        RowRejected(String reason) {
            super(reason);
        }
    }

    public CleaningReport clean(Reader csv) throws IOException {
        // RFC 4180 parsing: quotes work as in a spreadsheet export, and a backslash is just a character.
        try (CSVReader reader = new CSVReaderBuilder(csv).withCSVParser(new RFC4180ParserBuilder().build()).build()) {
            String[] header = reader.readNext();
            if (header == null) {
                throw new IllegalArgumentException("the CSV is empty: no header row");
            }
            Map<String, Integer> column = columnIndexes(header);

            List<Row> rows = new ArrayList<>();
            List<RejectedRow> rejected = new ArrayList<>();
            int rowsRead = 0;
            while (true) {
                long line = reader.getLinesRead() + 1;
                String[] fields = reader.readNext();
                if (fields == null) {
                    break;
                }
                if (isBlank(fields)) {
                    continue;
                }
                rowsRead++;
                try {
                    rows.add(parse(line, fields, header.length, column));
                } catch (RowRejected e) {
                    rejected.add(new RejectedRow(line, e.getMessage(), String.join(",", fields)));
                }
            }
            return new CleaningReport(rowsRead, merge(rows), rejected);
        } catch (CsvValidationException e) {
            throw new IOException("the CSV could not be parsed: " + e.getMessage(), e);
        }
    }

    /** Finds each column by its (cleaned) header name, so a reordered export still works. */
    private static Map<String, Integer> columnIndexes(String[] header) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            String name = Values.clean(header[i]);
            if (name != null) {
                index.put(name.toLowerCase(Locale.ROOT), i);
            }
        }
        List<String> missing = COLUMNS.stream().filter(c -> !index.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("the CSV header is missing column(s) " + missing
                    + "; found " + Arrays.toString(header));
        }
        return index;
    }

    private static boolean isBlank(String[] fields) {
        return Arrays.stream(fields).allMatch(String::isBlank);
    }

    // ---- pass 1: one row at a time -------------------------------------------------------------

    private static Row parse(long line, String[] fields, int expectedFields, Map<String, Integer> column)
            throws RowRejected {
        if (fields.length != expectedFields) {
            throw new RowRejected("expected " + expectedFields + " fields, found " + fields.length);
        }
        String cleanedId = Values.clean(fields[column.get("intersection_id")]);
        if (cleanedId == null) {
            throw new RowRejected("no intersection_id");
        }
        String id = cleanedId.toUpperCase(Locale.ROOT).replace(" ", "");

        List<String> notes = new ArrayList<>();
        if (!INTERSECTION_ID.matcher(id).matches()) {
            notes.add("intersection_id '" + id + "' does not look like INT-<number>");
        }
        String district = district(fields[column.get("district")], notes);
        String signalType = signalType(fields[column.get("signal_type")], notes);
        Boolean active = active(fields[column.get("active_flag")], notes);
        return new Row(line, id, district, signalType, active, notes);
    }

    private static String district(String raw, List<String> notes) {
        String cleaned = Values.clean(raw);
        if (cleaned == null) {
            notes.add(missing("district", raw));
            return null;
        }
        return Values.titleCase(cleaned);
    }

    private static String signalType(String raw, List<String> notes) {
        String cleaned = Values.clean(raw);
        if (cleaned == null) {
            notes.add(missing("signal_type", raw));
            return null;
        }
        String known = SIGNAL_TYPES.get(Values.matchKey(cleaned));
        if (known == null) {
            notes.add("signal_type '" + cleaned + "' is not a known type, kept as written");
            return cleaned.toLowerCase(Locale.ROOT);
        }
        return known;
    }

    private static Boolean active(String raw, List<String> notes) {
        String cleaned = Values.clean(raw);
        if (cleaned == null) {
            notes.add(missing("active_flag", raw));
            return null;
        }
        Boolean active = Values.flag(cleaned);
        if (active == null) {
            notes.add("active_flag '" + cleaned + "' is not a yes/no value, so it is unknown");
        }
        return active;
    }

    /** {@code district missing in the source (blank)}, or {@code (was 'unknown')} for a placeholder. */
    private static String missing(String column, String raw) {
        String shown = raw.strip();
        return column + " missing in the source (" + (shown.isEmpty() ? "blank" : "was '" + shown + "'") + ")";
    }

    // ---- pass 2: merge rows with the same ID ---------------------------------------------------

    private static List<CleanIntersection> merge(List<Row> rows) {
        return rows.stream()
                .collect(Collectors.groupingBy(Row::id, LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .map(IntersectionCsvCleaner::mergeGroup)
                .sorted(Comparator.comparing(CleanIntersection::id, IntersectionCsvCleaner::compareIds))
                .toList();
    }

    private static CleanIntersection mergeGroup(List<Row> group) {
        Row first = group.get(0);
        if (group.size() == 1) {
            return new CleanIntersection(first.id(), first.district(), first.signalType(), first.active(), first.notes());
        }
        List<String> notes = new ArrayList<>();
        notes.add("merged " + group.size() + " rows with this ID (lines "
                + group.stream().map(row -> String.valueOf(row.line())).collect(Collectors.joining(", ")) + ")");
        group.forEach(row -> row.notes().forEach(note -> notes.add("line " + row.line() + ": " + note)));

        String district = resolve("district", group, Row::district, notes);
        String signalType = resolve("signal_type", group, Row::signalType, notes);
        Boolean active = resolve("active_flag", group, Row::active, notes);
        return new CleanIntersection(first.id(), district, signalType, active, notes);
    }

    /**
     * One value for a field the duplicate rows may disagree on. A missing value never outvotes a
     * present one. Otherwise the value most rows agree on wins, and a tie stays unknown (null).
     * Any disagreement is written into the notes.
     */
    private static <T> T resolve(String column, List<Row> group, Function<Row, T> field, List<String> notes) {
        Map<T, Long> votes = group.stream()
                .map(field)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
        if (votes.size() <= 1) {
            return votes.keySet().stream().findFirst().orElse(null);
        }
        long top = Collections.max(votes.values());
        List<T> leaders = votes.entrySet().stream().filter(e -> e.getValue() == top).map(Map.Entry::getKey).toList();
        T decided = leaders.size() == 1 ? leaders.get(0) : null;
        notes.add(column + " disagrees across the duplicate rows " + votes.keySet() + ": "
                + (decided == null ? "a tie, so left unknown" : "most rows say " + decided));
        return decided;
    }

    /** INT-9 sorts before INT-10; anything that isn't INT-&lt;number&gt; sorts after, alphabetically. */
    private static int compareIds(String a, String b) {
        Matcher ma = INTERSECTION_ID.matcher(a);
        Matcher mb = INTERSECTION_ID.matcher(b);
        boolean aNumeric = ma.matches();
        boolean bNumeric = mb.matches();
        if (aNumeric && bNumeric) {
            return new BigInteger(ma.group(1)).compareTo(new BigInteger(mb.group(1)));
        }
        if (aNumeric != bNumeric) {
            return aNumeric ? -1 : 1;
        }
        return a.compareTo(b);
    }
}
