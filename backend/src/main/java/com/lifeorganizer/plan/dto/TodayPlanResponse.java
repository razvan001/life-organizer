package com.lifeorganizer.plan.dto;

import java.util.List;

public record TodayPlanResponse(String date, List<BlockDto> blocks, List<String> warnings) {
}
