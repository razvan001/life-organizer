package com.lifeorganizer.ai.agent.tools;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** JSON helpers shared by the tools. */
public final class ToolJson {

    /** Lenient on input: the model may send fields a request type does not know. */
    public static final JsonMapper MAPPER = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    private ToolJson() {
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static String requiredText(JsonNode args, String field, int max) {
        String value = optionalText(args, field, max);
        if (value == null) {
            throw new ToolException(field + " is required");
        }
        return value;
    }

    public static String optionalText(JsonNode args, String field, int max) {
        JsonNode node = args.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new ToolException(field + " must be a string");
        }
        String value = node.asString().strip();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > max) {
            throw new ToolException(field + " must be at most " + max + " characters");
        }
        return value;
    }
}
