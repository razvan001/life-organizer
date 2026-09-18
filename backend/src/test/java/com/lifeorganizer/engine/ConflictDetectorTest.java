package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ConflictDetectorTest {

    private final LocalDate date = LocalDate.of(2026, 9, 14);
    private final ConflictDetector detector = new ConflictDetector();

    @Test
    void detectsOverlappingBlocks() {
        Block a = new Block("a", date, LocalTime.of(9, 0), LocalTime.of(10, 0), "A", BlockKind.GOAL, "g1", BlockStatus.PLANNED, 0, false, null);
        Block b = new Block("b", date, LocalTime.of(9, 30), LocalTime.of(10, 30), "B", BlockKind.GOAL, "g2", BlockStatus.PLANNED, 0, false, null);
        DayPlan plan = new DayPlan(date, List.of(a, b), List.of());

        List<Conflict> conflicts = detector.detectInDay(plan);

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).type()).isEqualTo(ConflictType.OVERLAP);
        assertThat(conflicts.get(0).relatedBlockIds()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void noConflictWhenBlocksDoNotOverlap() {
        Block a = new Block("a", date, LocalTime.of(9, 0), LocalTime.of(10, 0), "A", BlockKind.GOAL, "g1", BlockStatus.PLANNED, 0, false, null);
        Block b = new Block("b", date, LocalTime.of(10, 0), LocalTime.of(11, 0), "B", BlockKind.GOAL, "g2", BlockStatus.PLANNED, 0, false, null);
        DayPlan plan = new DayPlan(date, List.of(a, b), List.of());

        assertThat(detector.detectInDay(plan)).isEmpty();
    }

    @Test
    void detectsUnfittableGoalAcrossTheWeek() {
        Category career = Category.of("Career");
        Goal cert = Goal.withDefaults("career-cert", career, "Claude certification");
        Unit unit = new Unit("u1", cert, 60, PreferredTime.EVENING, null);
        WeekPlan weekPlan = new WeekPlan(Map.of(date, List.of(unit)), List.of());

        List<Conflict> conflicts = detector.detectUnfittable(weekPlan, Map.of("career-cert", 300));

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).type()).isEqualTo(ConflictType.QUOTA_UNFITTABLE);
        assertThat(conflicts.get(0).message()).contains("Claude certification");
    }

    @Test
    void noUnfittableConflictWhenRequestedMinutesAreMet() {
        Category career = Category.of("Career");
        Goal cert = Goal.withDefaults("career-cert", career, "Claude certification");
        Unit unit = new Unit("u1", cert, 300, PreferredTime.EVENING, null);
        WeekPlan weekPlan = new WeekPlan(Map.of(date, List.of(unit)), List.of());

        assertThat(detector.detectUnfittable(weekPlan, Map.of("career-cert", 300))).isEmpty();
    }
}
