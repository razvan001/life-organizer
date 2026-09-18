package com.lifeorganizer.engine;

public record PostponeBlock(String blockId, String reason) implements PlanEvent {}
