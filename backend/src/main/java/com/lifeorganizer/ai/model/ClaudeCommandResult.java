package com.lifeorganizer.ai.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;
import java.util.Optional;

/** Claude's raw answer to one command: either a rationale plus events, or an explanation why not. */
public record ClaudeCommandResult(
    @JsonPropertyDescription("True only if the text maps to at least one concrete, executable event "
        + "against a real block from the list given in the prompt.")
    boolean understood,
    @JsonPropertyDescription("One short sentence explaining the change. Present only when understood is true.")
    Optional<String> rationale,
    @JsonPropertyDescription("One short sentence explaining why the text could not be understood. "
        + "Present only when understood is false.")
    Optional<String> message,
    @JsonPropertyDescription("Zero or more events. Empty when understood is false. At most 5.")
    List<ProposedEvent> events
) {}
