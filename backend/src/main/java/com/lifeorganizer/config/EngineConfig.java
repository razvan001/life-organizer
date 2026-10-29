package com.lifeorganizer.config;

import com.lifeorganizer.domain.EngineSettings;
import com.lifeorganizer.engine.DayScheduler;
import com.lifeorganizer.engine.PostponePolicy;
import com.lifeorganizer.engine.Replanner;
import com.lifeorganizer.engine.WeekAllocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires the framework-free Phase 1a engine into the Spring context. */
@Configuration
public class EngineConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public EngineSettings engineSettings() {
        return EngineSettings.defaults();
    }

    @Bean
    public WeekAllocator weekAllocator() {
        return new WeekAllocator();
    }

    @Bean
    public DayScheduler dayScheduler(EngineSettings settings) {
        return new DayScheduler(settings);
    }

    @Bean
    public PostponePolicy postponePolicy() {
        return new PostponePolicy();
    }

    @Bean
    public Replanner replanner(PostponePolicy postponePolicy) {
        return new Replanner(postponePolicy);
    }
}
