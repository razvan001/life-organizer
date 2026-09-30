package com.lifeorganizer.ai.agent;

import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanDiffTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 30);

    private static Block block(String id, String title, int hour, BlockStatus status) {
        return new Block(id, DAY, LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0), title, BlockKind.GOAL, null,
            status, 0, false, null);
    }

    @Test
    void listsNewMovedAndStatusChangedBlocksOnly() {
        List<Block> before = List.of(block("a", "Gym", 7, BlockStatus.PLANNED), block("b", "Study", 18, BlockStatus.PLANNED),
            block("c", "Social", 20, BlockStatus.PLANNED));
        List<Block> after = List.of(block("a", "Gym", 7, BlockStatus.CANCELLED), block("b", "Study", 19, BlockStatus.PLANNED),
            block("c", "Social", 20, BlockStatus.PLANNED), block("d", "Movie", 21, BlockStatus.PLANNED));

        assertThat(PlanDiff.lines(before, after)).containsExactly(
            "Gym 07:00 -> CANCELLED 07:00",
            "Study 18:00 -> PLANNED 19:00",
            "Movie 21:00-22:00 -> new");
    }
}
