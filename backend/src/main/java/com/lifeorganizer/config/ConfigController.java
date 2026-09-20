package com.lifeorganizer.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells the UI which optional features exist. Both stay false until the AI and RAG phases are built. */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    public record ConfigResponse(boolean aiEnabled, boolean ragEnabled) {
    }

    @GetMapping
    public ConfigResponse config() {
        return new ConfigResponse(false, false);
    }
}
