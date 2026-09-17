package com.lifeorganizer.engine;

import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.PreferredTime;
import java.time.LocalTime;
import java.util.Objects;

/**
 * A chunk of a goal's weekly time to be placed on a specific day. `anchorStart`
 * is null for most units (DayScheduler picks the best-fitting free gap), but is
 * set to a fixed clock time for evening-quota units (e.g. Social always anchors
 * to EngineSettings.eveningAnchor()) so they land at a conventional hour rather
 * than immediately filling whatever gap is free first.
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
