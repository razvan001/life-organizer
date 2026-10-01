package com.lifeorganizer.ai.client;

import com.lifeorganizer.ai.agent.AgentMessage;
import com.lifeorganizer.ai.agent.AgentReply;
import com.lifeorganizer.ai.agent.ToolCall;
import com.lifeorganizer.ai.agent.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Groq chat completions with OpenAI-style function tools (tools / tool_calls / tool messages). */
public class GroqAgentClient implements AgentLlmClient {

    private static final Logger log = LoggerFactory.getLogger(GroqAgentClient.class);

    private final GroqChatClient chat;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public GroqAgentClient(RestClient restClient, String model) {
        this.chat = new GroqChatClient(restClient, model);
    }

    @Override
    public AgentReply chat(List<AgentMessage> messages, List<ToolSpec> tools) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("tools", tools.stream().map(this::toolJson).toList());
        extra.put("tool_choice", "auto");
        GroqChatClient.ChatMessage message;
        try {
            message = chat.send(messages.stream().map(GroqAgentClient::messageJson).toList(), extra);
        } catch (HttpClientErrorException.BadRequest e) {
            String body = e.getResponseBodyAsString();
            if (body.contains("tool_use_failed")) {
                throw new ToolCallRejectedException(rejectionMessage(body));
            }
            throw e;
        }

        List<ToolCall> calls = message.toolCalls() == null ? List.of() : message.toolCalls().stream()
            .map(call -> new ToolCall(call.id(),
                call.function() == null ? null : call.function().name(),
                call.function() == null ? null : call.function().arguments()))
            .toList();
        boolean noText = message.content() == null || message.content().isBlank();
        if (noText && calls.isEmpty()) {
            log.warn("Groq returned neither text nor tool calls");
            throw new MalformedLlmReplyException("Groq returned neither text nor tool calls");
        }
        log.debug("Groq agent reply: text.length={} toolCalls={}", noText ? 0 : message.content().length(),
            calls.stream().map(ToolCall::name).toList());
        return new AgentReply(noText ? null : message.content(), calls);
    }

    private String rejectionMessage(String body) {
        try {
            JsonNode message = jsonMapper.readTree(body).path("error").path("message");
            if (message.isString() && !message.asString().isBlank()) {
                return message.asString();
            }
        } catch (RuntimeException e) {
            log.debug("Could not parse Groq's tool_use_failed body", e);
        }
        return "the tool call was not valid";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolJson(ToolSpec spec) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", spec.name());
        function.put("description", spec.description());
        function.put("parameters", jsonMapper.readValue(spec.parametersJson(), Map.class));
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    static Map<String, Object> messageJson(AgentMessage message) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("role", message.role().name().toLowerCase(Locale.ROOT));
        switch (message.role()) {
            case ASSISTANT -> {
                json.put("content", message.content() == null ? "" : message.content());
                if (!message.toolCalls().isEmpty()) {
                    json.put("tool_calls", message.toolCalls().stream().map(call -> {
                        Map<String, Object> function = new LinkedHashMap<>();
                        function.put("name", call.name());
                        function.put("arguments", call.arguments() == null ? "{}" : call.arguments());
                        Map<String, Object> toolCall = new LinkedHashMap<>();
                        toolCall.put("id", call.id());
                        toolCall.put("type", "function");
                        toolCall.put("function", function);
                        return toolCall;
                    }).toList());
                }
            }
            case TOOL -> {
                json.put("tool_call_id", message.toolCallId());
                json.put("content", message.content());
            }
            default -> json.put("content", message.content());
        }
        return json;
    }
}
