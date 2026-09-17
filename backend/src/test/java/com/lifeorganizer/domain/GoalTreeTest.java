package com.lifeorganizer.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GoalTreeTest {

    @Test
    void goalsInFiltersByCategory() {
        Category career = Category.of("Career");
        Category health = Category.of("Health");
        Goal cert = Goal.withDefaults("career-cert", career, "Claude certification");
        Goal gym = Goal.withDefaults("health-gym", health, "Gym");
        GoalTree tree = new GoalTree(List.of(career, health), List.of(cert, gym));

        assertThat(tree.goalsIn(career)).containsExactly(cert);
        assertThat(tree.goalsIn(health)).containsExactly(gym);
    }
}
