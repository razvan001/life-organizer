package com.lifeorganizer.ai.agent;

import java.util.List;

/** The model's answer to one call: text, tool calls, or both. */
public record AgentReply(String content, List<ToolCall> toolCalls) {

    public AgentReply {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
