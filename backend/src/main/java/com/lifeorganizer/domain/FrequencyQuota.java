package com.lifeorganizer.domain;

import java.util.Objects;

public record FrequencyQuota(String label, int timesPerWeek, int sessionMinutes, String goalId) implements Constraint {

    public FrequencyQuota {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(goalId, "goalId");
        if (timesPerWeek <= 0) throw new IllegalArgumentException("timesPerWeek must be positive");
        if (sessionMinutes <= 0) throw new IllegalArgumentException("sessionMinutes must be positive");
    }
}
