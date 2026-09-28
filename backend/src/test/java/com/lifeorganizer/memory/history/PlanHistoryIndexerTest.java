package com.lifeorganizer.memory.history;

import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.memory.embedding.TextEmbedder;
import com.lifeorganizer.memory.index.MemoryIndexer;
import com.lifeorganizer.memory.store.MemoryChunkStore;
import com.lifeorganizer.memory.store.MemoryHit;
import com.lifeorganizer.memory.store.SourceType;
import com.lifeorganizer.persistence.PlanStore;
import com.lifeorganizer.support.HashingTextEmbedder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({PlanHistoryIndexer.class, MemoryIndexer.class, MemoryChunkStore.class, PlanStore.class,
    PlanHistoryIndexerTest.Beans.class})
class PlanHistoryIndexerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    /** "Today" is Monday 2026-09-28. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);

    @TestConfiguration
    static class Beans {
        @Bean
        TextEmbedder textEmbedder() {
            return new HashingTextEmbedder();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    PlanHistoryIndexer historyIndexer;

    @Autowired
    PlanStore planStore;

    @Autowired
    MemoryChunkStore chunks;

    @Autowired
    JdbcTemplate jdbc;

    @PersistenceContext
    EntityManager entityManager;

    private final HashingTextEmbedder embedder = new HashingTextEmbedder();

    private static Block block(String id, LocalDate date, int hour, BlockKind kind, BlockStatus status) {
        return new Block(id, date, LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0), id, kind, null, status, 0,
            false, null);
    }

    private void saveDay(LocalDate date, Block... blocks) {
        planStore.saveNewDay(new DayPlan(date, List.of(blocks), List.of()));
        entityManager.flush();
        entityManager.clear();
    }

    private List<MemoryHit> dayHits() {
        return chunks.search(embedder.embed("gym done"), 20, SourceType.DAY);
    }

    @Test
    void indexesPastDaysButNotTodayOrOlderThanTheWindow() {
        saveDay(TODAY.minusDays(1), block("Gym1", TODAY.minusDays(1), 7, BlockKind.GOAL, BlockStatus.DONE));
        saveDay(TODAY, block("Gym2", TODAY, 7, BlockKind.GOAL, BlockStatus.DONE));
        saveDay(TODAY.minusDays(91), block("Gym3", TODAY.minusDays(91), 7, BlockKind.GOAL, BlockStatus.DONE));

        assertThat(historyIndexer.indexPastDays()).isEqualTo(1);

        assertThat(dayHits()).extracting(MemoryHit::sourceId).containsExactly(TODAY.minusDays(1).toString());
    }

    @Test
    void anUnchangedDayIsNotReindexed() {
        saveDay(TODAY.minusDays(1), block("Gym", TODAY.minusDays(1), 7, BlockKind.GOAL, BlockStatus.DONE));

        assertThat(historyIndexer.indexPastDays()).isEqualTo(1);
        assertThat(historyIndexer.indexPastDays()).isZero();
    }

    @Test
    void aDayWithNothingWorthRememberingLosesItsOldEntry() {
        LocalDate yesterday = TODAY.minusDays(1);
        saveDay(yesterday, block("Gym", yesterday, 7, BlockKind.GOAL, BlockStatus.DONE));
        historyIndexer.indexPastDays();

        // The day now only has a FIXED block (e.g. the goal block was removed by a replan).
        jdbc.update("update block set kind = 'FIXED' where block_date = ?", java.sql.Date.valueOf(yesterday));
        entityManager.clear(); // otherwise findDay returns the cached GOAL entity, not the updated row
        historyIndexer.indexPastDays();

        assertThat(dayHits()).isEmpty();
    }

    @Test
    void neverGeneratesMissingDays() {
        historyIndexer.indexPastDays();

        Integer blockRows = jdbc.queryForObject("select count(*) from block", Integer.class);
        assertThat(blockRows).isZero();
    }
}
