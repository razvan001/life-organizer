package com.lifeorganizer.plan;

import com.lifeorganizer.config.EngineConfig;
import com.lifeorganizer.domain.Block;
import java.util.EnumSet;
import java.time.LocalTime;
import java.time.DayOfWeek;
import com.lifeorganizer.domain.PreferredTime;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.FixedBlock;
import com.lifeorganizer.domain.Energy;
import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.Constraint;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.domain.GoalTree;
import com.lifeorganizer.error.SetupRequiredException;
import com.lifeorganizer.persistence.BlockEntity;
import com.lifeorganizer.persistence.BlockRepository;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({PlanService.class, SetupStore.class, PlanStore.class, EngineConfig.class})
class PlanServiceGenerationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @Autowired
    PlanService planService;

    @Autowired
    SetupStore setupStore;

    @Autowired
    PlanStore planStore;

    @Autowired
    BlockRepository blockRepository;

    @PersistenceContext
    EntityManager entityManager;

    private void saveExampleSetup() {
        setupStore.replace(ExampleSetup.tree(), ExampleSetup.constraints());
    }

    private static List<String> summary(DayPlan plan) {
        return plan.blocksInOrder().stream().map(b -> b.start() + " " + b.title()).toList();
    }

    @Test
    void firstRequestForADayGeneratesTheExampleMonday() {
        saveExampleSetup();

        DayPlan plan = planService.getOrGenerate(ExampleSetup.MONDAY);

        assertThat(summary(plan)).containsExactly(
            "07:00 Gym",
            "08:00 Free",
            "08:30 Work",
            "17:00 Dinner / break",
            "18:00 Claude certification",
            "19:00 RAG showcase",
            "20:00 Dating / events",
            "21:00 Free",
            "22:30 Wind down");
        assertThat(plan.warnings()).isEmpty();
    }

    @Test
    void generatesALaterDayOfTheSameWeek() {
        saveExampleSetup();

        DayPlan tuesday = planService.getOrGenerate(ExampleSetup.MONDAY.plusDays(1));

        // every goal with hours gets one hour every day, so Tuesday looks like Monday
        assertThat(summary(tuesday)).containsExactly(
            "07:00 Gym",
            "08:00 Free",
            "08:30 Work",
            "17:00 Dinner / break",
            "18:00 Claude certification",
            "19:00 RAG showcase",
            "20:00 Dating / events",
            "21:00 Free",
            "22:30 Wind down");
    }

    @Test
    void theSecondRequestReadsThePersistedDayInsteadOfRegeneratingIt() {
        saveExampleSetup();
        DayPlan first = planService.getOrGenerate(ExampleSetup.MONDAY);
        Block gym = first.blocks().stream().filter(b -> b.title().equals("Gym")).findFirst().orElseThrow();

        // simulate a change made later by a replan event
        blockRepository.save(new BlockEntity(gym.id(), gym.date(), gym.start(), gym.end(), gym.title(),
            gym.kind().name(), gym.goalId(), "DONE", 0, false, null));
        entityManager.flush();
        entityManager.clear();

        DayPlan second = planService.getOrGenerate(ExampleSetup.MONDAY);

        Block reloadedGym = second.blocks().stream().filter(b -> b.id().equals(gym.id())).findFirst().orElseThrow();
        assertThat(reloadedGym.status()).isEqualTo(BlockStatus.DONE);
    }

    @Test
    void onlyTheRequestedDayIsGenerated() {
        saveExampleSetup();

        planService.getOrGenerate(ExampleSetup.MONDAY);

        assertThat(planStore.findDay(ExampleSetup.MONDAY.plusDays(1))).isEmpty();
    }

    @Test
    void aSetupWhoseGoalsHaveNoHoursIsNotPlannedYet() {
        // right after the V4 migration every goal has 0 hours: a day stored now would stay empty for good
        Category health = Category.of("Health");
        setupStore.replace(new GoalTree(List.of(health), List.of(Goal.withDefaults("health-gym", health, "Gym"))),
            ExampleSetup.constraints());

        assertThatThrownBy(() -> planService.getOrGenerate(ExampleSetup.MONDAY))
            .isInstanceOf(SetupRequiredException.class)
            .hasMessage("Your goals have no hours yet - set hours per week on the Setup page");
        assertThat(planStore.findDay(ExampleSetup.MONDAY)).isEmpty();
    }

    @Test
    void throwsWhenNoSetupWasEverSaved() {
        assertThatThrownBy(() -> planService.getOrGenerate(ExampleSetup.MONDAY))
            .isInstanceOf(SetupRequiredException.class);
    }

    @Test
    void generationWarningsArePersistedAndReturnedOnTheNextLoad() {
        // 8 hours for a single goal cannot fit into 7 daily 60-minute chunks.
        Category career = Category.of("Career");
        GoalTree tree = new GoalTree(List.of(career), List.of(new Goal("career-claude-certification", career,
            "Claude certification", 480, 60, Energy.MEDIUM, PreferredTime.ANY)));
        List<Constraint> constraints = List.of(new FixedBlock("Sleep", LocalTime.of(23, 30), LocalTime.of(7, 0),
            EnumSet.allOf(DayOfWeek.class)));
        setupStore.replace(tree, constraints);

        DayPlan first = planService.getOrGenerate(ExampleSetup.MONDAY);

        assertThat(first.warnings())
            .containsExactly("Claude certification could not fit 60 of its 480 weekly minutes (ran out of days)");
        assertThat(summary(first)).containsExactly("07:00 Claude certification", "08:00 Free", "22:30 Wind down");

        entityManager.flush();
        entityManager.clear();
        DayPlan second = planService.getOrGenerate(ExampleSetup.MONDAY);

        assertThat(second.warnings()).isEqualTo(first.warnings());
    }
}
