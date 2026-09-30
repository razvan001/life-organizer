package com.lifeorganizer.ai;

import com.lifeorganizer.ai.agent.AgentMessage;
import com.lifeorganizer.ai.agent.AgentReply;
import com.lifeorganizer.ai.agent.ToolSpec;
import com.lifeorganizer.ai.client.GroqAgentClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** One real Groq call that must come back as a tool call. Runs only with a real key. */
@EnabledIfEnvironmentVariable(named = "GROQ_API_KEY", matches = ".+")
class LiveGroqAgentSmokeTest {

    @Test
    void theModelCallsTheToolItIsToldToUse() {
        RestClient restClient = RestClient.builder()
            .baseUrl("https://api.groq.com/openai/v1/chat/completions")
            .defaultHeader("Authorization", "Bearer " + System.getenv("GROQ_API_KEY"))
            .build();
        GroqAgentClient sut = new GroqAgentClient(restClient, "openai/gpt-oss-120b");
        ToolSpec tool = new ToolSpec("get_today_plan", "Returns today's plan blocks.",
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");

        AgentReply reply = sut.chat(List.of(
            AgentMessage.system("You are a planning assistant. Always look at today's plan with the tool before answering."),
            AgentMessage.user("What is on my plan today?")), List.of(tool));

        assertThat(reply.toolCalls()).isNotEmpty();
        assertThat(reply.toolCalls().get(0).name()).isEqualTo("get_today_plan");
    }
}
