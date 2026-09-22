package com.lifeorganizer.plan.dto;

import java.util.List;

/** Body of POST /api/plan/today/events/batch: the exact events a proposal was built from. */
public record ApplyEventsRequest(List<ApplyEventRequest> events) {
}
