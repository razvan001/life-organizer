package com.lifeorganizer.ai.client;

import com.lifeorganizer.ai.model.LlmCommandResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Wraps the one Groq (OpenAI-compatible) chat-completions call the command feature needs. */
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

    private final GroqChatClient chat;
    private final Map<String, Object> responseFormat =
        GroqChatClient.jsonSchemaResponseFormat("assistant_command_result", RESPONSE_SCHEMA_JSON);

    public GroqCommandClient(RestClient restClient, String model) {
        this.chat = new GroqChatClient(restClient, model);
    }

    @Override
    public LlmCommandResult interpret(String systemPrompt, String userMessage) {
        LlmCommandResult result = chat.call(systemPrompt, userMessage, responseFormat, LlmCommandResult.class);
        log.debug("Groq reply parsed: understood={} events={}", result.understood(), result.events().size());
        return result;
    }
}
