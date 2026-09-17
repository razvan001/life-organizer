package com.lifeorganizer.engine;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record WeekPlan(Map<LocalDate, List<Unit>> unitsByDate, List<String> warnings) {

    public WeekPlan {
        Objects.requireNonNull(unitsByDate, "unitsByDate");
        Objects.requireNonNull(warnings, "warnings");
        unitsByDate = Collections.unmodifiableMap(new LinkedHashMap<>(unitsByDate));
        warnings = List.copyOf(warnings);
    }
}
