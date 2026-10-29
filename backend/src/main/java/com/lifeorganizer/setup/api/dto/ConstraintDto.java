package com.lifeorganizer.setup.api.dto;

import java.util.List;

/** A fixed block such as Work or Sleep. {@code type} is always FIXED_BLOCK, the only kind of constraint. */
public record ConstraintDto(String type, String label, String start, String end, List<String> days) {
}
