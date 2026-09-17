package com.lifeorganizer.domain;

public final class Slug {

    private Slug() {}

    public static String of(String text) {
        String slug = text.toLowerCase().trim()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+|-+$)", "");
        return slug.isEmpty() ? "item" : slug;
    }
}
