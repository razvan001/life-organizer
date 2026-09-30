package com.lifeorganizer.ai.client;

import com.lifeorganizer.ai.agent.AgentMessage;
import com.lifeorganizer.ai.agent.AgentReply;
import com.lifeorganizer.ai.agent.ToolCall;
import com.lifeorganizer.ai.agent.ToolSpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GroqAgentClientTest {

    private static final String ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";
    private static final ToolSpec TOOL = new ToolSpec("get_today_plan", "Today's blocks",
        "{\"type\":\"object\",\"properties\":{}}");

    private MockRestServiceServer server;
    private GroqAgentClient sut;

    private void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(ENDPOINT);
        server = MockRestServiceServer.bindTo(builder).build();
        sut = new GroqAgentClient(builder.build(), "openai/gpt-oss-120b");
    }

    @Test
    void sendsToolsAndTheWholeConversationAndParsesToolCalls() {
        setUp();
        server.expect(requestTo(ENDPOINT))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.model").value("openai/gpt-oss-120b"))
            .andExpect(jsonPath("$.tool_choice").value("auto"))
            .andExpect(jsonPath("$.tools[0].type").value("function"))
            .andExpect(jsonPath("$.tools[0].function.name").value("get_today_plan"))
            .andExpect(jsonPath("$.tools[0].function.parameters.type").value("object"))
            .andExpect(jsonPath("$.messages[0].role").value("system"))
            .andExpect(jsonPath("$.messages[1].role").value("user"))
            .andExpect(jsonPath("$.messages[2].role").value("assistant"))
            .andExpect(jsonPath("$.messages[2].tool_calls[0].id").value("c1"))
            .andExpect(jsonPath("$.messages[2].tool_calls[0].type").value("function"))
            .andExpect(jsonPath("$.messages[2].tool_calls[0].function.name").value("get_today_plan"))
            .andExpect(jsonPath("$.messages[2].tool_calls[0].function.arguments").value("{}"))
            .andExpect(jsonPath("$.messages[3].role").value("tool"))
            .andExpect(jsonPath("$.messages[3].tool_call_id").value("c1"))
            .andExpect(jsonPath("$.messages[3].content").value("{\"blocks\":[]}"))
            .andRespond(withSuccess("""
                {"choices":[{"message":{"content":null,"tool_calls":[{"id":"c2","type":"function",
                  "function":{"name":"search_memory","arguments":"{\\"query\\":\\"tired\\"}"}}]}}]}
                """, MediaType.APPLICATION_JSON));

        AgentReply reply = sut.chat(List.of(
            AgentMessage.system("sys"),
            AgentMessage.user("hi"),
            AgentMessage.assistant(null, List.of(new ToolCall("c1", "get_today_plan", "{}"))),
            AgentMessage.tool("c1", "{\"blocks\":[]}")), List.of(TOOL));

        assertThat(reply.toolCalls()).containsExactly(new ToolCall("c2", "search_memory", "{\"query\":\"tired\"}"));
        server.verify();
    }

    @Test
    void parsesAPlainTextAnswer() {
        setUp();
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
            "{\"choices\":[{\"message\":{\"content\":\"Done thinking.\"}}]}", MediaType.APPLICATION_JSON));

        AgentReply reply = sut.chat(List.of(AgentMessage.user("hi")), List.of(TOOL));

        assertThat(reply.content()).isEqualTo("Done thinking.");
        assertThat(reply.toolCalls()).isEmpty();
    }

    @Test
    void aReplyWithNeitherTextNorToolsIsMalformed() {
        setUp();
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
            "{\"choices\":[{\"message\":{\"content\":\"  \"}}]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> sut.chat(List.of(AgentMessage.user("hi")), List.of(TOOL)))
            .isInstanceOf(MalformedLlmReplyException.class);
    }

    @Test
    void noChoicesIsMalformed() {
        setUp();
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> sut.chat(List.of(AgentMessage.user("hi")), List.of(TOOL)))
            .isInstanceOf(MalformedLlmReplyException.class);
    }

    @Test
    void aNon2xxResponseThrowsARestClientException() {
        setUp();
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> sut.chat(List.of(AgentMessage.user("hi")), List.of(TOOL)))
            .isInstanceOf(RestClientException.class);
    }
}
