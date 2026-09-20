package com.lifeorganizer.api;

import com.lifeorganizer.error.InvalidRequestException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/** The only place that converts between java.time values and the API's string formats. */
public final class TimeFormats {

    // STRICT on purpose: the default SMART style silently accepts "24:00" as midnight (verified).
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm")
        .withResolverStyle(ResolverStyle.STRICT);

    private TimeFormats() {
    }

    public static String format(LocalTime time) {
        return time.format(HH_MM);
    }

    public static String format(LocalDate date) {
        return date.toString();
    }

    public static LocalTime parseTime(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(field + " is required (HH:mm)");
        }
        try {
            return LocalTime.parse(value, HH_MM);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException(field + " must be HH:mm, was '" + value + "'");
        }
    }

    public static LocalDate parseDate(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(field + " is required (yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException(field + " must be yyyy-MM-dd, was '" + value + "'");
        }
    }
}
