package com.lifeorganizer.ai.agent;

import com.lifeorganizer.plan.dto.ApplyEventRequest;
import com.lifeorganizer.plan.dto.TodayPlanResponse;

import java.util.List;

/** The one change set the agent proposes in a turn: what Apply will replay, plus the simulated result. */
public record Proposal(String rationale, List<ApplyEventRequest> events, List<String> changes, TodayPlanResponse preview) {
}
