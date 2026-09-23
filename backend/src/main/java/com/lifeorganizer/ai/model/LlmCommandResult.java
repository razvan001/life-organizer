package com.lifeorganizer.ai.model;

import java.util.List;

/**
 * The model's raw answer to one command: either a rationale plus events, or an explanation why not.
 * {@code rationale} is present only when {@code understood} is true; {@code message} only when false.
 * {@code events} is empty when not understood, otherwise at most 5 entries.
 */
public record LlmCommandResult(boolean understood, String rationale, String message, List<ProposedEvent> events) {
}
