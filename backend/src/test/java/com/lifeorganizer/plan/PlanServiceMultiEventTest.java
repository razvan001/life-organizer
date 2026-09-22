package com.lifeorganizer.plan;

import com.lifeorganizer.config.EngineConfig;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.engine.CancelBlock;
import com.lifeorganizer.engine.MarkDone;
import com.lifeorganizer.engine.PlanEvent;
import com.lifeorganizer.engine.PostponeBlock;
import com.lifeorganizer.persistence.PlanStore;
import com.lifeorganizer.persistence.SetupStore;
import com.lifeorganizer.support.ExampleSetup;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({PlanService.class, SetupStore.class, PlanStore.class, EngineConfig.class})
class PlanServiceMultiEventTest {

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

    @PersistenceContext
    EntityManager entityManager;

    private void saveExampleSetup() {
        setupStore.replace(ExampleSetup.tree(), ExampleSetup.constraints());
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
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
    void simulateEventsNeverPersists() {
        saveExampleSetup();
        String gymId = idOf(planService.getOrGenerate(MON), "Gym");

        AppliedEvent simulated = planService.simulateEvents(List.of(new MarkDone(gymId)), MON);
        flushAndClear();

        assertThat(block(simulated.today(), gymId).status()).isEqualTo(BlockStatus.DONE);
        assertThat(simulated.changes()).containsExactly("Marked done Gym");
        assertThat(block(planStore.findDay(MON).orElseThrow(), gymId).status()).isEqualTo(BlockStatus.PLANNED);
    }

    @Test
    void simulateEventsCanGenerateAFutureDayWhilePostponing() {
        saveExampleSetup();
        DayPlan before = planService.getOrGenerate(MON);
        String gymId = idOf(before, "Gym");

        // Postponing looks at the rest of the week for room, which lazily generates and persists those
        // days (see PlanService.loadDaysFor). Under a read-only transaction those inserts are rejected by
        // Postgres; this call must complete normally and return a sensible AppliedEvent.
        AppliedEvent simulated = planService.simulateEvents(List.of(new PostponeBlock(gymId, "tired")), MON);

        assertThat(simulated.today()).isNotNull();
        assertThat(simulated.changes()).isNotEmpty();
    }

    @Test
    void applyEventsAppliesEveryEventAndPersistsAll() {
        saveExampleSetup();
        DayPlan before = planService.getOrGenerate(MON);
        String gymId = idOf(before, "Gym");
        String certId = idOf(before, "Claude certification");

        List<PlanEvent> events = List.of(new MarkDone(gymId), new CancelBlock(certId));
        AppliedEvent applied = planService.applyEvents(events, MON);
        flushAndClear();

        assertThat(applied.changes()).containsExactly("Marked done Gym", "Cancelled Claude certification - slot is now free");
        DayPlan reloaded = planStore.findDay(MON).orElseThrow();
        assertThat(block(reloaded, gymId).status()).isEqualTo(BlockStatus.DONE);
        assertThat(block(reloaded, certId).status()).isEqualTo(BlockStatus.CANCELLED);
    }
}
