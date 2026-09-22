# Life Organizer Phase 2 (Natural-Language Commands) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a natural-language command box to the Today page. The user types a sentence ("padel got canceled"); Claude proposes zero or more of the engine's existing plan events; the user reviews a rationale and a diff, then applies or rejects. Nothing is ever mutated without an explicit Apply.

**Architecture:** A new, technical-layer-organized `ai/` backend package (`controller` → `service` → `client`) wraps the Anthropic Java SDK behind one interface, `ClaudeCommandClient`, so the business logic (`CommandInterpreter`) and its tests never touch the SDK directly. `PlanService` gains `simulateEvents`/`applyEvents` (multi-event versions of the existing `applyEvent`, one that never persists, one that persists atomically). A new generic batch-apply endpoint on the existing `PlanController` is what both a future multi-event UI action and this feature's Apply button call. The frontend adds one component pair (`AssistantBox`, `ProposalCard`) and fetches `/api/config` once to decide whether to render them at all.

**Tech Stack:** `com.anthropic:anthropic-java:2.65.0` (Maven Central; verified below), Spring Boot 4.1.1 (existing), React 19 + TypeScript (existing). No new frontend libraries.

**Global Constraints**

- No `Proposal` entity, no persisted proposal status. The server computes a proposal per request and returns it; the browser holds it until Apply, which resends the *exact* events verbatim.
- A command may only produce the engine's existing 5 events (`CANCEL_BLOCK`, `POSTPONE_BLOCK`, `MARK_DONE`, `SET_ENERGY`, `ADD_ADHOC`) against **today's** blocks. No goal-priority edits.
- A proposal is capped at 5 events; more is "not understood".
- `aiEnabled` in `GET /api/config` is derived from whether an `AnthropicClient` bean exists (no `ANTHROPIC_API_KEY` env var → no bean → `false`). There is no separate on/off flag.
- A malformed/unparseable/hallucinated-block-id reply from Claude → "not understood" (handled in `CommandInterpreter`, service layer). A `ClaudeCommandClient` connectivity failure (network, auth, rate limit, timeout — all subtypes of `com.anthropic.errors.AnthropicException`) → propagates untouched to `AssistantController`, which is the only place that turns it into `502 "Assistant unavailable"`. These two must never be confused.
- Commit messages end with the trailer line `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`.
- Write files with the file-writing tool, not shell heredocs (some strings below use an en dash/arrow); keep shell commands ASCII-only.
- Run backend tests with the Maven wrapper from `backend/` (`.\mvnw.cmd test` in PowerShell, `./mvnw test` in Git Bash). Docker must be running (Testcontainers). Run frontend tests with `npm --prefix frontend test` from the repo root; `npm --prefix frontend run build` for the production build.

**Verified before writing this plan** (a scratch project at a temp path, `com.anthropic:anthropic-java:2.65.0`, was compiled and run against real SDK classes — no API key or network call was needed for any of this):
- `Model.CLAUDE_SONNET_5` exists as a constant.
- `MessageCreateParams.builder().model(Model).maxTokens(long).system(String).addUserMessage(String).outputConfig(Class<T>, JsonSchemaLocalValidation.YES).build()` returns `StructuredMessageCreateParams<T>`; `client.messages().create(params)` returns `StructuredMessage<T>`; `.content()` → `List<StructuredContentBlock<T>>` → `.text()` → `Optional<StructuredTextBlock<T>>` → `.text()` → the parsed `T`. Confirmed by compiling and running real code that produced the JSON schema and, separately, by mocking these types with Mockito and parsing a value back out.
- A Java record's `Optional<String>` field generates a nullable JSON-schema entry (`type:[string,null]`, still listed under `required` — the key must be present, the value may be `null`); a plain `String` field is a required, non-nullable `type:string`; a Java `enum` field generates a constrained `enum:[...]` schema entry. This is why `ProposedEvent`'s conditionally-used fields are typed `Optional<String>` below.
- `AnthropicOkHttpClient.builder().apiKey(String).build()` and `.close()` succeed with a dummy key and make no network call — safe to construct in tests.
- `com.anthropic.errors.AnthropicException` is the common `RuntimeException` base of every SDK failure (`AnthropicIoException`, `RateLimitException`, `UnauthorizedException`, etc.) — one catch clause covers all connectivity failures.
- Mockito 5.23.0 (already a project test dependency) mocks the SDK's `final` classes (`StructuredMessage`, `StructuredContentBlock`, `StructuredTextBlock`) directly via `mock(Class)` — no extra setup, only a harmless self-attach warning.
- `org.springframework.boot.test.context.runner.ApplicationContextRunner` is unchanged in Boot 4.1.1 (present in `spring-boot-test-4.1.1.jar`, same package as older Boot versions).
- Jackson coexistence: Spring Boot 4.1.1 uses `tools.jackson.core:jackson-databind:3.1.5` (the renamed Jackson-3 namespace) exclusively for its own REST (de)serialization. `anthropic-java` brings classic `com.fasterxml.jackson.core:jackson-databind:2.19.4`/`jackson-core:2.19.4`, used only internally by the SDK for its own HTTP calls to Anthropic — different package namespace, no classpath collision. The only shared coordinate is `com.fasterxml.jackson.core:jackson-annotations` (Spring wants 2.21, the SDK wants 2.19.4); Maven resolves this to one version via ordinary dependency mediation, which is safe given how stable that annotations-only artifact is across such a small gap. Confirmed by running `./mvnw dependency:tree` on the real backend project.

---

## File Structure

```
backend/src/main/java/com/lifeorganizer/
├── ai/
│   ├── controller/AssistantController.java
│   ├── service/CommandInterpreter.java
│   ├── client/ClaudeClientConfig.java, ClaudeCommandClient.java, AnthropicCommandClient.java
│   ├── model/ProposedEventType.java, ProposedEvent.java, ClaudeCommandResult.java, InterpretedCommand.java
│   └── dto/CommandRequest.java, CommandResponse.java
├── plan/dto/ApplyEventsRequest.java, ApplyEventsResponse.java   (new, alongside the existing ApplyEventRequest/Response)
├── plan/PlanService.java     (+ simulateEvents, + applyEvents)
├── plan/PlanController.java  (+ POST /api/plan/today/events/batch)
├── plan/PlanDtoMapper.java   (+ toEventsResponse)
├── config/ConfigController.java  (aiEnabled now real)
├── error/AiNotConfiguredException.java, AssistantUnavailableException.java
└── config/GlobalExceptionHandler.java  (+ 502, + 503)

frontend/src/
├── api/types.ts    (+ ConfigResponse, ProposedEventDto, CommandRequest/Response, ApplyEventsRequest/Response)
├── api/client.ts   (+ getConfig, proposeCommand, applyEvents)
├── components/ProposalCard.tsx, AssistantBox.tsx
└── pages/TodayPage.tsx  (fetch config, render <AssistantBox> when aiEnabled)
```

---

### Task 1: Gate `aiEnabled` on the Anthropic SDK dependency

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/lifeorganizer/ai/client/ClaudeClientConfig.java`
- Modify: `backend/src/main/java/com/lifeorganizer/config/ConfigController.java`
- Test: `backend/src/test/java/com/lifeorganizer/ai/client/ClaudeClientConfigTest.java`
- Test: `backend/src/test/java/com/lifeorganizer/config/ConfigControllerAiEnabledTest.java` (new file; the existing `ConfigControllerTest.java` is not touched)

**Interfaces:**
- Produces: `AnthropicClient` bean (from `com.anthropic.client.AnthropicClient`), present only when the `ANTHROPIC_API_KEY` property/env var is set. `ConfigController.ConfigResponse{aiEnabled, ragEnabled}` (`ragEnabled` stays `false`).

- [ ] **Step 1: Add the dependency**

In `backend/pom.xml`, inside the existing `<dependencies>` block (after the `postgresql` dependency, before the test-scoped ones), add:

```xml
<dependency>
    <groupId>com.anthropic</groupId>
    <artifactId>anthropic-java</artifactId>
    <version>2.65.0</version>
</dependency>
```

Run: `cd backend && ./mvnw -q dependency:tree`
Expected: no errors; `com.anthropic:anthropic-java:jar:2.65.0:compile` and its transitive `anthropic-java-core`/`anthropic-java-client-okhttp` appear. (This also re-confirms the Jackson coexistence noted above on your machine; if `dependency:tree` reports any `[ERROR]`, stop and report it rather than continuing.)

- [ ] **Step 2: Write the failing test for `ClaudeClientConfig`**

`backend/src/test/java/com/lifeorganizer/ai/client/ClaudeClientConfigTest.java`:

```java
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
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ClaudeClientConfigTest`
Expected: FAIL to compile — `ClaudeClientConfig` does not exist yet.

- [ ] **Step 4: Write `ClaudeClientConfig`**

`backend/src/main/java/com/lifeorganizer/ai/client/ClaudeClientConfig.java`:

```java
package com.lifeorganizer.ai.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the Anthropic SDK client, but only when an API key is configured. No key, no bean - every
 * class that depends on {@link AnthropicClient} (or, transitively, {@link ClaudeCommandClient}) must
 * accept that it might not exist: inject an {@code ObjectProvider<...>}, never the bean directly.
 */
@Configuration
public class ClaudeClientConfig {

    @Bean
    @ConditionalOnProperty(name = "ANTHROPIC_API_KEY")
    public AnthropicClient anthropicClient(@Value("${ANTHROPIC_API_KEY}") String apiKey) {
        return AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ClaudeClientConfigTest`
Expected: PASS, 2/2.

- [ ] **Step 6: Write the failing test for `ConfigController`**

`backend/src/test/java/com/lifeorganizer/config/ConfigControllerAiEnabledTest.java`:

```java
package com.lifeorganizer.config;

import com.lifeorganizer.ai.client.ClaudeClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConfigController.class)
@Import(ClaudeClientConfig.class)
@TestPropertySource(properties = "ANTHROPIC_API_KEY=sk-ant-test-dummy-key")
class ConfigControllerAiEnabledTest {

    @Autowired
    MockMvc mvc;

