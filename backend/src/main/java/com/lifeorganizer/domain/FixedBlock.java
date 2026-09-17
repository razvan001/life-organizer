package com.lifeorganizer.domain;

import java.time.LocalTime;
import java.time.DayOfWeek;
import java.util.Set;
import java.util.Objects;

public record FixedBlock(String label, LocalTime start, LocalTime end, Set<DayOfWeek> days) implements Constraint {

    public FixedBlock {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (days == null || days.isEmpty()) {
            throw new IllegalArgumentException("days must not be empty");
        }
    }

    /** True for overnight ranges such as Sleep 23:30-07:00, where end is numerically before start. */
    public boolean isOvernight() {
        return end.isBefore(start);
    }
}
