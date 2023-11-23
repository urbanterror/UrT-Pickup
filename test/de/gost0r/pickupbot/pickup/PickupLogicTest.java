package de.gost0r.pickupbot.pickup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PickupLogicTest {
    @Test
    void parseDurationFromYearString() {
        long time = PickupLogic.parseDurationFromString("10y");
        assertEquals(290304000000L, time);
    }

    @Test
    void parseStringFromYearDuration() {
        String time = PickupLogic.parseStringFromDuration(290304000000L);
        assertEquals("10y", time);
    }
}
