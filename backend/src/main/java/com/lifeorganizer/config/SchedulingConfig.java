package com.lifeorganizer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled jobs (the nightly plan-history indexing). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
