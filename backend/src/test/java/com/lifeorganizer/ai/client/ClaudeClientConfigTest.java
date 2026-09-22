package com.lifeorganizer.ai.client;

import com.anthropic.client.AnthropicClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeClientConfigTest {

    private final ApplicationContextRunner contextRunner =
        new ApplicationContextRunner().withUserConfiguration(ClaudeClientConfig.class);

    @Test
    void noAnthropicClientBeanWithoutAnApiKey() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(AnthropicClient.class));
    }

    @Test
    void anAnthropicClientBeanExistsWhenTheApiKeyIsSet() {
        contextRunner.withPropertyValues("ANTHROPIC_API_KEY=sk-ant-test-dummy-key")
            .run(context -> assertThat(context).hasSingleBean(AnthropicClient.class));
    }
}
