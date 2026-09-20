package com.lifeorganizer.setup.api.dto;

import java.util.List;

/** The whole setup: what {@code GET /api/setup} returns and {@code PUT /api/setup} accepts. */
public record SetupPayload(List<CategoryDto> categories, List<GoalDto> goals, List<ConstraintDto> constraints) {
}
