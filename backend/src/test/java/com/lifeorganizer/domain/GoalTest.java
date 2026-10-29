package com.lifeorganizer.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoalTest {

    @Test
    void withDefaultsHasNoHoursAndMediumEnergy() {
        Category career = Category.of("Career");
        Goal goal = Goal.withDefaults("career-claude-certification", career, "Claude certification");

        assertThat(goal.minutesPerWeek()).isZero();
        assertThat(goal.defaultBlockMinutes()).isEqualTo(60);
        assertThat(goal.energy()).isEqualTo(Energy.MEDIUM);
        assertThat(goal.preferredTime()).isEqualTo(PreferredTime.ANY);
    }

    @Test
    void rejectsNegativeMinutesPerWeek() {
        Category career = Category.of("Career");
        assertThatThrownBy(() -> new Goal("id", career, "name", -30, 60, Energy.MEDIUM, PreferredTime.ANY))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
