package com.lifeorganizer.engine;

import com.lifeorganizer.domain.Energy;

public record SetEnergy(String blockId, Energy level) implements PlanEvent {}
