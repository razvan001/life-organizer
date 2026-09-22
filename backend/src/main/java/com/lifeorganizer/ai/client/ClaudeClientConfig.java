package com.lifeorganizer.ai.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the Anthropic SDK client, but only when an API key is configured. No key, no bean - every
 * class that depends on {@link AnthropicClient} (or, transitively, {@link ClaudeCommandClient}) must
 * accept that it might not exist: inject an {@code ObjectProvider<...>}, never the bean directly.
 */
@Configuration
public class ClaudeClientConfig {

    @Bean
    @ConditionalOnProperty(name = "ANTHROPIC_API_KEY")
    public AnthropicClient anthropicClient(@Value("${ANTHROPIC_API_KEY}") String apiKey) {
        return AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }
}
