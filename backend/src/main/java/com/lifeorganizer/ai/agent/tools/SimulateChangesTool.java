package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.TurnContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SimulateChangesTool implements AgentTool {

    private final ChangeSimulator simulator;

    public SimulateChangesTool(ChangeSimulator simulator) {
        this.simulator = simulator;
    }

    @Override
    public String name() {
        return "simulate_changes";
    }

    @Override
    public String description() {
        return "Dry-run a set of changes to today's plan and see what the planner would do (moved, cancelled or new "
            + "blocks, and the planner's own messages, e.g. no room to postpone). Nothing is changed or proposed.";
    }

    @Override
    public String parametersJson() {
        return "{\"type\":\"object\",\"properties\":{\"events\":" + ChangeSimulator.EVENTS_SCHEMA
            + "},\"required\":[\"events\"],\"additionalProperties\":false}";
    }

    @Override
    public String execute(JsonNode arguments, TurnContext context) {
        ChangeSimulator.Simulation simulation = simulator.simulate(arguments, context.today());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("diff", simulation.diff());
        result.put("engineMessages", simulation.engineMessages());
        return ToolJson.write(result);
    }
}
