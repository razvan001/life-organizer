package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;
import org.junit.jupiter.api.Test;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumSet;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DaySchedulerTest {

    private final LocalDate monday = LocalDate.of(2026, 9, 14).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    private final DayScheduler scheduler = new DayScheduler(EngineSettings.defaults());

    private static final EnumSet<DayOfWeek> WEEKDAYS = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
    private static final EnumSet<DayOfWeek> DAILY = EnumSet.allOf(DayOfWeek.class);

    @Test
    void producesTheExampleTimelineShape() {
        Category health = Category.of("Health");
        Category career = Category.of("Career");
        Category social = Category.of("Social");

        Goal gym = Goal.withDefaults("health-gym", health, "Gym");
        Goal cert = new Goal("career-cert", career, "Claude certification", 0, 60, Energy.MEDIUM, PreferredTime.EVENING);
        Goal socialGoal = Goal.withDefaults("social-dating", social, "Dating / events");

        List<Constraint> constraints = List.of(
            new FixedBlock("Work", LocalTime.of(8, 30), LocalTime.of(17, 0), WEEKDAYS),
            new FixedBlock("Sleep", LocalTime.of(23, 30), LocalTime.of(7, 0), DAILY)
        );

        List<Unit> units = List.of(
            new Unit("gym-unit", gym, 60, PreferredTime.MORNING, null),
            new Unit("cert-unit", cert, 60, PreferredTime.EVENING, null),
            new Unit("social-unit", socialGoal, 60, PreferredTime.EVENING, LocalTime.of(20, 0))
        );

        DayPlan plan = scheduler.schedule(monday, units, constraints, List.of());
        List<Block> nonFree = plan.blocksInOrder().stream().filter(b -> b.kind() != BlockKind.FREE).toList();

        // The user's example is illustrative (it doesn't show every leftover Free
        // fragment), so this asserts the named, timed activities they specified,
        // not the full block list.
        assertThat(nonFree).extracting(b -> b.start() + " " + b.title()).containsExactly(
            "07:00 Gym",
            "08:30 Work",
            "17:00 Dinner / break",
            "18:00 Claude certification",
            "20:00 Dating / events",
            "22:30 Wind down"
        );

        Block certBlock = nonFree.stream().filter(b -> b.title().equals("Claude certification")).findFirst().orElseThrow();
        assertThat(certBlock.durationMinutes()).isEqualTo(60);
        assertThat(certBlock.end()).isEqualTo(LocalTime.of(19, 0));

        // The example explicitly calls out a 19:00 Free slot between cert and social.
        assertThat(plan.blocksInOrder()).anyMatch(b -> b.kind() == BlockKind.FREE && b.start().equals(LocalTime.of(19, 0)));
    }

    @Test
    void unfilledGapsBecomeFreeBlocksOfAtLeastGridMinutes() {
        List<Constraint> constraints = List.of(
            new FixedBlock("Sleep", LocalTime.of(23, 30), LocalTime.of(7, 0), DAILY)
        );

        DayPlan plan = scheduler.schedule(monday, List.of(), constraints, List.of());

        List<Block> freeBlocks = plan.blocksInOrder().stream().filter(b -> b.kind() == BlockKind.FREE).toList();
        assertThat(freeBlocks).hasSize(1);
        assertThat(freeBlocks.get(0).start()).isEqualTo(LocalTime.of(7, 0));
        assertThat(freeBlocks.get(0).end()).isEqualTo(LocalTime.of(22, 30));
        assertThat(plan.blocksInOrder()).anyMatch(b -> b.kind() == BlockKind.ROUTINE && b.title().equals("Wind down"));
    }

    @Test
    void alreadyPlacedBlocksAreKeptAndNotOverlapped() {
        Block doneGym = new Block("done-gym", monday, LocalTime.of(7, 0), LocalTime.of(8, 0), "Gym",
            BlockKind.GOAL, "health-gym", BlockStatus.DONE, 0, false, null);
        List<Constraint> constraints = List.of(
            new FixedBlock("Sleep", LocalTime.of(23, 30), LocalTime.of(7, 0), DAILY)
        );

        DayPlan plan = scheduler.schedule(monday, List.of(), constraints, List.of(doneGym));

        assertThat(plan.blocksInOrder()).contains(doneGym);
        assertThat(plan.blocksInOrder()).noneMatch(b -> !b.id().equals("done-gym") && b.overlaps(doneGym));
    }

    @Test
    void overnightNonSleepFixedBlockIsSkippedWithWarningInsteadOfCrashing() {
        List<Constraint> constraints = List.of(
            new FixedBlock("Sleep", LocalTime.of(23, 30), LocalTime.of(7, 0), DAILY),
            new FixedBlock("Night shift", LocalTime.of(22, 0), LocalTime.of(6, 0), DAILY)
        );

        DayPlan plan = scheduler.schedule(monday, List.of(), constraints, List.of());

        assertThat(plan.blocksInOrder()).noneMatch(b -> b.title().equals("Night shift"));
        assertThat(plan.warnings()).anyMatch(w -> w.contains("Night shift"));
    }
}
