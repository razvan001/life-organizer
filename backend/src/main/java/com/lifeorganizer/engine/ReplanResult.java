package com.lifeorganizer.engine;

import com.lifeorganizer.domain.DayPlan;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record ReplanResult(Map<LocalDate, DayPlan> updatedPlans, List<String> changes) {}
