package com.lifeorganizer.config;

import com.lifeorganizer.ai.client.AgentLlmClient;
import com.lifeorganizer.memory.embedding.TextEmbedder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells the UI which optional features exist. */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    public record ConfigResponse(boolean aiEnabled, boolean ragEnabled) {
    }

    private final ObjectProvider<AgentLlmClient> agentLlmClient;
    private final ObjectProvider<TextEmbedder> textEmbedder;

    public ConfigController(ObjectProvider<AgentLlmClient> agentLlmClient,
                            ObjectProvider<TextEmbedder> textEmbedder) {
        this.agentLlmClient = agentLlmClient;
        this.textEmbedder = textEmbedder;
    }

    @GetMapping
    public ConfigResponse config() {
        return new ConfigResponse(agentLlmClient.getIfAvailable() != null, textEmbedder.getIfAvailable() != null);
    }
}
