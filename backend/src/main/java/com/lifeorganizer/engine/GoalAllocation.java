package com.lifeorganizer.engine;

import com.lifeorganizer.domain.Goal;

public record GoalAllocation(Goal goal, int minutesPerWeek) {}
