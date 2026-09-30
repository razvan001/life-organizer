package com.lifeorganizer.ai.client;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LlmClientConfigTest {

    private final ApplicationContextRunner contextRunner =
        new ApplicationContextRunner().withUserConfiguration(LlmClientConfig.class);

    @Test
    void noLlmCommandClientBeanWithoutAnApiKey() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(LlmCommandClient.class));
    }

    @Test
    void anLlmCommandClientBeanExistsWhenTheApiKeyIsSet() {
        contextRunner.withPropertyValues("GROQ_API_KEY=gsk_test-dummy-key")
            .run(context -> assertThat(context).hasSingleBean(LlmCommandClient.class));
    }

    @Test
    void noAgentClientWithoutAnApiKey() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(AgentLlmClient.class));
    }

    @Test
    void anAgentClientExistsWhenTheApiKeyIsSet() {
        contextRunner.withPropertyValues("GROQ_API_KEY=gsk_test-dummy-key")
            .run(context -> assertThat(context).hasSingleBean(AgentLlmClient.class));
    }
}
