package com.lifeorganizer.ai.client;

import com.lifeorganizer.ai.model.SetupExtractionResult;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Groq-backed brain-dump extraction. */
public class GroqExtractionClient implements SetupExtractionClient {

    // Strict JSON Schema for SetupExtractionResult. energy/preferredTime/days carry no enum on purpose:
    // the service defaults or drops invalid values anyway, and enum inside a nullable union is unevenly supported.
    private static final String RESPONSE_SCHEMA_JSON = """
        {
          "type": "object",
          "properties": {
            "categories": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {"id": {"type": "string"}, "name": {"type": "string"}},
                "required": ["id", "name"],
                "additionalProperties": false
              }
            },
            "goals": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "id": {"type": "string"},
                  "categoryId": {"type": "string"},
                  "name": {"type": "string"},
                  "priority": {"type": ["integer", "null"]},
                  "deadline": {"type": ["string", "null"]},
                  "defaultBlockMinutes": {"type": ["integer", "null"]},
                  "energy": {"type": ["string", "null"]},
                  "preferredTime": {"type": ["string", "null"]}
                },
                "required": ["id", "categoryId", "name", "priority", "deadline", "defaultBlockMinutes", "energy", "preferredTime"],
                "additionalProperties": false
              }
            },
            "constraints": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "type": {"type": "string", "enum": ["FIXED_BLOCK", "FREQUENCY_QUOTA", "DURATION_QUOTA", "EVENING_QUOTA"]},
                  "label": {"type": "string"},
                  "start": {"type": ["string", "null"]},
                  "end": {"type": ["string", "null"]},
                  "days": {"type": ["array", "null"], "items": {"type": "string"}},
                  "timesPerWeek": {"type": ["integer", "null"]},
                  "sessionMinutes": {"type": ["integer", "null"]},
                  "goalId": {"type": ["string", "null"]},
                  "minutesPerWeek": {"type": ["integer", "null"]},
                  "categoryId": {"type": ["string", "null"]},
                  "eveningsPerWeek": {"type": ["integer", "null"]}
                },
                "required": ["type", "label", "start", "end", "days", "timesPerWeek", "sessionMinutes", "goalId", "minutesPerWeek", "categoryId", "eveningsPerWeek"],
                "additionalProperties": false
              }
            },
            "warnings": {"type": "array", "items": {"type": "string"}}
          },
          "required": ["categories", "goals", "constraints", "warnings"],
          "additionalProperties": false
        }
        """;

    private final GroqChatClient chat;
    private final Map<String, Object> responseFormat =
        GroqChatClient.jsonSchemaResponseFormat("setup_extraction_result", RESPONSE_SCHEMA_JSON);

    public GroqExtractionClient(RestClient restClient, String model) {
        this.chat = new GroqChatClient(restClient, model);
    }

    @Override
    public SetupExtractionResult extract(String systemPrompt, String userMessage) {
        return chat.call(systemPrompt, userMessage, responseFormat, SetupExtractionResult.class);
    }
}
