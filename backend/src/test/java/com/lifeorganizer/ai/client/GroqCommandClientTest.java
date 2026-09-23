package com.lifeorganizer.ai.client;

import com.lifeorganizer.ai.model.LlmCommandResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GroqCommandClientTest {

    private static final String ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";

    @Test
    void sendsTheGivenPromptAndReturnsTheParsedReply() {
        RestClient.Builder builder = RestClient.builder().baseUrl(ENDPOINT);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(ENDPOINT))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andRespond(withSuccess("""
                {"choices":[{"message":{"content":"{\\"understood\\":true,\\"rationale\\":\\"done\\",\\"message\\":null,\\"events\\":[]}"}}]}
                """, MediaType.APPLICATION_JSON));

        GroqCommandClient sut = new GroqCommandClient(builder.build(), "openai/gpt-oss-120b");
        LlmCommandResult result = sut.interpret("system prompt text", "user text");

        assertThat(result.understood()).isTrue();
        assertThat(result.rationale()).isEqualTo("done");
        assertThat(result.events()).isEmpty();
        server.verify();
    }

    @Test
    void failsClearlyWhenTheReplyHasNoChoices() {
        RestClient.Builder builder = RestClient.builder().baseUrl(ENDPOINT);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(ENDPOINT))
            .andRespond(withSuccess("""
                {"choices":[]}
                """, MediaType.APPLICATION_JSON));

        GroqCommandClient sut = new GroqCommandClient(builder.build(), "openai/gpt-oss-120b");

        assertThatThrownBy(() -> sut.interpret("s", "u")).isInstanceOf(MalformedLlmReplyException.class);
    }

    @Test
    void failsClearlyWhenTheContentIsNotValidJson() {
        RestClient.Builder builder = RestClient.builder().baseUrl(ENDPOINT);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(ENDPOINT))
            .andRespond(withSuccess("""
                {"choices":[{"message":{"content":"not json"}}]}
                """, MediaType.APPLICATION_JSON));

        GroqCommandClient sut = new GroqCommandClient(builder.build(), "openai/gpt-oss-120b");

        assertThatThrownBy(() -> sut.interpret("s", "u")).isInstanceOf(MalformedLlmReplyException.class);
    }

    @Test
    void aNon2xxResponseThrowsARestClientException() {
        RestClient.Builder builder = RestClient.builder().baseUrl(ENDPOINT);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(ENDPOINT))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                .withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));

        GroqCommandClient sut = new GroqCommandClient(builder.build(), "openai/gpt-oss-120b");

        assertThatThrownBy(() -> sut.interpret("s", "u"))
            .isInstanceOf(org.springframework.web.client.RestClientException.class);
    }
}
