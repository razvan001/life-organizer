package com.lifeorganizer.plan.dto;

import java.util.List;

public record ApplyEventsResponse(String date, List<BlockDto> blocks, List<String> warnings, List<String> changes) {
}
