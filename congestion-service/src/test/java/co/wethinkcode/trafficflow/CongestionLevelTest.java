package co.wethinkcode.trafficflow;

import co.wethinkcode.trafficflow.CongestionLevel.Level;
import co.wethinkcode.trafficflow.CongestionPublisher.PublishFailed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CongestionLevelTest {

    private static final Instant EIGHT_AM = Instant.parse("2026-09-24T08:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(EIGHT_AM);
    private final List<CongestionChanged> published = new ArrayList<>();
    private final CongestionLevel congestion = new CongestionLevel(published::add, now::get);

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
    void eachChangeIsPublishedWithTheLevelBeforeIt() {
        congestion.set(3);
        now.set(EIGHT_AM.plusSeconds(60));
        congestion.set(6);

        assertEquals(List.of(
                        new CongestionChanged(3, 0, "2026-09-24T08:00:00Z"),
                        new CongestionChanged(6, 3, "2026-09-24T08:01:00Z")),
                published);
    }

    @Test
    void settingTheLevelItAlreadyHasChangesAndPublishesNothing() {
        congestion.set(3);
        now.set(EIGHT_AM.plusSeconds(3600));

        assertEquals(new Level(3, "2026-09-24T08:00:00Z"), congestion.set(3), "it last changed at eight");
        assertEquals(1, published.size());
    }

    @Test
    void ifTheChangeCannotBePublishedTheLevelStays() {
        CongestionLevel brokerDown = new CongestionLevel(event -> {
            throw new PublishFailed("could not publish to congestion-topic: connection refused", null);
        }, now::get);

        assertThrows(PublishFailed.class, () -> brokerDown.set(5));
        assertEquals(new Level(0, null), brokerDown.current());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 9, 100})
    void refusesALevelOutsideZeroToEight(int level) {
        assertThrows(IllegalArgumentException.class, () -> congestion.set(level));
        assertEquals(new Level(0, null), congestion.current());
        assertEquals(List.of(), published);
    }

    @Test
    void zeroAndEightAreTheBounds() {
        assertEquals(8, congestion.set(8).level());
        assertEquals(0, congestion.set(0).level());
    }
}
