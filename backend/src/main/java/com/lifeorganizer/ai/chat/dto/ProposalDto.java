package com.lifeorganizer.ai.chat.dto;

import com.lifeorganizer.ai.agent.PriorityChange;
import com.lifeorganizer.plan.dto.ApplyEventRequest;
import com.lifeorganizer.plan.dto.TodayPlanResponse;

import java.util.List;

/** kind is PLAN or PRIORITIES; preview is null for PRIORITIES, priorityChanges is empty for PLAN. */
public record ProposalDto(String kind, String rationale, List<ApplyEventRequest> events, List<String> changes,
                          TodayPlanResponse preview, List<PriorityChange> priorityChanges, String status) {
}
