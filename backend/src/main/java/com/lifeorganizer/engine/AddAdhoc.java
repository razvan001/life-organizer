package com.lifeorganizer.engine;

import java.time.LocalDate;
import java.time.LocalTime;

public record AddAdhoc(LocalDate date, String title, LocalTime start, LocalTime end) implements PlanEvent {}
