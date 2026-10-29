package com.lifeorganizer.engine;

import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.PreferredTime;
import java.time.LocalTime;
import java.util.Objects;

/**
 * A chunk of a goal's weekly time to be placed on a specific day. `anchorStart`
 * is an optional fixed clock time the DayScheduler tries first; the WeekAllocator
 * leaves it null, so the scheduler picks the best-fitting free gap.
 */
public record Unit(String id, Goal goal, int minutes, PreferredTime preferredTime, LocalTime anchorStart) {

    public Unit {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(preferredTime, "preferredTime");
        if (minutes <= 0) {
            throw new IllegalArgumentException("minutes must be positive");
        }
    }
}
