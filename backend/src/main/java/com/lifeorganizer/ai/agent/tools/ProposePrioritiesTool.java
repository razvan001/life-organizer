package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.PriorityChange;
import com.lifeorganizer.ai.agent.Proposal;
import com.lifeorganizer.ai.agent.TurnContext;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.memory.history.DaySummarizer;
import com.lifeorganizer.persistence.SetupStore;
import com.lifeorganizer.plan.PlanService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Records ONE weekly-review proposal of new goal priorities; nothing changes until the user presses Apply. */
@Component
public class ProposePrioritiesTool implements AgentTool {

    static final int MAX_RATIONALE = 500;
    static final int MAX_REASON = 200;
    static final int MAX_CHANGES = 8;

    private final SetupStore setupStore;
    private final PlanService planService;

    public ProposePrioritiesTool(SetupStore setupStore, PlanService planService) {
        this.setupStore = setupStore;
        this.planService = planService;
    }

    @Override
    public String name() {
        return "propose_priorities";
    }

    @Override
    public String description() {
        return "Record ONE final proposal of new goal priorities (1 least to 5 most important) for the user to Apply "
            + "or Reject. Include only goals whose priority should change. On Apply, the days later this week that "
            + "the user has not touched are planned again. At most once per message.";
    }

    @Override
    public String parametersJson() {
        return """
            {"type":"object","properties":{
              "rationale":{"type":"string","description":"one short sentence the user sees above the changes"},
              "changes":{"type":"array","minItems":1,"maxItems":8,"items":{"type":"object","properties":{
                "goalId":{"type":"string","description":"exact goal id from list_goals or get_week_progress"},
                "priority":{"type":"integer","minimum":1,"maximum":5},
                "reason":{"type":"string","description":"a few words on why"}},
                "required":["goalId","priority","reason"],"additionalProperties":false}}},
             "required":["rationale","changes"],"additionalProperties":false}""";
    }

    @Override
    public String execute(JsonNode arguments, TurnContext context) {
        String rationale = ToolJson.requiredText(arguments, "rationale", MAX_RATIONALE);
        JsonNode changes = arguments.path("changes");
        if (!changes.isArray() || changes.isEmpty() || changes.size() > MAX_CHANGES) {
            throw new ToolException("changes must be an array of 1 to " + MAX_CHANGES + " change objects");
        }
        Map<String, Goal> goals = setupStore.loadTree().goals().stream()
            .collect(Collectors.toMap(Goal::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Set<String> seen = new HashSet<>();
        List<PriorityChange> priorityChanges = new ArrayList<>();
        for (JsonNode change : changes) {
            if (!change.isObject()) {
                throw new ToolException("each change must be an object");
            }
            String goalId = ToolJson.requiredText(change, "goalId", 255);
            Goal goal = goals.get(goalId);
            if (goal == null) {
                throw new ToolException("unknown goalId '" + goalId + "'; use the ids from list_goals or get_week_progress");
            }
            if (!seen.add(goalId)) {
                throw new ToolException("goal '" + goalId + "' appears more than once");
            }
            JsonNode priorityNode = change.path("priority");
            if (!priorityNode.isIntegralNumber() || !priorityNode.canConvertToInt()
                || priorityNode.asInt() < 1 || priorityNode.asInt() > 5) {
                throw new ToolException("priority must be a whole number from 1 to 5");
            }
            int priority = priorityNode.asInt();
            if (priority == goal.priority()) {
                throw new ToolException(goal.name() + " already has priority " + priority + "; leave it out");
            }
            String reason = ToolJson.requiredText(change, "reason", MAX_REASON);
            priorityChanges.add(new PriorityChange(goal.id(), goal.name(), goal.priority(), priority, reason));
        }
        List<LocalDate> days = planService.untouchedFutureDays(context.today());
        String replan = days.isEmpty()
            ? "No planned days later this week need re-planning"
            : "Would re-plan " + days.stream().map(DaySummarizer::title).collect(Collectors.joining(", "));
        context.propose(Proposal.priorities(rationale, priorityChanges, List.of(replan)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recorded", true);
        result.put("changes", priorityChanges.stream()
            .map(c -> c.goalName() + ": " + c.from() + " -> " + c.to() + " (" + c.reason() + ")").toList());
        result.put("replan", replan);
        result.put("next", "Reply to the user with a short summary; they will press Apply or Reject.");
        return ToolJson.write(result);
    }
}
