package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.TurnContext;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.persistence.SetupStore;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ListGoalsTool implements AgentTool {

    private final SetupStore setupStore;

    public ListGoalsTool(SetupStore setupStore) {
        this.setupStore = setupStore;
    }

    @Override
    public String name() {
        return "list_goals";
    }

    @Override
    public String description() {
        return "The user's goals with category, priority (1 least to 5 most important), deadline, preferred time of "
            + "day, energy a session needs, and the usual session length in minutes.";
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
        return ToolJson.write(goals.stream().map(ListGoalsTool::describe).toList());
    }

    private static Map<String, Object> describe(Goal goal) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", goal.id());
        json.put("name", goal.name());
        json.put("category", goal.category().name());
        json.put("priority", goal.priority());
        json.put("deadline", goal.deadline() == null ? null : goal.deadline().toString());
        json.put("preferredTime", goal.preferredTime().name());
        json.put("energy", goal.energy().name());
        json.put("sessionMinutes", goal.defaultBlockMinutes());
        return json;
    }
}
