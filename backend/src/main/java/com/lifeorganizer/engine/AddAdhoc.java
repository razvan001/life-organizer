package com.lifeorganizer.engine;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

public record AddAdhoc(LocalDate date, String title, LocalTime start, LocalTime end) implements PlanEvent {

    public AddAdhoc {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
    }
}
