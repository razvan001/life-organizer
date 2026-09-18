package com.lifeorganizer.engine;

import java.util.Objects;

public record CancelBlock(String blockId) implements PlanEvent {

    public CancelBlock {
        Objects.requireNonNull(blockId, "blockId");
    }
}
