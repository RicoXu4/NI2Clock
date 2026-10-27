package com.example.clockapp;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClockPositionTest {
    @Test
    public void movementStaysInsideAvailableArea() {
        Random random = new Random(42);
        for (int i = 0; i < 1000; i++) {
            float position = ClockPosition.randomOffset(800, 350, random);
            assertTrue(position >= 0 && position <= 450);
        }
    }

    @Test
    public void oversizedClockDoesNotProduceNegativeCoordinates() {
        assertEquals(0, ClockPosition.randomOffset(200, 350, new Random(42)), 0);
        assertEquals(0, ClockPosition.randomOffset(0, 0, new Random(42)), 0);
        assertEquals(0, ClockPosition.clamp(100, 200, 350), 0);
    }

    @Test
    public void resizedAreaClampsExistingPosition() {
        assertEquals(150, ClockPosition.clamp(400, 500, 350), 0);
        assertEquals(0, ClockPosition.clamp(-20, 500, 350), 0);
        assertEquals(100, ClockPosition.clamp(100, 500, 350), 0);
    }
}
