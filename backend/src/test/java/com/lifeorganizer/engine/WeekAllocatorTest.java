package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;
import org.junit.jupiter.api.Test;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;

class WeekAllocatorTest {

    private final LocalDate monday = LocalDate.of(2026, 9, 14).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    private final Category health = Category.of("Health");
    private final WeekAllocator allocator = new WeekAllocator();

    private Goal goal(String id, int minutesPerWeek) {
        return new Goal(id, health, id, minutesPerWeek, 60, Energy.MEDIUM, PreferredTime.ANY);
    }

    /** Minutes of each of the goal's units, by day, for the days that have one. */
    private static Map<LocalDate, Integer> minutesByDay(WeekPlan plan, String goalId) {
        return plan.unitsByDate().entrySet().stream()
            .filter(entry -> entry.getValue().stream().anyMatch(u -> u.goal().id().equals(goalId)))
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().stream()
                .filter(u -> u.goal().id().equals(goalId)).mapToInt(Unit::minutes).sum()));
    }

    @Test
    void splitsHoursIntoSessionsOfTheGoalsBlockLength() {
        WeekPlan plan = allocator.allocate(List.of(goal("gym", 150)), List.of(), monday);

        assertThat(minutesByDay(plan, "gym")).containsExactlyInAnyOrderEntriesOf(Map.of(
            monday, 60, monday.plusDays(1), 60, monday.plusDays(2), 30));
        assertThat(plan.warnings()).isEmpty();
    }

    @Test
    void atMostOneSessionPerGoalPerDay() {
        WeekPlan plan = allocator.allocate(List.of(goal("gym", 600)), List.of(), monday);

        assertThat(minutesByDay(plan, "gym")).hasSize(7).allSatisfy((date, minutes) -> assertThat(minutes).isEqualTo(60));
        assertThat(plan.warnings()).containsExactly("gym could not fit 180 of its 600 weekly minutes (ran out of days)");
    }

    @Test
    void spreadsGoalsOverTheWeekWithASharedCursor() {
        WeekPlan plan = allocator.allocate(List.of(goal("a", 120), goal("b", 120)), List.of(), monday);

        assertThat(minutesByDay(plan, "a")).containsOnlyKeys(monday, monday.plusDays(1));
        assertThat(minutesByDay(plan, "b")).containsOnlyKeys(monday.plusDays(2), monday.plusDays(3));
    }

    @Test
    void aGoalWithZeroHoursGetsNothing() {
        WeekPlan plan = allocator.allocate(List.of(goal("chess", 0)), List.of(), monday);

        assertThat(plan.unitsByDate().values()).allSatisfy(units -> assertThat(units).isEmpty());
        assertThat(plan.warnings()).isEmpty();
    }

    @Test
    void unitsOfADayKeepSetupOrder() {
        WeekPlan plan = allocator.allocate(List.of(goal("b", 420), goal("a", 420)), List.of(), monday);

        assertThat(plan.unitsByDate().values()).allSatisfy(units ->
            assertThat(units).extracting(u -> u.goal().id()).containsExactly("b", "a"));
    }

    @Test
    void fixedBlocksMakeNoUnits() {
        FixedBlock work = new FixedBlock("Work", java.time.LocalTime.of(9, 0), java.time.LocalTime.of(17, 0),
            EnumSet.of(DayOfWeek.MONDAY));

        WeekPlan plan = allocator.allocate(List.of(), List.of(work), monday);

        assertThat(plan.unitsByDate().values()).allSatisfy(units -> assertThat(units).isEmpty());
    }

    @Test
    void everyWeekdayKeyIsPresentEvenWithNoGoals() {
        WeekPlan plan = allocator.allocate(List.of(), List.of(), monday);

        Set<DayOfWeek> days = plan.unitsByDate().keySet().stream().map(LocalDate::getDayOfWeek).collect(Collectors.toSet());
        assertThat(days).isEqualTo(EnumSet.allOf(DayOfWeek.class));
    }

    @Test
    void duplicateGoalIdWarnsInsteadOfCrashing() {
        WeekPlan plan = allocator.allocate(List.of(goal("gym", 60), goal("gym", 120)), List.of(), monday);

        assertThat(plan.warnings()).anyMatch(w -> w.contains("Duplicate goal id 'gym'"));
        assertThat(minutesByDay(plan, "gym").values()).containsExactly(60);
    }
}
