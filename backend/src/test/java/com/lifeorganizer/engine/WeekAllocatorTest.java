package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;
import org.junit.jupiter.api.Test;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class WeekAllocatorTest {

    private final LocalDate monday = LocalDate.of(2026, 9, 14).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    private final Category health = Category.of("Health");
    private final Category career = Category.of("Career");
    private final WeekAllocator allocator = new WeekAllocator(new GoalWeighting(), EngineSettings.defaults());

    @Test
    void spreadsFrequencyQuotaAcrossFourDistinctWeekDays() {
        Goal gym = Goal.withDefaults("health-gym", health, "Gym");
        FrequencyQuota quota = new FrequencyQuota("Gym", 4, 60, "health-gym");

        WeekPlan plan = allocator.allocate(List.of(gym), List.of(quota), monday);

        long gymDays = plan.unitsByDate().values().stream()
            .filter(units -> units.stream().anyMatch(u -> u.goal().id().equals("health-gym")))
            .count();
        assertThat(gymDays).isEqualTo(4);
        assertThat(plan.unitsByDate()).hasSize(7);
    }

    @Test
    void splitsDurationQuotaAcrossGoalsByPriorityThenIntoDailyChunks() {
        Goal cert = new Goal("career-cert", career, "Claude certification", 5, null, 60, Energy.MEDIUM, PreferredTime.EVENING);
        Goal azure = new Goal("career-azure", career, "Azure AI", 3, null, 60, Energy.MEDIUM, PreferredTime.EVENING);
        DurationQuota learning = new DurationQuota("Learning", 300, "career");

        WeekPlan plan = allocator.allocate(List.of(cert, azure), List.of(learning), monday);

        int certMinutes = plan.unitsByDate().values().stream()
            .flatMap(List::stream)
            .filter(u -> u.goal().id().equals("career-cert"))
            .mapToInt(Unit::minutes).sum();
        int azureMinutes = plan.unitsByDate().values().stream()
            .flatMap(List::stream)
            .filter(u -> u.goal().id().equals("career-azure"))
            .mapToInt(Unit::minutes).sum();

        assertThat(certMinutes).isGreaterThan(azureMinutes);
        assertThat(certMinutes + azureMinutes).isEqualTo(300);
        // no single day gets more than one 60-minute chunk of the same goal
        plan.unitsByDate().values().forEach(units ->
            assertThat(units.stream().filter(u -> u.goal().id().equals("career-cert")).count()).isLessThanOrEqualTo(1));
    }

    @Test
    void warnsWhenQuotaReferencesUnknownGoal() {
        FrequencyQuota quota = new FrequencyQuota("Gym", 4, 60, "does-not-exist");

        WeekPlan plan = allocator.allocate(List.of(), List.of(quota), monday);

        assertThat(plan.warnings()).anyMatch(w -> w.contains("does-not-exist"));
        assertThat(plan.unitsByDate().values()).allMatch(List::isEmpty);
    }

    @Test
    void everyWeekdayKeyIsPresentEvenWithNoConstraints() {
        WeekPlan plan = allocator.allocate(List.of(), List.of(), monday);

        Set<DayOfWeek> days = plan.unitsByDate().keySet().stream().map(LocalDate::getDayOfWeek).collect(java.util.stream.Collectors.toSet());
        assertThat(days).isEqualTo(EnumSet.allOf(DayOfWeek.class));
    }

    @Test
    void warnsWhenDurationQuotaCannotFitAllMinutes() {
        Goal cert = new Goal("career-cert", career, "Claude certification", 5, null, 60, Energy.MEDIUM, PreferredTime.EVENING);
        DurationQuota learning = new DurationQuota("Learning", 480, "career");

        WeekPlan plan = allocator.allocate(List.of(cert), List.of(learning), monday);

        // 480 minutes / 60 minutes per block = 8 blocks needed, but only 7 days available
        // so 60 minutes should be dropped
        assertThat(plan.warnings()).anyMatch(w -> w.contains("ran out of days"));
        assertThat(plan.warnings()).anyMatch(w -> w.contains("60"));

        int certMinutes = plan.unitsByDate().values().stream()
            .flatMap(List::stream)
            .filter(u -> u.goal().id().equals("career-cert"))
            .mapToInt(Unit::minutes).sum();
        assertThat(certMinutes).isEqualTo(420); // 7 blocks * 60 minutes
    }

    @Test
    void duplicateGoalIdWarnsInsteadOfCrashing() {
        Goal first = Goal.withDefaults("health-gym", health, "Gym");
        Goal duplicate = Goal.withDefaults("health-gym", health, "Gym (duplicate)");
        FrequencyQuota quota = new FrequencyQuota("Gym", 4, 60, "health-gym");

        WeekPlan plan = allocator.allocate(List.of(first, duplicate), List.of(quota), monday);

        assertThat(plan.warnings()).anyMatch(w -> w.contains("Duplicate goal id 'health-gym'"));
    }
}
