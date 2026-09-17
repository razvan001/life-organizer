package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;

import java.time.LocalDate;
import java.util.*;

/**
 * Turns weekly constraints into per-day Units. Frequency and evening quotas are
 * spread across the week using an evenly-spaced index formula; duration quotas
 * are first split across goals by GoalWeighting, then chunked into daily
 * sessions of at most the goal's defaultBlockMinutes, one chunk per goal per day.
 */
public final class WeekAllocator {

    private final GoalWeighting goalWeighting;
    private final EngineSettings settings;

    public WeekAllocator(GoalWeighting goalWeighting, EngineSettings settings) {
        this.goalWeighting = goalWeighting;
        this.settings = settings;
    }

    public WeekPlan allocate(List<Goal> goals, List<Constraint> constraints, LocalDate weekStart) {
        Map<LocalDate, List<Unit>> unitsByDate = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            unitsByDate.put(weekStart.plusDays(i), new ArrayList<>());
        }
        List<String> warnings = new ArrayList<>();
        Map<String, Goal> goalsById = goals.stream().collect(java.util.stream.Collectors.toMap(Goal::id, g -> g));
        Map<String, List<Goal>> goalsByCategoryId = new LinkedHashMap<>();
        for (Goal g : goals) {
            goalsByCategoryId.computeIfAbsent(g.category().id(), k -> new ArrayList<>()).add(g);
        }

        for (Constraint constraint : constraints) {
            switch (constraint) {
                case FrequencyQuota freq -> allocateFrequency(freq, goalsById, weekStart, unitsByDate, warnings);
                case DurationQuota dur -> allocateDuration(dur, goalsByCategoryId, weekStart, unitsByDate, warnings);
                case EveningQuota eve -> allocateEvening(eve, goalsByCategoryId, weekStart, unitsByDate, warnings);
                case FixedBlock fixed -> { /* placed directly by DayScheduler, not a Unit */ }
            }
        }
        return new WeekPlan(unitsByDate, warnings);
    }

    private void allocateFrequency(FrequencyQuota freq, Map<String, Goal> goalsById, LocalDate weekStart,
                                    Map<LocalDate, List<Unit>> unitsByDate, List<String> warnings) {
        Goal goal = goalsById.get(freq.goalId());
        if (goal == null) {
            warnings.add("Frequency quota '" + freq.label() + "' references unknown goal id " + freq.goalId());
            return;
        }
        for (int dayIndex : spreadIndices(freq.timesPerWeek(), 7)) {
            LocalDate date = weekStart.plusDays(dayIndex);
            unitsByDate.get(date).add(new Unit(goal.id() + "-" + date, goal, freq.sessionMinutes(), goal.preferredTime(), null));
        }
    }

    private void allocateEvening(EveningQuota eve, Map<String, List<Goal>> goalsByCategoryId, LocalDate weekStart,
                                  Map<LocalDate, List<Unit>> unitsByDate, List<String> warnings) {
        List<Goal> candidates = goalsByCategoryId.get(eve.categoryId());
        if (candidates == null || candidates.isEmpty()) {
            warnings.add("Evening quota '" + eve.label() + "' references unknown or empty category " + eve.categoryId());
            return;
        }
        Goal representative = candidates.get(0);
        int[] indices = spreadIndices(eve.eveningsPerWeek(), 7);
        for (int dayIndex : indices) {
            LocalDate date = weekStart.plusDays(dayIndex);
            unitsByDate.get(date).add(new Unit(representative.id() + "-evening-" + date, representative,
                representative.defaultBlockMinutes(), PreferredTime.EVENING, settings.eveningAnchor()));
        }
    }

    private void allocateDuration(DurationQuota dur, Map<String, List<Goal>> goalsByCategoryId, LocalDate weekStart,
                                   Map<LocalDate, List<Unit>> unitsByDate, List<String> warnings) {
        List<Goal> candidates = goalsByCategoryId.get(dur.categoryId());
        if (candidates == null || candidates.isEmpty()) {
            warnings.add("Duration quota '" + dur.label() + "' references unknown or empty category " + dur.categoryId());
            return;
        }
        List<GoalAllocation> allocations = goalWeighting.allocate(candidates, dur.minutesPerWeek(), weekStart, settings.gridMinutes());

        for (GoalAllocation allocation : allocations) {
            int remaining = allocation.minutesPerWeek();
            int dayOffset = 0;
            while (remaining > 0 && dayOffset < 7) {
                LocalDate date = weekStart.plusDays(dayOffset % 7);
                int chunk = Math.min(allocation.goal().defaultBlockMinutes(), remaining);
                unitsByDate.get(date).add(new Unit(allocation.goal().id() + "-chunk-" + date, allocation.goal(), chunk, allocation.goal().preferredTime(), null));
                remaining -= chunk;
                dayOffset++;
            }
            if (remaining > 0) {
                warnings.add(allocation.goal().name() + " could not fit " + remaining + " of its " + allocation.minutesPerWeek() + " weekly minutes (ran out of days)");
            }
        }
    }

    /** Evenly spaced day-of-week indices (0=Monday..6=Sunday) for placing `count` occurrences across `totalDays`. */
    private int[] spreadIndices(int count, int totalDays) {
        int[] indices = new int[count];
        for (int i = 0; i < count; i++) {
            indices[i] = (int) Math.round(i * (double) totalDays / count) % totalDays;
        }
        return indices;
    }
}
