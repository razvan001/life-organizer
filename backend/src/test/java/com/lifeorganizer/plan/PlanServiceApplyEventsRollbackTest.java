package com.lifeorganizer.plan;

import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.engine.MarkDone;
import com.lifeorganizer.engine.PlanEvent;
import com.lifeorganizer.error.UnknownBlockException;
import com.lifeorganizer.persistence.PlanStore;
import com.lifeorganizer.persistence.SetupStore;
import com.lifeorganizer.support.ExampleSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A plain @SpringBootTest, deliberately not @DataJpaTest: this test needs no wrapping ambient
 * transaction, so PlanService.applyEvents' own @Transactional is the real, outermost, genuinely
 * committing-or-rolling-back boundary - exactly how a controller calls it in production.
 */
@SpringBootTest
@Testcontainers
class PlanServiceApplyEventsRollbackTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private static final LocalDate MON = ExampleSetup.MONDAY;

    @Autowired
    PlanService planService;

    @Autowired
    SetupStore setupStore;

    @Autowired
    PlanStore planStore;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table day_warning, block, plan_constraint, goal, category restart identity");
    }

    private String idOf(DayPlan plan, String title) {
        return plan.blocks().stream()
            .filter(b -> b.title().equals(title) && b.status() == BlockStatus.PLANNED)
            .findFirst().orElseThrow().id();
    }

    private Block block(DayPlan plan, String id) {
        return plan.blocks().stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void applyEventsRollsBackEveryEventWhenOneFails() {
        setupStore.replace(ExampleSetup.tree(), ExampleSetup.constraints());
        String gymId = idOf(planService.getOrGenerate(MON), "Gym");

        List<PlanEvent> events = List.of(new MarkDone(gymId), new MarkDone("no-such-id"));
        assertThatThrownBy(() -> planService.applyEvents(events, MON)).isInstanceOf(UnknownBlockException.class);

        assertThat(block(planStore.findDay(MON).orElseThrow(), gymId).status()).isEqualTo(BlockStatus.PLANNED);
    }
}
