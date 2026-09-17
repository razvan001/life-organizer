package com.lifeorganizer.domain;

import java.time.LocalDate;
import java.util.Objects;

public record Goal(
    String id,
    Category category,
    String name,
    int priority,
    LocalDate deadline,
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
        if (priority < 1 || priority > 5) {
            throw new IllegalArgumentException("priority must be between 1 and 5, was " + priority);
        }
        if (defaultBlockMinutes <= 0) {
            throw new IllegalArgumentException("defaultBlockMinutes must be positive");
        }
    }

    public static Goal withDefaults(String id, Category category, String name) {
        return new Goal(id, category, name, 3, null, 60, Energy.MEDIUM, PreferredTime.ANY);
    }
}
