package com.lifeorganizer.engine;

import java.util.Objects;

public record PostponeBlock(String blockId, String reason) implements PlanEvent {

    public PostponeBlock {
        Objects.requireNonNull(blockId, "blockId");
    }
}
