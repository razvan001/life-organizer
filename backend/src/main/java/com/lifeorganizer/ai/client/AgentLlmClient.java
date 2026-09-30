package com.lifeorganizer.ai.client;

import com.lifeorganizer.ai.agent.AgentMessage;
import com.lifeorganizer.ai.agent.AgentReply;
import com.lifeorganizer.ai.agent.ToolSpec;

import java.util.List;

/**
 * One model call with tools. Throws {@code RestClientException} when the provider is unreachable or answers
 * non-2xx, and {@link MalformedLlmReplyException} when the reply has neither text nor tool calls.
 */
public interface AgentLlmClient {

    AgentReply chat(List<AgentMessage> messages, List<ToolSpec> tools);
}
