package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;
import com.lifeorganizer.setup.ConstraintParseResult;
import com.lifeorganizer.setup.ConstraintParser;
import com.lifeorganizer.setup.GoalTreeParser;
import org.junit.jupiter.api.Test;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real GoalTreeParser -> ConstraintParser -> WeekAllocator -> DayScheduler
 * pipeline end to end on the user's exact goals/constraints text, catching composition
 * bugs that pass at each stage in isolation but break once wired together (see the
 * Phase 1a whole-branch review: duration-quota work piled entirely onto Monday and
 * silently dropped the Social evening quota).
 */
class PipelineIntegrationTest {

    private static final String USER_GOAL_TREE = """
        Goals
        ├── Career
        │   ├── Claude certification
        │   ├── Azure AI
        │   └── System design
        ├── Projects
        │   └── RAG showcase
        ├── Health
        │   ├── Gym
        │   └── Padel / football
        ├── Social
        │   └── Dating / events
        ├── Finance
        │   └── Investing
        └── Hobbies
            └── Chess
        """;

    private static final String USER_CONSTRAINTS = """
        Work:       08:30-17:00
        Gym:        4x/week
        Sleep:      23:30-07:00
        Learning:   5h/week
        Side project: 4h/week
        Social:     2 evenings/week
        """;

    private final LocalDate monday = LocalDate.of(2026, 9, 14).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

    @Test
    void realPipelineSpreadsDurationQuotasAcrossTheWeekAndPlacesEveryQuota() {
        GoalTree tree = new GoalTreeParser().parse(USER_GOAL_TREE);
        ConstraintParseResult parsedConstraints = new ConstraintParser().parse(USER_CONSTRAINTS, tree);
        assertThat(parsedConstraints.warnings()).isEmpty();

        WeekPlan weekPlan = new WeekAllocator(new GoalWeighting(), EngineSettings.defaults())
            .allocate(tree.goals(), parsedConstraints.constraints(), monday);

        // No goal's weekly demand should be silently dropped: WeekAllocator only warns
        // when a single goal can't fit within 7 days, which should not happen here
        // (Learning 5h across 3 goals and Side project 4h on 1 goal both comfortably fit).
        assertThat(weekPlan.warnings()).isEmpty();

        // Duration-quota work must be spread, not piled onto one day: every day of the
        // week should carry at least some scheduled work (the regression this guards
        // against left Sunday at 0 minutes while Monday absorbed everything).
        for (LocalDate date : weekPlan.unitsByDate().keySet()) {
            int dayMinutes = weekPlan.unitsByDate().get(date).stream().mapToInt(Unit::minutes).sum();
            assertThat(dayMinutes)
                .withFailMessage("Expected some work scheduled on %s, but the day was empty - "
                    + "duration quotas are piling onto earlier days instead of spreading", date)
                .isGreaterThan(0);
        }

        // Every goal referenced by a quota must appear on at least one day somewhere in
        // the week (the regression this guards against silently dropped the Social
        // evening-quota goal once Monday's earlier chunks used up the day's placement
        // order - but at the WeekAllocator level, Social's unit must exist regardless).
        List<Unit> allUnits = weekPlan.unitsByDate().values().stream().flatMap(List::stream).toList();
        assertThat(allUnits).extracting(u -> u.goal().id()).contains("social-dating-events");
    }

    @Test
    void realPipelineSchedulesAFullWeekWithoutCrashing() {
        GoalTree tree = new GoalTreeParser().parse(USER_GOAL_TREE);
        ConstraintParseResult parsedConstraints = new ConstraintParser().parse(USER_CONSTRAINTS, tree);
        WeekPlan weekPlan = new WeekAllocator(new GoalWeighting(), EngineSettings.defaults())
            .allocate(tree.goals(), parsedConstraints.constraints(), monday);
        DayScheduler scheduler = new DayScheduler(EngineSettings.defaults());

        for (Map.Entry<LocalDate, List<Unit>> entry : weekPlan.unitsByDate().entrySet()) {
            DayPlan dayPlan = scheduler.schedule(entry.getKey(), entry.getValue(), parsedConstraints.constraints(), List.of());
            assertThat(dayPlan.blocks()).isNotEmpty();
        }
    }
}
