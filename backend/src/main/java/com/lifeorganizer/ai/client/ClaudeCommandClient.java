package com.lifeorganizer.ai.client;

import com.lifeorganizer.ai.model.ClaudeCommandResult;

/**
 * The only interface that touches the Anthropic SDK. {@code interpret} sends the already-built prompt
 * and returns Claude's raw structured answer, or throws {@link com.anthropic.errors.AnthropicException}
 * on a connectivity failure (network, auth, rate limit, timeout) - it does no validation of the
 * content, that is {@link com.lifeorganizer.ai.service.CommandInterpreter}'s job.
 */
public interface ClaudeCommandClient {

    ClaudeCommandResult interpret(String systemPrompt, String userMessage);
}
