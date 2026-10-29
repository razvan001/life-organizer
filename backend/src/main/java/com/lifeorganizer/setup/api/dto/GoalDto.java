package com.lifeorganizer.setup.api.dto;

/** minutesPerWeek is the goal's whole weekly time; the Setup page shows it as hours. */
public record GoalDto(String id, String categoryId, String name, int minutesPerWeek,
                      int defaultBlockMinutes, String energy, String preferredTime) {
}
