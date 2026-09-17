package com.lifeorganizer.engine;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record WeekPlan(Map<LocalDate, List<Unit>> unitsByDate, List<String> warnings) {}
