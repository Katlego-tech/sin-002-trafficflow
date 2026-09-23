package co.wethinkcode.trafficflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValuesTest {

    @Test
    void trimsAndCollapsesWhitespace() {
        assertEquals("Stop Sign", Values.clean("  Stop  Sign "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "N/A", "n/a", "TBD", "unknown", "-", "NaN"})
    void placeholdersBecomeNull(String placeholder) {
        assertNull(Values.clean(placeholder));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Y", "yes", "YES", "1", "true", "TRUE"})
    void trueFlags(String raw) {
        assertTrue(Values.flag(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"N", "n", "no", "0", "false", "FALSE"})
    void falseFlags(String raw) {
        assertFalse(Values.flag(raw));
    }

    @Test
    void anUnrecognisedOrMissingFlagIsUnknownNotGuessed() {
        assertNull(Values.flag("maybe"));
        assertNull(Values.flag(null));
    }

    @Test
    void titleCasesEachWordAndHyphenatedPart() {
        assertEquals("Downtown", Values.titleCase("DOWNTOWN"));
        assertEquals("North-East Side", Values.titleCase("north-east side"));
    }

    @Test
    void spellingVariantsShareAMatchKey() {
        assertEquals(Values.matchKey("stop-sign"), Values.matchKey("Stop Sign"));
        assertEquals(Values.matchKey("4-way"), Values.matchKey("4-Way"));
    }
}
