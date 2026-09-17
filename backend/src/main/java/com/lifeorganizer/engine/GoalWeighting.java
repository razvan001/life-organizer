package com.lifeorganizer.engine;

import com.lifeorganizer.domain.Goal;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Splits a category's weekly minute budget across its goals, weighted by
 * priority (1-5) with a boost for goals whose deadline is close. Ties (equal
 * weight) are broken by input order, never by hash-based iteration.
 */
public final class GoalWeighting {

    public List<GoalAllocation> allocate(List<Goal> goalsInCategory, int categoryMinutesPerWeek,
                                          LocalDate today, int gridMinutes) {
        if (goalsInCategory.isEmpty() || categoryMinutesPerWeek <= 0) {
            return List.of();
        }

        int goalCount = goalsInCategory.size();
        double[] weights = new double[goalCount];
        double totalWeight = 0;
        for (int i = 0; i < goalCount; i++) {
            weights[i] = weightOf(goalsInCategory.get(i), today);
            totalWeight += weights[i];
        }

        int totalUnits = categoryMinutesPerWeek / gridMinutes;
        double[] rawUnits = new double[goalCount];
        int[] flooredUnits = new int[goalCount];
        int flooredTotal = 0;
        for (int i = 0; i < goalCount; i++) {
            rawUnits[i] = totalUnits * (weights[i] / totalWeight);
            flooredUnits[i] = (int) Math.floor(rawUnits[i]);
            flooredTotal += flooredUnits[i];
        }

        int remainder = totalUnits - flooredTotal;
        List<Integer> byFractionDesc = IntStream.range(0, goalCount).boxed()
            .sorted((a, b) -> {
                int cmp = Double.compare(rawUnits[b] - flooredUnits[b], rawUnits[a] - flooredUnits[a]);
                if (cmp != 0) return cmp;
                return Integer.compare(a, b); // Earlier input position wins in ties
            })
            .toList();
        for (int i = 0; i < remainder; i++) {
            flooredUnits[byFractionDesc.get(i)]++;
        }

        List<GoalAllocation> result = new ArrayList<>();
        for (int i = 0; i < goalCount; i++) {
            result.add(new GoalAllocation(goalsInCategory.get(i), flooredUnits[i] * gridMinutes));
        }
        return result;
    }

    private double weightOf(Goal goal, LocalDate today) {
        double boost = 1.0;
        if (goal.deadline() != null) {
            long daysLeft = ChronoUnit.DAYS.between(today, goal.deadline());
            if (daysLeft <= 7) {
                boost = 2.0;
            } else if (daysLeft <= 30) {
                boost = 1.5;
            }
        }
        return goal.priority() * boost;
    }
}
