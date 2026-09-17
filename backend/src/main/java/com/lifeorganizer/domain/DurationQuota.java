package com.lifeorganizer.domain;

import java.util.Objects;

public record DurationQuota(String label, int minutesPerWeek, String categoryId) implements Constraint {

    public DurationQuota {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(categoryId, "categoryId");
        if (minutesPerWeek <= 0) throw new IllegalArgumentException("minutesPerWeek must be positive");
    }
}
