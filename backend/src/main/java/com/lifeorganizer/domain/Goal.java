package com.lifeorganizer.domain;

import java.util.Objects;

public record Goal(
    String id,
    Category category,
    String name,
    int minutesPerWeek,
    int defaultBlockMinutes,
    Energy energy,
    PreferredTime preferredTime
) {

    public Goal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(energy, "energy");
        Objects.requireNonNull(preferredTime, "preferredTime");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Goal name must not be blank");
        }
        if (minutesPerWeek < 0) {
            throw new IllegalArgumentException("minutesPerWeek must not be negative, was " + minutesPerWeek);
        }
        if (defaultBlockMinutes <= 0) {
            throw new IllegalArgumentException("defaultBlockMinutes must be positive");
        }
    }

    public static Goal withDefaults(String id, Category category, String name) {
        return new Goal(id, category, name, 0, 60, Energy.MEDIUM, PreferredTime.ANY);
    }
}
