package com.lifeorganizer.plan.dto;

import java.util.List;

public record ApplyEventResponse(String date, List<BlockDto> blocks, List<String> warnings, List<String> changes) {
}
