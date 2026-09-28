package com.lifeorganizer.memory.history;

import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.DayPlan;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DaySummarizerTest {

    private static final LocalDate THU = LocalDate.of(2026, 9, 24);

    private static Block block(String title, int hour, BlockKind kind, BlockStatus status, String reason) {
        return new Block(title + "-" + hour, THU, LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0), title, kind,
            null, status, 0, false, reason);
    }

    @Test
    void titleIsShortWeekdayAndDate() {
        assertThat(DaySummarizer.title(THU)).isEqualTo("Thu 2026-09-24");
    }

    @Test
    void summarizesGoalAndAdhocOutcomesInTimeOrder() {
        DayPlan day = new DayPlan(THU, List.of(
            block("Social", 20, BlockKind.GOAL, BlockStatus.CANCELLED, null),
            block("Gym", 7, BlockKind.GOAL, BlockStatus.DONE, null),
            block("Claude cert", 18, BlockKind.GOAL, BlockStatus.POSTPONED, "tired after gym"),
            block("Dentist", 12, BlockKind.ADHOC, BlockStatus.PLANNED, null)), List.of());

        assertThat(DaySummarizer.summarize(day)).contains(
            "Thu 2026-09-24: Gym done 07:00; Dentist not marked done 12:00; "
                + "Claude cert postponed 18:00 (reason: tired after gym); Social cancelled 20:00");
    }

    @Test
    void freeRoutineAndFixedBlocksAreLeftOut() {
        DayPlan day = new DayPlan(THU, List.of(
            block("Work", 9, BlockKind.FIXED, BlockStatus.PLANNED, null),
            block("Dinner", 17, BlockKind.ROUTINE, BlockStatus.PLANNED, null),
            block("Free", 19, BlockKind.FREE, BlockStatus.PLANNED, null)), List.of());

        assertThat(DaySummarizer.summarize(day)).isEmpty();
    }

    @Test
    void aCancelledBlockKeepsItsReason() {
        DayPlan day = new DayPlan(THU, List.of(block("Padel", 19, BlockKind.GOAL, BlockStatus.CANCELLED, "rain")), List.of());

        assertThat(DaySummarizer.summarize(day)).contains("Thu 2026-09-24: Padel cancelled 19:00 (reason: rain)");
    }
}
