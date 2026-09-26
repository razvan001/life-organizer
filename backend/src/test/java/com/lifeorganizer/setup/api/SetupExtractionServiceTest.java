package com.lifeorganizer.setup.api;

import com.lifeorganizer.ai.client.MalformedLlmReplyException;
import com.lifeorganizer.ai.client.SetupExtractionClient;
import com.lifeorganizer.ai.model.SetupExtractionResult;
import com.lifeorganizer.ai.model.SetupExtractionResult.ExtractedGoal;
import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.GoalTree;
import com.lifeorganizer.error.AiNotConfiguredException;
import com.lifeorganizer.persistence.SetupStore;
import com.lifeorganizer.setup.api.dto.CategoryDto;
import com.lifeorganizer.setup.api.dto.ConstraintDto;
import com.lifeorganizer.setup.api.dto.GoalDto;
import com.lifeorganizer.setup.api.dto.SetupParseResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SetupExtractionServiceTest {

    private static final CategoryDto HEALTH = new CategoryDto("health", "Health");

    private SetupExtractionClient client;
    private SetupStore store;
    private SetupExtractionService sut;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        client = mock(SetupExtractionClient.class);
        store = mock(SetupStore.class);
        ObjectProvider<SetupExtractionClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        when(store.loadTree()).thenReturn(new GoalTree(List.of(), List.of()));
        sut = new SetupExtractionService(provider, store);
    }

    private static ExtractedGoal goal(String id, String categoryId, Integer priority, Integer minutes,
                                      String energy, String time) {
        return new ExtractedGoal(id, categoryId, id, priority, null, minutes, energy, time);
    }

    private void reply(List<CategoryDto> categories, List<ExtractedGoal> goals, List<ConstraintDto> constraints) {
        when(client.extract(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(new SetupExtractionResult(categories, goals, constraints, List.of("model warning")));
    }

    @Test
    void failsWhenNoClientIsConfigured() {
        @SuppressWarnings("unchecked")
        ObjectProvider<SetupExtractionClient> empty = mock(ObjectProvider.class);
        SetupExtractionService none = new SetupExtractionService(empty, store);

        assertThatThrownBy(() -> none.extract("x")).isInstanceOf(AiNotConfiguredException.class);
    }

    @Test
    void aMalformedReplyBecomesAnEmptyPreviewWithAWarning() {
        when(client.extract(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenThrow(new MalformedLlmReplyException("bad"));

        SetupParseResponse response = sut.extract("x");

        assertThat(response.goals()).isEmpty();
        assertThat(response.warnings()).hasSize(1);
    }

    @Test
    void connectivityFailuresPropagate() {
        when(client.extract(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
            .thenThrow(new ResourceAccessException("down"));

        assertThatThrownBy(() -> sut.extract("x")).isInstanceOf(RestClientException.class);
    }

    @Test
    void aWellFormedReplyPassesThroughAndKeepsModelWarnings() {
        reply(List.of(HEALTH), List.of(goal("gym", "health", 4, 45, "HIGH", "MORNING")),
            List.of(new ConstraintDto("FREQUENCY_QUOTA", "Gym 3x", null, null, null, 3, 45, "gym", null, null, null)));

        SetupParseResponse response = sut.extract("x");

        assertThat(response.categories()).containsExactly(HEALTH);
        assertThat(response.goals()).containsExactly(new GoalDto("gym", "health", "gym", 4, null, 45, "HIGH", "MORNING"));
        assertThat(response.constraints()).hasSize(1);
        assertThat(response.warnings()).containsExactly("model warning");
    }

    @Test
    void missingOrInvalidNumbersAndEnumsGetDefaults() {
        reply(List.of(HEALTH), List.of(
            goal("a", "health", null, null, null, null),
            goal("b", "health", 9, -5, "EXTREME", "NOON")), List.of());

        List<GoalDto> goals = sut.extract("x").goals();

        assertThat(goals).extracting(GoalDto::priority).containsExactly(3, 3);
        assertThat(goals).extracting(GoalDto::defaultBlockMinutes).containsExactly(60, 60);
        assertThat(goals).extracting(GoalDto::energy).containsExactly("MEDIUM", "MEDIUM");
        assertThat(goals).extracting(GoalDto::preferredTime).containsExactly("ANY", "ANY");
    }

    @Test
    void aGoalWithAnUnknownCategoryIsDroppedWithAWarning() {
        reply(List.of(HEALTH), List.of(goal("a", "ghost", 3, 60, "LOW", "ANY")), List.of());

        SetupParseResponse response = sut.extract("x");

        assertThat(response.goals()).isEmpty();
        assertThat(response.warnings()).anyMatch(w -> w.contains("unknown category"));
    }

    @Test
    void reusingAnExistingCategoryKeepsItsExistingNameEvenIfTheModelRenamedIt() {
        when(store.loadTree()).thenReturn(new GoalTree(List.of(new Category("health", "Health")), List.of()));
        reply(List.of(new CategoryDto("health", "Totally Different")),
            List.of(goal("a", "health", 3, 60, "LOW", "ANY")), List.of());

        assertThat(sut.extract("x").categories()).containsExactly(HEALTH);
    }

    @Test
    void anExistingCategoryTheModelDidNotRepeatIsSynthesizedIntoTheResponse() {
        when(store.loadTree()).thenReturn(new GoalTree(List.of(new Category("health", "Health")), List.of()));
        reply(List.of(), List.of(goal("a", "health", 3, 60, "LOW", "ANY")), List.of());

        SetupParseResponse response = sut.extract("x");

        assertThat(response.categories()).containsExactly(HEALTH);
        assertThat(response.goals()).hasSize(1);
    }

    @Test
    void anOverlongTextIsDroppedWithAWarning() {
        String longName = "n".repeat(256);
        reply(List.of(HEALTH), List.of(new ExtractedGoal("a", "health", longName, 3, null, 60, "LOW", "ANY")), List.of());

        SetupParseResponse response = sut.extract("x");

        assertThat(response.goals()).isEmpty();
        assertThat(response.warnings()).anyMatch(w -> w.contains("at most 255"));
    }

    @Test
    void anUnparseableDeadlineDropsTheGoal() {
        reply(List.of(HEALTH), List.of(new ExtractedGoal("a", "health", "A", 3, "next month", 60, "LOW", "ANY")),
            List.of());

        assertThat(sut.extract("x").goals()).isEmpty();
    }

    @Test
    void constraintsMissingRequiredFieldsOrPointingAtUnknownIdsAreDropped() {
        reply(List.of(HEALTH), List.of(goal("gym", "health", 3, 60, "LOW", "ANY")), List.of(
            new ConstraintDto("FIXED_BLOCK", "Work", "08:30", "17:00", List.of("MONDAY"), null, null, null, null, null, null),
            new ConstraintDto("FIXED_BLOCK", "No days", "08:30", "17:00", null, null, null, null, null, null, null),
            new ConstraintDto("FREQUENCY_QUOTA", "Ghost goal", null, null, null, 2, 30, "ghost", null, null, null),
            new ConstraintDto("DURATION_QUOTA", "Ghost cat", null, null, null, null, null, null, 120, "ghost", null),
            new ConstraintDto("MAGIC", "Bad type", null, null, null, null, null, null, null, null, null),
            new ConstraintDto("EVENING_QUOTA", "Evenings", null, null, null, null, null, null, null, "health", 2)));

        SetupParseResponse response = sut.extract("x");

        assertThat(response.constraints()).extracting(ConstraintDto::label).containsExactly("Work", "Evenings");
        assertThat(response.warnings()).hasSize(5);
    }

    @Test
    void nullListsFromTheModelAreTreatedAsEmpty() {
        reply(null, null, null);

        SetupParseResponse response = sut.extract("x");

        assertThat(response.categories()).isEmpty();
        assertThat(response.goals()).isEmpty();
        assertThat(response.constraints()).isEmpty();
    }
}
