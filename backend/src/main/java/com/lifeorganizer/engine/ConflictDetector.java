package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;

import java.util.*;

public final class ConflictDetector {

    public List<Conflict> detectInDay(DayPlan plan) {
        List<Conflict> conflicts = new ArrayList<>();
        List<Block> blocks = plan.blocksInOrder();
        for (int i = 0; i < blocks.size(); i++) {
            for (int j = i + 1; j < blocks.size(); j++) {
                Block a = blocks.get(i);
                Block b = blocks.get(j);
                if (a.overlaps(b)) {
                    conflicts.add(new Conflict(ConflictType.OVERLAP,
                        a.title() + " overlaps with " + b.title() + " on " + plan.date(),
                        List.of(a.id(), b.id())));
                }
            }
        }
        return conflicts;
    }

    public List<Conflict> detectUnfittable(WeekPlan weekPlan, Map<String, Integer> requestedMinutesByGoalId) {
        Map<String, Integer> scheduledByGoalId = new LinkedHashMap<>();
        Map<String, Goal> goalById = new LinkedHashMap<>();
        for (List<Unit> units : weekPlan.unitsByDate().values()) {
            for (Unit unit : units) {
                scheduledByGoalId.merge(unit.goal().id(), unit.minutes(), Integer::sum);
                goalById.putIfAbsent(unit.goal().id(), unit.goal());
            }
        }

        List<Conflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, Integer> requested : requestedMinutesByGoalId.entrySet()) {
            int scheduled = scheduledByGoalId.getOrDefault(requested.getKey(), 0);
            if (scheduled < requested.getValue()) {
                String name = goalById.containsKey(requested.getKey()) ? goalById.get(requested.getKey()).name() : requested.getKey();
                conflicts.add(new Conflict(ConflictType.QUOTA_UNFITTABLE,
                    name + " is short " + (requested.getValue() - scheduled) + " minutes this week",
                    List.of(requested.getKey())));
            }
        }
        return conflicts;
    }
}
