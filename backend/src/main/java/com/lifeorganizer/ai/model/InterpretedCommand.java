package com.lifeorganizer.ai.model;

import com.lifeorganizer.plan.dto.ApplyEventRequest;

import java.util.List;

/** The result of interpreting one command: a validated set of events, or why there are none. */
public record InterpretedCommand(boolean understood, String rationale, String message, List<ApplyEventRequest> events) {

    public static InterpretedCommand understood(String rationale, List<ApplyEventRequest> events) {
        return new InterpretedCommand(true, rationale, null, events);
    }

    public static InterpretedCommand notUnderstood(String message) {
        return new InterpretedCommand(false, null, message, List.of());
    }
}
