package com.lifeorganizer.setup.api.dto;

import java.util.List;

/**
 * One flat shape for all four constraint variants. {@code type} is FIXED_BLOCK, FREQUENCY_QUOTA,
 * DURATION_QUOTA or EVENING_QUOTA; the fields a variant does not use are null.
 */
public record ConstraintDto(String type, String label, String start, String end, List<String> days,
                            Integer timesPerWeek, Integer sessionMinutes, String goalId,
                            Integer minutesPerWeek, String categoryId, Integer eveningsPerWeek) {
}
