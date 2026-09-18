package com.lifeorganizer.engine;

import com.lifeorganizer.domain.Energy;
import java.util.Objects;

public record SetEnergy(String blockId, Energy level) implements PlanEvent {

    public SetEnergy {
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(level, "level");
    }
}
