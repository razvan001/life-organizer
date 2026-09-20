package com.lifeorganizer.persistence;

import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.Constraint;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.GoalTree;
import com.lifeorganizer.setup.ConstraintParser;
import com.lifeorganizer.setup.GoalTreeParser;
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
@Import(SetupStore.class)
class SetupStoreTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @Autowired
    SetupStore store;

    @PersistenceContext
    EntityManager entityManager;

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void anEmptyDatabaseHasNoSetup() {
        assertThat(store.isEmpty()).isTrue();
        assertThat(store.loadTree()).isEqualTo(new GoalTree(List.of(), List.of()));
        assertThat(store.loadConstraints()).isEmpty();
    }

    @Test
    void theExampleSetupRoundTripsInDeclaredOrder() {
        GoalTree tree = ExampleSetup.tree();
        List<Constraint> constraints = ExampleSetup.constraints();

        store.replace(tree, constraints);
        flushAndClear();

        assertThat(store.isEmpty()).isFalse();
        // Declared order (Career, Projects, Health, ...) is deliberately not alphabetical.
        assertThat(store.loadTree()).isEqualTo(tree);
        assertThat(store.loadConstraints()).isEqualTo(constraints);
    }

    @Test
    void replacingOverwritesThePreviousSetupCompletely() {
        store.replace(ExampleSetup.tree(), ExampleSetup.constraints());

        GoalTree smaller = new GoalTreeParser().parse("Goals\n├── Health\n│   └── Gym\n");
        List<Constraint> oneConstraint = new ConstraintParser().parse("Gym: 3x/week\n", smaller).constraints();
        store.replace(smaller, oneConstraint);
        flushAndClear();

        assertThat(store.loadTree()).isEqualTo(smaller);
        assertThat(store.loadConstraints()).isEqualTo(oneConstraint);
    }

    @Test
    void aGoalWhoseCategoryIsNotInTheTreeIsRejected() {
        Category known = Category.of("Health");
        Category other = Category.of("Other");
        GoalTree bad = new GoalTree(List.of(known), List.of(Goal.withDefaults("other-x", other, "X")));

        assertThatThrownBy(() -> store.replace(bad, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown category");
    }
}
