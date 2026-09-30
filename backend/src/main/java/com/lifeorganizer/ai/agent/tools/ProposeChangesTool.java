package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.Proposal;
import com.lifeorganizer.ai.agent.TurnContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ProposeChangesTool implements AgentTool {

    static final int MAX_RATIONALE = 500;

    private final ChangeSimulator simulator;

    public ProposeChangesTool(ChangeSimulator simulator) {
        this.simulator = simulator;
    }

    @Override
    public String name() {
        return "propose_changes";
    }

    @Override
    public String description() {
        return "Record ONE final proposal of changes to today's plan for the user to Apply or Reject. At most once per "
            + "message. The plan does not change until the user presses Apply.";
    }

    @Override
    public String parametersJson() {
        return "{\"type\":\"object\",\"properties\":{\"rationale\":{\"type\":\"string\",\"description\":"
            + "\"one short sentence the user sees above the changes\"},\"events\":" + ChangeSimulator.EVENTS_SCHEMA
            + "},\"required\":[\"rationale\",\"events\"],\"additionalProperties\":false}";
    }

    @Override
    public String execute(JsonNode arguments, TurnContext context) {
        String rationale = ToolJson.requiredText(arguments, "rationale", MAX_RATIONALE);
        ChangeSimulator.Simulation simulation = simulator.simulate(arguments, context.today());
        context.propose(new Proposal(rationale, simulation.requests(), simulation.engineMessages(), simulation.preview()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recorded", true);
        result.put("diff", simulation.diff());
        result.put("engineMessages", simulation.engineMessages());
        result.put("next", "Reply to the user with a short summary; they will press Apply or Reject.");
        return ToolJson.write(result);
    }
}
