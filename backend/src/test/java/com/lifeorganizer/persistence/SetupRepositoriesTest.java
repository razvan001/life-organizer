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
    void goalsRoundTripWithTheirCategoryAndHours() {
        CategoryEntity career = categories.save(new CategoryEntity("career", "Career", 0));
        goals.save(new GoalEntity("career-cert", career, "Claude certification", 150, 90, "HIGH", "EVENING", 1));
        goals.save(new GoalEntity("career-azure", career, "Azure AI", 0, 60, "MEDIUM", "ANY", 0));
        flushAndClear();

        List<GoalEntity> found = goals.findAllByOrderByPositionAsc();

        assertThat(found).extracting(GoalEntity::getId).containsExactly("career-azure", "career-cert");
        assertThat(found.get(0).getMinutesPerWeek()).isZero();
        GoalEntity cert = found.get(1);
        assertThat(cert.getCategory().getName()).isEqualTo("Career");
        assertThat(cert.getName()).isEqualTo("Claude certification");
        assertThat(cert.getMinutesPerWeek()).isEqualTo(150);
        assertThat(cert.getDefaultBlockMinutes()).isEqualTo(90);
        assertThat(cert.getEnergy()).isEqualTo("HIGH");
        assertThat(cert.getPreferredTime()).isEqualTo("EVENING");
    }

    @Test
    void fixedBlocksRoundTripInPositionOrder() {
        constraints.save(new FixedBlockEntity("Sleep", 1, LocalTime.of(23, 30), LocalTime.of(7, 0), "MONDAY"));
        constraints.save(new FixedBlockEntity("Work", 0, LocalTime.of(8, 30), LocalTime.of(17, 0), "MONDAY,TUESDAY"));
        flushAndClear();

        List<ConstraintEntity> all = constraints.findAllByOrderByPositionAsc();

        assertThat(all).extracting(ConstraintEntity::getLabel).containsExactly("Work", "Sleep");
        FixedBlockEntity work = (FixedBlockEntity) all.get(0);
        assertThat(work.getStartTime()).isEqualTo(LocalTime.of(8, 30));
        assertThat(work.getEndTime()).isEqualTo(LocalTime.of(17, 0));
        assertThat(work.getDays()).isEqualTo("MONDAY,TUESDAY");
    }
}
