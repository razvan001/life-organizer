package com.lifeorganizer.ai.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place that speaks Groq's (OpenAI-compatible) chat-completions protocol: builds the request, posts
 * it and parses {@code choices[0].message.content} as JSON into the caller's target type.
 */
class GroqChatClient {

    /** How a rate-limit wait is spent; tests record the pause instead of sleeping. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** A rate-limited call is tried this many times in all. */
    static final int MAX_ATTEMPTS = 3;
    /** Longer waits mean a per-day limit or an outage: fail instead of holding the request. */
    static final Duration MAX_WAIT = Duration.ofSeconds(30);
    private static final Duration WAIT_MARGIN = Duration.ofSeconds(1);
    private static final Pattern TRY_AGAIN_IN = Pattern.compile("try again in (?:(\\d+)m)?(\\d+(?:\\.\\d+)?)s");

    private static final Logger log = LoggerFactory.getLogger(GroqChatClient.class);

    private final RestClient restClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final String model;
    private final Sleeper sleeper;

    GroqChatClient(RestClient restClient, String model) {
        this(restClient, model, duration -> Thread.sleep(duration.toMillis()));
    }

    GroqChatClient(RestClient restClient, String model, Sleeper sleeper) {
        this.restClient = restClient;
        this.model = model;
        this.sleeper = sleeper;
    }

    /**
     * Wraps a hand-written JSON Schema as a strict {@code response_format}. Groq/OpenAI strict mode requires
     * every property to be listed under "required"; a nullable value still uses a ["type", "null"] union.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> jsonSchemaResponseFormat(String schemaName, String schemaJson) {
        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", schemaName);
        jsonSchema.put("strict", true);
        jsonSchema.put("schema", JsonMapper.builder().build().readValue(schemaJson, Map.class));
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "json_schema");
        format.put("json_schema", jsonSchema);
        return format;
    }

    /** Posts one chat-completions request and returns the first choice's message. */
    ChatMessage send(List<Map<String, Object>> messages, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.putAll(extra);

        log.debug("Calling Groq model={} messages={}", model, messages.size());

        // RestClient's default behaviour throws RestClientResponseException for any non-2xx status,
        // and ResourceAccessException for network/timeout failures - both are RestClientException
        // subtypes, which is exactly the "connectivity failure" signal callers let propagate untouched
        // (never caught here, only logged before it propagates).
        GroqChatResponse response;
        try {
            response = post(body);
        } catch (RestClientException e) {
            log.warn("Groq call failed: {}", e.getMessage());
            throw e;
        }
        if (response == null || response.choices() == null || response.choices().isEmpty()
            || response.choices().get(0).message() == null) {
            log.warn("Groq returned no choices: {}", response);
            throw new MalformedLlmReplyException("Groq returned no choices");
        }
        logUsage(response.usage());
        return response.choices().get(0).message();
    }

    /**
     * Posts the request; on a 429 it waits as long as Groq asks and tries again. The free tier's tokens-per-minute
     * limit is close to what one agent turn uses, and the wait is usually a few seconds - far better than failing
     * the whole turn. A long or unknown wait is not worth holding the request for and propagates.
     */
    private GroqChatResponse post(Map<String, Object> body) {
        for (int attempt = 1; ; attempt++) {
            try {
                return restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(GroqChatResponse.class);
            } catch (HttpClientErrorException.TooManyRequests e) {
                Duration wait = retryDelay(e);
                if (attempt == MAX_ATTEMPTS || wait == null || wait.compareTo(MAX_WAIT) > 0) {
                    throw e;
                }
                log.info("Groq rate limit hit (attempt {} of {}); waiting {} ms: {}", attempt, MAX_ATTEMPTS,
                    wait.toMillis(), e.getResponseBodyAsString());
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Groq's retry-after header (seconds), else the "try again in 11.07s" of its message; null when neither is there. */
    private static Duration retryDelay(HttpClientErrorException e) {
        HttpHeaders headers = e.getResponseHeaders();
        String retryAfter = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter != null) {
            try {
                return Duration.ofMillis(Math.round(Double.parseDouble(retryAfter.strip()) * 1000)).plus(WAIT_MARGIN);
            } catch (NumberFormatException ignored) {
                // fall through to the message
            }
        }
        Matcher message = TRY_AGAIN_IN.matcher(e.getResponseBodyAsString());
        if (!message.find()) {
            return null;
        }
        long minutes = message.group(1) == null ? 0 : Long.parseLong(message.group(1));
        long millis = Math.round(Double.parseDouble(message.group(2)) * 1000);
        return Duration.ofMinutes(minutes).plusMillis(millis).plus(WAIT_MARGIN);
    }

    /** Token use per call, so the per-minute budget can be read from the log instead of guessed. */
    private void logUsage(Usage usage) {
        if (usage == null) {
            return;
        }
        log.info("Groq usage: prompt={} (cached {}) completion={} (reasoning {}) total={}", usage.promptTokens(),
            usage.promptDetails() == null ? 0 : usage.promptDetails().cachedTokens(), usage.completionTokens(),
            usage.completionDetails() == null ? 0 : usage.completionDetails().reasoningTokens(), usage.totalTokens());
    }

    <T> T call(String systemPrompt, String userMessage, Map<String, Object> responseFormat, Class<T> targetType) {
        String content = send(List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)),
            Map.of("response_format", responseFormat)).content();
        if (content == null || content.isBlank()) {
            log.warn("Groq returned an empty message");
            throw new MalformedLlmReplyException("Groq returned no message content");
        }
        log.debug("Groq raw reply content: {}", content);
        try {
            return jsonMapper.readValue(content, targetType);
        } catch (RuntimeException e) {
            log.warn("Could not parse Groq's reply as the expected JSON shape: {}", content, e);
            throw new MalformedLlmReplyException("Could not parse Groq's reply as the expected JSON shape");
        }
    }

    record GroqChatResponse(List<Choice> choices, Usage usage) {
    }

    record Usage(@JsonProperty("prompt_tokens") Integer promptTokens,
                 @JsonProperty("completion_tokens") Integer completionTokens,
                 @JsonProperty("total_tokens") Integer totalTokens,
                 @JsonProperty("prompt_tokens_details") PromptDetails promptDetails,
                 @JsonProperty("completion_tokens_details") CompletionDetails completionDetails) {
    }

    record PromptDetails(@JsonProperty("cached_tokens") Integer cachedTokens) {
    }

    record CompletionDetails(@JsonProperty("reasoning_tokens") Integer reasoningTokens) {
    }

    record Choice(ChatMessage message) {
    }

    record ChatMessage(String content, @JsonProperty("tool_calls") List<GroqToolCall> toolCalls) {
    }

    record GroqToolCall(String id, GroqFunction function) {
    }

    record GroqFunction(String name, String arguments) {
    }
}
