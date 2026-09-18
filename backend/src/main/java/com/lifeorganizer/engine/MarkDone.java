package com.lifeorganizer.engine;

import java.util.Objects;

public record MarkDone(String blockId) implements PlanEvent {

    public MarkDone {
        Objects.requireNonNull(blockId, "blockId");
    }
}
