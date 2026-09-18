package com.lifeorganizer.domain;

import java.util.List;
import java.util.Objects;

public record Conflict(ConflictType type, String message, List<String> relatedBlockIds) {

    public Conflict {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(relatedBlockIds, "relatedBlockIds");
        relatedBlockIds = List.copyOf(relatedBlockIds);
    }
}
