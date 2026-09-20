package com.lifeorganizer.setup.api.dto;

import java.util.List;

public record SetupParseResponse(List<CategoryDto> categories, List<GoalDto> goals,
                                 List<ConstraintDto> constraints, List<String> warnings) {
}
