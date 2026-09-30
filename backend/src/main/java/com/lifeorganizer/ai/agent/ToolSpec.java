package com.lifeorganizer.ai.agent;

/** A tool as the model sees it. {@code parametersJson} is the JSON Schema of the arguments object. */
public record ToolSpec(String name, String description, String parametersJson) {
}
