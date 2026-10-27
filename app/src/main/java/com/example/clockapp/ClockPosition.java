package com.example.clockapp;

import java.util.Random;

final class ClockPosition {
    private ClockPosition() {}

    static float randomOffset(int parentSize, int clockSize, Random random) {
        return random.nextFloat() * Math.max(0, parentSize - clockSize);
    }

    static float clamp(float offset, int parentSize, int clockSize) {
        return Math.max(0, Math.min(offset, Math.max(0, parentSize - clockSize)));
    }
}
