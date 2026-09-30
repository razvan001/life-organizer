package com.lifeorganizer.ai.agent;

/** One tool the model wants run. {@code arguments} is the model's raw JSON text. */
public record ToolCall(String id, String name, String arguments) {
}
