package com.lifeorganizer.domain;

import java.time.LocalTime;
import java.util.Objects;

public record EngineSettings(
    int dinnerMinutesAfterWork,
    int windDownMinutesBeforeSleep,
    int minBlockMinutes,
    int gridMinutes,
    LocalTime eveningAnchor
) {

    public EngineSettings {
        Objects.requireNonNull(eveningAnchor, "eveningAnchor");
        if (dinnerMinutesAfterWork < 0 || windDownMinutesBeforeSleep < 0) {
            throw new IllegalArgumentException("routine offsets must not be negative");
        }
        if (minBlockMinutes <= 0 || gridMinutes <= 0) {
            throw new IllegalArgumentException("minBlockMinutes and gridMinutes must be positive");
        }
        if (minBlockMinutes % gridMinutes != 0) {
            throw new IllegalArgumentException("minBlockMinutes must be a multiple of gridMinutes");
        }
    }

    public static EngineSettings defaults() {
        return new EngineSettings(60, 60, 30, 30, LocalTime.of(20, 0));
    }
}
