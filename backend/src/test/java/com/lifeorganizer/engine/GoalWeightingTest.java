package com.lifeorganizer.engine;

import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.Goal;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GoalWeightingTest {

    private final Category career = Category.of("Career");
    private final LocalDate today = LocalDate.of(2026, 9, 14);

    @Test
    void splitsEvenlyWhenAllPrioritiesEqual() {
        Goal a = Goal.withDefaults("a", career, "A");
        Goal b = Goal.withDefaults("b", career, "B");

        List<GoalAllocation> result = new GoalWeighting().allocate(List.of(a, b), 300, today, 30);

        assertThat(result).extracting(GoalAllocation::minutesPerWeek).containsExactly(150, 150);
    }

    @Test
    void higherPriorityGetsMoreMinutes() {
        Goal high = new Goal("high", career, "High", 5, null, 60, com.lifeorganizer.domain.Energy.MEDIUM, com.lifeorganizer.domain.PreferredTime.ANY);
        Goal low = new Goal("low", career, "Low", 1, null, 60, com.lifeorganizer.domain.Energy.MEDIUM, com.lifeorganizer.domain.PreferredTime.ANY);

        List<GoalAllocation> result = new GoalWeighting().allocate(List.of(high, low), 360, today, 30);

        assertThat(result.get(0).minutesPerWeek()).isGreaterThan(result.get(1).minutesPerWeek());
        assertThat(result.get(0).minutesPerWeek() + result.get(1).minutesPerWeek()).isEqualTo(360);
    }

    @Test
    void nearDeadlineGoalGetsDoubleBoost() {
        Goal urgent = new Goal("urgent", career, "Urgent", 3, today.plusDays(5), 60, com.lifeorganizer.domain.Energy.MEDIUM, com.lifeorganizer.domain.PreferredTime.ANY);
        Goal normal = new Goal("normal", career, "Normal", 3, null, 60, com.lifeorganizer.domain.Energy.MEDIUM, com.lifeorganizer.domain.PreferredTime.ANY);

        List<GoalAllocation> result = new GoalWeighting().allocate(List.of(urgent, normal), 300, today, 30);

        assertThat(result.get(0).minutesPerWeek()).isEqualTo(210);
        assertThat(result.get(1).minutesPerWeek()).isEqualTo(90);
    }

    @Test
    void allocationsAreMultiplesOfTheGrid() {
        Goal a = Goal.withDefaults("a", career, "A");
        Goal b = Goal.withDefaults("b", career, "B");
        Goal c = Goal.withDefaults("c", career, "C");

        List<GoalAllocation> result = new GoalWeighting().allocate(List.of(a, b, c), 300, today, 30);

        assertThat(result).allMatch(r -> r.minutesPerWeek() % 30 == 0);
        assertThat(result.stream().mapToInt(GoalAllocation::minutesPerWeek).sum()).isEqualTo(300);
    }

    @Test
    void emptyGoalListReturnsEmptyAllocation() {
        assertThat(new GoalWeighting().allocate(List.of(), 300, today, 30)).isEmpty();
    }
}
