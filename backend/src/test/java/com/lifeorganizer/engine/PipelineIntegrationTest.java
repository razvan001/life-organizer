package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;
import com.lifeorganizer.support.ExampleSetup;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises WeekAllocator -> DayScheduler end to end on the user's example setup, catching composition bugs that
 * pass at each stage in isolation but break once wired together (see the Phase 1a whole-branch review: weekly work
 * piled entirely onto Monday and silently dropped a goal).
 */
class PipelineIntegrationTest {

    private final LocalDate monday = ExampleSetup.MONDAY;
    private final WeekAllocator allocator = new WeekAllocator();

    @Test
    void realPipelineSpreadsWeeklyHoursAcrossTheWeekAndPlacesEveryGoalWithHours() {
        GoalTree tree = ExampleSetup.tree();

        WeekPlan weekPlan = allocator.allocate(tree.goals(), ExampleSetup.constraints(), monday);

        // Four goals with one hour a day each fit: nothing is dropped.
        assertThat(weekPlan.warnings()).isEmpty();

        // Weekly work is spread, not piled onto one day: every day of the week carries some of it.
        for (LocalDate date : weekPlan.unitsByDate().keySet()) {
            int dayMinutes = weekPlan.unitsByDate().get(date).stream().mapToInt(Unit::minutes).sum();
            assertThat(dayMinutes)
                .withFailMessage("Expected some work scheduled on %s, but the day was empty", date)
                .isGreaterThan(0);
        }

        // Every goal with hours appears somewhere in the week; goals without hours do not.
        List<Unit> allUnits = weekPlan.unitsByDate().values().stream().flatMap(List::stream).toList();
        List<String> withHours = tree.goals().stream().filter(g -> g.minutesPerWeek() > 0).map(Goal::id).toList();
        assertThat(allUnits).extracting(u -> u.goal().id()).containsAll(withHours)
            .doesNotContain("hobbies-chess", "finance-investing");
    }

    @Test
    void realPipelineSchedulesAFullWeekWithoutCrashing() {
        WeekPlan weekPlan = allocator.allocate(ExampleSetup.tree().goals(), ExampleSetup.constraints(), monday);
        DayScheduler scheduler = new DayScheduler(EngineSettings.defaults());

        for (Map.Entry<LocalDate, List<Unit>> entry : weekPlan.unitsByDate().entrySet()) {
            DayPlan dayPlan = scheduler.schedule(entry.getKey(), entry.getValue(), ExampleSetup.constraints(), List.of());
            assertThat(dayPlan.blocks()).isNotEmpty();
        }
    }
}
