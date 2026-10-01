package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.TurnContext;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.memory.history.DaySummarizer;
import com.lifeorganizer.persistence.PlanStore;
import com.lifeorganizer.persistence.SetupStore;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** This week so far, per goal. Reads stored days only: it must never generate a day (PlanService.getOrGenerate). */
@Component
public class WeekProgressTool implements AgentTool {

    private final PlanStore planStore;
    private final SetupStore setupStore;

    public WeekProgressTool(PlanStore planStore, SetupStore setupStore) {
        this.planStore = planStore;
        this.setupStore = setupStore;
    }

    @Override
    public String name() {
        return "get_week_progress";
    }

    @Override
    public String description() {
        return "This week from Monday to today, per goal: minutes planned and done, and how many sessions were "
            + "cancelled or postponed, with each goal's id, priority and deadline. Use it for a weekly review.";
    }

    @Override
    public String parametersJson() {
        return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    }

    @Override
    public String execute(JsonNode arguments, TurnContext context) {
        List<Goal> goals = setupStore.loadTree().goals();
        if (goals.isEmpty()) {
            return "No goals saved yet.";
        }
        LocalDate today = context.today();
        List<String> stored = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<Block> blocks = new ArrayList<>();
        for (LocalDate date = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); !date.isAfter(today);
             date = date.plusDays(1)) {
            String label = DaySummarizer.title(date) + (date.equals(today) ? " (today)" : "");
            Optional<DayPlan> day = planStore.findDay(date);
            if (day.isPresent()) {
                stored.add(label);
                blocks.addAll(day.get().blocks());
            } else {
                missing.add(label);
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("Days so far: " + (stored.isEmpty() ? "none" : String.join(", ", stored)) + ". Not planned: "
            + (missing.isEmpty() ? "none" : String.join(", ", missing)) + ".");
        for (Goal goal : goals) {
            lines.add(describe(goal, blocks.stream()
                .filter(block -> block.kind() == BlockKind.GOAL && goal.id().equals(block.goalId())).toList()));
        }
        return String.join("\n", lines);
    }

    private static String describe(Goal goal, List<Block> sessions) {
        long planned = sessions.stream().filter(b -> b.status() != BlockStatus.POSTPONED).mapToLong(WeekProgressTool::minutes).sum();
        long done = sessions.stream().filter(b -> b.status() == BlockStatus.DONE).mapToLong(WeekProgressTool::minutes).sum();
        long cancelled = sessions.stream().filter(b -> b.status() == BlockStatus.CANCELLED).count();
        long postponed = sessions.stream().filter(b -> b.status() == BlockStatus.POSTPONED).count();
        return goal.name() + " (id " + goal.id() + ", priority " + goal.priority() + ", deadline "
            + (goal.deadline() == null ? "none" : goal.deadline().toString()) + "): planned " + planned
            + " min, done " + done + " min, " + cancelled + " cancelled, " + postponed + " postponed";
    }

    private static long minutes(Block block) {
        return Duration.between(block.start(), block.end()).toMinutes();
    }
}
