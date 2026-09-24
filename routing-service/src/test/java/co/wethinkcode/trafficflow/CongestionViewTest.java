package co.wethinkcode.trafficflow;

import co.wethinkcode.trafficflow.CongestionSource.Reading;
import co.wethinkcode.trafficflow.CongestionView.CongestionChanged;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CongestionViewTest {

    private static final CongestionChanged THREE_AT_EIGHT = new CongestionChanged(3, "2026-09-24T08:00:00Z");
    private static final CongestionChanged SIX_AT_NINE = new CongestionChanged(6, "2026-09-24T09:00:00Z");

    private final CongestionView view = new CongestionView();

    @Test
    void unknownUntilTheFirstChangeArrives() {
        assertEquals(Reading.UNKNOWN, view.current());
        assertFalse(view.current().known());
    }

    @Test
    void theLatestChangeWins() {
        view.apply(THREE_AT_EIGHT);
        view.apply(SIX_AT_NINE);

        assertEquals(new Reading(6, "2026-09-24T09:00:00Z"), view.current());
    }

    @Test
    void anOlderChangeArrivingLateIsIgnored() {
        view.apply(SIX_AT_NINE);
        view.apply(THREE_AT_EIGHT);

        assertEquals(new Reading(6, "2026-09-24T09:00:00Z"), view.current());
    }

    @Test
    void theSameChangeTwiceIsHarmless() {
        view.apply(SIX_AT_NINE);
        view.apply(SIX_AT_NINE);

        assertEquals(new Reading(6, "2026-09-24T09:00:00Z"), view.current());
    }

    @Test
    void aChangeThatMakesNoSenseCannotBeMade() {
        assertThrows(IllegalArgumentException.class, () -> new CongestionChanged(9, "2026-09-24T09:00:00Z"));
        assertThrows(RuntimeException.class, () -> new CongestionChanged(4, null));
        assertThrows(RuntimeException.class, () -> new CongestionChanged(4, "yesterday"));
    }
}
