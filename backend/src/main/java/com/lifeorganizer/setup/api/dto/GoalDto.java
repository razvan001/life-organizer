package com.lifeorganizer.setup.api.dto;

public record GoalDto(String id, String categoryId, String name, int priority, String deadline,
                      int defaultBlockMinutes, String energy, String preferredTime) {
}
