package co.wethinkcode.trafficflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistrictMapTest {

    private static final List<String> DISTRICTS = List.of("Downtown", "Midtown", "Uptown", "Eastside", "Westside");

    @Test
    void knowsTheCrossingTimeBetweenTwoDistricts() {
        assertEquals(6, DistrictMap.minutes("Downtown", "Midtown"));
        assertEquals(12, DistrictMap.minutes("Downtown", "Uptown"));
        assertEquals(16, DistrictMap.minutes("Eastside", "Westside"));
    }

    @Test
    void aCrossingTakesAsLongEitherWay() {
        for (String from : DISTRICTS) {
            for (String to : DISTRICTS) {
                assertEquals(DistrictMap.minutes(from, to), DistrictMap.minutes(to, from), from + " <-> " + to);
            }
        }
    }

    @Test
    void stayingInADistrictIsAShortHop() {
        for (String district : DISTRICTS) {
            assertEquals(DistrictMap.WITHIN_A_DISTRICT, DistrictMap.minutes(district, district));
        }
    }

    @Test
    void everyCrossingTakesLongerThanStayingPut() {
        for (String from : DISTRICTS) {
            for (String to : DISTRICTS) {
                if (!from.equals(to)) {
                    assertTrue(DistrictMap.minutes(from, to) > DistrictMap.WITHIN_A_DISTRICT, from + " -> " + to);
                }
            }
        }
    }

    @Test
    void theLongestCrossingIsThePessimisticGuess() {
        assertEquals(16, DistrictMap.longestCrossing());
    }

    @Test
    void findsADistrictIgnoringCaseAndPadding() {
        assertEquals(Optional.of("Downtown"), DistrictMap.find(" downtown "));
        assertEquals(Optional.empty(), DistrictMap.find("Northside"));
        assertEquals(Optional.empty(), DistrictMap.find(null));
    }
}
