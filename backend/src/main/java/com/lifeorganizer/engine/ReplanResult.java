package com.lifeorganizer.engine;

import com.lifeorganizer.domain.DayPlan;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ReplanResult(Map<LocalDate, DayPlan> updatedPlans, List<String> changes) {

    public ReplanResult {
        Objects.requireNonNull(updatedPlans, "updatedPlans");
        Objects.requireNonNull(changes, "changes");
        updatedPlans = Collections.unmodifiableMap(new LinkedHashMap<>(updatedPlans));
        changes = List.copyOf(changes);
    }
}
