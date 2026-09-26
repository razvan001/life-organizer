package com.lifeorganizer.ai.client;

import com.lifeorganizer.ai.model.SetupExtractionResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GroqExtractionClientTest {

    private static final String ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";

    private MockRestServiceServer server;
    private GroqExtractionClient sut;

    private void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(ENDPOINT);
        server = MockRestServiceServer.bindTo(builder).build();
        sut = new GroqExtractionClient(builder.build(), "openai/gpt-oss-120b");
    }

    @Test
    void sendsAStrictSchemaRequestAndParsesNullableGoalNumbers() {
        setUp();
        server.expect(requestTo(ENDPOINT))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.model").value("openai/gpt-oss-120b"))
            .andExpect(jsonPath("$.response_format.type").value("json_schema"))
            .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
            .andExpect(jsonPath("$.messages[0].content").value("sys"))
            .andExpect(jsonPath("$.messages[1].content").value("user"))
            .andRespond(withSuccess("""
                {"choices":[{"message":{"content":"{\\"categories\\":[{\\"id\\":\\"health\\",\\"name\\":\\"Health\\"}],\\"goals\\":[{\\"id\\":\\"gym\\",\\"categoryId\\":\\"health\\",\\"name\\":\\"Gym\\",\\"priority\\":null,\\"deadline\\":null,\\"defaultBlockMinutes\\":null,\\"energy\\":null,\\"preferredTime\\":null}],\\"constraints\\":[],\\"warnings\\":[]}"}}]}
                """, MediaType.APPLICATION_JSON));

        SetupExtractionResult result = sut.extract("sys", "user");

        assertThat(result.categories()).hasSize(1);
        assertThat(result.goals()).hasSize(1);
        assertThat(result.goals().get(0).priority()).isNull();
        assertThat(result.goals().get(0).defaultBlockMinutes()).isNull();
        server.verify();
    }

    @Test
    void aReplyWithNoChoicesIsMalformed() {
        setUp();
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> sut.extract("s", "u")).isInstanceOf(MalformedLlmReplyException.class);
    }

    @Test
    void nonJsonContentIsMalformed() {
        setUp();
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
            "{\"choices\":[{\"message\":{\"content\":\"not json\"}}]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> sut.extract("s", "u")).isInstanceOf(MalformedLlmReplyException.class);
    }

    @Test
    void aNon2xxResponseThrowsARestClientException() {
        setUp();
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> sut.extract("s", "u")).isInstanceOf(RestClientException.class);
    }
}
