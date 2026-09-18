package com.lifeorganizer.engine;

import com.lifeorganizer.domain.Goal;
import java.util.Objects;

public record GoalAllocation(Goal goal, int minutesPerWeek) {

    public GoalAllocation {
        Objects.requireNonNull(goal, "goal");
        if (minutesPerWeek < 0) {
            throw new IllegalArgumentException("minutesPerWeek must not be negative");
        }
    }
}
