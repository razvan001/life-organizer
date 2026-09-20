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
class SetupRepositoriesTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @Autowired
    CategoryRepository categories;

    @Autowired
    GoalRepository goals;

    @Autowired
    ConstraintRepository constraints;

    @PersistenceContext
    EntityManager entityManager;

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void categoriesComeBackInPositionOrderNotInsertionOrIdOrder() {
        // insertion order: a, b   |   id order: a, b   |   position order: b, a
        categories.save(new CategoryEntity("a", "A", 1));
        categories.save(new CategoryEntity("b", "B", 0));
        flushAndClear();

        assertThat(categories.findAllByOrderByPositionAsc())
            .extracting(CategoryEntity::getId)
            .containsExactly("b", "a");
    }

    @Test
    void goalsRoundTripWithTheirCategoryAndAnOptionalDeadline() {
        CategoryEntity career = categories.save(new CategoryEntity("career", "Career", 0));
        goals.save(new GoalEntity("career-cert", career, "Claude certification", 5,
            LocalDate.of(2026, 12, 1), 90, "HIGH", "EVENING", 1));
        goals.save(new GoalEntity("career-azure", career, "Azure AI", 3,
            null, 60, "MEDIUM", "ANY", 0));
        flushAndClear();

        List<GoalEntity> found = goals.findAllByOrderByPositionAsc();

        assertThat(found).extracting(GoalEntity::getId).containsExactly("career-azure", "career-cert");
        assertThat(found.get(0).getDeadline()).isNull();
        GoalEntity cert = found.get(1);
        assertThat(cert.getCategory().getName()).isEqualTo("Career");
        assertThat(cert.getName()).isEqualTo("Claude certification");
        assertThat(cert.getPriority()).isEqualTo(5);
        assertThat(cert.getDeadline()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(cert.getDefaultBlockMinutes()).isEqualTo(90);
        assertThat(cert.getEnergy()).isEqualTo("HIGH");
        assertThat(cert.getPreferredTime()).isEqualTo("EVENING");
    }

    @Test
    void everyConstraintSubtypeRoundTripsInPositionOrder() {
        categories.save(new CategoryEntity("career", "Career", 0));
        categories.save(new CategoryEntity("social", "Social", 1));
        CategoryEntity health = categories.save(new CategoryEntity("health", "Health", 2));
        goals.save(new GoalEntity("health-gym", health, "Gym", 3, null, 60, "MEDIUM", "ANY", 0));
        categories.flush();
        goals.flush();

        constraints.save(new EveningQuotaEntity("Social", 3, 2, "social"));
        constraints.save(new FixedBlockEntity("Work", 0, LocalTime.of(8, 30), LocalTime.of(17, 0), "MONDAY,TUESDAY"));
        constraints.save(new DurationQuotaEntity("Learning", 2, 300, "career"));
        constraints.save(new FrequencyQuotaEntity("Gym", 1, 4, 60, "health-gym"));
        flushAndClear();

        List<ConstraintEntity> all = constraints.findAllByOrderByPositionAsc();

        assertThat(all).extracting(ConstraintEntity::getLabel).containsExactly("Work", "Gym", "Learning", "Social");

        FixedBlockEntity work = (FixedBlockEntity) all.get(0);
        assertThat(work.getStartTime()).isEqualTo(LocalTime.of(8, 30));
        assertThat(work.getEndTime()).isEqualTo(LocalTime.of(17, 0));
        assertThat(work.getDays()).isEqualTo("MONDAY,TUESDAY");

        FrequencyQuotaEntity gym = (FrequencyQuotaEntity) all.get(1);
        assertThat(gym.getTimesPerWeek()).isEqualTo(4);
        assertThat(gym.getSessionMinutes()).isEqualTo(60);
        assertThat(gym.getGoalId()).isEqualTo("health-gym");

        DurationQuotaEntity learning = (DurationQuotaEntity) all.get(2);
        assertThat(learning.getMinutesPerWeek()).isEqualTo(300);
        assertThat(learning.getCategoryId()).isEqualTo("career");

        EveningQuotaEntity social = (EveningQuotaEntity) all.get(3);
        assertThat(social.getEveningsPerWeek()).isEqualTo(2);
        assertThat(social.getCategoryId()).isEqualTo("social");
    }
}
