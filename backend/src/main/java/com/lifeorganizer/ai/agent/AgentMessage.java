package com.lifeorganizer.ai.agent;

import java.util.List;

/** One message in the model conversation. Only ASSISTANT messages carry tool calls; only TOOL messages a tool call id. */
public record AgentMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public AgentMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static AgentMessage system(String content) {
        return new AgentMessage(Role.SYSTEM, content, List.of(), null);
    }

    public static AgentMessage user(String content) {
        return new AgentMessage(Role.USER, content, List.of(), null);
    }

    public static AgentMessage assistant(String content, List<ToolCall> toolCalls) {
        return new AgentMessage(Role.ASSISTANT, content, toolCalls, null);
    }

    public static AgentMessage tool(String toolCallId, String content) {
        return new AgentMessage(Role.TOOL, content, List.of(), toolCallId);
    }
}
