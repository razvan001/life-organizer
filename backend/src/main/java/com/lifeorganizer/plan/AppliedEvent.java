package com.lifeorganizer.plan;

import com.lifeorganizer.domain.DayPlan;

import java.util.List;

/** The outcome of applying one plan event: today's plan afterwards plus the replanner's change messages. */
public record AppliedEvent(DayPlan today, List<String> changes) {
}
