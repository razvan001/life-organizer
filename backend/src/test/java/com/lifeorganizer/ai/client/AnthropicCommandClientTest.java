package com.lifeorganizer.ai.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.StructuredContentBlock;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredTextBlock;
import com.anthropic.services.blocking.MessageService;
import com.lifeorganizer.ai.model.ClaudeCommandResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnthropicCommandClientTest {

    @SuppressWarnings("unchecked")
    @Test
    void sendsTheGivenPromptAndReturnsTheParsedReply() {
        AnthropicClient client = mock(AnthropicClient.class);
        MessageService messages = mock(MessageService.class);
        when(client.messages()).thenReturn(messages);

        StructuredMessage<ClaudeCommandResult> reply = mock(StructuredMessage.class);
        StructuredContentBlock<ClaudeCommandResult> block = mock(StructuredContentBlock.class);
        StructuredTextBlock<ClaudeCommandResult> textBlock = mock(StructuredTextBlock.class);
        ClaudeCommandResult expected = new ClaudeCommandResult(true, Optional.of("done"), Optional.empty(), List.of());
        when(reply.content()).thenReturn(List.of(block));
        when(block.text()).thenReturn(Optional.of(textBlock));
        when(textBlock.text()).thenReturn(expected);
        when(messages.create(any(StructuredMessageCreateParams.class))).thenReturn(reply);

        AnthropicCommandClient sut = new AnthropicCommandClient(client, "claude-sonnet-5");
        ClaudeCommandResult result = sut.interpret("system prompt text", "user text");

        assertThat(result).isEqualTo(expected);
        verify(messages).create(any(StructuredMessageCreateParams.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void failsClearlyWhenTheReplyHasNoTextContent() {
        AnthropicClient client = mock(AnthropicClient.class);
        MessageService messages = mock(MessageService.class);
        when(client.messages()).thenReturn(messages);
        StructuredMessage<ClaudeCommandResult> reply = mock(StructuredMessage.class);
        when(reply.content()).thenReturn(List.of());
        when(messages.create(any(StructuredMessageCreateParams.class))).thenReturn(reply);

        AnthropicCommandClient sut = new AnthropicCommandClient(client, "claude-sonnet-5");

        assertThatThrownBy(() -> sut.interpret("s", "u")).isInstanceOf(IllegalStateException.class);
    }
}
