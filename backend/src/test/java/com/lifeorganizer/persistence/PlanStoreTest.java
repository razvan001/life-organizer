package com.lifeorganizer.persistence;

import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.DayPlan;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(PlanStore.class)
class PlanStoreTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private static final LocalDate MON = LocalDate.of(2026, 9, 14);
    private static final LocalDate TUE = MON.plusDays(1);

    @Autowired
    PlanStore store;

    @PersistenceContext
    EntityManager entityManager;

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static Block block(String id, LocalDate date, int startHour, BlockStatus status) {
        return new Block(id, date, LocalTime.of(startHour, 0), LocalTime.of(startHour + 1, 0), "Title " + id,
            BlockKind.GOAL, "goal-1", status, 0, false, null);
    }

    @Test
    void findDayIsEmptyWhenNothingWasSaved() {
        assertThat(store.findDay(MON)).isEmpty();
    }

    @Test
    void savedBlocksAndWarningsComeBackInOrder() {
        Block late = block("late", MON, 18, BlockStatus.PLANNED);
        Block early = block("early", MON, 7, BlockStatus.PLANNED);

        store.saveNewDay(new DayPlan(MON, List.of(late, early), List.of("first warning", "second warning")));
        flushAndClear();

        DayPlan loaded = store.findDay(MON).orElseThrow();
        assertThat(loaded.date()).isEqualTo(MON);
        assertThat(loaded.blocks()).containsExactly(early, late);
        assertThat(loaded.warnings()).containsExactly("first warning", "second warning");
    }

    @Test
    void blocksWithTheSameIdAreMadeUniqueInsteadOfLosingOne() {
        Block first = block("dup", MON, 7, BlockStatus.PLANNED);
        Block second = block("dup", MON, 9, BlockStatus.PLANNED);
        Block third = block("dup", MON, 11, BlockStatus.PLANNED);

        store.saveNewDay(new DayPlan(MON, List.of(first, second, third), List.of()));
        flushAndClear();

        assertThat(store.findDay(MON).orElseThrow().blocks())
            .extracting(Block::id)
            .containsExactly("dup", "dup~2", "dup~3");
    }

    @Test
    void overlongWarningsAreTruncatedTo500Characters() {
        String huge = "x".repeat(700);

        store.saveNewDay(new DayPlan(MON, List.of(block("a", MON, 7, BlockStatus.PLANNED)), List.of(huge)));
        flushAndClear();

        assertThat(store.findDay(MON).orElseThrow().warnings().get(0)).hasSize(500);
    }

    @Test
    void applyChangesUpdatesAddsAndRemovesBlocks() {
        Block a = block("a", MON, 7, BlockStatus.PLANNED);
        Block b = block("b", MON, 9, BlockStatus.PLANNED);
        Block c = block("c", MON, 11, BlockStatus.PLANNED);
        DayPlan before = new DayPlan(MON, List.of(a, b, c), List.of());
        store.saveNewDay(before);
        flushAndClear();

        Block aDone = block("a", MON, 7, BlockStatus.DONE);
        Block d = block("d", MON, 13, BlockStatus.PLANNED);
        DayPlan after = new DayPlan(MON, List.of(aDone, c, d), List.of());
        store.applyChanges(Map.of(MON, before), Map.of(MON, after));
        flushAndClear();

        assertThat(store.findDay(MON).orElseThrow().blocks()).containsExactly(aDone, c, d);
    }

    @Test
    void applyChangesIgnoresDatesThatWereNotLoaded() {
        Block a = block("a", MON, 7, BlockStatus.PLANNED);
        DayPlan monday = new DayPlan(MON, List.of(a), List.of());
        store.saveNewDay(monday);
        flushAndClear();

        DayPlan stray = new DayPlan(TUE, List.of(block("t", TUE, 7, BlockStatus.PLANNED)), List.of());
        store.applyChanges(Map.of(MON, monday), Map.of(MON, monday, TUE, stray));
        flushAndClear();

        assertThat(store.findDay(TUE)).isEmpty();
    }

    @Test
    void findBlockDateReturnsTheDateOfAKnownBlockOnly() {
        store.saveNewDay(new DayPlan(TUE, List.of(block("t", TUE, 7, BlockStatus.PLANNED)), List.of()));
        flushAndClear();

        assertThat(store.findBlockDate("t")).contains(TUE);
        assertThat(store.findBlockDate("missing")).isEmpty();
    }

    @Test
    void deleteDayRemovesBlocksAndWarningsOfThatDayOnly() {
        store.saveNewDay(new DayPlan(MON, List.of(block("m1", MON, 7, BlockStatus.PLANNED)), List.of("warn")));
        LocalDate tue = MON.plusDays(1);
        store.saveNewDay(new DayPlan(tue, List.of(block("t1", tue, 7, BlockStatus.PLANNED)), List.of()));
        flushAndClear();

        store.deleteDay(MON);
        flushAndClear();

        assertThat(store.findDay(MON)).isEmpty();
        assertThat(store.findDay(tue)).isPresent();
        store.saveNewDay(new DayPlan(MON, List.of(block("m1", MON, 8, BlockStatus.PLANNED)), List.of()));
        flushAndClear();
        assertThat(store.findDay(MON).orElseThrow().blocks()).extracting(Block::start).containsExactly(LocalTime.of(8, 0));
        assertThat(store.findDay(MON).orElseThrow().warnings()).isEmpty();
    }

    @Test
    void theGenerationLockCanBeTakenRepeatedlyInsideOneTransaction() {
        // Advisory locks are re-entrant within a session; this proves the SQL runs on a real Postgres.
        assertThatCode(() -> {
            store.lockGeneration();
            store.lockGeneration();
        }).doesNotThrowAnyException();
    }
}
