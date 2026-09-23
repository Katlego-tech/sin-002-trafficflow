package co.wethinkcode.trafficflow;

import java.util.List;

/**
 * What came out of one pass over the CSV: the intersections, and the rows that could not be used.
 *
 * @param rowsRead non-blank data rows, header excluded
 */
public record CleaningReport(int rowsRead, List<CleanIntersection> intersections, List<RejectedRow> rejected) {

    /** A row that was skipped, and why. {@code line} is its line in the file, the header being line 1. */
    public record RejectedRow(long line, String reason, String raw) {
    }
}
