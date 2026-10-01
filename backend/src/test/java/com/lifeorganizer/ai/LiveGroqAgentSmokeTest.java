package com.lifeorganizer.ai;

import com.lifeorganizer.ai.agent.AgentMessage;
import com.lifeorganizer.ai.agent.AgentReply;
import com.lifeorganizer.ai.agent.ToolCall;
import com.lifeorganizer.ai.agent.ToolSpec;
import com.lifeorganizer.ai.client.GroqAgentClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A real Groq round trip: the model calls the tool, gets a result, and answers from it. Runs only with a real key. */
@EnabledIfEnvironmentVariable(named = "GROQ_API_KEY", matches = ".+")
class LiveGroqAgentSmokeTest {

    private static final String PLAN_RESULT = "{\"date\":\"2026-09-30\",\"blocks\":[{\"id\":\"gym-1\",\"title\":\"Gym\","
        + "\"kind\":\"GOAL\",\"status\":\"PLANNED\",\"start\":\"07:00\",\"end\":\"08:00\"}]}";

    @Test
    void theModelCallsTheToolThenAnswersFromItsResult() {
        RestClient restClient = RestClient.builder()
            .baseUrl("https://api.groq.com/openai/v1/chat/completions")
            .defaultHeader("Authorization", "Bearer " + System.getenv("GROQ_API_KEY"))
            .build();
        GroqAgentClient sut = new GroqAgentClient(restClient, "openai/gpt-oss-120b");
        ToolSpec tool = new ToolSpec("get_today_plan", "Returns today's plan blocks.",
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");
        List<AgentMessage> messages = new ArrayList<>(List.of(
            AgentMessage.system("You are a planning assistant. Always look at today's plan with the tool before answering."),
            AgentMessage.user("What is on my plan today?")));

        AgentReply first = sut.chat(messages, List.of(tool));

        assertThat(first.toolCalls()).isNotEmpty();
        assertThat(first.toolCalls().get(0).name()).isEqualTo("get_today_plan");

        messages.add(AgentMessage.assistant(first.content(), first.toolCalls()));
        for (ToolCall call : first.toolCalls()) {
            messages.add(AgentMessage.tool(call.id(), PLAN_RESULT));
        }
        AgentReply second = sut.chat(messages, List.of(tool));

        assertThat(second.content()).isNotBlank();
        assertThat(second.content().toLowerCase()).contains("gym");
    }
}
