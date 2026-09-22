package com.lifeorganizer.ai.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonSchemaLocalValidation;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.lifeorganizer.ai.model.ClaudeCommandResult;

/** Wraps the one Anthropic SDK call this feature needs. */
public class AnthropicCommandClient implements ClaudeCommandClient {

    private static final long MAX_TOKENS = 1024;

    private final AnthropicClient client;
    private final Model model;

    public AnthropicCommandClient(AnthropicClient client, String model) {
        this.client = client;
        this.model = Model.of(model);
    }

    @Override
    public ClaudeCommandResult interpret(String systemPrompt, String userMessage) {
        StructuredMessageCreateParams<ClaudeCommandResult> params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .system(systemPrompt)
            .addUserMessage(userMessage)
            .outputConfig(ClaudeCommandResult.class, JsonSchemaLocalValidation.YES)
            .build();

        StructuredMessage<ClaudeCommandResult> message = client.messages().create(params);
        return message.content().stream()
            .flatMap(block -> block.text().stream())
            .findFirst()
            .map(text -> text.text())
            .orElseThrow(() -> new MalformedClaudeReplyException("Claude returned no structured text content"));
    }
}
