package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ReplannerTest {

    private final LocalDate mon = LocalDate.of(2026, 9, 14);
    private final LocalDate tue = mon.plusDays(1);
    private final Replanner replanner = new Replanner(new PostponePolicy());

    @Test
    void cancelMarksBlockCancelled() {
        Block padel = new Block("padel", mon, LocalTime.of(19, 0), LocalTime.of(20, 30), "Padel",
            BlockKind.ADHOC, null, BlockStatus.PLANNED, 0, false, null);
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(padel), List.of()));

        ReplanResult result = replanner.apply(week, new CancelBlock("padel"));

        Block updated = findBlock(result, mon, "padel");
        assertThat(updated.status()).isEqualTo(BlockStatus.CANCELLED);
        assertThat(result.changes()).anyMatch(c -> c.contains("Padel"));
    }

    @Test
    void postponeMovesToALaterFreeSlotTheSameDay() {
        Block certBlock = new Block("cert", mon, LocalTime.of(18, 0), LocalTime.of(19, 0), "Claude certification",
            BlockKind.GOAL, "career-cert", BlockStatus.PLANNED, 0, false, null);
        Block freeSlot = new Block("free-1", mon, LocalTime.of(20, 0), LocalTime.of(22, 30), "Free",
            BlockKind.FREE, null, BlockStatus.PLANNED, 0, false, null);
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(certBlock, freeSlot), List.of()));

        ReplanResult result = replanner.apply(week, new PostponeBlock("cert", "not in the mood"));

        DayPlan monPlan = result.updatedPlans().get(mon);
        Block original = monPlan.blocks().stream().filter(b -> b.id().equals("cert")).findFirst().orElseThrow();
        assertThat(original.status()).isEqualTo(BlockStatus.POSTPONED);
        Block moved = monPlan.blocks().stream()
            .filter(b -> b.goalId() != null && b.goalId().equals("career-cert") && b.status() == BlockStatus.PLANNED)
            .findFirst().orElseThrow();
        assertThat(moved.start()).isEqualTo(LocalTime.of(20, 0));
        assertThat(moved.postponeCount()).isEqualTo(1);
    }

    @Test
    void postponeMovesToNextDayWhenTodayHasNoRoom() {
        Block certBlock = new Block("cert", mon, LocalTime.of(18, 0), LocalTime.of(19, 0), "Claude certification",
            BlockKind.GOAL, "career-cert", BlockStatus.PLANNED, 0, false, null);
        DayPlan monPlan = new DayPlan(mon, List.of(certBlock), List.of());
        Block tueFree = new Block("free-tue", tue, LocalTime.of(18, 0), LocalTime.of(19, 0), "Free",
            BlockKind.FREE, null, BlockStatus.PLANNED, 0, false, null);
        DayPlan tuePlan = new DayPlan(tue, List.of(tueFree), List.of());
        Map<LocalDate, DayPlan> week = new LinkedHashMap<>();
        week.put(mon, monPlan);
        week.put(tue, tuePlan);

        ReplanResult result = replanner.apply(week, new PostponeBlock("cert", "tired"));

        Block moved = result.updatedPlans().get(tue).blocks().stream()
            .filter(b -> "career-cert".equals(b.goalId()) && b.status() == BlockStatus.PLANNED)
            .findFirst().orElseThrow();
        assertThat(moved.start()).isEqualTo(LocalTime.of(18, 0));
    }

    @Test
    void thirdPostponeFlagsTheBlock() {
        Block certBlock = new Block("cert", mon, LocalTime.of(18, 0), LocalTime.of(19, 0), "Claude certification",
            BlockKind.GOAL, "career-cert", BlockStatus.PLANNED, 2, false, null);
        Block freeSlot = new Block("free-1", mon, LocalTime.of(20, 0), LocalTime.of(22, 30), "Free",
            BlockKind.FREE, null, BlockStatus.PLANNED, 0, false, null);
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(certBlock, freeSlot), List.of()));

        ReplanResult result = replanner.apply(week, new PostponeBlock("cert", "again"));

        Block moved = result.updatedPlans().get(mon).blocks().stream()
            .filter(b -> "career-cert".equals(b.goalId()) && b.status() == BlockStatus.PLANNED)
            .findFirst().orElseThrow();
        assertThat(moved.postponeCount()).isEqualTo(3);
        assertThat(moved.flagged()).isTrue();
    }

    @Test
    void markDoneSetsStatusDone() {
        Block gym = new Block("gym", mon, LocalTime.of(7, 0), LocalTime.of(8, 0), "Gym",
            BlockKind.GOAL, "health-gym", BlockStatus.PLANNED, 0, false, null);
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(gym), List.of()));

        ReplanResult result = replanner.apply(week, new MarkDone("gym"));

        assertThat(findBlock(result, mon, "gym").status()).isEqualTo(BlockStatus.DONE);
    }

    @Test
    void addAdhocInsertsNewBlock() {
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(), List.of()));

        ReplanResult result = replanner.apply(week, new AddAdhoc(mon, "Padel", LocalTime.of(19, 0), LocalTime.of(20, 30)));

        assertThat(result.updatedPlans().get(mon).blocks()).anyMatch(b -> b.title().equals("Padel") && b.kind() == BlockKind.ADHOC);
    }

    @Test
    void twoAdhocsAtTheSameStartBothSurvive() {
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(), List.of()));

        ReplanResult first = replanner.apply(week, new AddAdhoc(mon, "Padel", LocalTime.of(21, 0), LocalTime.of(22, 0)));
        ReplanResult second = replanner.apply(first.updatedPlans(),
            new AddAdhoc(mon, "Dinner", LocalTime.of(21, 0), LocalTime.of(21, 30)));

        List<Block> adhocs = second.updatedPlans().get(mon).blocks().stream()
            .filter(b -> b.kind() == BlockKind.ADHOC).toList();
        assertThat(adhocs).extracting(Block::id)
            .containsExactlyInAnyOrder("adhoc-" + mon + "-21:00", "adhoc-" + mon + "-21:00~2");
        assertThat(adhocs).extracting(Block::title).containsExactlyInAnyOrder("Padel", "Dinner");
        assertThat(adhocs).filteredOn(b -> b.id().equals("adhoc-" + mon + "-21:00"))
            .extracting(Block::title).containsExactly("Padel");
    }

    @Test
    void addAdhocConsumesTheFreeTimeItOverlaps() {
        Block free = new Block("free-1", mon, LocalTime.of(20, 0), LocalTime.of(22, 30), "Free",
            BlockKind.FREE, null, BlockStatus.PLANNED, 0, false, null);
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(free), List.of()));

        ReplanResult result = replanner.apply(week, new AddAdhoc(mon, "Padel", LocalTime.of(21, 0), LocalTime.of(22, 0)));

        List<Block> blocks = result.updatedPlans().get(mon).blocks();
        assertThat(blocks).hasSize(3);
        assertThat(blocks).noneMatch(b -> b.id().equals("free-1"));
        assertThat(blocks).anyMatch(b -> b.id().equals("free-1-before") && b.kind() == BlockKind.FREE
            && b.start().equals(LocalTime.of(20, 0)) && b.end().equals(LocalTime.of(21, 0)));
        assertThat(blocks).anyMatch(b -> b.id().equals("free-1-after") && b.kind() == BlockKind.FREE
            && b.start().equals(LocalTime.of(22, 0)) && b.end().equals(LocalTime.of(22, 30)));
        assertThat(blocks).anyMatch(b -> b.kind() == BlockKind.ADHOC && b.title().equals("Padel"));
    }

    @Test
    void addAdhocThatExactlyCoversAFreeBlockRemovesIt() {
        Block free = new Block("free-1", mon, LocalTime.of(21, 0), LocalTime.of(22, 30), "Free",
            BlockKind.FREE, null, BlockStatus.PLANNED, 0, false, null);
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(free), List.of()));

        ReplanResult result = replanner.apply(week, new AddAdhoc(mon, "Padel", LocalTime.of(21, 0), LocalTime.of(22, 30)));

        List<Block> blocks = result.updatedPlans().get(mon).blocks();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).kind()).isEqualTo(BlockKind.ADHOC);
    }

    @Test
    void addAdhocLeavesNonFreeBlocksUntouched() {
        Block work = new Block("work", mon, LocalTime.of(8, 30), LocalTime.of(17, 0), "Work",
            BlockKind.FIXED, null, BlockStatus.PLANNED, 0, false, null);
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(work), List.of()));

        ReplanResult result = replanner.apply(week, new AddAdhoc(mon, "Call", LocalTime.of(9, 0), LocalTime.of(10, 0)));

        List<Block> blocks = result.updatedPlans().get(mon).blocks();
        assertThat(blocks).hasSize(2);
        assertThat(blocks).contains(work);
    }

    @Test
    void postponeRefusesAFixedBlock() {
        Block work = new Block("work", mon, LocalTime.of(8, 30), LocalTime.of(17, 0), "Work",
            BlockKind.FIXED, null, BlockStatus.PLANNED, 0, false, null);
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(work), List.of()));

        ReplanResult result = replanner.apply(week, new PostponeBlock("work", "not today"));

        Block unchanged = findBlock(result, mon, "work");
        assertThat(unchanged.status()).isEqualTo(BlockStatus.PLANNED);
        assertThat(unchanged.start()).isEqualTo(LocalTime.of(8, 30));
        assertThat(result.changes()).anyMatch(c -> c.contains("Cannot postpone"));
    }

    @Test
    void postponeRefusesADoneBlock() {
        Block gym = new Block("gym", mon, LocalTime.of(7, 0), LocalTime.of(8, 0), "Gym",
            BlockKind.GOAL, "health-gym", BlockStatus.DONE, 0, false, null);
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(gym), List.of()));

        ReplanResult result = replanner.apply(week, new PostponeBlock("gym", "already did it, but retry?"));

        Block unchanged = findBlock(result, mon, "gym");
        assertThat(unchanged.status()).isEqualTo(BlockStatus.DONE);
        assertThat(result.changes()).anyMatch(c -> c.contains("Cannot postpone"));
    }

    @Test
    void postponeRefusesAnAlreadyPostponedBlock() {
        Block cert = new Block("cert", mon, LocalTime.of(18, 0), LocalTime.of(19, 0), "Claude certification",
            BlockKind.GOAL, "career-cert", BlockStatus.POSTPONED, 0, false, "tired");
        Block freeSlot = new Block("free-1", mon, LocalTime.of(20, 0), LocalTime.of(22, 30), "Free",
            BlockKind.FREE, null, BlockStatus.PLANNED, 0, false, null);
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(cert, freeSlot), List.of()));

        ReplanResult result = replanner.apply(week, new PostponeBlock("cert", "again"));

        assertThat(result.changes()).containsExactly("Cannot postpone a postponed block (Claude certification)");
        assertThat(result.updatedPlans().get(mon).blocks()).containsExactlyInAnyOrder(cert, freeSlot);
    }

    @Test
    void postponeRefusesACancelledBlock() {
        Block padel = new Block("padel", mon, LocalTime.of(19, 0), LocalTime.of(20, 30), "Padel",
            BlockKind.ADHOC, null, BlockStatus.CANCELLED, 0, false, null);
        Block freeSlot = new Block("free-1", mon, LocalTime.of(21, 0), LocalTime.of(22, 30), "Free",
            BlockKind.FREE, null, BlockStatus.PLANNED, 0, false, null);
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(padel, freeSlot), List.of()));

        ReplanResult result = replanner.apply(week, new PostponeBlock("padel", "again"));

        assertThat(result.changes()).containsExactly("Cannot postpone a cancelled block (Padel)");
        assertThat(result.updatedPlans().get(mon).blocks()).containsExactlyInAnyOrder(padel, freeSlot);
    }

    @Test
    void cancelFreesTheSlot() {
        Block padel = new Block("padel", mon, LocalTime.of(19, 0), LocalTime.of(20, 30), "Padel",
            BlockKind.ADHOC, null, BlockStatus.PLANNED, 0, false, null);
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(padel), List.of()));

        ReplanResult result = replanner.apply(week, new CancelBlock("padel"));

        List<Block> blocks = result.updatedPlans().get(mon).blocks();
        assertThat(blocks).anyMatch(b -> b.id().equals("padel") && b.status() == BlockStatus.CANCELLED);
        assertThat(blocks).anyMatch(b -> b.kind() == BlockKind.FREE
            && b.start().equals(LocalTime.of(19, 0)) && b.end().equals(LocalTime.of(20, 30)));
    }

    private Map<LocalDate, DayPlan> weekOf(LocalDate date, DayPlan plan) {
        Map<LocalDate, DayPlan> map = new LinkedHashMap<>();
        map.put(date, plan);
        return map;
    }

    private Block findBlock(ReplanResult result, LocalDate date, String id) {
        return result.updatedPlans().get(date).blocks().stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
    }
}
