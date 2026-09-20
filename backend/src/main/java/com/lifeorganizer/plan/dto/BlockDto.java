package com.lifeorganizer.plan.dto;

public record BlockDto(String id, String date, String start, String end, String title, String kind,
                       String goalId, String status, int postponeCount, boolean flagged, String reason) {
}
