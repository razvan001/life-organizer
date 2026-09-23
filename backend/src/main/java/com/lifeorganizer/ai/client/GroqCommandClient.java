package com.lifeorganizer.ai.client;

import com.lifeorganizer.ai.model.LlmCommandResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Wraps the one Groq (OpenAI-compatible) chat-completions call this feature needs. */
public class GroqCommandClient implements LlmCommandClient {

    private static final Logger log = LoggerFactory.getLogger(GroqCommandClient.class);

    // Strict JSON Schema for LlmCommandResult/ProposedEvent, hand-written since there is no SDK to
    // generate it from the Java types. Every property must be listed under "required" (Groq/OpenAI
    // strict mode requires the key to be present; a nullable value still uses a ["type", "null"] union).
    private static final String RESPONSE_SCHEMA_JSON = """
        {
          "type": "object",
          "properties": {
            "understood": {"type": "boolean"},
            "rationale": {"type": ["string", "null"]},
            "message": {"type": ["string", "null"]},
            "events": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "type": {"type": "string", "enum": ["CANCEL_BLOCK", "POSTPONE_BLOCK", "MARK_DONE", "SET_ENERGY", "ADD_ADHOC"]},
                  "blockId": {"type": ["string", "null"]},
                  "reason": {"type": ["string", "null"]},
                  "date": {"type": ["string", "null"]},
                  "title": {"type": ["string", "null"]},
                  "start": {"type": ["string", "null"]},
                  "end": {"type": ["string", "null"]},
                  "energy": {"type": ["string", "null"]}
                },
                "required": ["type", "blockId", "reason", "date", "title", "start", "end", "energy"],
                "additionalProperties": false
              }
            }
          },
          "required": ["understood", "rationale", "message", "events"],
          "additionalProperties": false
        }
        """;

    private final RestClient restClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final String model;
    private final Map<String, Object> responseFormat;

    public GroqCommandClient(RestClient restClient, String model) {
        this.restClient = restClient;
        this.model = model;
        this.responseFormat = buildResponseFormat();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildResponseFormat() {
        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", "assistant_command_result");
        jsonSchema.put("strict", true);
        jsonSchema.put("schema", jsonMapper.readValue(RESPONSE_SCHEMA_JSON, Map.class));
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "json_schema");
        format.put("json_schema", jsonSchema);
        return format;
    }

    @Override
    public LlmCommandResult interpret(String systemPrompt, String userMessage) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userMessage)));
        body.put("response_format", responseFormat);

        log.debug("Calling Groq model={} userMessage.length={}", model, userMessage.length());

        // RestClient's default behaviour throws RestClientResponseException for any non-2xx status,
        // and ResourceAccessException for network/timeout failures - both are RestClientException
        // subtypes, which is exactly the "connectivity failure" signal CommandInterpreter/AssistantController
        // let propagate untouched (never caught here, only logged before it propagates).
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
            LlmCommandResult result = jsonMapper.readValue(content, LlmCommandResult.class);
            log.debug("Groq reply parsed: understood={} events={}", result.understood(), result.events().size());
            return result;
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
