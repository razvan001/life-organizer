package com.lifeorganizer.ai.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Builds the LLM command client, but only when an API key is configured. No key, no bean - every class
 * that depends on {@link LlmCommandClient} must accept that it might not exist: inject an
 * {@code ObjectProvider<LlmCommandClient>}, never the client directly.
 */
@Configuration
public class LlmClientConfig {

    private static final String GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";

    @Bean
    @ConditionalOnProperty(name = "GROQ_API_KEY")
    public LlmCommandClient llmCommandClient(@Value("${GROQ_API_KEY}") String apiKey,
                                              @Value("${app.ai.model:openai/gpt-oss-120b}") String model) {
        return new GroqCommandClient(groqRestClient(apiKey), model);
    }

    @Bean
    @ConditionalOnProperty(name = "GROQ_API_KEY")
    public SetupExtractionClient setupExtractionClient(@Value("${GROQ_API_KEY}") String apiKey,
                                                       @Value("${app.ai.model:openai/gpt-oss-120b}") String model) {
        return new GroqExtractionClient(groqRestClient(apiKey), model);
    }

    @Bean
    @ConditionalOnProperty(name = "GROQ_API_KEY")
    public AgentLlmClient agentLlmClient(@Value("${GROQ_API_KEY}") String apiKey,
                                         @Value("${app.ai.model:openai/gpt-oss-120b}") String model) {
        return new GroqAgentClient(groqRestClient(apiKey), model);
    }

    private static RestClient groqRestClient(String apiKey) {
        return RestClient.builder()
            .baseUrl(GROQ_ENDPOINT)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .build();
    }
}
