package com.lifeorganizer.ai.model;

import com.lifeorganizer.setup.api.dto.CategoryDto;
import com.lifeorganizer.setup.api.dto.ConstraintDto;

import java.util.List;

/**
 * The model's raw reply, before validation. Goals get their own record with boxed numbers because
 * {@code GoalDto} uses primitive ints, which Jackson cannot fill from a JSON null.
 */
public record SetupExtractionResult(List<CategoryDto> categories, List<ExtractedGoal> goals,
                                    List<ConstraintDto> constraints, List<String> warnings) {

    public record ExtractedGoal(String id, String categoryId, String name, Integer priority, String deadline,
                                Integer defaultBlockMinutes, String energy, String preferredTime) {
    }
}
