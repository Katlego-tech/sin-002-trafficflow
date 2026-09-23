package co.wethinkcode.trafficflow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IntersectionCsvCleanerTest {

    private static final String HEADER = "intersection_id,District ,signal_type,active_flag\n";

    private static CleaningReport clean(String rows) throws IOException {
        return new IntersectionCsvCleaner().clean(new StringReader(HEADER + rows));
    }

    private static CleanIntersection only(CleaningReport report) {
        assertEquals(1, report.intersections().size(), () -> "expected one record, got " + report.intersections());
        return report.intersections().get(0);
    }

    @Test
    void cleansPaddingCasingAndFlags() throws IOException {
        CleanIntersection intersection = only(clean("int-1001 , Downtown ,4-way,Y\n"));

        assertEquals(new CleanIntersection("INT-1001", "Downtown", "4-way", true, List.of()), intersection);
    }

    @Test
    void signalTypeSpellingsBecomeOneLowerCaseForm() throws IOException {
        CleaningReport report = clean("""
                INT-1006,Midtown,4-Way,no
                INT-1012,Eastside,ROUNDABOUT,0
                INT-1020,Uptown,Stop Sign,1
                """);

        assertEquals(List.of("4-way", "roundabout", "stop-sign"),
                report.intersections().stream().map(CleanIntersection::signalType).toList());
    }

    @Test
    void theReadmesDuplicateCollapsesIntoOneRecord() throws IOException {
        CleanIntersection intersection = only(clean("""
                INT-1005,Downtown,Roundabout,true
                int-1005,downtown ,ROUNDABOUT,TRUE
                """));

        assertEquals(new CleanIntersection("INT-1005", "Downtown", "roundabout", true,
                List.of("merged 2 rows with this ID (lines 2, 3)")), intersection);
    }

    @Test
    void duplicatesThatDisagreeAreLeftUnknownAndSaySo() throws IOException {
        CleanIntersection intersection = only(clean("""
                INT-1030,Downtown,4-way,Y
                int-1030,Midtown,4-way,Y
                """));

        assertNull(intersection.district());
        assertEquals("4-way", intersection.signalType());
        assertEquals(List.of("merged 2 rows with this ID (lines 2, 3)",
                "district disagrees across the duplicate rows [Downtown, Midtown]: a tie, so left unknown"),
                intersection.notes());
    }

    @Test
    void theMajorityOfDuplicatesWins() throws IOException {
        CleanIntersection intersection = only(clean("""
                INT-1032,Uptown,4-way,Y
                INT-1032,Uptown,4-way,N
                INT-1032,Uptown,4-way,yes
                """));

        assertEquals(true, intersection.active());
        assertEquals("active_flag disagrees across the duplicate rows [true, false]: most rows say true",
                intersection.notes().get(1));
    }

    @Test
    void aValueMissingFromOneDuplicateIsTakenFromTheOtherAndTheGapIsNoted() throws IOException {
        CleanIntersection intersection = only(clean("""
                INT-1031,,4-way,Y
                INT-1031,Westside,4-way,Y
                """));

        assertEquals("Westside", intersection.district());
        assertEquals(List.of("merged 2 rows with this ID (lines 2, 3)", "line 2: district missing in the source (blank)"),
                intersection.notes());
    }

    @Test
    void missingValuesAreExplicitNullsWithANote() throws IOException {
        CleaningReport report = clean("""
                INT-1007,Eastside,,1
                INT-1013,Westside ,unknown,unknown
                INT-1015,,4-way,Y
                """);

        CleanIntersection blankSignal = report.intersections().get(0);
        assertNull(blankSignal.signalType());
        assertEquals(List.of("signal_type missing in the source (blank)"), blankSignal.notes());

        CleanIntersection unknowns = report.intersections().get(1);
        assertNull(unknowns.signalType());
        assertNull(unknowns.active());
        assertEquals(List.of("signal_type missing in the source (was 'unknown')",
                "active_flag missing in the source (was 'unknown')"), unknowns.notes());

        CleanIntersection noDistrict = report.intersections().get(2);
        assertNull(noDistrict.district());
        assertEquals(List.of("district missing in the source (blank)"), noDistrict.notes());
    }

    @Test
    void anUnrecognisedFlagIsUnknownNotGuessed() throws IOException {
        CleanIntersection intersection = only(clean("INT-1040,Uptown,4-way,maybe\n"));

        assertNull(intersection.active());
        assertEquals(List.of("active_flag 'maybe' is not a yes/no value, so it is unknown"), intersection.notes());
    }

    @Test
    void anUnrecognisedSignalTypeIsKeptAndFlagged() throws IOException {
        CleanIntersection intersection = only(clean("INT-1041,Uptown,Flashing Amber,Y\n"));

        assertEquals("flashing amber", intersection.signalType());
        assertEquals(List.of("signal_type 'Flashing Amber' is not a known type, kept as written"),
                intersection.notes());
    }

    @Test
    void anOddlyShapedIdIsKeptAndFlagged() throws IOException {
        CleanIntersection intersection = only(clean("J-17,Uptown,4-way,Y\n"));

        assertEquals("J-17", intersection.id());
        assertEquals(List.of("intersection_id 'J-17' does not look like INT-<number>"), intersection.notes());
    }

    @Test
    void recordsAreSortedByIdNumber() throws IOException {
        CleaningReport report = clean("""
                INT-10,Uptown,4-way,Y
                INT-9,Uptown,4-way,Y
                INT-100,Uptown,4-way,Y
                """);

        assertEquals(List.of("INT-9", "INT-10", "INT-100"),
                report.intersections().stream().map(CleanIntersection::id).toList());
    }

    @Test
    void aMalformedRowIsRejectedWithoutStoppingTheRest() throws IOException {
        CleaningReport report = clean("""
                INT-1009,Westside,4-way,FALSE
                INT-1099,Downtown
                ,Downtown,4-way,Y

                INT-1017,eastside,stop-sign,n
                """);

        assertEquals(4, report.rowsRead(), "a blank line is not a row");
        assertEquals(List.of("INT-1009", "INT-1017"),
                report.intersections().stream().map(CleanIntersection::id).toList());
        assertEquals(List.of(
                        new CleaningReport.RejectedRow(3, "expected 4 fields, found 2", "INT-1099,Downtown"),
                        new CleaningReport.RejectedRow(4, "no intersection_id", ",Downtown,4-way,Y")),
                report.rejected());
    }

    @Test
    void aReorderedExportStillWorks() throws IOException {
        CleaningReport report = new IntersectionCsvCleaner().clean(new StringReader(
                "active_flag,signal_type,intersection_id,district\nY,4-way,INT-1001,Downtown\n"));

        assertEquals(new CleanIntersection("INT-1001", "Downtown", "4-way", true, List.of()), only(report));
    }

    @Test
    void aHeaderWithoutTheExpectedColumnsFailsLoudly() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new IntersectionCsvCleaner().clean(new StringReader("id,where\nINT-1,Downtown\n")));

        assertEquals("the CSV header is missing column(s) [intersection_id, district, signal_type, active_flag];"
                + " found [id, where]", e.getMessage());
    }

    @Test
    void anEmptyFileFailsLoudly() {
        assertThrows(IllegalArgumentException.class, () -> new IntersectionCsvCleaner().clean(new StringReader("")));
    }
}
