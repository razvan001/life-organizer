package com.lifeorganizer.ai.dto;

import com.lifeorganizer.plan.AppliedEvent;
import com.lifeorganizer.plan.PlanDtoMapper;
import com.lifeorganizer.plan.dto.ApplyEventRequest;
import com.lifeorganizer.plan.dto.TodayPlanResponse;

import java.util.List;

public record CommandResponse(boolean understood, String rationale, String message,
                              List<ApplyEventRequest> events, TodayPlanResponse preview, List<String> changes) {

    public static CommandResponse notUnderstood(String message) {
        return new CommandResponse(false, null, message, List.of(), null, List.of());
    }

    public static CommandResponse understood(String rationale, List<ApplyEventRequest> events, AppliedEvent simulated) {
        return new CommandResponse(true, rationale, null, events,
            PlanDtoMapper.toResponse(simulated.today()), simulated.changes());
    }
}
