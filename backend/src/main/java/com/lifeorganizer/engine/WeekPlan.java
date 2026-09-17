package com.lifeorganizer.engine;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record WeekPlan(Map<LocalDate, List<Unit>> unitsByDate, List<String> warnings) {

    public WeekPlan {
        Objects.requireNonNull(unitsByDate, "unitsByDate");
        Objects.requireNonNull(warnings, "warnings");
        unitsByDate = Map.copyOf(unitsByDate);
        warnings = List.copyOf(warnings);
    }
}