    @Test
    void reportsAiEnabledWhenAnAnthropicClientBeanExists() throws Exception {
        mvc.perform(get("/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.aiEnabled").value(true))
            .andExpect(jsonPath("$.ragEnabled").value(false));
    }
}
```

- [ ] **Step 7: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ConfigControllerAiEnabledTest`
Expected: FAIL — `ConfigController`'s current constructor takes no arguments and always returns `aiEnabled:false`, so `jsonPath("$.aiEnabled").value(true)` fails.

- [ ] **Step 8: Update `ConfigController`**

Replace `backend/src/main/java/com/lifeorganizer/config/ConfigController.java` with:

```java
package com.lifeorganizer.config;

import com.anthropic.client.AnthropicClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells the UI which optional features exist. ragEnabled stays false until the RAG phase is built. */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    public record ConfigResponse(boolean aiEnabled, boolean ragEnabled) {
    }

    private final ObjectProvider<AnthropicClient> anthropicClient;

    public ConfigController(ObjectProvider<AnthropicClient> anthropicClient) {
        this.anthropicClient = anthropicClient;
    }

    @GetMapping
    public ConfigResponse config() {
        return new ConfigResponse(anthropicClient.getIfAvailable() != null, false);
    }
}
```

- [ ] **Step 9: Run both config tests and the full suite**

Run: `cd backend && ./mvnw test -Dtest=ConfigControllerTest,ConfigControllerAiEnabledTest,ClaudeClientConfigTest`
Expected: PASS, 4/4 (the existing `ConfigControllerTest` still passes unchanged: with no `ClaudeClientConfig` imported into its `@WebMvcTest` slice, `ObjectProvider<AnthropicClient>.getIfAvailable()` returns `null`, so `aiEnabled` is still `false`).

Run: `cd backend && ./mvnw test`
Expected: PASS, 208 (the current total) + 3 new (2 in `ClaudeClientConfigTest`, 1 in `ConfigControllerAiEnabledTest`) = 211.

- [ ] **Step 10: Commit**

```bash
git add backend/pom.xml backend/src/main/java/com/lifeorganizer/ai backend/src/main/java/com/lifeorganizer/config/ConfigController.java backend/src/test/java/com/lifeorganizer/ai backend/src/test/java/com/lifeorganizer/config/ConfigControllerAiEnabledTest.java
git commit -m "feat: add the Anthropic SDK dependency and gate aiEnabled on it"
```

---

### Task 2: The Claude command client

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/ai/model/ProposedEventType.java`, `ProposedEvent.java`, `ClaudeCommandResult.java`
- Create: `backend/src/main/java/com/lifeorganizer/ai/client/ClaudeCommandClient.java`, `AnthropicCommandClient.java`
- Modify: `backend/src/main/java/com/lifeorganizer/ai/client/ClaudeClientConfig.java` (add the `claudeCommandClient` bean)
- Test: `backend/src/test/java/com/lifeorganizer/ai/client/AnthropicCommandClientTest.java`

**Interfaces:**
- Consumes: `AnthropicClient` bean (Task 1).
- Produces: `ClaudeCommandClient.interpret(String systemPrompt, String userMessage): ClaudeCommandResult` — the one seam `CommandInterpreter` (Task 3) depends on. `ClaudeCommandResult(boolean understood, Optional<String> rationale, Optional<String> message, List<ProposedEvent> events)`. `ProposedEvent(ProposedEventType type, Optional<String> blockId, Optional<String> reason, Optional<String> date, Optional<String> title, Optional<String> start, Optional<String> end, Optional<String> energy)`.

- [ ] **Step 1: Write the model records**

`backend/src/main/java/com/lifeorganizer/ai/model/ProposedEventType.java`:

```java
package com.lifeorganizer.ai.model;

public enum ProposedEventType { CANCEL_BLOCK, POSTPONE_BLOCK, MARK_DONE, SET_ENERGY, ADD_ADHOC }
```

`backend/src/main/java/com/lifeorganizer/ai/model/ProposedEvent.java`:

```java
package com.lifeorganizer.ai.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.Optional;

/**
 * One event Claude proposes, in (almost) the shape {@link com.lifeorganizer.plan.dto.ApplyEventRequest}
 * already parses. Optional fields let the model send an explicit JSON null for whatever a given
 * {@link #type()} does not need, which the Anthropic SDK's structured-output schema generator only
 * offers for {@code Optional<T>} fields (a plain String field is generated as required and non-null).
 */
public record ProposedEvent(
    @JsonPropertyDescription("One of CANCEL_BLOCK, POSTPONE_BLOCK, MARK_DONE, SET_ENERGY, ADD_ADHOC.")
    ProposedEventType type,
    @JsonPropertyDescription("The block id this event acts on. Required for CANCEL_BLOCK, POSTPONE_BLOCK, "
        + "MARK_DONE and SET_ENERGY, and must be exactly one of the ids listed in the prompt. Null for ADD_ADHOC.")
    Optional<String> blockId,
    @JsonPropertyDescription("Optional short reason. Only ever used for POSTPONE_BLOCK.")
    Optional<String> reason,
    @JsonPropertyDescription("The date for a new ADD_ADHOC block, yyyy-MM-dd. Null for every other type.")
    Optional<String> date,
    @JsonPropertyDescription("The title of a new ADD_ADHOC block. Null for every other type.")
    Optional<String> title,
    @JsonPropertyDescription("Start time of a new ADD_ADHOC block, HH:mm. Null for every other type.")
    Optional<String> start,
    @JsonPropertyDescription("End time of a new ADD_ADHOC block, HH:mm. Null for every other type.")
    Optional<String> end,
    @JsonPropertyDescription("LOW or MEDIUM. Only ever used for SET_ENERGY.")
    Optional<String> energy
) {}
```

`backend/src/main/java/com/lifeorganizer/ai/model/ClaudeCommandResult.java`:

```java
package com.lifeorganizer.ai.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;
import java.util.Optional;

/** Claude's raw answer to one command: either a rationale plus events, or an explanation why not. */
public record ClaudeCommandResult(
    @JsonPropertyDescription("True only if the text maps to at least one concrete, executable event "
        + "against a real block from the list given in the prompt.")
    boolean understood,
    @JsonPropertyDescription("One short sentence explaining the change. Present only when understood is true.")
    Optional<String> rationale,
    @JsonPropertyDescription("One short sentence explaining why the text could not be understood. "
        + "Present only when understood is false.")
    Optional<String> message,
    @JsonPropertyDescription("Zero or more events. Empty when understood is false. At most 5.")
    List<ProposedEvent> events
) {}
```

- [ ] **Step 2: Write the failing test for `AnthropicCommandClient`**

`backend/src/test/java/com/lifeorganizer/ai/client/AnthropicCommandClientTest.java`:

```java
package com.lifeorganizer.ai.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.StructuredContentBlock;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredTextBlock;
import com.anthropic.services.blocking.MessageService;
import com.lifeorganizer.ai.model.ClaudeCommandResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnthropicCommandClientTest {

    @SuppressWarnings("unchecked")
    @Test
    void sendsTheGivenPromptAndReturnsTheParsedReply() {
        AnthropicClient client = mock(AnthropicClient.class);
        MessageService messages = mock(MessageService.class);
        when(client.messages()).thenReturn(messages);

        StructuredMessage<ClaudeCommandResult> reply = mock(StructuredMessage.class);
        StructuredContentBlock<ClaudeCommandResult> block = mock(StructuredContentBlock.class);
        StructuredTextBlock<ClaudeCommandResult> textBlock = mock(StructuredTextBlock.class);
        ClaudeCommandResult expected = new ClaudeCommandResult(true, Optional.of("done"), Optional.empty(), List.of());
        when(reply.content()).thenReturn(List.of(block));
        when(block.text()).thenReturn(Optional.of(textBlock));
        when(textBlock.text()).thenReturn(expected);
        when(messages.create(any(StructuredMessageCreateParams.class))).thenReturn(reply);

        AnthropicCommandClient sut = new AnthropicCommandClient(client, "claude-sonnet-5");
        ClaudeCommandResult result = sut.interpret("system prompt text", "user text");

        assertThat(result).isEqualTo(expected);
        verify(messages).create(any(StructuredMessageCreateParams.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void failsClearlyWhenTheReplyHasNoTextContent() {
        AnthropicClient client = mock(AnthropicClient.class);
        MessageService messages = mock(MessageService.class);
        when(client.messages()).thenReturn(messages);
        StructuredMessage<ClaudeCommandResult> reply = mock(StructuredMessage.class);
        when(reply.content()).thenReturn(List.of());
        when(messages.create(any())).thenReturn(reply);

        AnthropicCommandClient sut = new AnthropicCommandClient(client, "claude-sonnet-5");

        assertThatThrownBy(() -> sut.interpret("s", "u")).isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AnthropicCommandClientTest`
Expected: FAIL to compile — `ClaudeCommandClient`/`AnthropicCommandClient` do not exist yet.

- [ ] **Step 4: Write the client interface and implementation**

`backend/src/main/java/com/lifeorganizer/ai/client/ClaudeCommandClient.java`:

```java
package com.lifeorganizer.ai.client;

import com.lifeorganizer.ai.model.ClaudeCommandResult;

/**
 * The only interface that touches the Anthropic SDK. {@code interpret} sends the already-built prompt
 * and returns Claude's raw structured answer, or throws {@link com.anthropic.errors.AnthropicException}
 * on a connectivity failure (network, auth, rate limit, timeout) - it does no validation of the
 * content, that is {@link com.lifeorganizer.ai.service.CommandInterpreter}'s job.
 */
public interface ClaudeCommandClient {

    ClaudeCommandResult interpret(String systemPrompt, String userMessage);
}
```

`backend/src/main/java/com/lifeorganizer/ai/client/AnthropicCommandClient.java`:

```java
package com.lifeorganizer.ai.client;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonSchemaLocalValidation;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.lifeorganizer.ai.model.ClaudeCommandResult;

/** Wraps the one Anthropic SDK call this feature needs. */
public class AnthropicCommandClient implements ClaudeCommandClient {

    private static final long MAX_TOKENS = 1024;

    private final AnthropicClient client;
    private final Model model;

    public AnthropicCommandClient(AnthropicClient client, String model) {
        this.client = client;
        this.model = Model.of(model);
    }

    @Override
    public ClaudeCommandResult interpret(String systemPrompt, String userMessage) {
        StructuredMessageCreateParams<ClaudeCommandResult> params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .system(systemPrompt)
            .addUserMessage(userMessage)
            .outputConfig(ClaudeCommandResult.class, JsonSchemaLocalValidation.YES)
            .build();

        StructuredMessage<ClaudeCommandResult> message = client.messages().create(params);
        return message.content().stream()
            .flatMap(block -> block.text().stream())
            .findFirst()
            .map(text -> text.text())
            .orElseThrow(() -> new IllegalStateException("Claude returned no structured text content"));
    }
}
```

- [ ] **Step 5: Wire the bean into `ClaudeClientConfig`**

In `backend/src/main/java/com/lifeorganizer/ai/client/ClaudeClientConfig.java`, add imports for `ConditionalOnBean` and `ClaudeCommandClient`, and add a second `@Bean` method:

```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
```

```java
    @Bean
    @ConditionalOnBean(AnthropicClient.class)
    public ClaudeCommandClient claudeCommandClient(AnthropicClient client,
                                                    @Value("${app.ai.model:claude-sonnet-5}") String model) {
        return new AnthropicCommandClient(client, model);
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=AnthropicCommandClientTest,ClaudeClientConfigTest`
Expected: PASS, 4/4.

Run: `cd backend && ./mvnw test`
Expected: PASS, 211 + 2 = 213.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/lifeorganizer/ai backend/src/test/java/com/lifeorganizer/ai/client/AnthropicCommandClientTest.java
git commit -m "feat: add the Claude command client (structured output over the Anthropic SDK)"
```

---

### Task 3: `CommandInterpreter`

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/ai/model/InterpretedCommand.java`
- Create: `backend/src/main/java/com/lifeorganizer/ai/service/CommandInterpreter.java`
- Create: `backend/src/main/java/com/lifeorganizer/error/AiNotConfiguredException.java`
- Test: `backend/src/test/java/com/lifeorganizer/ai/service/CommandInterpreterTest.java`

**Interfaces:**
- Consumes: `ClaudeCommandClient` (Task 2, as an `ObjectProvider<ClaudeCommandClient>` — always constructible even with no API key), `com.lifeorganizer.domain.Block`, `com.lifeorganizer.plan.dto.ApplyEventRequest` (existing, reused as-is for its `.toEvent(LocalDate)` validation).
- Produces: `CommandInterpreter.interpret(String text, LocalDate today, List<Block> todaysBlocks): InterpretedCommand`, throwing `AiNotConfiguredException` when no client is available. `InterpretedCommand(boolean understood, String rationale, String message, List<ApplyEventRequest> events)` with static factories `understood(...)`/`notUnderstood(...)` — this is what `AssistantController` (Task 6) consumes.

- [ ] **Step 1: Write `AiNotConfiguredException` and `InterpretedCommand`**

`backend/src/main/java/com/lifeorganizer/error/AiNotConfiguredException.java`:

```java
package com.lifeorganizer.error;

/** No Claude client is configured (no ANTHROPIC_API_KEY), but an AI endpoint was called anyway. */
public class AiNotConfiguredException extends RuntimeException {

    public AiNotConfiguredException(String message) {
        super(message);
    }
}
```

`backend/src/main/java/com/lifeorganizer/ai/model/InterpretedCommand.java`:

```java
package com.lifeorganizer.ai.model;

import com.lifeorganizer.plan.dto.ApplyEventRequest;

import java.util.List;

/** The result of interpreting one command: a validated set of events, or why there are none. */
public record InterpretedCommand(boolean understood, String rationale, String message, List<ApplyEventRequest> events) {

    public static InterpretedCommand understood(String rationale, List<ApplyEventRequest> events) {
        return new InterpretedCommand(true, rationale, null, events);
    }

    public static InterpretedCommand notUnderstood(String message) {
        return new InterpretedCommand(false, null, message, List.of());
    }
}
```

- [ ] **Step 2: Write the failing tests**

`backend/src/test/java/com/lifeorganizer/ai/service/CommandInterpreterTest.java`:

```java
package com.lifeorganizer.ai.service;

import com.lifeorganizer.ai.client.ClaudeCommandClient;
import com.lifeorganizer.ai.model.ClaudeCommandResult;
import com.lifeorganizer.ai.model.InterpretedCommand;
import com.lifeorganizer.ai.model.ProposedEvent;
import com.lifeorganizer.ai.model.ProposedEventType;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.error.AiNotConfiguredException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommandInterpreterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);
    private static final Block PADEL = new Block("padel-2026-09-22", TODAY, LocalTime.of(19, 0), LocalTime.of(20, 0),
        "Padel", BlockKind.GOAL, "health-padel", BlockStatus.PLANNED, 0, false, null);

    private static final ProposedEvent NO_FIELDS = new ProposedEvent(ProposedEventType.MARK_DONE, Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    @SuppressWarnings("unchecked")
    private static CommandInterpreter interpreterFor(ClaudeCommandClient fake) {
        ObjectProvider<ClaudeCommandClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(fake);
        return new CommandInterpreter(provider);
    }

    @Test
    void turnsAWellFormedReplyIntoAnUnderstoodCommand() {
        ProposedEvent cancel = new ProposedEvent(ProposedEventType.CANCEL_BLOCK, Optional.of("padel-2026-09-22"),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        ClaudeCommandResult reply = new ClaudeCommandResult(true, Optional.of("Padel is cancelled"), Optional.empty(),
            List.of(cancel));
        CommandInterpreter sut = interpreterFor((system, user) -> reply);

        InterpretedCommand result = sut.interpret("padel got canceled", TODAY, List.of(PADEL));

        assertThat(result.understood()).isTrue();
        assertThat(result.rationale()).isEqualTo("Padel is cancelled");
        assertThat(result.events()).hasSize(1);
        assertThat(result.events().get(0).type()).isEqualTo("CANCEL_BLOCK");
        assertThat(result.events().get(0).blockId()).isEqualTo("padel-2026-09-22");
    }

    @Test
    void downgradesToNotUnderstoodWhenTheModelSaysSo() {
        ClaudeCommandResult reply = new ClaudeCommandResult(false, Optional.empty(),
            Optional.of("I couldn't find a matching block"), List.of());
        CommandInterpreter sut = interpreterFor((system, user) -> reply);

        InterpretedCommand result = sut.interpret("do something vague", TODAY, List.of(PADEL));

        assertThat(result.understood()).isFalse();
        assertThat(result.message()).isEqualTo("I couldn't find a matching block");
    }

    @Test
    void downgradesToNotUnderstoodWhenTheBlockIdIsNotInTodaysPlan() {
        ProposedEvent cancel = new ProposedEvent(ProposedEventType.CANCEL_BLOCK, Optional.of("no-such-block"),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        ClaudeCommandResult reply = new ClaudeCommandResult(true, Optional.of("Cancelling it"), Optional.empty(),
            List.of(cancel));
        CommandInterpreter sut = interpreterFor((system, user) -> reply);

        InterpretedCommand result = sut.interpret("cancel soccer", TODAY, List.of(PADEL));

        assertThat(result.understood()).isFalse();
    }

    @Test
    void downgradesToNotUnderstoodWhenThereAreMoreThanFiveEvents() {
        ProposedEvent event = new ProposedEvent(ProposedEventType.MARK_DONE, Optional.of("padel-2026-09-22"),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        ClaudeCommandResult reply = new ClaudeCommandResult(true, Optional.of("many things"), Optional.empty(),
            List.of(event, event, event, event, event, event));
        CommandInterpreter sut = interpreterFor((system, user) -> reply);

        InterpretedCommand result = sut.interpret("do everything", TODAY, List.of(PADEL));

        assertThat(result.understood()).isFalse();
    }

    @Test
    void downgradesToNotUnderstoodWhenThereAreNoEventsAtAll() {
        ClaudeCommandResult reply = new ClaudeCommandResult(true, Optional.of("nothing to do"), Optional.empty(), List.of());
        CommandInterpreter sut = interpreterFor((system, user) -> reply);

        InterpretedCommand result = sut.interpret("hmm", TODAY, List.of(PADEL));

        assertThat(result.understood()).isFalse();
    }

    @Test
    void downgradesToNotUnderstoodWhenAnEventFailsEngineValidation() {
        // ADD_ADHOC with no title - ApplyEventRequest.toEvent rejects it
        ProposedEvent badAdhoc = new ProposedEvent(ProposedEventType.ADD_ADHOC, Optional.empty(), Optional.empty(),
            Optional.of("2026-09-22"), Optional.empty(), Optional.of("19:00"), Optional.of("20:00"), Optional.empty());
        ClaudeCommandResult reply = new ClaudeCommandResult(true, Optional.of("adding it"), Optional.empty(),
            List.of(badAdhoc));
        CommandInterpreter sut = interpreterFor((system, user) -> reply);

        InterpretedCommand result = sut.interpret("add something", TODAY, List.of(PADEL));

        assertThat(result.understood()).isFalse();
    }

    @Test
    void includesTodaysDateAndEveryBlockInThePromptGivenToTheClient() {
        List<String> capturedUserMessages = new ArrayList<>();
        ClaudeCommandClient fake = (system, user) -> {
            capturedUserMessages.add(user);
            return new ClaudeCommandResult(false, Optional.empty(), Optional.of("n/a"), List.of());
        };
        CommandInterpreter sut = interpreterFor(fake);

        sut.interpret("anything", TODAY, List.of(PADEL));

        assertThat(capturedUserMessages).hasSize(1);
        String prompt = capturedUserMessages.get(0);
        assertThat(prompt).contains("2026-09-22");
        assertThat(prompt).contains("padel-2026-09-22");
        assertThat(prompt).contains("Padel");
        assertThat(prompt).contains("19:00");
    }

    @SuppressWarnings("unchecked")
    @Test
    void throwsAiNotConfiguredWhenNoClientIsAvailable() {
        ObjectProvider<ClaudeCommandClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        CommandInterpreter sut = new CommandInterpreter(provider);

        assertThatThrownBy(() -> sut.interpret("x", TODAY, List.of(PADEL)))
            .isInstanceOf(AiNotConfiguredException.class);
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=CommandInterpreterTest`
Expected: FAIL to compile — `CommandInterpreter` does not exist yet.

- [ ] **Step 4: Write `CommandInterpreter`**

`backend/src/main/java/com/lifeorganizer/ai/service/CommandInterpreter.java`:

```java
package com.lifeorganizer.ai.service;

import com.lifeorganizer.ai.client.ClaudeCommandClient;
import com.lifeorganizer.ai.model.ClaudeCommandResult;
import com.lifeorganizer.ai.model.InterpretedCommand;
import com.lifeorganizer.ai.model.ProposedEvent;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.error.AiNotConfiguredException;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.plan.dto.ApplyEventRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns one sentence of free text into zero or more validated engine events. Never mutates the plan -
 * every event it returns still has to go through {@code PlanService.simulateEvents}/{@code applyEvents}.
 */
@Service
public class CommandInterpreter {

    private static final int MAX_EVENTS = 5;

    private static final String SYSTEM_PROMPT = """
        You help the user adjust their daily plan by turning one sentence of free text into zero or more
        structured events. You are given today's date and today's blocks (id, title, kind, status, start,
        end). You may only propose these event types, and every blockId you use must be exactly one of the
        ids given to you:
        - CANCEL_BLOCK: cancel a block (blockId required)
        - POSTPONE_BLOCK: postpone a block later today or later this week (blockId required, reason optional)
        - MARK_DONE: mark a block done (blockId required)
        - SET_ENERGY: set a block's energy to LOW or MEDIUM (blockId and energy required)
        - ADD_ADHOC: add a new block (date yyyy-MM-dd, title, start and end HH:mm all required)
        Only ever target blocks that are still PLANNED. Never invent a blockId. If the text does not clearly
        map to at least one of these events against a real block, set understood to false and explain why in
        message instead of guessing. Propose at most 5 events. Keep rationale to one short sentence.
        """;

    private final ObjectProvider<ClaudeCommandClient> clientProvider;

    public CommandInterpreter(ObjectProvider<ClaudeCommandClient> clientProvider) {
        this.clientProvider = clientProvider;
    }

    public InterpretedCommand interpret(String text, LocalDate today, List<Block> todaysBlocks) {
        ClaudeCommandClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new AiNotConfiguredException("AI features are not configured");
        }

        ClaudeCommandResult raw = client.interpret(SYSTEM_PROMPT, userMessage(text, today, todaysBlocks));

        if (!raw.understood()) {
            return InterpretedCommand.notUnderstood(raw.message().orElse("I couldn't understand that."));
        }
        if (raw.events().isEmpty() || raw.events().size() > MAX_EVENTS) {
            return InterpretedCommand.notUnderstood("That would need too many changes at once, or none at all.");
        }

        Set<String> knownIds = new LinkedHashSet<>();
        for (Block block : todaysBlocks) {
            knownIds.add(block.id());
        }

        List<ApplyEventRequest> events = new ArrayList<>();
        for (ProposedEvent proposed : raw.events()) {
            String blockId = proposed.blockId().orElse(null);
            if (blockId != null && !knownIds.contains(blockId)) {
                return InterpretedCommand.notUnderstood("I couldn't find a matching block in today's plan.");
            }
            ApplyEventRequest request = new ApplyEventRequest(proposed.type().name(), blockId,
                proposed.reason().orElse(null), proposed.date().orElse(null), proposed.title().orElse(null),
                proposed.start().orElse(null), proposed.end().orElse(null), proposed.energy().orElse(null));
            try {
                request.toEvent(today);
            } catch (InvalidRequestException e) {
                return InterpretedCommand.notUnderstood("I couldn't turn that into a valid change: " + e.getMessage());
            }
            events.add(request);
        }

        return InterpretedCommand.understood(raw.rationale().orElse(""), events);
    }

    private String userMessage(String text, LocalDate today, List<Block> todaysBlocks) {
        StringBuilder sb = new StringBuilder();
        sb.append("Today is ").append(today).append(".\n");
        sb.append("Today's blocks:\n");
        for (Block block : todaysBlocks) {
            sb.append("- id=").append(block.id())
                .append(" title=\"").append(block.title()).append('"')
                .append(" kind=").append(block.kind())
                .append(" status=").append(block.status())
                .append(" start=").append(block.start())
                .append(" end=").append(block.end())
                .append('\n');
        }
        sb.append("\nUser's text: ").append(text);
        return sb.toString();
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=CommandInterpreterTest`
Expected: PASS, 8/8.

Run: `cd backend && ./mvnw test`
Expected: PASS, 213 + 8 = 221.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/lifeorganizer/ai/model/InterpretedCommand.java backend/src/main/java/com/lifeorganizer/ai/service backend/src/main/java/com/lifeorganizer/error/AiNotConfiguredException.java backend/src/test/java/com/lifeorganizer/ai/service
git commit -m "feat: add CommandInterpreter (prompt building and reply validation)"
```

---

### Task 4: `PlanService.simulateEvents` and `applyEvents`

**Files:**
- Modify: `backend/src/main/java/com/lifeorganizer/plan/PlanService.java`
- Test: `backend/src/test/java/com/lifeorganizer/plan/PlanServiceMultiEventTest.java`

**Interfaces:**
- Consumes: nothing new — reuses `loadDaysFor`, `dateOf`, `getOrGenerate`, `replanner`, `planStore` already in `PlanService`.
- Produces: `PlanService.simulateEvents(List<PlanEvent> events, LocalDate today): AppliedEvent` (never persists), `PlanService.applyEvents(List<PlanEvent> events, LocalDate today): AppliedEvent` (persists all events atomically in one transaction — a failure partway through rolls back every earlier event in the same call). These are what `AssistantController` (Task 6) and the new batch endpoint (Task 5) call.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/lifeorganizer/plan/PlanServiceMultiEventTest.java`:

```java
package com.lifeorganizer.plan;

import com.lifeorganizer.config.EngineConfig;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.engine.CancelBlock;
import com.lifeorganizer.engine.MarkDone;
import com.lifeorganizer.engine.PlanEvent;
import com.lifeorganizer.error.UnknownBlockException;
import com.lifeorganizer.persistence.PlanStore;
import com.lifeorganizer.persistence.SetupStore;
import com.lifeorganizer.support.ExampleSetup;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({PlanService.class, SetupStore.class, PlanStore.class, EngineConfig.class})
class PlanServiceMultiEventTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private static final LocalDate MON = ExampleSetup.MONDAY;

    @Autowired
    PlanService planService;

    @Autowired
    SetupStore setupStore;

    @Autowired
    PlanStore planStore;

    @PersistenceContext
    EntityManager entityManager;

    private void saveExampleSetup() {
        setupStore.replace(ExampleSetup.tree(), ExampleSetup.constraints());
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private String idOf(DayPlan plan, String title) {
        return plan.blocks().stream()
            .filter(b -> b.title().equals(title) && b.status() == BlockStatus.PLANNED)
            .findFirst().orElseThrow().id();
    }

    private Block block(DayPlan plan, String id) {
        return plan.blocks().stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void simulateEventsNeverPersists() {
        saveExampleSetup();
        String gymId = idOf(planService.getOrGenerate(MON), "Gym");

        AppliedEvent simulated = planService.simulateEvents(List.of(new MarkDone(gymId)), MON);
        flushAndClear();

        assertThat(block(simulated.today(), gymId).status()).isEqualTo(BlockStatus.DONE);
        assertThat(simulated.changes()).containsExactly("Marked done Gym");
        assertThat(block(planStore.findDay(MON).orElseThrow(), gymId).status()).isEqualTo(BlockStatus.PLANNED);
    }

    @Test
    void applyEventsAppliesEveryEventAndPersistsAll() {
        saveExampleSetup();
        DayPlan before = planService.getOrGenerate(MON);
        String gymId = idOf(before, "Gym");
        String certId = idOf(before, "Claude certification");

        List<PlanEvent> events = List.of(new MarkDone(gymId), new CancelBlock(certId));
        AppliedEvent applied = planService.applyEvents(events, MON);
        flushAndClear();

        assertThat(applied.changes()).containsExactly("Marked done Gym", "Cancelled Claude certification - slot is now free");
        DayPlan reloaded = planStore.findDay(MON).orElseThrow();
        assertThat(block(reloaded, gymId).status()).isEqualTo(BlockStatus.DONE);
        assertThat(block(reloaded, certId).status()).isEqualTo(BlockStatus.CANCELLED);
    }

    @Test
    void applyEventsRollsBackEveryEventWhenOneFails() {
        saveExampleSetup();
        String gymId = idOf(planService.getOrGenerate(MON), "Gym");

        List<PlanEvent> events = List.of(new MarkDone(gymId), new MarkDone("no-such-id"));
        assertThatThrownBy(() -> planService.applyEvents(events, MON)).isInstanceOf(UnknownBlockException.class);
        flushAndClear();

        assertThat(block(planStore.findDay(MON).orElseThrow(), gymId).status()).isEqualTo(BlockStatus.PLANNED);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=PlanServiceMultiEventTest`
Expected: FAIL to compile — `simulateEvents`/`applyEvents` do not exist yet on `PlanService`.

- [ ] **Step 3: Add the two methods to `PlanService`**

Open `backend/src/main/java/com/lifeorganizer/plan/PlanService.java`. Add `import java.util.ArrayList;` to the existing import list, then add these two methods right after the existing `applyEvent` method:

```java
    /**
     * Like {@link #applyEvent}, but for several events at once and without persisting anything - used
     * to build a preview/proposal. Events are chained: each one sees the plan as the previous one left it.
     */
    @Transactional(readOnly = true)
    public AppliedEvent simulateEvents(List<PlanEvent> events, LocalDate today) {
        Map<LocalDate, DayPlan> current = new LinkedHashMap<>();
        List<String> allChanges = new ArrayList<>();
        for (PlanEvent event : events) {
            LocalDate anchor = dateOf(event);
            for (Map.Entry<LocalDate, DayPlan> entry : loadDaysFor(event, anchor).entrySet()) {
                current.putIfAbsent(entry.getKey(), entry.getValue());
            }
            ReplanResult result = replanner.apply(current, event);
            current = new LinkedHashMap<>(result.updatedPlans());
            allChanges.addAll(result.changes());
        }
        DayPlan todayPlan = current.getOrDefault(today, getOrGenerate(today));
        return new AppliedEvent(todayPlan, allChanges);
    }

    /**
     * Applies every event and persists all of them in one transaction: if any event fails, none of the
     * batch's changes are written.
     */
    @Transactional
    public AppliedEvent applyEvents(List<PlanEvent> events, LocalDate today) {
        Map<LocalDate, DayPlan> current = new LinkedHashMap<>();
        List<String> allChanges = new ArrayList<>();
        for (PlanEvent event : events) {
            LocalDate anchor = dateOf(event);
            for (Map.Entry<LocalDate, DayPlan> entry : loadDaysFor(event, anchor).entrySet()) {
                current.putIfAbsent(entry.getKey(), entry.getValue());
            }
            ReplanResult result = replanner.apply(current, event);
            planStore.applyChanges(current, result.updatedPlans());
            current = new LinkedHashMap<>(result.updatedPlans());
            allChanges.addAll(result.changes());
        }
        return new AppliedEvent(getOrGenerate(today), allChanges);
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=PlanServiceMultiEventTest`
Expected: PASS, 3/3.

Run: `cd backend && ./mvnw test`
Expected: PASS, 221 + 3 = 224.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/lifeorganizer/plan/PlanService.java backend/src/test/java/com/lifeorganizer/plan/PlanServiceMultiEventTest.java
git commit -m "feat: add PlanService.simulateEvents and applyEvents"
```

---

### Task 5: The batch-apply endpoint

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/plan/dto/ApplyEventsRequest.java`, `ApplyEventsResponse.java`
- Modify: `backend/src/main/java/com/lifeorganizer/plan/PlanDtoMapper.java` (add `toEventsResponse`)
- Modify: `backend/src/main/java/com/lifeorganizer/plan/PlanController.java` (add the endpoint)
- Test: `backend/src/test/java/com/lifeorganizer/plan/PlanControllerBatchTest.java`

**Interfaces:**
- Consumes: `PlanService.applyEvents` (Task 4), `ApplyEventRequest.toEvent(LocalDate)` (existing).
- Produces: `POST /api/plan/today/events/batch` — body `{events: [ApplyEventRequest, ...]}` → `200 ApplyEventsResponse{date, blocks[], warnings[], changes[]}`; `400` if `events` is missing/empty or any event is malformed; `422` if any event's `blockId` is unknown. This is what `AssistantBox`'s Apply button (Task 9) calls, and what `CommandResponse.events` (Task 6) is shaped to be resent as.

- [ ] **Step 1: Write the DTOs**

`backend/src/main/java/com/lifeorganizer/plan/dto/ApplyEventsRequest.java`:

```java
package com.lifeorganizer.plan.dto;

import java.util.List;

/** Body of POST /api/plan/today/events/batch: the exact events a proposal was built from. */
public record ApplyEventsRequest(List<ApplyEventRequest> events) {
}
```

`backend/src/main/java/com/lifeorganizer/plan/dto/ApplyEventsResponse.java`:

```java
package com.lifeorganizer.plan.dto;

import java.util.List;

public record ApplyEventsResponse(String date, List<BlockDto> blocks, List<String> warnings, List<String> changes) {
}
```

- [ ] **Step 2: Write the failing test**

`backend/src/test/java/com/lifeorganizer/plan/PlanControllerBatchTest.java`:

```java
package com.lifeorganizer.plan;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlanController.class)
class PlanControllerBatchTest {

    private static final LocalDateTimeStub TODAY = new LocalDateTimeStub();

    private static final class LocalDateTimeStub {
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PlanService planService;

    @org.springframework.boot.test.context.TestConfiguration
    static class FixedClockConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    void appliesAllEventsAndReturnsConcatenatedChanges() throws Exception {
        when(planService.applyEvents(any(), eq(java.time.LocalDate.of(2026, 9, 14))))
            .thenReturn(new AppliedEvent(
                new com.lifeorganizer.domain.DayPlan(java.time.LocalDate.of(2026, 9, 14), List.of(), List.of()),
                List.of("Marked done Gym", "Cancelled Claude certification - slot is now free")));

        mvc.perform(post("/api/plan/today/events/batch").contentType(MediaType.APPLICATION_JSON).content("""
            {"events":[{"type":"MARK_DONE","blockId":"gym-1"},{"type":"CANCEL_BLOCK","blockId":"cert-1"}]}
            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changes[0]").value("Marked done Gym"))
            .andExpect(jsonPath("$.changes[1]").value("Cancelled Claude certification - slot is now free"));
    }

    @Test
    void rejectsAnEmptyEventsList() throws Exception {
        mvc.perform(post("/api/plan/today/events/batch").contentType(MediaType.APPLICATION_JSON).content("""
            {"events":[]}
            """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    @Test
    void returns422WhenAnEventReferencesAnUnknownBlock() throws Exception {
        when(planService.applyEvents(any(), any())).thenThrow(new com.lifeorganizer.error.UnknownBlockException("No block with id 'x'"));

        mvc.perform(post("/api/plan/today/events/batch").contentType(MediaType.APPLICATION_JSON).content("""
            {"events":[{"type":"MARK_DONE","blockId":"x"}]}
            """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.title").value("Unknown block"));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=PlanControllerBatchTest`
Expected: FAIL — no such endpoint (404), `ApplyEventsRequest`/`ApplyEventsResponse` referenced only via JSON here so this compiles, but the endpoint returns 404.

- [ ] **Step 4: Add `PlanDtoMapper.toEventsResponse`**

In `backend/src/main/java/com/lifeorganizer/plan/PlanDtoMapper.java`, add this method (it reuses the existing private `blocksOf` helper already in the file):

```java
    public static ApplyEventsResponse toEventsResponse(AppliedEvent applied) {
        DayPlan today = applied.today();
        return new ApplyEventsResponse(TimeFormats.format(today.date()), blocksOf(today), today.warnings(), applied.changes());
    }
```

Add `import com.lifeorganizer.plan.dto.ApplyEventsResponse;` to its imports.

- [ ] **Step 5: Add the endpoint to `PlanController`**

In `backend/src/main/java/com/lifeorganizer/plan/PlanController.java`, add imports for `ApplyEventsRequest`, `ApplyEventsResponse`, `InvalidRequestException` and `java.util.List`, then add this method after `applyEvent`:

```java
    @PostMapping("/today/events/batch")
    public ApplyEventsResponse applyEvents(@RequestBody ApplyEventsRequest request) {
        if (request.events() == null || request.events().isEmpty()) {
            throw new InvalidRequestException("events must not be empty");
        }
        LocalDate today = LocalDate.now(clock);
        List<PlanEvent> events = request.events().stream().map(event -> event.toEvent(today)).toList();
        return PlanDtoMapper.toEventsResponse(planService.applyEvents(events, today));
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=PlanControllerBatchTest`
Expected: PASS, 3/3.

Run: `cd backend && ./mvnw test`
Expected: PASS, 224 + 3 = 227.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/lifeorganizer/plan backend/src/test/java/com/lifeorganizer/plan/PlanControllerBatchTest.java
git commit -m "feat: add POST /api/plan/today/events/batch"
```

---

### Task 6: `AssistantController` and error mapping

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/ai/dto/CommandRequest.java`, `CommandResponse.java`
- Create: `backend/src/main/java/com/lifeorganizer/ai/controller/AssistantController.java`
- Create: `backend/src/main/java/com/lifeorganizer/error/AssistantUnavailableException.java`
- Modify: `backend/src/main/java/com/lifeorganizer/config/GlobalExceptionHandler.java` (add 502 and 503)
- Test: `backend/src/test/java/com/lifeorganizer/ai/controller/AssistantControllerTest.java`

**Interfaces:**
- Consumes: `CommandInterpreter` (Task 3), `PlanService.simulateEvents` (Task 4), `PlanDtoMapper.toResponse(DayPlan)` (existing).
- Produces: `POST /api/assistant/command` — body `{text}` → `200 CommandResponse{understood, rationale, message, events[], preview, changes[]}`; `400` blank text; `502` on any `AnthropicException`; `503` when no client is configured.

- [ ] **Step 1: Write the error class and DTOs**

`backend/src/main/java/com/lifeorganizer/error/AssistantUnavailableException.java`:

```java
package com.lifeorganizer.error;

/** The Claude call failed for a connectivity reason (network, auth, rate limit, timeout). */
public class AssistantUnavailableException extends RuntimeException {

    public AssistantUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`backend/src/main/java/com/lifeorganizer/ai/dto/CommandRequest.java`:

```java
package com.lifeorganizer.ai.dto;

public record CommandRequest(String text) {
}
```

`backend/src/main/java/com/lifeorganizer/ai/dto/CommandResponse.java`:

```java
package com.lifeorganizer.ai.dto;

import com.lifeorganizer.plan.AppliedEvent;
import com.lifeorganizer.plan.PlanDtoMapper;
import com.lifeorganizer.plan.dto.ApplyEventRequest;
import com.lifeorganizer.plan.dto.TodayPlanResponse;

import java.util.List;

public record CommandResponse(boolean understood, String rationale, String message,
                              List<ApplyEventRequest> events, TodayPlanResponse preview, List<String> changes) {

    public static CommandResponse notUnderstood(String message) {
        return new CommandResponse(false, null, message, List.of(), null, List.of());
    }

    public static CommandResponse understood(String rationale, List<ApplyEventRequest> events, AppliedEvent simulated) {
        return new CommandResponse(true, rationale, null, events,
            PlanDtoMapper.toResponse(simulated.today()), simulated.changes());
    }
}
```

- [ ] **Step 2: Write the failing tests**

`backend/src/test/java/com/lifeorganizer/ai/controller/AssistantControllerTest.java`:

```java
package com.lifeorganizer.ai.controller;

import com.anthropic.errors.AnthropicIoException;
import com.lifeorganizer.ai.model.InterpretedCommand;
import com.lifeorganizer.ai.service.CommandInterpreter;
import com.lifeorganizer.config.GlobalExceptionHandler;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.error.AiNotConfiguredException;
import com.lifeorganizer.plan.AppliedEvent;
import com.lifeorganizer.plan.PlanService;
import com.lifeorganizer.plan.dto.ApplyEventRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AssistantController.class)
@Import({AssistantControllerTest.FixedClock.class, GlobalExceptionHandler.class})
class AssistantControllerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CommandInterpreter commandInterpreter;

    @MockitoBean
    PlanService planService;

    private DayPlan emptyPlan() {
        return new DayPlan(TODAY, List.of(), List.of());
    }

    @Test
    void returnsAProposalWhenUnderstood() throws Exception {
        when(planService.getOrGenerate(TODAY)).thenReturn(emptyPlan());
        ApplyEventRequest event = new ApplyEventRequest("CANCEL_BLOCK", "padel-1", null, null, null, null, null, null);
        when(commandInterpreter.interpret(eq("padel got canceled"), eq(TODAY), any()))
            .thenReturn(InterpretedCommand.understood("Padel is cancelled", List.of(event)));
        when(planService.simulateEvents(any(), eq(TODAY)))
            .thenReturn(new AppliedEvent(emptyPlan(), List.of("Cancelled Padel - slot is now free")));

        mvc.perform(post("/api/assistant/command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"padel got canceled\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.understood").value(true))
            .andExpect(jsonPath("$.rationale").value("Padel is cancelled"))
            .andExpect(jsonPath("$.changes[0]").value("Cancelled Padel - slot is now free"));
    }

    @Test
    void returnsAMessageWhenNotUnderstood() throws Exception {
        when(planService.getOrGenerate(TODAY)).thenReturn(emptyPlan());
        when(commandInterpreter.interpret(any(), eq(TODAY), any()))
            .thenReturn(InterpretedCommand.notUnderstood("I couldn't find a matching block"));

        mvc.perform(post("/api/assistant/command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"do the thing\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.understood").value(false))
            .andExpect(jsonPath("$.message").value("I couldn't find a matching block"));
    }

    @Test
    void rejectsBlankText() throws Exception {
        mvc.perform(post("/api/assistant/command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"  \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    @Test
    void returns502WhenTheClientThrows() throws Exception {
        when(planService.getOrGenerate(TODAY)).thenReturn(emptyPlan());
        when(commandInterpreter.interpret(any(), eq(TODAY), any())).thenThrow(new AnthropicIoException("boom"));

        mvc.perform(post("/api/assistant/command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"anything\"}"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.title").value("Assistant unavailable"));
    }

    @Test
    void returns503WhenAiIsNotConfigured() throws Exception {
        when(planService.getOrGenerate(TODAY)).thenReturn(emptyPlan());
        when(commandInterpreter.interpret(any(), eq(TODAY), any()))
            .thenThrow(new AiNotConfiguredException("AI features are not configured"));

        mvc.perform(post("/api/assistant/command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"anything\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.title").value("AI features are not configured"));
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=AssistantControllerTest`
Expected: FAIL to compile — `AssistantController` does not exist yet.

- [ ] **Step 4: Write `AssistantController`**

`backend/src/main/java/com/lifeorganizer/ai/controller/AssistantController.java`:

```java
package com.lifeorganizer.ai.controller;

import com.anthropic.errors.AnthropicException;
import com.lifeorganizer.ai.dto.CommandRequest;
import com.lifeorganizer.ai.dto.CommandResponse;
import com.lifeorganizer.ai.model.InterpretedCommand;
import com.lifeorganizer.ai.service.CommandInterpreter;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.engine.PlanEvent;
import com.lifeorganizer.error.AssistantUnavailableException;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.plan.PlanService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final CommandInterpreter commandInterpreter;
    private final PlanService planService;
    private final Clock clock;

    public AssistantController(CommandInterpreter commandInterpreter, PlanService planService, Clock clock) {
        this.commandInterpreter = commandInterpreter;
        this.planService = planService;
        this.clock = clock;
    }

    @PostMapping("/command")
    public CommandResponse command(@RequestBody CommandRequest request) {
        if (request.text() == null || request.text().isBlank()) {
            throw new InvalidRequestException("text is required");
        }
        LocalDate today = LocalDate.now(clock);
        DayPlan plan = planService.getOrGenerate(today);

        InterpretedCommand interpreted;
        try {
            interpreted = commandInterpreter.interpret(request.text(), today, plan.blocksInOrder());
        } catch (AnthropicException e) {
            throw new AssistantUnavailableException("The assistant is unavailable right now", e);
        }

        if (!interpreted.understood()) {
            return CommandResponse.notUnderstood(interpreted.message());
        }
        List<PlanEvent> events = interpreted.events().stream().map(event -> event.toEvent(today)).toList();
        return CommandResponse.understood(interpreted.rationale(), interpreted.events(),
            planService.simulateEvents(events, today));
    }
}
```

- [ ] **Step 5: Add the two new handlers to `GlobalExceptionHandler`**

In `backend/src/main/java/com/lifeorganizer/config/GlobalExceptionHandler.java`, add imports for `AiNotConfiguredException` and `AssistantUnavailableException`, then add these two handler methods alongside the existing `@ExceptionHandler` methods (before the `handleHttpMessageNotReadable` override):

```java
    @ExceptionHandler(AssistantUnavailableException.class)
    public ProblemDetail assistantUnavailable(AssistantUnavailableException e) {
        return problem(502, "Assistant unavailable", e.getMessage());
    }

    @ExceptionHandler(AiNotConfiguredException.class)
    public ProblemDetail aiNotConfigured(AiNotConfiguredException e) {
        return problem(503, "AI features are not configured", e.getMessage());
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=AssistantControllerTest`
Expected: PASS, 5/5.

Run: `cd backend && ./mvnw test`
Expected: PASS, 227 + 5 = 232.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/lifeorganizer/ai/dto backend/src/main/java/com/lifeorganizer/ai/controller backend/src/main/java/com/lifeorganizer/error/AssistantUnavailableException.java backend/src/main/java/com/lifeorganizer/config/GlobalExceptionHandler.java backend/src/test/java/com/lifeorganizer/ai/controller
git commit -m "feat: add AssistantController (POST /api/assistant/command)"
```

---

### Task 7: Frontend API layer additions

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/client.ts`
- Modify: `frontend/src/api/client.test.ts`

**Interfaces:**
- Produces: `ConfigResponse`, `ProposedEventDto`, `CommandRequest`, `CommandResponse`, `ApplyEventsRequest`, `ApplyEventsResponse` types; `api.getConfig()`, `api.proposeCommand(request)`, `api.applyEvents(request)`.

- [ ] **Step 1: Write the failing tests**

In `frontend/src/api/client.test.ts`, add these three tests inside the existing `describe('api client', ...)` block, right before its closing `})` (after the `saves the setup with PUT...` test):

```ts
  it('fetches the feature flags', async () => {
    mockApi({ 'GET /api/config': () => jsonResponse({ aiEnabled: true, ragEnabled: false }) })

    expect(await api.getConfig()).toEqual({ aiEnabled: true, ragEnabled: false })
  })

  it('posts a command as JSON and returns the proposal', async () => {
    const fetchMock = mockApi({
      'POST /api/assistant/command': () =>
        jsonResponse({ understood: true, rationale: 'ok', message: null, events: [], preview: TODAY, changes: [] }),
    })

    const result = await api.proposeCommand({ text: 'padel got canceled' })

    expect(result.understood).toBe(true)
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(JSON.parse(init.body as string)).toEqual({ text: 'padel got canceled' })
  })

  it('posts events to the batch endpoint', async () => {
    const fetchMock = mockApi({
      'POST /api/plan/today/events/batch': () => jsonResponse({ ...TODAY, changes: ['Cancelled Padel'] }),
    })
    const event = { type: 'CANCEL_BLOCK' as const, blockId: 'b1', reason: null, date: null, title: null, start: null, end: null, energy: null }

    const result = await api.applyEvents({ events: [event] })

    expect(result.changes).toEqual(['Cancelled Padel'])
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(JSON.parse(init.body as string)).toEqual({ events: [event] })
  })
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npm --prefix frontend test -- src/api/client.test.ts`
Expected: FAIL to compile — `api.getConfig`/`api.proposeCommand`/`api.applyEvents` do not exist yet.

- [ ] **Step 3: Add the types**

Append to `frontend/src/api/types.ts`:

```ts
export interface ConfigResponse {
  aiEnabled: boolean
  ragEnabled: boolean
}

/** One event Claude proposes; the exact shape the backend replays on Apply. */
export interface ProposedEventDto {
  type: 'CANCEL_BLOCK' | 'POSTPONE_BLOCK' | 'MARK_DONE' | 'SET_ENERGY' | 'ADD_ADHOC'
  blockId: string | null
  reason: string | null
  date: string | null
  title: string | null
  start: string | null
  end: string | null
  energy: string | null
}

export interface CommandRequest {
  text: string
}

export interface CommandResponse {
  understood: boolean
  rationale: string | null
  message: string | null
  events: ProposedEventDto[]
  preview: TodayPlanResponse | null
  changes: string[]
}

export interface ApplyEventsRequest {
  events: ProposedEventDto[]
}

export interface ApplyEventsResponse extends TodayPlanResponse {
  changes: string[]
}
```

- [ ] **Step 4: Add the client functions**

In `frontend/src/api/client.ts`, change the top `import type { ... } from './types'` block to:

```ts
import type {
  ApplyEventResponse,
  ApplyEventsRequest,
  ApplyEventsResponse,
  CommandRequest,
  CommandResponse,
  ConfigResponse,
  PlanEventRequest,
  SetupParseRequest,
  SetupParseResponse,
  SetupPayload,
  TodayPlanResponse,
} from './types'
```

Add these three lines inside the `api` object, after `applyEvent`:

```ts
  getConfig: () => send<ConfigResponse>('GET', '/api/config'),
  proposeCommand: (request: CommandRequest) => send<CommandResponse>('POST', '/api/assistant/command', request),
  applyEvents: (request: ApplyEventsRequest) => send<ApplyEventsResponse>('POST', '/api/plan/today/events/batch', request),
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `npm --prefix frontend test`
Expected: PASS, 10 files, 94 tests (91 + 3 new).

Run: `npm --prefix frontend run build`
Expected: clean.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api
git commit -m "feat: add getConfig, proposeCommand and applyEvents to the API client"
```

---

### Task 8: `ProposalCard`

**Files:**
- Create: `frontend/src/components/ProposalCard.tsx`
- Test: `frontend/src/components/ProposalCard.test.tsx`

**Interfaces:**
- Consumes: `BlockDto`, `ProposedEventDto`, `TodayPlanResponse` (existing types).
- Produces: `ProposalCard({rationale, events, preview, currentBlocks, busy, onApply, onReject})` — one line per block that differs between `currentBlocks` and `preview.blocks` (by id: a new status/start time, or "new" for a block with no matching id), Apply/Reject buttons.

- [ ] **Step 1: Write the failing test**

`frontend/src/components/ProposalCard.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { makeBlock } from '../test/fixtures'
import type { TodayPlanResponse } from '../api/types'
import { ProposalCard } from './ProposalCard'

const CURRENT = [makeBlock({ id: 'padel-1', title: 'Padel', status: 'PLANNED', start: '19:00', end: '20:00' })]

function preview(blocks: TodayPlanResponse['blocks']): TodayPlanResponse {
  return { date: '2026-09-14', blocks, warnings: [] }
}

describe('ProposalCard', () => {
  it('shows the rationale and the Apply/Reject buttons', () => {
    render(
      <ProposalCard
        rationale="Padel is cancelled"
        events={[]}
        preview={preview(CURRENT)}
        currentBlocks={CURRENT}
        busy={false}
        onApply={vi.fn()}
        onReject={vi.fn()}
      />,
    )

    expect(screen.getByText('Padel is cancelled')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Apply' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument()
  })

  it('shows one line for a block whose status changed', () => {
    const changed = [makeBlock({ id: 'padel-1', title: 'Padel', status: 'CANCELLED', start: '19:00', end: '20:00' })]

    render(
      <ProposalCard rationale="x" events={[]} preview={preview(changed)} currentBlocks={CURRENT}
        busy={false} onApply={vi.fn()} onReject={vi.fn()} />,
    )

    expect(screen.getByText(/Padel/)).toHaveTextContent('Padel 19:00 → Cancelled 19:00')
  })

  it('shows nothing changed when the preview matches the current plan', () => {
    render(
      <ProposalCard rationale="x" events={[]} preview={preview(CURRENT)} currentBlocks={CURRENT}
        busy={false} onApply={vi.fn()} onReject={vi.fn()} />,
    )

    expect(screen.queryByRole('listitem')).not.toBeInTheDocument()
  })

  it('calls onApply and onReject', async () => {
    const user = userEvent.setup()
    const onApply = vi.fn()
    const onReject = vi.fn()
    render(
      <ProposalCard rationale="x" events={[]} preview={preview(CURRENT)} currentBlocks={CURRENT}
        busy={false} onApply={onApply} onReject={onReject} />,
    )

    await user.click(screen.getByRole('button', { name: 'Apply' }))
    await user.click(screen.getByRole('button', { name: 'Reject' }))

    expect(onApply).toHaveBeenCalledTimes(1)
    expect(onReject).toHaveBeenCalledTimes(1)
  })

  it('disables both buttons while busy', () => {
    render(
      <ProposalCard rationale="x" events={[]} preview={preview(CURRENT)} currentBlocks={CURRENT}
        busy={true} onApply={vi.fn()} onReject={vi.fn()} />,
    )

    expect(screen.getByRole('button', { name: 'Apply' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Reject' })).toBeDisabled()
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm --prefix frontend test -- src/components/ProposalCard.test.tsx`
Expected: FAIL: cannot resolve `./ProposalCard`.

- [ ] **Step 3: Write `ProposalCard`**

`frontend/src/components/ProposalCard.tsx`:

```tsx
import type { BlockDto, TodayPlanResponse, ProposedEventDto } from '../api/types'

interface Props {
  rationale: string
  events: ProposedEventDto[]
  preview: TodayPlanResponse
  currentBlocks: BlockDto[]
  busy: boolean
  onApply: () => void
  onReject: () => void
}

const STATUS_LABEL: Record<string, string> = {
  PLANNED: 'Planned',
  DONE: 'Done',
  CANCELLED: 'Cancelled',
  POSTPONED: 'Postponed',
}

/** One line per block that differs between the current plan and the simulated preview. */
function diffLines(currentBlocks: BlockDto[], previewBlocks: BlockDto[]): string[] {
  const current = new Map(currentBlocks.map((block) => [block.id, block]))
  const lines: string[] = []
  for (const next of previewBlocks) {
    const before = current.get(next.id)
    if (!before) {
      lines.push(`${next.title} ${next.start}–${next.end} → new`)
    } else if (before.status !== next.status || before.start !== next.start) {
      lines.push(`${before.title} ${before.start} → ${STATUS_LABEL[next.status]} ${next.start}`)
    }
  }
  return lines
}

/** The rationale plus a diff, shown after a command is understood. Apply resends the exact events. */
export function ProposalCard({ rationale, preview, currentBlocks, busy, onApply, onReject }: Props) {
  const lines = diffLines(currentBlocks, preview.blocks)

  return (
    <div className="proposal">
      <p>{rationale}</p>
      {lines.length > 0 && (
        <ul>
          {lines.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
      )}
      <div className="block-actions">
        <button type="button" disabled={busy} onClick={onApply}>
          Apply
        </button>
        <button type="button" disabled={busy} onClick={onReject}>
          Reject
        </button>
      </div>
    </div>
  )
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `npm --prefix frontend test -- src/components/ProposalCard.test.tsx`
Expected: PASS, 5/5.

- [ ] **Step 5: Add the `.proposal` style**

Append to `frontend/src/index.css`:

```css
.proposal {
  background: #fff;
  border: 1px solid #d9e2ec;
  border-radius: 4px;
  padding: 0.5rem 0.75rem;
  margin-top: 0.5rem;
}
```

- [ ] **Step 6: Run the whole suite and the build**

Run: `npm --prefix frontend test`
Expected: PASS, 11 files, 99 tests (94 + 5 new).

Run: `npm --prefix frontend run build`
Expected: clean.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/ProposalCard.tsx frontend/src/components/ProposalCard.test.tsx frontend/src/index.css
git commit -m "feat: add ProposalCard"
```

---

### Task 9: `AssistantBox` and wiring it into `TodayPage`

**Files:**
- Create: `frontend/src/components/AssistantBox.tsx`
- Test: `frontend/src/components/AssistantBox.test.tsx`
- Modify: `frontend/src/pages/TodayPage.tsx`
- Modify: `frontend/src/pages/TodayPage.test.tsx`

**Interfaces:**
- Consumes: `api.proposeCommand`, `api.applyEvents`, `api.getConfig` (Task 7), `ProposalCard` (Task 8).
- Produces: `AssistantBox({currentBlocks, busy, onApplied})` — a text box; Send calls `proposeCommand`; an understood reply shows `ProposalCard`; a not-understood reply shows the message inline; Apply calls `applyEvents` with the proposal's `events` and reports the result via `onApplied`; Reject clears the proposal. `TodayPage` fetches `/api/config` once and renders `<AssistantBox>` only when `aiEnabled`.

- [ ] **Step 1: Write the failing test for `AssistantBox`**

`frontend/src/components/AssistantBox.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { makeBlock } from '../test/fixtures'
import { jsonResponse, mockApi, problemResponse } from '../test/mockApi'
import { AssistantBox } from './AssistantBox'

afterEach(() => vi.unstubAllGlobals())

const CURRENT = [makeBlock({ id: 'padel-1', title: 'Padel', status: 'PLANNED', start: '19:00', end: '20:00' })]

describe('AssistantBox', () => {
  it('sends the typed text and shows the proposal', async () => {
    const user = userEvent.setup()
    const fetchMock = mockApi({
      'POST /api/assistant/command': () =>
        jsonResponse({
          understood: true,
          rationale: 'Padel is cancelled',
          message: null,
          events: [{ type: 'CANCEL_BLOCK', blockId: 'padel-1', reason: null, date: null, title: null, start: null, end: null, energy: null }],
          preview: { date: '2026-09-14', blocks: [makeBlock({ id: 'padel-1', title: 'Padel', status: 'CANCELLED', start: '19:00', end: '20:00' })], warnings: [] },
          changes: ['Cancelled Padel - slot is now free'],
        }),
    })
    render(<AssistantBox currentBlocks={CURRENT} busy={false} onApplied={vi.fn()} />)

    await user.type(screen.getByLabelText('Tell it what changed'), 'padel got canceled')
    await user.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText('Padel is cancelled')).toBeInTheDocument()
    const sent = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string)
    expect(sent).toEqual({ text: 'padel got canceled' })
  })

  it('shows the message and no proposal when not understood', async () => {
    const user = userEvent.setup()
    mockApi({
      'POST /api/assistant/command': () =>
        jsonResponse({ understood: false, rationale: null, message: "I couldn't find a matching block", events: [], preview: null, changes: [] }),
    })
    render(<AssistantBox currentBlocks={CURRENT} busy={false} onApplied={vi.fn()} />)

    await user.type(screen.getByLabelText('Tell it what changed'), 'do the thing')
    await user.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText("I couldn't find a matching block")).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Apply' })).not.toBeInTheDocument()
  })

  it('applies the exact proposed events and reports the result', async () => {
    const user = userEvent.setup()
    const event = { type: 'CANCEL_BLOCK', blockId: 'padel-1', reason: null, date: null, title: null, start: null, end: null, energy: null }
    const fetchMock = mockApi({
      'POST /api/assistant/command': () =>
        jsonResponse({ understood: true, rationale: 'ok', message: null, events: [event], preview: { date: '2026-09-14', blocks: CURRENT, warnings: [] }, changes: [] }),
      'POST /api/plan/today/events/batch': () => jsonResponse({ date: '2026-09-14', blocks: [], warnings: [], changes: ['Cancelled Padel'] }),
    })
    const onApplied = vi.fn()
    render(<AssistantBox currentBlocks={CURRENT} busy={false} onApplied={onApplied} />)
    await user.type(screen.getByLabelText('Tell it what changed'), 'cancel padel')
    await user.click(screen.getByRole('button', { name: 'Send' }))
    await screen.findByRole('button', { name: 'Apply' })

    await user.click(screen.getByRole('button', { name: 'Apply' }))

    expect(onApplied).toHaveBeenCalledWith({ date: '2026-09-14', blocks: [], warnings: [], changes: ['Cancelled Padel'] })
    const applyCall = fetchMock.mock.calls.find(([path]) => path === '/api/plan/today/events/batch')!
    expect(JSON.parse(applyCall[1]!.body as string)).toEqual({ events: [event] })
    expect(screen.queryByRole('button', { name: 'Apply' })).not.toBeInTheDocument()
  })

  it('clears the proposal on Reject without sending a request', async () => {
    const user = userEvent.setup()
    const fetchMock = mockApi({
      'POST /api/assistant/command': () =>
        jsonResponse({ understood: true, rationale: 'ok', message: null, events: [], preview: { date: '2026-09-14', blocks: CURRENT, warnings: [] }, changes: [] }),
    })
    render(<AssistantBox currentBlocks={CURRENT} busy={false} onApplied={vi.fn()} />)
    await user.type(screen.getByLabelText('Tell it what changed'), 'cancel padel')
    await user.click(screen.getByRole('button', { name: 'Send' }))
    await screen.findByRole('button', { name: 'Reject' })

    await user.click(screen.getByRole('button', { name: 'Reject' }))

    expect(screen.queryByRole('button', { name: 'Apply' })).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('shows an error and keeps the text when the assistant is unavailable', async () => {
    const user = userEvent.setup()
    mockApi({ 'POST /api/assistant/command': () => problemResponse(502, 'Assistant unavailable', 'The assistant is unavailable right now') })
    render(<AssistantBox currentBlocks={CURRENT} busy={false} onApplied={vi.fn()} />)

    await user.type(screen.getByLabelText('Tell it what changed'), 'cancel padel')
    await user.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('The assistant is unavailable right now')
  })

  it('disables Send while busy or while the text is empty', () => {
    render(<AssistantBox currentBlocks={CURRENT} busy={true} onApplied={vi.fn()} />)

    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled()
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm --prefix frontend test -- src/components/AssistantBox.test.tsx`
Expected: FAIL: cannot resolve `./AssistantBox`.

- [ ] **Step 3: Write `AssistantBox`**

`frontend/src/components/AssistantBox.tsx`:

```tsx
import { useId, useState } from 'react'
import { api, errorMessage } from '../api/client'
import type { ApplyEventsResponse, BlockDto, CommandResponse } from '../api/types'
import { ProposalCard } from './ProposalCard'

interface Props {
  currentBlocks: BlockDto[]
  /** True while the page's own actions (Done/Postpone/Cancel) are busy - Send is disabled too. */
  busy: boolean
  onApplied: (result: ApplyEventsResponse) => void
}

/** A one-line command box: type what changed, review a proposal, Apply or Reject. */
export function AssistantBox({ currentBlocks, busy: pageBusy, onApplied }: Props) {
  const [text, setText] = useState('')
  const [proposal, setProposal] = useState<CommandResponse | null>(null)
  const [notUnderstood, setNotUnderstood] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const inputId = useId()

  async function send() {
    if (!text.trim()) return
    setBusy(true)
    setError(null)
    setNotUnderstood(null)
    setProposal(null)
    try {
      const result = await api.proposeCommand({ text })
      if (result.understood) {
        setProposal(result)
      } else {
        setNotUnderstood(result.message ?? "I couldn't understand that.")
      }
    } catch (failure) {
      setError(errorMessage(failure))
    } finally {
      setBusy(false)
    }
  }

  async function apply() {
    if (!proposal) return
    setBusy(true)
    setError(null)
    try {
      const result = await api.applyEvents({ events: proposal.events })
      onApplied(result)
      setProposal(null)
      setText('')
    } catch (failure) {
      setError(errorMessage(failure))
    } finally {
      setBusy(false)
    }
  }

  function reject() {
    setProposal(null)
    setText('')
  }

  const disabled = busy || pageBusy

  return (
    <section className="assistant">
      <h3>Assistant</h3>
      <div className="field">
        <label htmlFor={inputId}>Tell it what changed</label>
        <input
          id={inputId}
          type="text"
          value={text}
          disabled={disabled}
          onChange={(event) => setText(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') send()
          }}
        />
      </div>
      <button type="button" disabled={disabled || !text.trim()} onClick={send}>
        Send
      </button>
      {error && <p role="alert">{error}</p>}
      {notUnderstood && <p className="muted">{notUnderstood}</p>}
      {proposal && proposal.understood && proposal.preview && (
        <ProposalCard
          rationale={proposal.rationale ?? ''}
          events={proposal.events}
          preview={proposal.preview}
          currentBlocks={currentBlocks}
          busy={disabled}
          onApply={apply}
          onReject={reject}
        />
      )}
    </section>
  )
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `npm --prefix frontend test -- src/components/AssistantBox.test.tsx`
Expected: PASS, 6/6.

- [ ] **Step 5: Write the failing test for `TodayPage`**

In `frontend/src/pages/TodayPage.test.tsx`, add this test inside the existing `describe('TodayPage', ...)` block:

```tsx
  it('shows the assistant box only when the backend reports aiEnabled', async () => {
    mockApi({
      'GET /api/plan/today': () => jsonResponse(TODAY),
      'GET /api/config': () => jsonResponse({ aiEnabled: true, ragEnabled: false }),
    })

    renderToday()

    expect(await screen.findByLabelText('Tell it what changed')).toBeInTheDocument()
  })

  it('does not show the assistant box when aiEnabled is false', async () => {
    mockApi({
      'GET /api/plan/today': () => jsonResponse(TODAY),
      'GET /api/config': () => jsonResponse({ aiEnabled: false, ragEnabled: false }),
    })

    renderToday()

    await screen.findByRole('heading', { name: "Today's plan" })
    expect(screen.queryByLabelText('Tell it what changed')).not.toBeInTheDocument()
  })
```

Every OTHER existing test in this file mocks `'GET /api/plan/today'` and will now also need `'GET /api/config'` mocked, since `TodayPage` will call it on every mount once Step 6 lands. Add `'GET /api/config': () => jsonResponse({ aiEnabled: false, ragEnabled: false }),` next to every existing `'GET /api/plan/today'` mock entry in this file (there are 17 such places — one per existing test, since `mockApi`'s fake `fetch` throws on any unmocked request).

- [ ] **Step 6: Run the tests to verify they fail**

Run: `npm --prefix frontend test -- src/pages/TodayPage.test.tsx`
Expected: FAIL — every test now throws `Unexpected request: GET /api/config` (since `mockApi` errors on unmocked requests), except this doesn't happen yet because `TodayPage` doesn't call `/api/config` yet: instead, the two NEW tests fail (`findByLabelText` never resolves — no assistant box exists).

- [ ] **Step 7: Wire `/api/config` and `AssistantBox` into `TodayPage`**

In `frontend/src/pages/TodayPage.tsx`:

Add to the imports:

```tsx
import { useApi } from '../api/useApi'
import { AssistantBox } from '../components/AssistantBox'
import type { ApplyEventsResponse, PlanEventRequest } from '../api/types'
```

(`useApi` is already imported; only add `AssistantBox` and the `ApplyEventsResponse` type to the existing type-only import line — do not duplicate the `useApi` import.)

Add this line right after `const { data, loading, error, setData, refetch } = useApi(api.getToday)`:

```tsx
  const { data: config } = useApi(api.getConfig)
```

Add this function next to `act`:

```tsx
  function onAssistantApplied(result: ApplyEventsResponse) {
    setData({ date: result.date, blocks: result.blocks, warnings: result.warnings })
    setChanges(result.changes)
  }
```

Add this JSX right after the closing `</Timeline>`'s sibling `<ChangeToast .../>` line (i.e. after `<ChangeToast changes={changes} onDismiss={dismissToast} />`), still inside the `<section>`:

```tsx
      {config?.aiEnabled && <AssistantBox currentBlocks={data.blocks} busy={busy} onApplied={onAssistantApplied} />}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `npm --prefix frontend test -- src/pages/TodayPage.test.tsx`
Expected: PASS, 19/19 (17 existing + 2 new).

Run: `npm --prefix frontend test`
Expected: PASS, 12 files, 107 tests (99 + 6 from AssistantBox + 2 from TodayPage).

Run: `npm --prefix frontend run build`
Expected: clean.

- [ ] **Step 9: Add the `.assistant` style**

Append to `frontend/src/index.css`:

```css
.assistant {
  margin-top: 1.5rem;
  padding-top: 1rem;
  border-top: 1px solid #d9e2ec;
}

.assistant h3 {
  margin: 0 0 0.5rem;
  font-size: 1rem;
}
```

- [ ] **Step 10: Commit**

```bash
git add frontend/src/components/AssistantBox.tsx frontend/src/components/AssistantBox.test.tsx frontend/src/pages/TodayPage.tsx frontend/src/pages/TodayPage.test.tsx frontend/src/index.css
git commit -m "feat: add AssistantBox and wire it into TodayPage behind aiEnabled"
```

---

### Task 10: Backend end-to-end test, live smoke test, and docs

**Files:**
- Create: `backend/src/test/java/com/lifeorganizer/AssistantApiEndToEndTest.java`
- Create: `backend/src/test/java/com/lifeorganizer/ai/LiveAnthropicSmokeTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: everything from Tasks 1–6, plus the existing `ApiEndToEndTest`'s conventions (fixed `Clock`, `truncate` `@BeforeEach`, Testcontainers Postgres).

- [ ] **Step 1: Write the end-to-end test**

`backend/src/test/java/com/lifeorganizer/AssistantApiEndToEndTest.java`:

```java
package com.lifeorganizer;

import com.lifeorganizer.ai.client.ClaudeCommandClient;
import com.lifeorganizer.ai.model.ClaudeCommandResult;
import com.lifeorganizer.ai.model.ProposedEvent;
import com.lifeorganizer.ai.model.ProposedEventType;
import com.lifeorganizer.support.ExampleSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.jayway.jsonpath.JsonPath.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the full HTTP flow (propose, then apply) with a fake {@link ClaudeCommandClient} standing
 * in for a real API call - no network, no API key needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({AssistantApiEndToEndTest.FixedClock.class, AssistantApiEndToEndTest.FakeClaudeConfig.class})
class AssistantApiEndToEndTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC);
        }
    }

    /** A fake standing in for the real Claude call: always cancels whatever block id it is asked about. */
    @TestConfiguration
    static class FakeClaudeConfig {
        @Bean
        ClaudeCommandClient fakeClaudeCommandClient() {
            return (system, user) -> {
                String blockId = user.lines()
                    .filter(line -> line.startsWith("- id="))
                    .map(line -> line.substring(5, line.indexOf(' ', 5)))
                    .findFirst()
                    .orElseThrow();
                ProposedEvent cancel = new ProposedEvent(ProposedEventType.CANCEL_BLOCK, Optional.of(blockId),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
                return new ClaudeCommandResult(true, Optional.of("Cancelling it"), Optional.empty(), List.of(cancel));
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table day_warning, block, plan_constraint, goal, category restart identity");
    }

    private static String jsonString(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Test
    void proposingThenApplyingACommandCancelsTheTargetBlock() throws Exception {
        String setupJson = "{\"goalsText\":" + jsonString(ExampleSetup.GOALS_TEXT)
            + ",\"constraintsText\":" + jsonString(ExampleSetup.CONSTRAINTS_TEXT) + "}";
        String parsed = mvc.perform(post("/api/setup/parse").contentType(MediaType.APPLICATION_JSON).content(setupJson))
            .andReturn().getResponse().getContentAsString();
        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON).content(parsed))
            .andExpect(status().isOk());

        String proposeResponse = mvc.perform(post("/api/assistant/command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"gym got canceled\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.understood").value(true))
            .andReturn().getResponse().getContentAsString();

        List<?> events = read(proposeResponse, "$.events");
        assertThat(events).hasSize(1);
        String applyBody = "{\"events\":" + com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .writeValueAsString(events) + "}";

        mvc.perform(post("/api/plan/today/events/batch").contentType(MediaType.APPLICATION_JSON).content(applyBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changes[0]").value(org.hamcrest.Matchers.containsString("Cancelled")));

        mvc.perform(post("/api/assistant/command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"anything else\"}"));
    }
}
```

- [ ] **Step 2: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AssistantApiEndToEndTest`
Expected: PASS, 1/1. If it fails because Jackson (Jackson 3, `tools.jackson`) is on the classpath instead of `com.fasterxml.jackson.databind.json.JsonMapper` for that one line, replace that line with the project's own existing JSON-writing convention instead (check how other end-to-end tests in this codebase serialize a `List`/`Map` back to JSON, e.g. `com.jayway.jsonpath.JsonPath`'s own utilities, and use that) — do not add a new Jackson dependency for one test.

Run: `cd backend && ./mvnw test`
Expected: PASS, 232 + 1 = 233 (plus `LiveAnthropicSmokeTest`, which is present but disabled without a key — Surefire reports it as skipped, not counted in the pass total).

- [ ] **Step 3: Write the optional live smoke test**

`backend/src/test/java/com/lifeorganizer/ai/LiveAnthropicSmokeTest.java`:

```java
package com.lifeorganizer.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.lifeorganizer.ai.client.AnthropicCommandClient;
import com.lifeorganizer.ai.model.ClaudeCommandResult;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Makes one real call to the Anthropic API. Runs only when a real key is present, and never in CI
 * without one - this is the only place in the suite that spends real tokens or needs real credentials.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class LiveAnthropicSmokeTest {

    @Test
    void aRealCallUnderstandsAnObviousCommand() {
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();
        AnthropicCommandClient sut = new AnthropicCommandClient(client, "claude-sonnet-5");

        Block gym = new Block("gym-1", java.time.LocalDate.now(), LocalTime.of(7, 0), LocalTime.of(8, 0),
            "Gym", BlockKind.GOAL, "health-gym", BlockStatus.PLANNED, 0, false, null);
        String prompt = "Today's blocks:\n- id=gym-1 title=\"Gym\" kind=GOAL status=PLANNED start=07:00 end=08:00\n"
            + "\nUser's text: mark the gym block done";

        ClaudeCommandResult result = sut.interpret(
            "You interpret plan commands. Respond with understood=true and a MARK_DONE event using blockId gym-1 "
                + "if the text asks to mark the gym done; otherwise understood=false.",
            prompt);

        assertThat(result.understood()).isTrue();
        assertThat(result.events()).isNotEmpty();
        client.close();
    }
}
```

Run this ONLY manually, with a real key: `cd backend && ANTHROPIC_API_KEY=sk-ant-... ./mvnw test -Dtest=LiveAnthropicSmokeTest` (PowerShell: `$env:ANTHROPIC_API_KEY="sk-ant-..."; .\mvnw.cmd test "-Dtest=LiveAnthropicSmokeTest"`). Do not run this as part of the plan's automated test steps — there is no key in this environment. Confirm only that `cd backend && ./mvnw test` (no key set) still shows this test as skipped, not failed, and that the total count is unaffected (`@EnabledIfEnvironmentVariable`-skipped tests do not add to the pass count in the summary the way a normal test does, but must not error).

- [ ] **Step 4: Update the README**

Add a new subsection to `README.md`, immediately after the existing `## API` table (before whatever section follows it — check with `grep -n '^## ' README.md` and insert right after the API table's rows end):

```markdown
## Assistant (optional)

Set `ANTHROPIC_API_KEY` before starting the backend to turn on the natural-language command box at the
bottom of Today:

```bash
export ANTHROPIC_API_KEY=sk-ant-...      # PowerShell: $env:ANTHROPIC_API_KEY="sk-ant-..."
cd backend && ./mvnw spring-boot:run
```

Type a sentence like "padel got canceled" or "I'm tired, postpone the gym" and press Send. You'll see a
one-line rationale and a list of what would change; press Apply to actually make the change, or Reject to
discard it and try again. Without the key, `GET /api/config` reports `aiEnabled:false` and the box does not
appear - every other feature is unaffected.
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/java/com/lifeorganizer/AssistantApiEndToEndTest.java backend/src/test/java/com/lifeorganizer/ai/LiveAnthropicSmokeTest.java README.md
git commit -m "test: add assistant end-to-end and live smoke tests; document ANTHROPIC_API_KEY"
```

- [ ] **Step 6: Manual end-to-end check (skipped by automated executors; the controller runs it in the browser pane)**

With Docker running and a real `ANTHROPIC_API_KEY` set: `docker compose up -d`, `cd backend && ANTHROPIC_API_KEY=... ./mvnw spring-boot:run`, `cd frontend && npm run dev`. Open http://localhost:5173, complete Setup, go to Today.

1. The assistant box is visible under the timeline (confirms `aiEnabled:true` reached the frontend).
2. Type "padel got canceled" (adjust to a real block title in your plan) and press Send. A rationale and a diff line appear; press Apply; confirm the block's status changes and the usual toast shows the change.
3. Type something nonsensical ("purple elephant seventeen"); confirm a plain message appears with no Apply/Reject buttons, and the text stays in the box.
4. Stop the backend, unset `ANTHROPIC_API_KEY`, restart it; reload Today; confirm the assistant box is gone and Setup/Done/Postpone/Cancel still work exactly as before.
