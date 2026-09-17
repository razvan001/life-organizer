package com.lifeorganizer.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

public record Block(
    String id,
    LocalDate date,
    LocalTime start,
    LocalTime end,
    String title,
    BlockKind kind,
    String goalId,
    BlockStatus status,
    int postponeCount,
    boolean flagged,
    String reason
) {

    public Block {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start for block " + id);
        }
        if (postponeCount < 0) {
            throw new IllegalArgumentException("postponeCount must not be negative");
        }
    }

    public int durationMinutes() {
        return (int) java.time.Duration.between(start, end).toMinutes();
    }

    public boolean overlaps(Block other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }
}
