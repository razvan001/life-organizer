package com.lifeorganizer.ai.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Builds the LLM client ({@link AgentLlmClient}), but only when an API key
 * is configured. No key, no bean - every class that depends on one of them must accept that it might not
 * exist: inject an {@code ObjectProvider}, never the client directly.
 */
@Configuration
public class LlmClientConfig {

    private static final String GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";

    @Bean
    @ConditionalOnProperty(name = "GROQ_API_KEY")
    public AgentLlmClient agentLlmClient(@Value("${GROQ_API_KEY}") String apiKey,
                                         @Value("${app.ai.model:openai/gpt-oss-120b}") String model,
                                         @Value("${app.ai.reasoning-effort:low}") String reasoningEffort) {
        return new GroqAgentClient(groqRestClient(apiKey), model, reasoningEffort);
    }

    private static RestClient groqRestClient(String apiKey) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder()
            .requestFactory(requestFactory)
            .baseUrl(GROQ_ENDPOINT)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
    }
}
