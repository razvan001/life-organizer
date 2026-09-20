package com.lifeorganizer.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PlanRepositoriesTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private static final LocalDate MON = LocalDate.of(2026, 9, 14);
    private static final LocalDate TUE = MON.plusDays(1);

    @Autowired
    BlockRepository blocks;

    @Autowired
    DayWarningRepository warnings;

    @PersistenceContext
    EntityManager entityManager;

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static BlockEntity block(String id, LocalDate date, LocalTime start, LocalTime end) {
        return new BlockEntity(id, date, start, end, "Title " + id, "GOAL", "goal-1", "PLANNED", 0, false, null);
    }

    @Test
    void blocksAreFilteredByDateAndOrderedByStartTimeThenId() {
        blocks.save(block("b", MON, LocalTime.of(7, 0), LocalTime.of(8, 0)));
        blocks.save(block("a", MON, LocalTime.of(7, 0), LocalTime.of(8, 0)));
        blocks.save(block("late", MON, LocalTime.of(20, 0), LocalTime.of(21, 0)));
        blocks.save(block("early", MON, LocalTime.of(6, 0), LocalTime.of(6, 30)));
        blocks.save(block("other-day", TUE, LocalTime.of(7, 0), LocalTime.of(8, 0)));
        flushAndClear();

        assertThat(blocks.findByDateOrderByStartTimeAscIdAsc(MON))
            .extracting(BlockEntity::getId)
            .containsExactly("early", "a", "b", "late");
    }

    @Test
    void everyBlockFieldRoundTripsIncludingNulls() {
        blocks.save(new BlockEntity("full", MON, LocalTime.of(18, 0), LocalTime.of(19, 0), "Claude certification",
            "GOAL", "career-claude-certification", "POSTPONED", 2, true, "tired"));
        blocks.save(new BlockEntity("bare", MON, LocalTime.of(22, 30), LocalTime.of(23, 30), "Wind down",
            "ROUTINE", null, "PLANNED", 0, false, null));
        flushAndClear();

        List<BlockEntity> found = blocks.findByDateOrderByStartTimeAscIdAsc(MON);

        BlockEntity full = found.get(0);
        assertThat(full.getId()).isEqualTo("full");
        assertThat(full.getDate()).isEqualTo(MON);
        assertThat(full.getStartTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(full.getEndTime()).isEqualTo(LocalTime.of(19, 0));
        assertThat(full.getTitle()).isEqualTo("Claude certification");
        assertThat(full.getKind()).isEqualTo("GOAL");
        assertThat(full.getGoalId()).isEqualTo("career-claude-certification");
        assertThat(full.getStatus()).isEqualTo("POSTPONED");
        assertThat(full.getPostponeCount()).isEqualTo(2);
        assertThat(full.isFlagged()).isTrue();
        assertThat(full.getReason()).isEqualTo("tired");

        BlockEntity bare = found.get(1);
        assertThat(bare.getGoalId()).isNull();
        assertThat(bare.getReason()).isNull();
        assertThat(bare.isFlagged()).isFalse();
    }

    @Test
    void savingAnExistingIdUpdatesTheRow() {
        blocks.save(block("x", MON, LocalTime.of(7, 0), LocalTime.of(8, 0)));
        flushAndClear();

        blocks.save(new BlockEntity("x", MON, LocalTime.of(7, 0), LocalTime.of(8, 0), "Title x",
            "GOAL", "goal-1", "DONE", 0, false, null));
        flushAndClear();

        List<BlockEntity> found = blocks.findByDateOrderByStartTimeAscIdAsc(MON);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getStatus()).isEqualTo("DONE");
    }

    @Test
    void warningsAreOrderedByPositionAndCanBeDeletedPerDate() {
        warnings.save(new DayWarningEntity(MON, 1, "second"));
        warnings.save(new DayWarningEntity(MON, 0, "first"));
        warnings.save(new DayWarningEntity(TUE, 0, "other day"));
        flushAndClear();

        assertThat(warnings.findByDateOrderByPositionAsc(MON))
            .extracting(DayWarningEntity::getMessage)
            .containsExactly("first", "second");

        warnings.deleteByDate(MON);
        flushAndClear();

        assertThat(warnings.findByDateOrderByPositionAsc(MON)).isEmpty();
        assertThat(warnings.findByDateOrderByPositionAsc(TUE)).hasSize(1);
    }
}
