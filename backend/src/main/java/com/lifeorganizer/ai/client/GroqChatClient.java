package com.lifeorganizer.ai.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place that speaks Groq's (OpenAI-compatible) chat-completions protocol: builds the request, posts
 * it and parses {@code choices[0].message.content} as JSON into the caller's target type.
 */
class GroqChatClient {

    private static final Logger log = LoggerFactory.getLogger(GroqChatClient.class);

    private final RestClient restClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final String model;

    GroqChatClient(RestClient restClient, String model) {
        this.restClient = restClient;
        this.model = model;
    }

    /**
     * Wraps a hand-written JSON Schema as a strict {@code response_format}. Groq/OpenAI strict mode requires
     * every property to be listed under "required"; a nullable value still uses a ["type", "null"] union.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> jsonSchemaResponseFormat(String schemaName, String schemaJson) {
        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", schemaName);
        jsonSchema.put("strict", true);
        jsonSchema.put("schema", JsonMapper.builder().build().readValue(schemaJson, Map.class));
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "json_schema");
        format.put("json_schema", jsonSchema);
        return format;
    }

    <T> T call(String systemPrompt, String userMessage, Map<String, Object> responseFormat, Class<T> targetType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userMessage)));
        body.put("response_format", responseFormat);

        log.debug("Calling Groq model={} userMessage.length={}", model, userMessage.length());

        // RestClient's default behaviour throws RestClientResponseException for any non-2xx status,
        // and ResourceAccessException for network/timeout failures - both are RestClientException
        // subtypes, which is exactly the "connectivity failure" signal callers let propagate untouched
        // (never caught here, only logged before it propagates).
        GroqChatResponse response;
        try {
            response = restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(GroqChatResponse.class);
        } catch (RestClientException e) {
            log.warn("Groq call failed: {}", e.getMessage());
            throw e;
        }

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            log.warn("Groq returned no choices: {}", response);
            throw new MalformedLlmReplyException("Groq returned no choices");
        }
        String content = response.choices().get(0).message().content();
        if (content == null || content.isBlank()) {
            log.warn("Groq returned an empty message");
            throw new MalformedLlmReplyException("Groq returned no message content");
        }
        log.debug("Groq raw reply content: {}", content);
        try {
            return jsonMapper.readValue(content, targetType);
        } catch (RuntimeException e) {
            log.warn("Could not parse Groq's reply as the expected JSON shape: {}", content, e);
            throw new MalformedLlmReplyException("Could not parse Groq's reply as the expected JSON shape");
        }
    }

    private record GroqChatResponse(List<Choice> choices) {
    }

    private record Choice(ChatMessage message) {
    }

    private record ChatMessage(String content) {
    }
}
