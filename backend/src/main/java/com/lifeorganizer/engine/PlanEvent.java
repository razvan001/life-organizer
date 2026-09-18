package com.lifeorganizer.engine;

public sealed interface PlanEvent
    permits CancelBlock, PostponeBlock, MarkDone, SetEnergy, AddAdhoc {}
