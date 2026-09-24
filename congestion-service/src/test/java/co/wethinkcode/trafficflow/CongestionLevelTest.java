package co.wethinkcode.trafficflow;

import co.wethinkcode.trafficflow.CongestionLevel.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CongestionLevelTest {

    private static final Instant EIGHT_AM = Instant.parse("2026-09-24T08:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(EIGHT_AM);
    private final CongestionLevel congestion = new CongestionLevel(now::get);

    @Test
    void startsClearAndNeverSet() {
        assertEquals(new Level(0, null), congestion.current());
    }

    @Test
    void aNewLevelIsStampedWithWhenItChanged() {
        assertEquals(new Level(6, "2026-09-24T08:00:00Z"), congestion.set(6));
        assertEquals(new Level(6, "2026-09-24T08:00:00Z"), congestion.current());
    }

    @Test
    void settingTheLevelItAlreadyHasChangesNothing() {
        congestion.set(3);
        now.set(EIGHT_AM.plusSeconds(3600));

        assertEquals(new Level(3, "2026-09-24T08:00:00Z"), congestion.set(3), "it last changed at eight");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 9, 100})
    void refusesALevelOutsideZeroToEight(int level) {
        assertThrows(IllegalArgumentException.class, () -> congestion.set(level));
        assertEquals(new Level(0, null), congestion.current());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 8})
    void zeroAndEightAreBothValid(int level) {
        assertEquals(level, congestion.set(level).level());
    }
}
