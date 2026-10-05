package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.PlanDiff;
import com.lifeorganizer.ai.agent.ProposedEventValidator;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.engine.PlanEvent;
import com.lifeorganizer.plan.AppliedEvent;
import com.lifeorganizer.plan.PlanDtoMapper;
import com.lifeorganizer.plan.PlanService;
import com.lifeorganizer.plan.dto.ApplyEventRequest;
import com.lifeorganizer.plan.dto.TodayPlanResponse;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Parses, validates and dry-runs a set of events against today's plan. Never persists a change. */
@Component
public class ChangeSimulator {

    public record Simulation(List<ApplyEventRequest> requests, List<String> diff, List<String> engineMessages,
                             TodayPlanResponse preview) {
    }

    private final PlanService planService;

    public ChangeSimulator(PlanService planService) {
        this.planService = planService;
    }

    public Simulation simulate(JsonNode arguments, LocalDate today) {
        List<ApplyEventRequest> requests = parse(arguments.path("events"));
        List<Block> before = planService.getOrGenerate(today).blocksInOrder();
        List<PlanEvent> events = ProposedEventValidator.validate(requests, today, before);
        AppliedEvent simulated = planService.simulateEvents(events, today);
        return new Simulation(requests, PlanDiff.lines(before, simulated.today().blocksInOrder()),
            simulated.changes(), PlanDtoMapper.toResponse(simulated.today()));
    }

    private static List<ApplyEventRequest> parse(JsonNode events) {
        if (!events.isArray()) {
            throw new ToolException("events must be an array of event objects");
        }
        List<ApplyEventRequest> requests = new ArrayList<>();
        int index = 0;
        for (JsonNode event : events) {
            if (!event.isObject()) {
                throw new ToolException("events must be an array of event objects");
            }
            try {
                requests.add(ToolJson.MAPPER.treeToValue(event, ApplyEventRequest.class));
            } catch (JacksonException e) {
                throw new ToolException("event " + (index + 1) + " has a field of the wrong type: every field must be a plain string");
            }
            index++;
        }
        return requests;
    }

    /** The JSON Schema of one event, shared by both change tools. */
    static final String EVENTS_SCHEMA = """
        {"type":"array","minItems":1,"maxItems":5,"items":{"type":"object","properties":{
          "type":{"type":"string","enum":["CANCEL_BLOCK","POSTPONE_BLOCK","MARK_DONE","SET_ENERGY","ADD_ADHOC"]},
          "blockId":{"type":"string","description":"exact id from today's plan in Current data; not for ADD_ADHOC"},
          "reason":{"type":"string","description":"optional, POSTPONE_BLOCK only"},
          "title":{"type":"string","description":"ADD_ADHOC only"},
          "start":{"type":"string","description":"HH:mm, ADD_ADHOC only"},
          "end":{"type":"string","description":"HH:mm, ADD_ADHOC only"},
          "energy":{"type":"string","enum":["LOW","MEDIUM"],"description":"SET_ENERGY only"}
        },"required":["type"]}}
        """;
}
