package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;

import java.time.LocalDate;
import java.util.*;

/**
 * Turns each goal's hours per week into per-day Units: the goal's minutes are cut into sessions of at most its
 * defaultBlockMinutes, one session per goal per day, spread over the week. Fixed blocks make no Units; the
 * DayScheduler places them directly.
 */
public final class WeekAllocator {

    public WeekPlan allocate(List<Goal> goals, List<Constraint> constraints, LocalDate weekStart) {
        Map<LocalDate, List<Unit>> unitsByDate = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            unitsByDate.put(weekStart.plusDays(i), new ArrayList<>());
        }
        List<String> warnings = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        // Shared across every goal so weekly work spreads across all 7 days instead of each goal restarting at Monday.
        int dayCursor = 0;
        // Goals are visited in setup order, so the units of one day keep that order too.
        for (Goal goal : goals) {
            if (!seenIds.add(goal.id())) {
                warnings.add("Duplicate goal id '" + goal.id() + "' - ignoring the duplicate");
                continue;
            }
            int remaining = goal.minutesPerWeek();
            int daysTried = 0;
            while (remaining > 0 && daysTried < 7) {
                LocalDate date = weekStart.plusDays(dayCursor % 7);
                int chunk = Math.min(goal.defaultBlockMinutes(), remaining);
                unitsByDate.get(date).add(new Unit(goal.id() + "-chunk-" + date, goal, chunk, goal.preferredTime(), null));
                remaining -= chunk;
                dayCursor++;
                daysTried++;
            }
            if (remaining > 0) {
                warnings.add(goal.name() + " could not fit " + remaining + " of its "
                    + goal.minutesPerWeek() + " weekly minutes (ran out of days)");
            }
        }
        return new WeekPlan(unitsByDate, warnings);
    }
}
