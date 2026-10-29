package com.lifeorganizer.setup.api;

import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.persistence.SetupStore;
import com.lifeorganizer.setup.api.dto.CategoryDto;
import com.lifeorganizer.setup.api.dto.ConstraintDto;
import com.lifeorganizer.setup.api.dto.GoalDto;
import com.lifeorganizer.setup.api.dto.SetupPayload;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({SetupService.class, SetupStore.class})
class SetupServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @Autowired
    SetupService service;

    @PersistenceContext
    EntityManager entityManager;

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void savingThenLoadingRoundTripsTheParsedSetup() {
        SetupPayload payload = ExampleSetup.payload();

        service.save(payload);
        flushAndClear();

        assertThat(service.load()).isEqualTo(payload);
    }

    @Test
    void savingReplacesThePreviousSetup() {
        service.save(ExampleSetup.payload());
        SetupPayload small = new SetupPayload(
            List.of(new CategoryDto("health", "Health")),
            List.of(new GoalDto("health-gym", "health", "Gym", 120, 60, "MEDIUM", "ANY")),
            List.of());

        service.save(small);
        flushAndClear();

        assertThat(service.load()).isEqualTo(small);
    }

    @Test
    void editedHoursAreKept() {
        SetupPayload parsed = ExampleSetup.payload();
        List<GoalDto> edited = new ArrayList<>();
        for (GoalDto goal : parsed.goals()) {
            edited.add(goal.id().equals("health-gym")
                ? new GoalDto(goal.id(), goal.categoryId(), goal.name(), 330,
                    goal.defaultBlockMinutes(), goal.energy(), goal.preferredTime())
                : goal);
        }

        service.save(new SetupPayload(parsed.categories(), edited, parsed.constraints()));
        flushAndClear();

        assertThat(service.load().goals()).filteredOn(g -> g.id().equals("health-gym"))
            .extracting(GoalDto::minutesPerWeek).containsExactly(330);
    }

    @Test
    void nullListsAreTreatedAsEmpty() {
        service.save(new SetupPayload(null, null, null));
        flushAndClear();

        assertThat(service.load()).isEqualTo(new SetupPayload(List.of(), List.of(), List.of()));
    }

    @Test
    void anOverlongGoalNameIsRejectedAsABadRequest() {
        GoalDto gym = new GoalDto("health-gym", "health", "G".repeat(256), 120, 60, "MEDIUM", "ANY");
        SetupPayload payload = new SetupPayload(List.of(new CategoryDto("health", "Health")), List.of(gym), List.of());

        assertThatThrownBy(() -> service.save(payload))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("goal name must be at most 255 characters");
    }

    @Test
    void anOverlongConstraintLabelIsRejectedAsABadRequest() {
        ConstraintDto social = new ConstraintDto("FIXED_BLOCK", "S".repeat(256), "08:30", "17:00", List.of("MONDAY"));
        SetupPayload payload = new SetupPayload(List.of(new CategoryDto("health", "Health")), List.of(), List.of(social));

        assertThatThrownBy(() -> service.save(payload))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("constraint label must be at most 255 characters");
    }

    @Test
    void nullEntriesInTheListsAreRejectedAsBadRequests() {
        CategoryDto health = new CategoryDto("health", "Health");
        GoalDto gym = new GoalDto("health-gym", "health", "Gym", 120, 60, "MEDIUM", "ANY");

        assertThatThrownBy(() -> service.save(new SetupPayload(Arrays.asList(health, null), List.of(), List.of())))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("categories must not contain null entries");
        assertThatThrownBy(() -> service.save(new SetupPayload(List.of(health), Arrays.asList(gym, null), List.of())))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("goals must not contain null entries");
        assertThatThrownBy(() -> service.save(new SetupPayload(List.of(health), List.of(), Arrays.asList((ConstraintDto) null))))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("constraints must not contain null entries");
    }

    @Test
    void aDuplicateGoalIdIsRejected() {
        GoalDto gym = new GoalDto("health-gym", "health", "Gym", 120, 60, "MEDIUM", "ANY");
        SetupPayload payload = new SetupPayload(List.of(new CategoryDto("health", "Health")), List.of(gym, gym), List.of());

        assertThatThrownBy(() -> service.save(payload))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("Duplicate goal id 'health-gym'");
    }

    @Test
    void aDuplicateCategoryIdIsRejected() {
        CategoryDto health = new CategoryDto("health", "Health");
        SetupPayload payload = new SetupPayload(List.of(health, health), List.of(), List.of());

        assertThatThrownBy(() -> service.save(payload))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("Duplicate category id 'health'");
    }

    @Test
    void aGoalWithAnUnknownCategoryIsRejected() {
        GoalDto gym = new GoalDto("health-gym", "nope", "Gym", 120, 60, "MEDIUM", "ANY");
        SetupPayload payload = new SetupPayload(List.of(new CategoryDto("health", "Health")), List.of(gym), List.of());

        assertThatThrownBy(() -> service.save(payload))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("Goal 'health-gym' refers to unknown category 'nope'");
    }

    private SetupPayload gymWithMinutes(int minutesPerWeek) {
        return new SetupPayload(List.of(new CategoryDto("health", "Health")),
            List.of(new GoalDto("health-gym", "health", "Gym", minutesPerWeek, 60, "MEDIUM", "ANY")), List.of());
    }

    @Test
    void rejectsHoursOffTheHalfHourGrid() {
        assertThatThrownBy(() -> service.save(gymWithMinutes(78)))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("Hours per week for 'Gym' must be between 0 and 168, in steps of half an hour");
    }

    @Test
    void rejectsMoreThan168Hours() {
        assertThatThrownBy(() -> service.save(gymWithMinutes(10110))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsNegativeHours() {
        assertThatThrownBy(() -> service.save(gymWithMinutes(-30))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void acceptsZeroAndAFullWeek() {
        service.save(gymWithMinutes(0));
        service.save(gymWithMinutes(10080));
        flushAndClear();

        assertThat(service.load().goals()).extracting(GoalDto::minutesPerWeek).containsExactly(10080);
    }

    @Test
    void aRejectedSaveKeepsThePreviousSetup() {
        SetupPayload good = ExampleSetup.payload();
        service.save(good);
        GoalDto bad = new GoalDto("x", "career", "X", 45, 60, "MEDIUM", "ANY");

        assertThatThrownBy(() -> service.save(new SetupPayload(good.categories(), List.of(bad), List.of())))
            .isInstanceOf(InvalidRequestException.class);
        flushAndClear();

        assertThat(service.load()).isEqualTo(good);
    }
}
