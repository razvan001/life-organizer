package com.lifeorganizer.domain;

import java.util.Objects;

public record Category(String id, String name) {

    public Category {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Category name must not be blank");
        }
    }

    public static Category of(String name) {
        return new Category(Slug.of(name), name);
    }
}
