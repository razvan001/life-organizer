package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.TurnContext;
import com.lifeorganizer.api.TimeFormats;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.memory.history.DaySummarizer;
import com.lifeorganizer.persistence.PlanStore;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** This week at a glance. Reads stored days only: it must never generate a day (PlanService.getOrGenerate). */
@Component
public class WeekOverviewTool implements AgentTool {

    private static final Set<BlockKind> SHOWN = Set.of(BlockKind.GOAL, BlockKind.ADHOC);

    private final PlanStore planStore;

    public WeekOverviewTool(PlanStore planStore) {
        this.planStore = planStore;
    }

    @Override
    public String name() {
        return "get_week_overview";
    }

    @Override
    public String description() {
        return "This week (Monday to Sunday), one line per day with its goal and ad-hoc blocks and their status. "
            + "Context only: changes can only be proposed for today.";
    }

    @Override
    public String parametersJson() {
        return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    }

    @Override
    public String execute(JsonNode arguments, TurnContext context) {
        LocalDate monday = context.today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<String> lines = new ArrayList<>();
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = monday.plusDays(offset);
            String label = DaySummarizer.title(date) + (date.equals(context.today()) ? " (today)" : "");
            lines.add(label + ": " + planStore.findDay(date).map(WeekOverviewTool::describe).orElse("not planned yet"));
        }
        return String.join("\n", lines);
    }

    private static String describe(DayPlan day) {
        String blocks = day.blocksInOrder().stream()
            .filter(block -> SHOWN.contains(block.kind()))
            .map(block -> block.title() + " " + TimeFormats.format(block.start()) + " " + block.status())
            .collect(Collectors.joining("; "));
        return blocks.isEmpty() ? "only routine and free time" : blocks;
    }
}
