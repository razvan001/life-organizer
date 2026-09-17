package com.lifeorganizer.domain;

import java.util.Objects;

public record EveningQuota(String label, int eveningsPerWeek, String categoryId) implements Constraint {

    public EveningQuota {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(categoryId, "categoryId");
        if (eveningsPerWeek <= 0) throw new IllegalArgumentException("eveningsPerWeek must be positive");
    }
}
