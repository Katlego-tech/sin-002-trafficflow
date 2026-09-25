package co.wethinkcode.trafficflow;

import co.wethinkcode.trafficflow.CongestionLevel.Level;
import co.wethinkcode.trafficflow.CongestionLevel.LevelUnknown;
import co.wethinkcode.trafficflow.CongestionPublisher.PublishFailed;
import co.wethinkcode.trafficflow.LastPublished.ReadFailed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CongestionLevelTest {

    private static final Instant EIGHT_AM = Instant.parse("2026-09-24T08:00:00Z");
    private static final CongestionChanged LAST_PUBLISHED = new CongestionChanged(5, 2, "2026-09-24T07:30:00Z");
    private static final LastPublished NOTHING_PUBLISHED = Optional::empty;
    private static final LastPublished BROKER_DOWN = () -> {
        throw new ReadFailed("could not read congestion-topic: connection refused", null);
    };

    private final AtomicReference<Instant> now = new AtomicReference<>(EIGHT_AM);
    private final List<CongestionChanged> published = new ArrayList<>();
    private final CongestionLevel congestion = new CongestionLevel(published::add, NOTHING_PUBLISHED, now::get);

    @Test
    void withNothingEverPublishedItStartsClearAndNeverSet() {
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
        }, NOTHING_PUBLISHED, now::get);

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

    @Test
    void afterARestartItHasTheLevelLastPublished() {
        CongestionLevel restarted = new CongestionLevel(published::add, () -> Optional.of(LAST_PUBLISHED), now::get);

        assertEquals(new Level(5, "2026-09-24T07:30:00Z"), restarted.current(), "changed at 07:30, not now");
    }

    @Test
    void theNextChangeIsPublishedWithTheRecoveredLevelBeforeIt() {
        CongestionLevel restarted = new CongestionLevel(published::add, () -> Optional.of(LAST_PUBLISHED), now::get);

        restarted.set(6);

        assertEquals(List.of(new CongestionChanged(6, 5, "2026-09-24T08:00:00Z")), published);
    }

    @Test
    void settingTheRecoveredLevelChangesAndPublishesNothing() {
        CongestionLevel restarted = new CongestionLevel(published::add, () -> Optional.of(LAST_PUBLISHED), now::get);

        assertEquals(new Level(5, "2026-09-24T07:30:00Z"), restarted.set(5));
        assertEquals(List.of(), published);
    }

    @Test
    void ifTheLastLevelCannotBeReadItIsUnknownNeverZero() {
        CongestionLevel blind = new CongestionLevel(published::add, BROKER_DOWN, now::get);

        LevelUnknown unknown = assertThrows(LevelUnknown.class, blind::current);
        assertEquals("level unknown: could not read congestion-topic: connection refused", unknown.getMessage());
    }

    @Test
    void anUnknownLevelIsNotChangedOrPublished() {
        CongestionLevel blind = new CongestionLevel(published::add, BROKER_DOWN, now::get);

        assertThrows(LevelUnknown.class, () -> blind.set(4));
        assertEquals(List.of(), published);
    }

    @Test
    void anUnknownLevelIsReadAgainNextTime() {
        AtomicInteger reads = new AtomicInteger();
        CongestionLevel recovering = new CongestionLevel(published::add, () -> {
            if (reads.incrementAndGet() == 1) {
                return BROKER_DOWN.read();
            }
            return Optional.of(LAST_PUBLISHED);
        }, now::get);

        assertThrows(LevelUnknown.class, recovering::current);
        assertEquals(new Level(5, "2026-09-24T07:30:00Z"), recovering.current());
    }

    @Test
    void onceKnownTheLevelIsNeverReadAgain() {
        AtomicInteger reads = new AtomicInteger();
        CongestionLevel restarted = new CongestionLevel(published::add, () -> {
            reads.incrementAndGet();
            return Optional.of(LAST_PUBLISHED);
        }, now::get);

        restarted.current();
        restarted.set(7);
        restarted.current();

        assertEquals(1, reads.get());
        assertEquals(new Level(7, "2026-09-24T08:00:00Z"), restarted.current());
    }
}
