package com.lifeorganizer.ai.client;

import com.lifeorganizer.ai.model.LlmCommandResult;

/**
 * The only interface that touches the LLM provider's transport. {@code interpret} sends the already-built
 * prompt and returns the model's raw structured answer, or throws {@link org.springframework.web.client.RestClientException}
 * on a connectivity failure (network, auth, rate limit, timeout, non-2xx status) - it does no validation
 * of the content, that is {@link com.lifeorganizer.ai.service.CommandInterpreter}'s job.
 */
public interface LlmCommandClient {

    LlmCommandResult interpret(String systemPrompt, String userMessage);
}
