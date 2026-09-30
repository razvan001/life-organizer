package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.TurnContext;
import com.lifeorganizer.api.TimeFormats;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.plan.PlanService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GetTodayPlanTool implements AgentTool {

    private final PlanService planService;

    public GetTodayPlanTool(PlanService planService) {
        this.planService = planService;
    }

    @Override
    public String name() {
        return "get_today_plan";
    }

    @Override
    public String description() {
        return "Today's plan: every block with its id, title, kind (FIXED, ROUTINE, GOAL, FREE, ADHOC), status "
            + "(PLANNED, DONE, CANCELLED, POSTPONED), start and end. Call this before proposing changes.";
    }

    @Override
    public String parametersJson() {
        return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    }

    @Override
    public String execute(JsonNode arguments, TurnContext context) {
        List<Map<String, Object>> blocks = planService.getOrGenerate(context.today()).blocksInOrder().stream()
            .map(GetTodayPlanTool::describe)
            .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", context.today().toString());
        result.put("blocks", blocks);
        return ToolJson.write(result);
    }

    private static Map<String, Object> describe(Block block) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", block.id());
        json.put("title", block.title());
        json.put("kind", block.kind().name());
        json.put("status", block.status().name());
        json.put("start", TimeFormats.format(block.start()));
        json.put("end", TimeFormats.format(block.end()));
        return json;
    }
}
