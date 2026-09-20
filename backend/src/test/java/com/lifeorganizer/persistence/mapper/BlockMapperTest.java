package com.lifeorganizer.persistence.mapper;

import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.persistence.BlockEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class BlockMapperTest {

    private static final LocalDate MON = LocalDate.of(2026, 9, 14);

    @Test
    void blockWithEveryFieldSetRoundTrips() {
        Block block = new Block("cert-p1", MON, LocalTime.of(21, 0), LocalTime.of(22, 0), "Claude certification",
            BlockKind.GOAL, "career-claude-certification", BlockStatus.POSTPONED, 2, true, "tired");

        BlockEntity entity = BlockMapper.toEntity(block);

        assertThat(entity.getKind()).isEqualTo("GOAL");
        assertThat(entity.getStatus()).isEqualTo("POSTPONED");
        assertThat(entity.getPostponeCount()).isEqualTo(2);
        assertThat(entity.isFlagged()).isTrue();
        assertThat(BlockMapper.toDomain(entity)).isEqualTo(block);
    }

    @Test
    void blockWithNullGoalAndReasonRoundTrips() {
        Block block = new Block("routine-winddown-2026-09-14", MON, LocalTime.of(22, 30), LocalTime.of(23, 30),
            "Wind down", BlockKind.ROUTINE, null, BlockStatus.PLANNED, 0, false, null);

        assertThat(BlockMapper.toDomain(BlockMapper.toEntity(block))).isEqualTo(block);
    }
}
