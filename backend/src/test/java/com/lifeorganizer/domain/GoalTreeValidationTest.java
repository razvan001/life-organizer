package com.lifeorganizer.domain;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoalTreeValidationTest {

    @Test
    void nullCategoriesThrowsNullPointerException() {
        assertThatThrownBy(() -> new GoalTree(null, List.of()))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("categories");
    }

    @Test
    void nullGoalsThrowsNullPointerException() {
        assertThatThrownBy(() -> new GoalTree(List.of(), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("goals");
    }

    @Test
    void defensiveCopyOfCategories() {
        ArrayList<Category> mutableCategories = new ArrayList<>();
        Category career = Category.of("Career");
        mutableCategories.add(career);

        GoalTree tree = new GoalTree(mutableCategories, List.of());
        mutableCategories.add(Category.of("Health"));

        assertThat(tree.categories()).containsExactly(career);
    }

    @Test
    void defensiveCopyOfGoals() {
        Category career = Category.of("Career");
        ArrayList<Goal> mutableGoals = new ArrayList<>();
        Goal cert = Goal.withDefaults("career-cert", career, "Claude certification");
        mutableGoals.add(cert);

        GoalTree tree = new GoalTree(List.of(career), mutableGoals);
        mutableGoals.add(Goal.withDefaults("career-other", career, "Another goal"));

        assertThat(tree.goals()).containsExactly(cert);
    }
}
