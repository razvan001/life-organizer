package com.lifeorganizer.domain;

import java.util.List;
import java.util.Objects;

public record GoalTree(List<Category> categories, List<Goal> goals) {

    public GoalTree {
        Objects.requireNonNull(categories, "categories");
        Objects.requireNonNull(goals, "goals");
        categories = List.copyOf(categories);
        goals = List.copyOf(goals);
    }

    public List<Goal> goalsIn(Category category) {
        return goals.stream().filter(g -> g.category().equals(category)).toList();
    }
}
