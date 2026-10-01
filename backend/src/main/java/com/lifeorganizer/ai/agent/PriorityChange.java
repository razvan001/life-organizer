package com.lifeorganizer.ai.agent;

/** One goal's priority change in a weekly-review proposal: from the current value to the proposed one. */
public record PriorityChange(String goalId, String goalName, int from, int to, String reason) {
}
