package com.lifeorganizer.ai.agent;

import com.lifeorganizer.plan.dto.ApplyEventRequest;
import com.lifeorganizer.plan.dto.TodayPlanResponse;

import java.util.List;

/**
 * The one change set the agent proposes in a turn. PLAN: events Apply will replay on today's plan, plus the
 * simulated result. PRIORITIES: new goal priorities from the old weekly review; nothing creates them any more, they
 * are only read from threads saved before. JSON stored before slice 2 has no kind and reads as PLAN.
 */
public record Proposal(Kind kind, String rationale, List<ApplyEventRequest> events, List<String> changes,
                       TodayPlanResponse preview, List<PriorityChange> priorityChanges) {

    public enum Kind { PLAN, PRIORITIES }

    public Proposal {
        kind = kind == null ? Kind.PLAN : kind;
        events = events == null ? List.of() : List.copyOf(events);
        changes = changes == null ? List.of() : List.copyOf(changes);
        priorityChanges = priorityChanges == null ? List.of() : List.copyOf(priorityChanges);
    }

    public Proposal(String rationale, List<ApplyEventRequest> events, List<String> changes, TodayPlanResponse preview) {
        this(Kind.PLAN, rationale, events, changes, preview, List.of());
    }
}
