package com.gmidi.util;

public final class SystemClock implements Clock {
    @Override
    public long nowMicros() {
        return System.nanoTime() / 1_000L;
    }
}
