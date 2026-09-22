package com.lifeorganizer.config;

import com.anthropic.client.AnthropicClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells the UI which optional features exist. ragEnabled stays false until the RAG phase is built. */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    public record ConfigResponse(boolean aiEnabled, boolean ragEnabled) {
    }

    private final ObjectProvider<AnthropicClient> anthropicClient;

    public ConfigController(ObjectProvider<AnthropicClient> anthropicClient) {
        this.anthropicClient = anthropicClient;
    }

    @GetMapping
    public ConfigResponse config() {
        return new ConfigResponse(anthropicClient.getIfAvailable() != null, false);
    }
}
