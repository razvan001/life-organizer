package com.lifeorganizer.config;

import com.lifeorganizer.ai.client.LlmCommandClient;
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

    private final ObjectProvider<LlmCommandClient> llmCommandClient;
    private final ObjectProvider<TextEmbedder> textEmbedder;

    public ConfigController(ObjectProvider<LlmCommandClient> llmCommandClient,
                            ObjectProvider<TextEmbedder> textEmbedder) {
        this.llmCommandClient = llmCommandClient;
        this.textEmbedder = textEmbedder;
    }

    @GetMapping
    public ConfigResponse config() {
        return new ConfigResponse(llmCommandClient.getIfAvailable() != null, textEmbedder.getIfAvailable() != null);
    }
}
