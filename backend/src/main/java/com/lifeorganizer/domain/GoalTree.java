package com.lifeorganizer.domain;

import java.util.List;

public record GoalTree(List<Category> categories, List<Goal> goals) {

    public List<Goal> goalsIn(Category category) {
        return goals.stream().filter(g -> g.category().equals(category)).toList();
    }
}
