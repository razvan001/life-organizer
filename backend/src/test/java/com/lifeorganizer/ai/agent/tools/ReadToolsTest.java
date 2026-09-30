package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.TurnContext;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.domain.Energy;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.GoalTree;
import com.lifeorganizer.domain.PreferredTime;
import com.lifeorganizer.memory.search.MemorySearch;
import com.lifeorganizer.memory.store.MemoryHit;
import com.lifeorganizer.memory.store.SourceType;
import com.lifeorganizer.persistence.PlanStore;
import com.lifeorganizer.persistence.SetupStore;
import com.lifeorganizer.plan.PlanService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadToolsTest {

    /** Wednesday. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);
    private static final TurnContext CONTEXT = new TurnContext(TODAY);

    private static JsonNode args(String json) {
        return ToolJson.MAPPER.readTree(json);
    }

    private static Block block(String id, LocalDate date, int hour, String title, BlockKind kind, BlockStatus status) {
        return new Block(id, date, LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0), title, kind, null, status, 0,
            false, null);
    }

    @Test
    void everyToolHasTheAgreedNameAndAParseableSchema() {
        List<AgentTool> tools = List.of(new GetTodayPlanTool(mock(PlanService.class)),
            new WeekOverviewTool(mock(PlanStore.class)), new ListGoalsTool(mock(SetupStore.class)),
            new SearchMemoryTool(mock(MemorySearch.class)));

        assertThat(tools).extracting(AgentTool::name)
            .containsExactly("get_today_plan", "get_week_overview", "list_goals", "search_memory");
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.description()).isNotBlank();
            assertThat(ToolJson.MAPPER.readTree(tool.parametersJson()).path("type").asString()).isEqualTo("object");
        });
    }

    @Test
    void todaysPlanListsBlockIdsAndKinds() {
        PlanService plans = mock(PlanService.class);
        when(plans.getOrGenerate(TODAY)).thenReturn(new DayPlan(TODAY, List.of(
            block("gym-1", TODAY, 7, "Gym", BlockKind.GOAL, BlockStatus.PLANNED)), List.of()));

        JsonNode result = ToolJson.MAPPER.readTree(new GetTodayPlanTool(plans).execute(args("{}"), CONTEXT));

        assertThat(result.path("date").asString()).isEqualTo("2026-09-30");
        assertThat(result.path("blocks").get(0).path("id").asString()).isEqualTo("gym-1");
        assertThat(result.path("blocks").get(0).path("kind").asString()).isEqualTo("GOAL");
        assertThat(result.path("blocks").get(0).path("start").asString()).isEqualTo("07:00");
    }

    @Test
    void weekOverviewReadsStoredDaysOnlyAndMarksToday() {
        PlanStore store = mock(PlanStore.class);
        LocalDate monday = LocalDate.of(2026, 9, 28);
        when(store.findDay(any())).thenReturn(Optional.empty());
        when(store.findDay(monday)).thenReturn(Optional.of(new DayPlan(monday, List.of(
            block("gym-m", monday, 7, "Gym", BlockKind.GOAL, BlockStatus.DONE),
            block("work-m", monday, 9, "Work", BlockKind.FIXED, BlockStatus.PLANNED)), List.of())));
        when(store.findDay(TODAY)).thenReturn(Optional.of(new DayPlan(TODAY, List.of(
            block("free-t", TODAY, 8, "Free", BlockKind.FREE, BlockStatus.PLANNED)), List.of())));

        String result = new WeekOverviewTool(store).execute(args("{}"), CONTEXT);

        assertThat(result.lines()).containsExactly(
            "Mon 2026-09-28: Gym 07:00 DONE",
            "Tue 2026-09-29: not planned yet",
            "Wed 2026-09-30 (today): only routine and free time",
            "Thu 2026-10-01: not planned yet",
            "Fri 2026-10-02: not planned yet",
            "Sat 2026-10-03: not planned yet",
            "Sun 2026-10-04: not planned yet");
    }

    @Test
    void listGoalsDescribesEachGoal() {
        SetupStore setup = mock(SetupStore.class);
        Category health = new Category("health", "Health");
        when(setup.loadTree()).thenReturn(new GoalTree(List.of(health), List.of(new Goal("health-gym", health, "Gym", 4,
            null, 60, Energy.HIGH, PreferredTime.MORNING))));

        JsonNode goal = ToolJson.MAPPER.readTree(new ListGoalsTool(setup).execute(args("{}"), CONTEXT)).get(0);

        assertThat(goal.path("id").asString()).isEqualTo("health-gym");
        assertThat(goal.path("category").asString()).isEqualTo("Health");
        assertThat(goal.path("priority").asInt()).isEqualTo(4);
        assertThat(goal.path("preferredTime").asString()).isEqualTo("MORNING");
    }

    @Test
    void listGoalsSaysSoWhenThereAreNone() {
        SetupStore setup = mock(SetupStore.class);
        when(setup.loadTree()).thenReturn(new GoalTree(List.of(), List.of()));

        assertThat(new ListGoalsTool(setup).execute(args("{}"), CONTEXT)).isEqualTo("No goals saved yet.");
    }

    @Test
    void searchMemoryPassesQueryKAndSource() {
        MemorySearch search = mock(MemorySearch.class);
        when(search.search("tired", 3, SourceType.DAY)).thenReturn(List.of(new MemoryHit(SourceType.DAY, "2026-09-24",
            LocalDate.of(2026, 9, 24), "Thu 2026-09-24", "Thu 2026-09-24: Gym done 07:00", 0.61234)));

        JsonNode hit = ToolJson.MAPPER.readTree(new SearchMemoryTool(search)
            .execute(args("{\"query\":\"tired\",\"k\":3,\"source\":\"day\"}"), CONTEXT)).get(0);

        assertThat(hit.path("source").asString()).isEqualTo("DAY");
        assertThat(hit.path("date").asString()).isEqualTo("2026-09-24");
        assertThat(hit.path("score").asDouble()).isEqualTo(0.61);
    }

    @Test
    void searchMemoryDefaultsKToFiveAndSaysWhenNothingMatched() {
        MemorySearch search = mock(MemorySearch.class);
        when(search.search("x", 5, null)).thenReturn(List.of());

        assertThat(new SearchMemoryTool(search).execute(args("{\"query\":\"x\"}"), CONTEXT))
            .isEqualTo("No matching notes or past days.");
        verify(search).search("x", 5, null);
    }

    @Test
    void searchMemoryRejectsBadArguments() {
        SearchMemoryTool tool = new SearchMemoryTool(mock(MemorySearch.class));

        assertThatThrownBy(() -> tool.execute(args("{}"), CONTEXT)).isInstanceOf(ToolException.class)
            .hasMessage("query is required");
        assertThatThrownBy(() -> tool.execute(args("{\"query\":\"x\",\"k\":9}"), CONTEXT))
            .hasMessage("k must be a whole number between 1 and 8");
        assertThatThrownBy(() -> tool.execute(args("{\"query\":\"x\",\"source\":\"EMAIL\"}"), CONTEXT))
            .hasMessage("source must be NOTE or DAY");
    }
}
