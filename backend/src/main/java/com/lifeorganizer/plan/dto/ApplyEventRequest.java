package com.lifeorganizer.plan.dto;

import com.lifeorganizer.api.TimeFormats;
import com.lifeorganizer.domain.Energy;
import com.lifeorganizer.engine.AddAdhoc;
import com.lifeorganizer.engine.CancelBlock;
import com.lifeorganizer.engine.MarkDone;
import com.lifeorganizer.engine.PlanEvent;
import com.lifeorganizer.engine.PostponeBlock;
import com.lifeorganizer.engine.SetEnergy;
import com.lifeorganizer.error.InvalidRequestException;

import java.time.LocalDate;
import java.util.Arrays;

/**
 * One user action on the plan. {@code type} is CANCEL_BLOCK, MARK_DONE, POSTPONE_BLOCK, SET_ENERGY or
 * ADD_ADHOC; only the fields that type needs are read.
 */
public record ApplyEventRequest(String type, String blockId, String reason, String date, String title,
                                String start, String end, String energy) {

    private static final int MAX_ID = 255;
    private static final int MAX_TITLE = 255;
    private static final int MAX_REASON = 500;

    public PlanEvent toEvent(LocalDate today) {
        if (type == null || type.isBlank()) {
            throw new InvalidRequestException("type is required");
        }
        try {
            return switch (type) {
                case "CANCEL_BLOCK" -> new CancelBlock(required(blockId, "blockId", MAX_ID));
                case "MARK_DONE" -> new MarkDone(required(blockId, "blockId", MAX_ID));
                case "POSTPONE_BLOCK" -> new PostponeBlock(required(blockId, "blockId", MAX_ID),
                    optional(reason, "reason", MAX_REASON));
                case "SET_ENERGY" -> new SetEnergy(required(blockId, "blockId", MAX_ID), energyLevel());
                case "ADD_ADHOC" -> new AddAdhoc(
                    date == null || date.isBlank() ? today : TimeFormats.parseDate(date, "date"),
                    required(title, "title", MAX_TITLE),
                    TimeFormats.parseTime(start, "start"),
                    TimeFormats.parseTime(end, "end"));
                default -> throw new InvalidRequestException("Unknown event type '" + type + "'");
            };
        } catch (IllegalArgumentException e) {
            // an engine record's own rule, e.g. AddAdhoc: "end must be after start"
            throw new InvalidRequestException(e.getMessage());
        }
    }

    private Energy energyLevel() {
        if (energy == null || energy.isBlank()) {
            throw new InvalidRequestException("energy is required");
        }
        try {
            return Energy.valueOf(energy);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("energy must be one of "
                + Arrays.toString(Energy.values()) + ", was '" + energy + "'");
        }
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(field + " is required");
        }
        return limited(value, field, max);
    }

    private static String optional(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return limited(value, field, max);
    }

    private static String limited(String value, String field, int max) {
        if (value.length() > max) {
            throw new InvalidRequestException(field + " must be at most " + max + " characters");
        }
        return value;
    }
}
