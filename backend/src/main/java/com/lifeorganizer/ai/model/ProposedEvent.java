package com.lifeorganizer.ai.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.Optional;

/**
 * One event Claude proposes, in (almost) the shape {@link com.lifeorganizer.plan.dto.ApplyEventRequest}
 * already parses. Optional fields let the model send an explicit JSON null for whatever a given
 * {@link #type()} does not need, which the Anthropic SDK's structured-output schema generator only
 * offers for {@code Optional<T>} fields (a plain String field is generated as required and non-null).
 */
public record ProposedEvent(
    @JsonPropertyDescription("One of CANCEL_BLOCK, POSTPONE_BLOCK, MARK_DONE, SET_ENERGY, ADD_ADHOC.")
    ProposedEventType type,
    @JsonPropertyDescription("The block id this event acts on. Required for CANCEL_BLOCK, POSTPONE_BLOCK, "
        + "MARK_DONE and SET_ENERGY, and must be exactly one of the ids listed in the prompt. Null for ADD_ADHOC.")
    Optional<String> blockId,
    @JsonPropertyDescription("Optional short reason. Only ever used for POSTPONE_BLOCK.")
    Optional<String> reason,
    @JsonPropertyDescription("The date for a new ADD_ADHOC block, yyyy-MM-dd. Null for every other type.")
    Optional<String> date,
    @JsonPropertyDescription("The title of a new ADD_ADHOC block. Null for every other type.")
    Optional<String> title,
    @JsonPropertyDescription("Start time of a new ADD_ADHOC block, HH:mm. Null for every other type.")
    Optional<String> start,
    @JsonPropertyDescription("End time of a new ADD_ADHOC block, HH:mm. Null for every other type.")
    Optional<String> end,
    @JsonPropertyDescription("LOW or MEDIUM. Only ever used for SET_ENERGY.")
    Optional<String> energy
) {}
