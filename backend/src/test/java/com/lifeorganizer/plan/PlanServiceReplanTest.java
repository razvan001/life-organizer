package com.lifeorganizer.plan;

import com.lifeorganizer.config.EngineConfig;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.persistence.PlanStore;
import com.lifeorganizer.persistence.SetupStore;
import com.lifeorganizer.support.ExampleSetup;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({PlanService.class, SetupStore.class, PlanStore.class, EngineConfig.class})
class PlanServiceReplanTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    /** ExampleSetup.MONDAY is a Monday; "today" in these tests is the Wednesday of that week. */
    private static final LocalDate MON = ExampleSetup.MONDAY;
    private static final LocalDate WED = MON.plusDays(2);
    private static final LocalDate THU = MON.plusDays(3);
    private static final LocalDate FRI = MON.plusDays(4);
    private static final LocalDate SAT = MON.plusDays(5);
    private static final LocalDate SUN = MON.plusDays(6);

    @Autowired
    PlanService planService;

    @Autowired
    SetupStore setupStore;

    @Autowired
    PlanStore planStore;

    @PersistenceContext
    EntityManager entityManager;

    @BeforeEach
    void saveSetup() {
        setupStore.replace(ExampleSetup.tree(), ExampleSetup.constraints());
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    /** A hand-made day with one block, so a re-generated day is easy to tell apart. */
    private void storeDay(LocalDate date, String id, BlockKind kind, BlockStatus status, int postponeCount) {
        planStore.saveNewDay(new DayPlan(date, List.of(new Block(id, date, LocalTime.of(10, 0), LocalTime.of(11, 0),
            "Old " + id, kind, null, status, postponeCount, false, null)), List.of("old warning")));
    }

    private List<String> ids(LocalDate date) {
        return planStore.findDay(date).orElseThrow().blocks().stream().map(Block::id).toList();
    }

    @Test
    void untouchedFutureDaysAreRegeneratedAndTouchedOnesKept() {
        storeDay(WED, "wed-old", BlockKind.GOAL, BlockStatus.PLANNED, 0);       // today: never re-planned
        storeDay(THU, "thu-old", BlockKind.GOAL, BlockStatus.PLANNED, 0);       // untouched
        storeDay(FRI, "fri-old", BlockKind.GOAL, BlockStatus.CANCELLED, 0);     // touched: cancelled
        storeDay(SAT, "sat-old", BlockKind.ADHOC, BlockStatus.PLANNED, 0);      // touched: ad-hoc
        storeDay(SUN, "sun-old", BlockKind.GOAL, BlockStatus.PLANNED, 1);       // touched: postponed copy
        storeDay(SUN.plusDays(1), "next-old", BlockKind.GOAL, BlockStatus.PLANNED, 0); // next week
        flushAndClear();

        assertThat(planService.untouchedFutureDays(WED)).containsExactly(THU);
        List<LocalDate> replanned = planService.replanUntouchedDays(WED);
        flushAndClear();

        assertThat(replanned).containsExactly(THU);
        assertThat(ids(THU)).doesNotContain("thu-old").isNotEmpty();
        assertThat(planStore.findDay(THU).orElseThrow().warnings()).doesNotContain("old warning");
        assertThat(ids(WED)).containsExactly("wed-old");
        assertThat(ids(FRI)).containsExactly("fri-old");
        assertThat(ids(SAT)).containsExactly("sat-old");
        assertThat(ids(SUN)).containsExactly("sun-old");
        assertThat(ids(SUN.plusDays(1))).containsExactly("next-old");
    }

    @Test
    void aGeneratedDayCanBeRegeneratedWithTheSameBlockIds() {
        planService.getOrGenerate(THU);
        flushAndClear();
        List<String> before = ids(THU);

        assertThat(planService.replanUntouchedDays(WED)).containsExactly(THU);
        flushAndClear();

        assertThat(ids(THU)).containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    void daysThatAreNotStoredStayNotStored() {
        assertThat(planService.replanUntouchedDays(WED)).isEmpty();
        flushAndClear();

        assertThat(planStore.findDay(THU)).isEmpty();
    }

    @Test
    void onSundayNothingIsReplanned() {
        storeDay(SUN.plusDays(1), "next-old", BlockKind.GOAL, BlockStatus.PLANNED, 0);
        flushAndClear();

        assertThat(planService.replanUntouchedDays(SUN)).isEmpty();
    }
}
