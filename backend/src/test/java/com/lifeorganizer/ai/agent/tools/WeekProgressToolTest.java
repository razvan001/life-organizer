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
import com.lifeorganizer.persistence.PlanStore;
import com.lifeorganizer.persistence.SetupStore;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class WeekProgressToolTest {

    /** Wednesday. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);
    private static final LocalDate MON = LocalDate.of(2026, 9, 28);
    private static final Category CAREER = new Category("career", "Career");
    private static final Goal STUDY = new Goal("career-study", CAREER, "Study", 3, LocalDate.of(2026, 10, 2), 60,
        Energy.HIGH, PreferredTime.EVENING);
    private static final Goal CHESS = new Goal("career-chess", CAREER, "Chess", 2, null, 30, Energy.LOW, PreferredTime.ANY);

    private static Block goalBlock(String id, LocalDate date, int hour, int minutes, String goalId, BlockStatus status) {
        LocalTime start = LocalTime.of(hour, 0);
        return new Block(id, date, start, start.plusMinutes(minutes), "x", BlockKind.GOAL, goalId, status, 0, false, null);
    }

    @Test
    void summarizesEachGoalFromStoredDaysOnly() {
        PlanStore plans = mock(PlanStore.class);
        SetupStore setup = mock(SetupStore.class);
        when(setup.loadTree()).thenReturn(new GoalTree(List.of(CAREER), List.of(STUDY, CHESS)));
        when(plans.findDay(any())).thenReturn(Optional.empty());
        when(plans.findDay(MON)).thenReturn(Optional.of(new DayPlan(MON, List.of(
            goalBlock("s1", MON, 18, 60, "career-study", BlockStatus.DONE),
            goalBlock("s2", MON, 19, 60, "career-study", BlockStatus.POSTPONED)), List.of())));
        when(plans.findDay(TODAY)).thenReturn(Optional.of(new DayPlan(TODAY, List.of(
            goalBlock("s2-p1", TODAY, 18, 60, "career-study", BlockStatus.PLANNED),
            goalBlock("c1", TODAY, 20, 30, "career-chess", BlockStatus.CANCELLED)), List.of())));

        String result = new WeekProgressTool(plans, setup).execute(ToolJson.MAPPER.readTree("{}"), new TurnContext(TODAY));

        assertThat(result.lines()).containsExactly(
            "Days so far: Mon 2026-09-28, Wed 2026-09-30 (today). Not planned: Tue 2026-09-29.",
            "Study (id career-study, priority 3, deadline 2026-10-02): planned 120 min, done 60 min, 0 cancelled, 1 postponed",
            "Chess (id career-chess, priority 2, deadline none): planned 30 min, done 0 min, 1 cancelled, 0 postponed");
        verify(plans, never()).findDay(TODAY.plusDays(1));
    }

    @Test
    void saysSoWhenThereAreNoGoals() {
        SetupStore setup = mock(SetupStore.class);
        when(setup.loadTree()).thenReturn(new GoalTree(List.of(), List.of()));

        assertThat(new WeekProgressTool(mock(PlanStore.class), setup)
            .execute(ToolJson.MAPPER.readTree("{}"), new TurnContext(TODAY))).isEqualTo("No goals saved yet.");
    }

    @Test
    void hasTheAgreedNameAndAnObjectSchema() {
        WeekProgressTool tool = new WeekProgressTool(mock(PlanStore.class), mock(SetupStore.class));

        assertThat(tool.name()).isEqualTo("get_week_progress");
        assertThat(tool.description()).isNotBlank();
        assertThat(ToolJson.MAPPER.readTree(tool.parametersJson()).path("type").asString()).isEqualTo("object");
    }
}
