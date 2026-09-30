package com.lifeorganizer.ai.chat.dto;

import com.lifeorganizer.plan.dto.ApplyEventRequest;
import com.lifeorganizer.plan.dto.TodayPlanResponse;

import java.util.List;

public record ProposalDto(String rationale, List<ApplyEventRequest> events, List<String> changes,
                          TodayPlanResponse preview, String status) {
}
