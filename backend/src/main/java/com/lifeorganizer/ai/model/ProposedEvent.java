package com.lifeorganizer.ai.model;

/**
 * One event the model proposes, in (almost) the shape {@link com.lifeorganizer.plan.dto.ApplyEventRequest}
 * already parses. Fields a given {@link #type()} does not need are null - the model is asked to send an
 * explicit JSON null for them, and the hand-written JSON Schema in {@link com.lifeorganizer.ai.client.GroqCommandClient} marks each
 * of these fields nullable.
 *
 * <p>Field meanings: {@code type} is one of CANCEL_BLOCK, POSTPONE_BLOCK, MARK_DONE, SET_ENERGY, ADD_ADHOC.
 * {@code blockId} is required for every type except ADD_ADHOC, and must be exactly one of the ids listed
 * in the prompt. {@code reason} is an optional short reason, only ever used for POSTPONE_BLOCK.
 * {@code date}/{@code title}/{@code start}/{@code end} are only used for ADD_ADHOC (date yyyy-MM-dd,
 * start/end HH:mm). {@code energy} (LOW or MEDIUM) is only used for SET_ENERGY.
 */
public record ProposedEvent(
    ProposedEventType type,
    String blockId,
    String reason,
    String date,
    String title,
    String start,
    String end,
    String energy
) {}
