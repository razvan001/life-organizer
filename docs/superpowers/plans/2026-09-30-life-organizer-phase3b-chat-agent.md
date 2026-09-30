# Phase 3b Chat Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Today's one-sentence command box with a saved, per-day chat whose server-side agent reads today's plan, the week, the goals and RAG memory through tools, simulates changes and proposes them for Apply / Reject.

**Architecture:** A tool-calling loop (`AgentRunner`) on Groq's OpenAI-compatible `tools` / `tool_calls` API. Six read-only or simulate-only tools; the only mutation is the user's Apply, which replays the proposal through `PlanService.applyEvents`. Chat messages and proposals are stored per day in a new `chat_message` table.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Jackson 3 (`tools.jackson.*`), Flyway, Postgres 17 + pgvector, Spring `RestClient`, JUnit 5 / AssertJ / Mockito / MockRestServiceServer / Testcontainers; React 19 + TypeScript + Vite + Vitest.

**Spec:** `docs/superpowers/specs/2026-09-30-life-organizer-phase3b-chat-agent-design.md`

## Global Constraints

- Groq model stays `openai/gpt-oss-120b` (`app.ai.model`), endpoint `https://api.groq.com/openai/v1/chat/completions`, every AI bean gated on `@ConditionalOnProperty(name = "GROQ_API_KEY")`.
- Tool names are exactly: `get_today_plan`, `get_week_overview`, `list_goals`, `search_memory`, `simulate_changes`, `propose_changes`.
- At most 8 model calls per turn; history sent to the model = the last 20 messages of today's thread; a message is at most 2000 characters; a proposal has 1 to 5 events; at most one proposal per turn.
- Fallback reply text, exactly: `I couldn't finish that. Try rephrasing.`
- Proposal statuses: `PENDING`, `APPLIED`, `REJECTED`, `EXPIRED`. Message roles: `USER`, `ASSISTANT`.
- Endpoints: `GET /api/assistant/chat?date=`, `GET /api/assistant/chat/days`, `POST /api/assistant/chat/messages`, `POST /api/assistant/chat/messages/{id}/apply`, `POST /api/assistant/chat/messages/{id}/reject`.
- Errors: Groq unreachable → 502 (nothing saved); no key → 503; proposal not pending or not today's → 409; plan moved on → engine 422; unknown message → 404.
- Proposals only ever target today; never a FREE block. `get_week_overview` reads through `PlanStore.findDay` only and never generates a day.
- `ChatService.send` must not run inside a database transaction (LLM calls take seconds); persistence happens after the agent turn in its own transaction.
- A tool problem is returned to the model as tool text starting with `error: ` — never an HTTP error.
- Every Testcontainers test uses `new PostgreSQLContainer("pgvector/pgvector:pg17")`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` (use a second `-m`). `git commit` prompts the user; that is expected.
- Shell commands ASCII-only (the Bash wrapper breaks on non-ASCII); write files with the Write/Edit tools.

## Review Focus

1. The user sends a new message while an older proposal is still pending: the older proposal becomes `EXPIRED` and Apply on it answers 409. Pinned in Task 7.
2. The model calls a tool with missing or malformed arguments, or a tool name that does not exist: the turn continues with `error: …` fed back, never a 500. Pinned in Task 6.
3. Groq fails in the middle of a turn (after some tool calls): 502, nothing saved, the typed text stays in the input. Pinned in Task 7 (nothing saved) and Task 9 (text kept).
4. Apply after the plan moved on (a proposed block no longer exists): the engine's 422 reaches the user and the proposal stays `PENDING`. Pinned in Task 7.
5. A tab left open past midnight applies yesterday's proposal: 409, the plan is untouched. Pinned in Task 7.

---

### Task 1: Memory search fixes carried over from Phase 3a

**Files:**
- Modify: `backend/src/main/java/com/lifeorganizer/memory/store/MemoryChunkStore.java` (method `search`)
- Modify: `backend/src/main/java/com/lifeorganizer/memory/search/MemorySearch.java`
- Test: `backend/src/test/java/com/lifeorganizer/memory/store/MemoryChunkStoreTest.java` (add one test)
- Create: `backend/src/test/java/com/lifeorganizer/memory/search/MemorySearchTest.java`

**Interfaces:**
- Consumes: existing `MemoryChunkStore.search(float[] query, int k, SourceType filter)`, `MemoryHit`, `SourceType`, `TextEmbedder`.
- Produces: unchanged signatures. `MemoryChunkStore.search` now returns hits sorted by score descending and, with a filter, still returns up to k rows on a large table. `MemorySearch.search(String query, int k, SourceType filter)` returns at most one hit per `(sourceType, sourceId)`.

- [ ] **Step 1: Failing store test**

Add to `MemoryChunkStoreTest` (it already has `store`; add `@Autowired JdbcTemplate jdbc;` and imports `java.util.ArrayList`, `java.util.Comparator`, `java.util.Random`, `org.springframework.jdbc.core.JdbcTemplate`):

```java
    private static float[] randomVector(Random random) {
        float[] vector = new float[384];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) random.nextGaussian();
        }
        return vector;
    }

    @Test
    void aFilteredSearchStillReturnsKHitsWhenThatSourceIsRare() {
        Random random = new Random(42);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 6000; i++) {
            SourceType type = i < 5500 ? SourceType.NOTE : SourceType.DAY;
            rows.add(new Object[] {type.name(), "s" + i, "T", "text " + i, "h",
                MemoryChunkStore.literal(randomVector(random))});
        }
        jdbc.batchUpdate("""
            insert into memory_chunk (source_type, source_id, title, text, content_hash, embedding)
            values (?, ?, ?, ?, ?, ?::vector)
            """, rows);
        jdbc.execute("analyze memory_chunk");

        List<MemoryHit> hits = store.search(randomVector(random), 20, SourceType.DAY);

        assertThat(hits).hasSize(20).allSatisfy(hit -> assertThat(hit.sourceType()).isEqualTo(SourceType.DAY));
        assertThat(hits).extracting(MemoryHit::score).isSortedAccordingTo(Comparator.reverseOrder());
    }
```

- [ ] **Step 2: Run it**

Run: `cd backend && ./mvnw -q test -Dtest=MemoryChunkStoreTest`
Expected: the new test FAILS with fewer than 20 hits (the HNSW index post-filters its 40 candidates). If it passes before the fix (the planner chose an exact scan), note that in the report and still implement Step 3 — the spec requires it.

- [ ] **Step 3: Implement the store change**

Replace `search` in `MemoryChunkStore` (add imports `java.util.Comparator`):

```java
    /**
     * The k chunks closest to the query by cosine distance, optionally only of one source type, best first.
     * With a filter, the HNSW index would only post-filter its first ef_search candidates, so a rare source type
     * could come back with fewer than k rows; iterative scan (pgvector 0.8+) keeps scanning until k rows match.
     * relaxed_order may return rows slightly out of order, hence the sort.
     */
    @Transactional(readOnly = true)
    public List<MemoryHit> search(float[] query, int k, SourceType filter) {
        String vector = literal(query);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
            select source_type, source_id, source_date, title, text, 1 - (embedding <=> ?::vector) as score
            from memory_chunk
            """);
        args.add(vector);
        if (filter != null) {
            jdbc.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
            sql.append("where source_type = ?\n");
            args.add(filter.name());
        }
        sql.append("order by embedding <=> ?::vector limit ?");
        args.add(vector);
        args.add(k);
        List<MemoryHit> hits = new ArrayList<>(jdbc.query(sql.toString(), HIT, args.toArray()));
        hits.sort(Comparator.comparingDouble(MemoryHit::score).reversed());
        return hits;
    }
```

- [ ] **Step 4: Failing dedup test**

`backend/src/test/java/com/lifeorganizer/memory/search/MemorySearchTest.java`:

```java
package com.lifeorganizer.memory.search;

import com.lifeorganizer.memory.store.MemoryChunkStore;
import com.lifeorganizer.memory.store.MemoryHit;
import com.lifeorganizer.memory.store.SourceType;
import com.lifeorganizer.support.HashingTextEmbedder;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemorySearchTest {

    private final MemoryChunkStore store = mock(MemoryChunkStore.class);
    private final MemorySearch search = new MemorySearch(new HashingTextEmbedder(), store);

    private static MemoryHit hit(SourceType type, String id, String text, double score) {
        return new MemoryHit(type, id, type == SourceType.DAY ? LocalDate.of(2026, 9, 24) : null, "T" + id, text, score);
    }

    @Test
    void keepsOnlyTheBestChunkOfEachSource() {
        when(store.search(any(), eq(20), isNull())).thenReturn(List.of(
            hit(SourceType.NOTE, "1", "best chunk of note 1", 0.9),
            hit(SourceType.NOTE, "1", "second chunk of note 1", 0.8),
            hit(SourceType.NOTE, "2", "note 2", 0.7),
            hit(SourceType.DAY, "2026-09-24", "a day", 0.6)));

        List<MemoryHit> hits = search.search("anything", 5, null);

        assertThat(hits).extracting(MemoryHit::text)
            .containsExactly("best chunk of note 1", "note 2", "a day");
    }

    @Test
    void returnsAtMostKSources() {
        when(store.search(any(), eq(8), eq(SourceType.NOTE))).thenReturn(List.of(
            hit(SourceType.NOTE, "1", "a", 0.9), hit(SourceType.NOTE, "2", "b", 0.8),
            hit(SourceType.NOTE, "3", "c", 0.7)));

        assertThat(search.search("anything", 2, SourceType.NOTE)).hasSize(2);
    }
}
```

Run: `cd backend && ./mvnw -q test -Dtest=MemorySearchTest` — Expected: FAIL (the current code asks the store for k, not 4k, and does not deduplicate).

- [ ] **Step 5: Implement the dedup**

Replace `search` in `MemorySearch` (imports `java.util.LinkedHashMap`, `java.util.Map`):

```java
    /** Chunks fetched per wanted result, so deduplicating by source still leaves k results in most cases. */
    static final int OVER_FETCH = 4;

    public List<MemoryHit> search(String query, int k, SourceType filter) {
        List<MemoryHit> chunks = store.search(embedder.embed(query), k * OVER_FETCH, filter);
        // Results are per chunk; keep each source's best chunk so one long note cannot fill every slot.
        Map<String, MemoryHit> bestPerSource = new LinkedHashMap<>();
        for (MemoryHit chunk : chunks) {
            bestPerSource.putIfAbsent(chunk.sourceType() + ":" + chunk.sourceId(), chunk);
        }
        List<MemoryHit> hits = bestPerSource.values().stream().limit(k).toList();
        log.info("Memory search: query.length={} k={} source={} hits={} topScore={}", query.length(), k, filter,
            hits.size(), hits.isEmpty() ? "-" : String.format(Locale.ROOT, "%.3f", hits.get(0).score()));
        return hits;
    }
```

- [ ] **Step 6: Run and commit**

Run: `cd backend && ./mvnw -q test -Dtest='MemoryChunkStoreTest,MemorySearchTest,MemoryControllerTest,MemoryApiEndToEndTest'` — Expected: all pass.

```bash
git add backend/src/main/java/com/lifeorganizer/memory backend/src/test/java/com/lifeorganizer/memory
git commit -m "fix: keep filtered memory searches complete and return one hit per source" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `ProposedEventValidator` and `PlanDiff`

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/ai/agent/ProposedEventValidator.java`
- Create: `backend/src/main/java/com/lifeorganizer/ai/agent/PlanDiff.java`
- Create: `backend/src/test/java/com/lifeorganizer/ai/agent/ProposedEventValidatorTest.java`
- Create: `backend/src/test/java/com/lifeorganizer/ai/agent/PlanDiffTest.java`

**Interfaces:**
- Consumes: `ApplyEventRequest.toEvent(LocalDate)` (throws `InvalidRequestException`), `Block`, `BlockKind`, `PlanEvent`, `TimeFormats.format(LocalTime)`.
- Produces:
  - `ProposedEventValidator.MAX_EVENTS = 5`; `static List<PlanEvent> validate(List<ApplyEventRequest> events, LocalDate today, List<Block> todaysBlocks)` — throws `InvalidRequestException` naming the first problem.
  - `PlanDiff.lines(List<Block> before, List<Block> after) : List<String>`.

- [ ] **Step 1: Failing tests**

`ProposedEventValidatorTest.java`:

```java
package com.lifeorganizer.ai.agent;

import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.engine.AddAdhoc;
import com.lifeorganizer.engine.CancelBlock;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.plan.dto.ApplyEventRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProposedEventValidatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);
    private static final Block GYM = new Block("gym-1", TODAY, LocalTime.of(7, 0), LocalTime.of(8, 0), "Gym",
        BlockKind.GOAL, "health-gym", BlockStatus.PLANNED, 0, false, null);
    private static final Block FREE = new Block("free-2026-09-30-08:00", TODAY, LocalTime.of(8, 0),
        LocalTime.of(23, 0), "Free", BlockKind.FREE, null, BlockStatus.PLANNED, 0, false, null);
    private static final List<Block> BLOCKS = List.of(GYM, FREE);

    private static ApplyEventRequest cancel(String blockId) {
        return new ApplyEventRequest("CANCEL_BLOCK", blockId, null, null, null, null, null, null);
    }

    private static ApplyEventRequest adhoc(String date) {
        return new ApplyEventRequest("ADD_ADHOC", null, null, date, "Movie", "20:00", "22:00", null);
    }

    @Test
    void turnsValidRequestsIntoEngineEvents() {
        assertThat(ProposedEventValidator.validate(List.of(cancel("gym-1"), adhoc(null)), TODAY, BLOCKS))
            .satisfiesExactly(
                event -> assertThat(event).isEqualTo(new CancelBlock("gym-1")),
                event -> assertThat(event).isInstanceOf(AddAdhoc.class));
    }

    @Test
    void anAdhocForTodaysDateIsAllowed() {
        assertThat(ProposedEventValidator.validate(List.of(adhoc("2026-09-30")), TODAY, BLOCKS)).hasSize(1);
    }

    @Test
    void rejectsAnUnknownBlock() {
        assertThatThrownBy(() -> ProposedEventValidator.validate(List.of(cancel("nope")), TODAY, BLOCKS))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("unknown blockId 'nope': use an id from today's plan");
    }

    @Test
    void rejectsAFreeBlock() {
        assertThatThrownBy(() -> ProposedEventValidator.validate(List.of(cancel(FREE.id())), TODAY, BLOCKS))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("block 'free-2026-09-30-08:00' is free time, not something that can be changed");
    }

    @Test
    void rejectsAnAdhocOnAnotherDay() {
        assertThatThrownBy(() -> ProposedEventValidator.validate(List.of(adhoc("2026-10-01")), TODAY, BLOCKS))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("ADD_ADHOC can only add to today (2026-09-30)");
    }

    @Test
    void rejectsZeroOrMoreThanFiveEvents() {
        assertThatThrownBy(() -> ProposedEventValidator.validate(List.of(), TODAY, BLOCKS))
            .hasMessage("propose between 1 and 5 events");
        assertThatThrownBy(() -> ProposedEventValidator.validate(Collections.nCopies(6, cancel("gym-1")), TODAY, BLOCKS))
            .hasMessage("propose between 1 and 5 events");
        assertThatThrownBy(() -> ProposedEventValidator.validate(null, TODAY, BLOCKS))
            .hasMessage("propose between 1 and 5 events");
    }

    @Test
    void passesOnTheEnginesOwnRules() {
        ApplyEventRequest noTitle = new ApplyEventRequest("ADD_ADHOC", null, null, null, null, "20:00", "22:00", null);
        assertThatThrownBy(() -> ProposedEventValidator.validate(List.of(noTitle), TODAY, BLOCKS))
            .isInstanceOf(InvalidRequestException.class).hasMessage("title is required");
    }
}
```

`PlanDiffTest.java`:

```java
package com.lifeorganizer.ai.agent;

import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanDiffTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 30);

    private static Block block(String id, String title, int hour, BlockStatus status) {
        return new Block(id, DAY, LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0), title, BlockKind.GOAL, null,
            status, 0, false, null);
    }

    @Test
    void listsNewMovedAndStatusChangedBlocksOnly() {
        List<Block> before = List.of(block("a", "Gym", 7, BlockStatus.PLANNED), block("b", "Study", 18, BlockStatus.PLANNED),
            block("c", "Social", 20, BlockStatus.PLANNED));
        List<Block> after = List.of(block("a", "Gym", 7, BlockStatus.CANCELLED), block("b", "Study", 19, BlockStatus.PLANNED),
            block("c", "Social", 20, BlockStatus.PLANNED), block("d", "Movie", 21, BlockStatus.PLANNED));

        assertThat(PlanDiff.lines(before, after)).containsExactly(
            "Gym 07:00 -> CANCELLED 07:00",
            "Study 18:00 -> PLANNED 19:00",
            "Movie 21:00-22:00 -> new");
    }
}
```

Run: `cd backend && ./mvnw -q test -Dtest='ProposedEventValidatorTest,PlanDiffTest'` — Expected: compilation failure.

- [ ] **Step 2: Implement**

`ProposedEventValidator.java`:

```java
package com.lifeorganizer.ai.agent;

import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.engine.PlanEvent;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.plan.dto.ApplyEventRequest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks events the model wants to propose before they reach the engine: today only, real block ids, never
 * FREE blocks (open time, not a commitment; its id also changes when an ad-hoc block splits it), 1 to 5 events,
 * and every event must pass the engine's own request rules.
 */
public final class ProposedEventValidator {

    public static final int MAX_EVENTS = 5;

    private ProposedEventValidator() {
    }

    public static List<PlanEvent> validate(List<ApplyEventRequest> events, LocalDate today, List<Block> todaysBlocks) {
        if (events == null || events.isEmpty() || events.size() > MAX_EVENTS) {
            throw new InvalidRequestException("propose between 1 and " + MAX_EVENTS + " events");
        }
        Map<String, Block> known = new HashMap<>();
        for (Block block : todaysBlocks) {
            known.put(block.id(), block);
        }
        List<PlanEvent> result = new ArrayList<>();
        for (ApplyEventRequest request : events) {
            if (request == null) {
                throw new InvalidRequestException("an event is missing");
            }
            String blockId = request.blockId();
            if (blockId != null && !blockId.isBlank()) {
                Block target = known.get(blockId);
                if (target == null) {
                    throw new InvalidRequestException("unknown blockId '" + blockId + "': use an id from today's plan");
                }
                if (target.kind() == BlockKind.FREE) {
                    throw new InvalidRequestException(
                        "block '" + blockId + "' is free time, not something that can be changed");
                }
            }
            if ("ADD_ADHOC".equals(request.type()) && request.date() != null && !request.date().isBlank()
                && !request.date().equals(today.toString())) {
                throw new InvalidRequestException("ADD_ADHOC can only add to today (" + today + ")");
            }
            result.add(request.toEvent(today));
        }
        return result;
    }
}
```

`PlanDiff.java`:

```java
package com.lifeorganizer.ai.agent;

import com.lifeorganizer.api.TimeFormats;
import com.lifeorganizer.domain.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** One readable line per block that is new, or whose status or start time changed (same idea as the UI's ProposalCard). */
public final class PlanDiff {

    private PlanDiff() {
    }

    public static List<String> lines(List<Block> before, List<Block> after) {
        Map<String, Block> old = before.stream().collect(Collectors.toMap(Block::id, Function.identity(), (a, b) -> b));
        List<String> lines = new ArrayList<>();
        for (Block next : after) {
            Block previous = old.get(next.id());
            if (previous == null) {
                lines.add(next.title() + " " + TimeFormats.format(next.start()) + "-" + TimeFormats.format(next.end())
                    + " -> new");
            } else if (previous.status() != next.status() || !previous.start().equals(next.start())) {
                lines.add(previous.title() + " " + TimeFormats.format(previous.start()) + " -> " + next.status() + " "
                    + TimeFormats.format(next.start()));
            }
        }
        return lines;
    }
}
```

- [ ] **Step 3: Run and commit**

Run: `cd backend && ./mvnw -q test -Dtest='ProposedEventValidatorTest,PlanDiffTest'` — Expected: 8 pass.

```bash
git add backend/src/main/java/com/lifeorganizer/ai/agent backend/src/test/java/com/lifeorganizer/ai/agent
git commit -m "feat: add proposal validation and plan diff for the chat agent" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Tool-calling Groq client

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/ai/agent/ToolCall.java`, `ToolSpec.java`, `AgentMessage.java`, `AgentReply.java`
- Create: `backend/src/main/java/com/lifeorganizer/ai/client/AgentLlmClient.java`
- Create: `backend/src/main/java/com/lifeorganizer/ai/client/GroqAgentClient.java`
- Modify: `backend/src/main/java/com/lifeorganizer/ai/client/GroqChatClient.java`
- Modify: `backend/src/main/java/com/lifeorganizer/ai/client/LlmClientConfig.java`
- Create: `backend/src/test/java/com/lifeorganizer/ai/client/GroqAgentClientTest.java`
- Create: `backend/src/test/java/com/lifeorganizer/ai/LiveGroqAgentSmokeTest.java`
- Modify: `backend/src/test/java/com/lifeorganizer/ai/client/LlmClientConfigTest.java`

**Interfaces:**
- Produces:
  - `record ToolCall(String id, String name, String arguments)` (arguments = raw JSON string from the model)
  - `record ToolSpec(String name, String description, String parametersJson)`
  - `record AgentMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId)` with `enum Role { SYSTEM, USER, ASSISTANT, TOOL }` and factories `system(String)`, `user(String)`, `assistant(String content, List<ToolCall> calls)`, `tool(String toolCallId, String content)`
  - `record AgentReply(String content, List<ToolCall> toolCalls)` (toolCalls never null)
  - `interface AgentLlmClient { AgentReply chat(List<AgentMessage> messages, List<ToolSpec> tools); }` — throws `RestClientException` (unreachable / non-2xx) or `MalformedLlmReplyException` (no text and no tool calls)
  - `GroqAgentClient(RestClient restClient, String model) implements AgentLlmClient`
  - `LlmClientConfig.agentLlmClient(...)` bean
  - `GroqChatClient` keeps `call(...)` and `jsonSchemaResponseFormat(...)` unchanged; gains package-private `ChatMessage send(List<Map<String, Object>> messages, Map<String, Object> extra)`

- [ ] **Step 1: Records and interface**

`ToolCall.java`:

```java
package com.lifeorganizer.ai.agent;

/** One tool the model wants run. {@code arguments} is the model's raw JSON text. */
public record ToolCall(String id, String name, String arguments) {
}
```

`ToolSpec.java`:

```java
package com.lifeorganizer.ai.agent;

/** A tool as the model sees it. {@code parametersJson} is the JSON Schema of the arguments object. */
public record ToolSpec(String name, String description, String parametersJson) {
}
```

`AgentMessage.java`:

```java
package com.lifeorganizer.ai.agent;

import java.util.List;

/** One message in the model conversation. Only ASSISTANT messages carry tool calls; only TOOL messages a tool call id. */
public record AgentMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public AgentMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static AgentMessage system(String content) {
        return new AgentMessage(Role.SYSTEM, content, List.of(), null);
    }

    public static AgentMessage user(String content) {
        return new AgentMessage(Role.USER, content, List.of(), null);
    }

    public static AgentMessage assistant(String content, List<ToolCall> toolCalls) {
        return new AgentMessage(Role.ASSISTANT, content, toolCalls, null);
    }

    public static AgentMessage tool(String toolCallId, String content) {
        return new AgentMessage(Role.TOOL, content, List.of(), toolCallId);
    }
}
```

`AgentReply.java`:

```java
package com.lifeorganizer.ai.agent;

import java.util.List;

/** The model's answer to one call: text, tool calls, or both. */
public record AgentReply(String content, List<ToolCall> toolCalls) {

    public AgentReply {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
```

`AgentLlmClient.java`:

```java
package com.lifeorganizer.ai.client;

import com.lifeorganizer.ai.agent.AgentMessage;
import com.lifeorganizer.ai.agent.AgentReply;
import com.lifeorganizer.ai.agent.ToolSpec;

import java.util.List;

/**
 * One model call with tools. Throws {@code RestClientException} when the provider is unreachable or answers
 * non-2xx, and {@link MalformedLlmReplyException} when the reply has neither text nor tool calls.
 */
public interface AgentLlmClient {

    AgentReply chat(List<AgentMessage> messages, List<ToolSpec> tools);
}
```

- [ ] **Step 2: Failing client test**

`GroqAgentClientTest.java`:

```java
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
```

Add to `LlmClientConfigTest`:

```java
    @Test
    void noAgentClientWithoutAnApiKey() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(AgentLlmClient.class));
    }

    @Test
    void anAgentClientExistsWhenTheApiKeyIsSet() {
        contextRunner.withPropertyValues("GROQ_API_KEY=gsk_test-dummy-key")
            .run(context -> assertThat(context).hasSingleBean(AgentLlmClient.class));
    }
```

Run: `cd backend && ./mvnw -q test -Dtest='GroqAgentClientTest,LlmClientConfigTest'` — Expected: compilation failure.

- [ ] **Step 3: Refactor `GroqChatClient`**

Keep `call(...)` and `jsonSchemaResponseFormat(...)` behavior and logs. Split the HTTP part out so both clients share it. The response records become package-private and learn `tool_calls`:

```java
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
            response = restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(GroqChatResponse.class);
        } catch (RestClientException e) {
            log.warn("Groq call failed: {}", e.getMessage());
            throw e;
        }
        if (response == null || response.choices() == null || response.choices().isEmpty()
            || response.choices().get(0).message() == null) {
            log.warn("Groq returned no choices: {}", response);
            throw new MalformedLlmReplyException("Groq returned no choices");
        }
        return response.choices().get(0).message();
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

    record GroqChatResponse(List<Choice> choices) {
    }

    record Choice(ChatMessage message) {
    }

    record ChatMessage(String content, @JsonProperty("tool_calls") List<GroqToolCall> toolCalls) {
    }

    record GroqToolCall(String id, GroqFunction function) {
    }

    record GroqFunction(String name, String arguments) {
    }
```

(`@JsonProperty` is `com.fasterxml.jackson.annotation.JsonProperty` — Jackson 3 keeps the annotations package.) Remove the old private records and the old body-building code in `call`.

- [ ] **Step 4: `GroqAgentClient` and the bean**

```java
package com.lifeorganizer.ai.client;

import com.lifeorganizer.ai.agent.AgentMessage;
import com.lifeorganizer.ai.agent.AgentReply;
import com.lifeorganizer.ai.agent.ToolCall;
import com.lifeorganizer.ai.agent.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Groq chat completions with OpenAI-style function tools (tools / tool_calls / tool messages). */
public class GroqAgentClient implements AgentLlmClient {

    private static final Logger log = LoggerFactory.getLogger(GroqAgentClient.class);

    private final GroqChatClient chat;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public GroqAgentClient(RestClient restClient, String model) {
        this.chat = new GroqChatClient(restClient, model);
    }

    @Override
    public AgentReply chat(List<AgentMessage> messages, List<ToolSpec> tools) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("tools", tools.stream().map(this::toolJson).toList());
        extra.put("tool_choice", "auto");
        GroqChatClient.ChatMessage message = chat.send(messages.stream().map(GroqAgentClient::messageJson).toList(), extra);

        List<ToolCall> calls = message.toolCalls() == null ? List.of() : message.toolCalls().stream()
            .map(call -> new ToolCall(call.id(),
                call.function() == null ? null : call.function().name(),
                call.function() == null ? null : call.function().arguments()))
            .toList();
        boolean noText = message.content() == null || message.content().isBlank();
        if (noText && calls.isEmpty()) {
            log.warn("Groq returned neither text nor tool calls");
            throw new MalformedLlmReplyException("Groq returned neither text nor tool calls");
        }
        log.debug("Groq agent reply: text.length={} toolCalls={}", noText ? 0 : message.content().length(),
            calls.stream().map(ToolCall::name).toList());
        return new AgentReply(noText ? null : message.content(), calls);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolJson(ToolSpec spec) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", spec.name());
        function.put("description", spec.description());
        function.put("parameters", jsonMapper.readValue(spec.parametersJson(), Map.class));
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    static Map<String, Object> messageJson(AgentMessage message) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("role", message.role().name().toLowerCase(Locale.ROOT));
        switch (message.role()) {
            case ASSISTANT -> {
                json.put("content", message.content() == null ? "" : message.content());
                if (!message.toolCalls().isEmpty()) {
                    json.put("tool_calls", message.toolCalls().stream().map(call -> {
                        Map<String, Object> function = new LinkedHashMap<>();
                        function.put("name", call.name());
                        function.put("arguments", call.arguments() == null ? "{}" : call.arguments());
                        Map<String, Object> toolCall = new LinkedHashMap<>();
                        toolCall.put("id", call.id());
                        toolCall.put("type", "function");
                        toolCall.put("function", function);
                        return toolCall;
                    }).toList());
                }
            }
            case TOOL -> {
                json.put("tool_call_id", message.toolCallId());
                json.put("content", message.content());
            }
            default -> json.put("content", message.content());
        }
        return json;
    }
}
```

In `LlmClientConfig`, extract the shared RestClient building into `private static RestClient groqRestClient(String apiKey)` and add:

```java
    @Bean
    @ConditionalOnProperty(name = "GROQ_API_KEY")
    public AgentLlmClient agentLlmClient(@Value("${GROQ_API_KEY}") String apiKey,
                                         @Value("${app.ai.model:openai/gpt-oss-120b}") String model) {
        return new GroqAgentClient(groqRestClient(apiKey), model);
    }
```

- [ ] **Step 5: Live smoke test (skipped without a key)**

`LiveGroqAgentSmokeTest.java`:

```java
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
```

- [ ] **Step 6: Run and commit**

Run: `cd backend && ./mvnw -q test -Dtest='GroqAgentClientTest,LlmClientConfigTest,GroqCommandClientTest,GroqExtractionClientTest,LiveGroqAgentSmokeTest'`
Expected: all pass; `LiveGroqAgentSmokeTest` skipped without a key (if the key is set, report its result).

```bash
git add backend/src/main/java/com/lifeorganizer/ai backend/src/test/java/com/lifeorganizer/ai
git commit -m "feat: add a tool-calling Groq client for the chat agent" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Tool framework and the four read tools

**Files:**
- Create in `backend/src/main/java/com/lifeorganizer/ai/agent/`: `Proposal.java`, `TurnContext.java`
- Create in `backend/src/main/java/com/lifeorganizer/ai/agent/tools/`: `AgentTool.java`, `ToolException.java`, `ToolJson.java`, `GetTodayPlanTool.java`, `WeekOverviewTool.java`, `ListGoalsTool.java`, `SearchMemoryTool.java`
- Create: `backend/src/test/java/com/lifeorganizer/ai/agent/tools/ReadToolsTest.java`

**Interfaces:**
- Consumes: `PlanService.getOrGenerate(LocalDate)`, `PlanStore.findDay(LocalDate)`, `SetupStore.loadTree()`, `MemorySearch.search(String, int, SourceType)` (Task 1), `DaySummarizer.title(LocalDate)`, `ToolSpec` (Task 3).
- Produces:
  - `record Proposal(String rationale, List<ApplyEventRequest> events, List<String> changes, TodayPlanResponse preview)`
  - `final class TurnContext` — `TurnContext(LocalDate today)`, `LocalDate today()`, `Optional<Proposal> proposal()`, `void propose(Proposal)` (throws `ToolException` if one exists)
  - `interface AgentTool` — `String name()`, `String description()`, `String parametersJson()`, `String execute(JsonNode arguments, TurnContext context)`, `default ToolSpec spec()`
  - `class ToolException extends RuntimeException` — `ToolException(String message)`
  - `final class ToolJson` — `static final JsonMapper MAPPER`, `static String write(Object value)`, `static String requiredText(JsonNode args, String field, int max)`, `static String optionalText(JsonNode args, String field, int max)`
  - Tool beans (`@Component`): `GetTodayPlanTool`, `WeekOverviewTool`, `ListGoalsTool`, `SearchMemoryTool` with names from Global Constraints.

- [ ] **Step 1: Framework types**

`Proposal.java`:

```java
package com.lifeorganizer.ai.agent;

import com.lifeorganizer.plan.dto.ApplyEventRequest;
import com.lifeorganizer.plan.dto.TodayPlanResponse;

import java.util.List;

/** The one change set the agent proposes in a turn: what Apply will replay, plus the simulated result. */
public record Proposal(String rationale, List<ApplyEventRequest> events, List<String> changes, TodayPlanResponse preview) {
}
```

`TurnContext.java`:

```java
package com.lifeorganizer.ai.agent;

import com.lifeorganizer.ai.agent.tools.ToolException;

import java.time.LocalDate;
import java.util.Optional;

/** State shared by the tools during one user turn: today's date and the (at most one) proposal. */
public final class TurnContext {

    private final LocalDate today;
    private Proposal proposal;

    public TurnContext(LocalDate today) {
        this.today = today;
    }

    public LocalDate today() {
        return today;
    }

    public Optional<Proposal> proposal() {
        return Optional.ofNullable(proposal);
    }

    public void propose(Proposal next) {
        if (proposal != null) {
            throw new ToolException("a proposal was already made in this turn; reply to the user now");
        }
        proposal = next;
    }
}
```

`AgentTool.java`:

```java
package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.ToolSpec;
import com.lifeorganizer.ai.agent.TurnContext;
import tools.jackson.databind.JsonNode;

/**
 * One capability the agent may call. Tools only read or simulate - they never change the plan. A problem the model
 * can fix (bad arguments, unknown block) is thrown as ToolException or InvalidRequestException; the runner turns it
 * into an "error: ..." tool result.
 */
public interface AgentTool {

    String name();

    String description();

    /** JSON Schema of the arguments object. */
    String parametersJson();

    /** Runs the tool; the returned text is exactly what the model sees. */
    String execute(JsonNode arguments, TurnContext context);

    default ToolSpec spec() {
        return new ToolSpec(name(), description(), parametersJson());
    }
}
```

`ToolException.java`:

```java
package com.lifeorganizer.ai.agent.tools;

/** A problem with a tool call that the model can correct; its message goes back to the model. */
public class ToolException extends RuntimeException {

    public ToolException(String message) {
        super(message);
    }
}
```

`ToolJson.java`:

```java
package com.lifeorganizer.ai.agent.tools;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** JSON helpers shared by the tools. */
public final class ToolJson {

    /** Lenient on input: the model may send fields a request type does not know. */
    public static final JsonMapper MAPPER = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    private ToolJson() {
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static String requiredText(JsonNode args, String field, int max) {
        String value = optionalText(args, field, max);
        if (value == null) {
            throw new ToolException(field + " is required");
        }
        return value;
    }

    public static String optionalText(JsonNode args, String field, int max) {
        JsonNode node = args.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new ToolException(field + " must be a string");
        }
        String value = node.asString().strip();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > max) {
            throw new ToolException(field + " must be at most " + max + " characters");
        }
        return value;
    }
}
```

- [ ] **Step 2: Failing tests for the four read tools**

`ReadToolsTest.java`:

```java
package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.TurnContext;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.domain.Energy;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.GoalTree;
import com.lifeorganizer.domain.PreferredTime;
import com.lifeorganizer.memory.search.MemorySearch;
import com.lifeorganizer.memory.store.MemoryHit;
import com.lifeorganizer.memory.store.SourceType;
import com.lifeorganizer.persistence.PlanStore;
import com.lifeorganizer.persistence.SetupStore;
import com.lifeorganizer.plan.PlanService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadToolsTest {

    /** Wednesday. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);
    private static final TurnContext CONTEXT = new TurnContext(TODAY);

    private static JsonNode args(String json) {
        return ToolJson.MAPPER.readTree(json);
    }

    private static Block block(String id, LocalDate date, int hour, String title, BlockKind kind, BlockStatus status) {
        return new Block(id, date, LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0), title, kind, null, status, 0,
            false, null);
    }

    @Test
    void everyToolHasTheAgreedNameAndAParseableSchema() {
        List<AgentTool> tools = List.of(new GetTodayPlanTool(mock(PlanService.class)),
            new WeekOverviewTool(mock(PlanStore.class)), new ListGoalsTool(mock(SetupStore.class)),
            new SearchMemoryTool(mock(MemorySearch.class)));

        assertThat(tools).extracting(AgentTool::name)
            .containsExactly("get_today_plan", "get_week_overview", "list_goals", "search_memory");
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.description()).isNotBlank();
            assertThat(ToolJson.MAPPER.readTree(tool.parametersJson()).path("type").asString()).isEqualTo("object");
        });
    }

    @Test
    void todaysPlanListsBlockIdsAndKinds() {
        PlanService plans = mock(PlanService.class);
        when(plans.getOrGenerate(TODAY)).thenReturn(new DayPlan(TODAY, List.of(
            block("gym-1", TODAY, 7, "Gym", BlockKind.GOAL, BlockStatus.PLANNED)), List.of()));

        JsonNode result = ToolJson.MAPPER.readTree(new GetTodayPlanTool(plans).execute(args("{}"), CONTEXT));

        assertThat(result.path("date").asString()).isEqualTo("2026-09-30");
        assertThat(result.path("blocks").get(0).path("id").asString()).isEqualTo("gym-1");
        assertThat(result.path("blocks").get(0).path("kind").asString()).isEqualTo("GOAL");
        assertThat(result.path("blocks").get(0).path("start").asString()).isEqualTo("07:00");
    }

    @Test
    void weekOverviewReadsStoredDaysOnlyAndMarksToday() {
        PlanStore store = mock(PlanStore.class);
        LocalDate monday = LocalDate.of(2026, 9, 28);
        when(store.findDay(any())).thenReturn(Optional.empty());
        when(store.findDay(monday)).thenReturn(Optional.of(new DayPlan(monday, List.of(
            block("gym-m", monday, 7, "Gym", BlockKind.GOAL, BlockStatus.DONE),
            block("work-m", monday, 9, "Work", BlockKind.FIXED, BlockStatus.PLANNED)), List.of())));
        when(store.findDay(TODAY)).thenReturn(Optional.of(new DayPlan(TODAY, List.of(
            block("free-t", TODAY, 8, "Free", BlockKind.FREE, BlockStatus.PLANNED)), List.of())));

        String result = new WeekOverviewTool(store).execute(args("{}"), CONTEXT);

        assertThat(result.lines()).containsExactly(
            "Mon 2026-09-28: Gym 07:00 DONE",
            "Tue 2026-09-29: not planned yet",
            "Wed 2026-09-30 (today): only routine and free time",
            "Thu 2026-10-01: not planned yet",
            "Fri 2026-10-02: not planned yet",
            "Sat 2026-10-03: not planned yet",
            "Sun 2026-10-04: not planned yet");
    }

    @Test
    void listGoalsDescribesEachGoal() {
        SetupStore setup = mock(SetupStore.class);
        Category health = new Category("health", "Health");
        when(setup.loadTree()).thenReturn(new GoalTree(List.of(health), List.of(new Goal("health-gym", health, "Gym", 4,
            null, 60, Energy.HIGH, PreferredTime.MORNING))));

        JsonNode goal = ToolJson.MAPPER.readTree(new ListGoalsTool(setup).execute(args("{}"), CONTEXT)).get(0);

        assertThat(goal.path("id").asString()).isEqualTo("health-gym");
        assertThat(goal.path("category").asString()).isEqualTo("Health");
        assertThat(goal.path("priority").asInt()).isEqualTo(4);
        assertThat(goal.path("preferredTime").asString()).isEqualTo("MORNING");
    }

    @Test
    void listGoalsSaysSoWhenThereAreNone() {
        SetupStore setup = mock(SetupStore.class);
        when(setup.loadTree()).thenReturn(new GoalTree(List.of(), List.of()));

        assertThat(new ListGoalsTool(setup).execute(args("{}"), CONTEXT)).isEqualTo("No goals saved yet.");
    }

    @Test
    void searchMemoryPassesQueryKAndSource() {
        MemorySearch search = mock(MemorySearch.class);
        when(search.search("tired", 3, SourceType.DAY)).thenReturn(List.of(new MemoryHit(SourceType.DAY, "2026-09-24",
            LocalDate.of(2026, 9, 24), "Thu 2026-09-24", "Thu 2026-09-24: Gym done 07:00", 0.61234)));

        JsonNode hit = ToolJson.MAPPER.readTree(new SearchMemoryTool(search)
            .execute(args("{\"query\":\"tired\",\"k\":3,\"source\":\"day\"}"), CONTEXT)).get(0);

        assertThat(hit.path("source").asString()).isEqualTo("DAY");
        assertThat(hit.path("date").asString()).isEqualTo("2026-09-24");
        assertThat(hit.path("score").asDouble()).isEqualTo(0.61);
    }

    @Test
    void searchMemoryDefaultsKToFiveAndSaysWhenNothingMatched() {
        MemorySearch search = mock(MemorySearch.class);
        when(search.search("x", 5, null)).thenReturn(List.of());

        assertThat(new SearchMemoryTool(search).execute(args("{\"query\":\"x\"}"), CONTEXT))
            .isEqualTo("No matching notes or past days.");
        verify(search).search("x", 5, null);
    }

    @Test
    void searchMemoryRejectsBadArguments() {
        SearchMemoryTool tool = new SearchMemoryTool(mock(MemorySearch.class));

        assertThatThrownBy(() -> tool.execute(args("{}"), CONTEXT)).isInstanceOf(ToolException.class)
            .hasMessage("query is required");
        assertThatThrownBy(() -> tool.execute(args("{\"query\":\"x\",\"k\":9}"), CONTEXT))
            .hasMessage("k must be a whole number between 1 and 8");
        assertThatThrownBy(() -> tool.execute(args("{\"query\":\"x\",\"source\":\"EMAIL\"}"), CONTEXT))
            .hasMessage("source must be NOTE or DAY");
    }
}
```

Before writing the Goal constructor call, confirm its parameter order in `backend/src/main/java/com/lifeorganizer/domain/Goal.java` (id, category, name, priority, deadline, defaultBlockMinutes, energy, preferredTime per `SetupDtoMapper.toDomain`); adjust the test call if it differs.

Run: `cd backend && ./mvnw -q test -Dtest=ReadToolsTest` — Expected: compilation failure.

- [ ] **Step 3: Implement the read tools**

`GetTodayPlanTool.java`:

```java
package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.TurnContext;
import com.lifeorganizer.api.TimeFormats;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.plan.PlanService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GetTodayPlanTool implements AgentTool {

    private final PlanService planService;

    public GetTodayPlanTool(PlanService planService) {
        this.planService = planService;
    }

    @Override
    public String name() {
        return "get_today_plan";
    }

    @Override
    public String description() {
        return "Today's plan: every block with its id, title, kind (FIXED, ROUTINE, GOAL, FREE, ADHOC), status "
            + "(PLANNED, DONE, CANCELLED, POSTPONED), start and end. Call this before proposing changes.";
    }

    @Override
    public String parametersJson() {
        return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    }

    @Override
    public String execute(JsonNode arguments, TurnContext context) {
        List<Map<String, Object>> blocks = planService.getOrGenerate(context.today()).blocksInOrder().stream()
            .map(GetTodayPlanTool::describe)
            .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", context.today().toString());
        result.put("blocks", blocks);
        return ToolJson.write(result);
    }

    private static Map<String, Object> describe(Block block) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", block.id());
        json.put("title", block.title());
        json.put("kind", block.kind().name());
        json.put("status", block.status().name());
        json.put("start", TimeFormats.format(block.start()));
        json.put("end", TimeFormats.format(block.end()));
        return json;
    }
}
```

`WeekOverviewTool.java`:

```java
package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.TurnContext;
import com.lifeorganizer.api.TimeFormats;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.memory.history.DaySummarizer;
import com.lifeorganizer.persistence.PlanStore;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** This week at a glance. Reads stored days only: it must never generate a day (PlanService.getOrGenerate). */
@Component
public class WeekOverviewTool implements AgentTool {

    private static final Set<BlockKind> SHOWN = Set.of(BlockKind.GOAL, BlockKind.ADHOC);

    private final PlanStore planStore;

    public WeekOverviewTool(PlanStore planStore) {
        this.planStore = planStore;
    }

    @Override
    public String name() {
        return "get_week_overview";
    }

    @Override
    public String description() {
        return "This week (Monday to Sunday), one line per day with its goal and ad-hoc blocks and their status. "
            + "Context only: changes can only be proposed for today.";
    }

    @Override
    public String parametersJson() {
        return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    }

    @Override
    public String execute(JsonNode arguments, TurnContext context) {
        LocalDate monday = context.today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<String> lines = new ArrayList<>();
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = monday.plusDays(offset);
            String label = DaySummarizer.title(date) + (date.equals(context.today()) ? " (today)" : "");
            lines.add(label + ": " + planStore.findDay(date).map(WeekOverviewTool::describe).orElse("not planned yet"));
        }
        return String.join("\n", lines);
    }

    private static String describe(DayPlan day) {
        String blocks = day.blocksInOrder().stream()
            .filter(block -> SHOWN.contains(block.kind()))
            .map(block -> block.title() + " " + TimeFormats.format(block.start()) + " " + block.status())
            .collect(Collectors.joining("; "));
        return blocks.isEmpty() ? "only routine and free time" : blocks;
    }
}
```

`ListGoalsTool.java`:

```java
package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.TurnContext;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.persistence.SetupStore;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ListGoalsTool implements AgentTool {

    private final SetupStore setupStore;

    public ListGoalsTool(SetupStore setupStore) {
        this.setupStore = setupStore;
    }

    @Override
    public String name() {
        return "list_goals";
    }

    @Override
    public String description() {
        return "The user's goals with category, priority (1 least to 5 most important), deadline, preferred time of "
            + "day, energy a session needs, and the usual session length in minutes.";
    }

    @Override
    public String parametersJson() {
        return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    }

    @Override
    public String execute(JsonNode arguments, TurnContext context) {
        List<Goal> goals = setupStore.loadTree().goals();
        if (goals.isEmpty()) {
            return "No goals saved yet.";
        }
        return ToolJson.write(goals.stream().map(ListGoalsTool::describe).toList());
    }

    private static Map<String, Object> describe(Goal goal) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", goal.id());
        json.put("name", goal.name());
        json.put("category", goal.category().name());
        json.put("priority", goal.priority());
        json.put("deadline", goal.deadline() == null ? null : goal.deadline().toString());
        json.put("preferredTime", goal.preferredTime().name());
        json.put("energy", goal.energy().name());
        json.put("sessionMinutes", goal.defaultBlockMinutes());
        return json;
    }
}
```

`SearchMemoryTool.java`:

```java
package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.TurnContext;
import com.lifeorganizer.memory.search.MemorySearch;
import com.lifeorganizer.memory.store.MemoryHit;
import com.lifeorganizer.memory.store.SourceType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class SearchMemoryTool implements AgentTool {

    static final int DEFAULT_K = 5;
    static final int MAX_K = 8;
    static final int MAX_QUERY = 500;
    static final int MAX_TEXT = 300;

    private final MemorySearch memorySearch;

    public SearchMemoryTool(MemorySearch memorySearch) {
        this.memorySearch = memorySearch;
    }

    @Override
    public String name() {
        return "search_memory";
    }

    @Override
    public String description() {
        return "Semantic search over the user's own notes and one-line summaries of past days (what was done, "
            + "postponed or cancelled, and why). Use it when the user mentions how they feel, what usually works "
            + "for them, or past days.";
    }

    @Override
    public String parametersJson() {
        return """
            {"type":"object","properties":{
              "query":{"type":"string","description":"what to look for, in natural language"},
              "k":{"type":"integer","minimum":1,"maximum":8,"description":"how many results (default 5)"},
              "source":{"type":"string","enum":["NOTE","DAY"],"description":"only notes or only past days"}
            },"required":["query"],"additionalProperties":false}
            """;
    }

    @Override
    public String execute(JsonNode arguments, TurnContext context) {
        String query = ToolJson.requiredText(arguments, "query", MAX_QUERY);
        List<MemoryHit> hits = memorySearch.search(query, k(arguments), source(arguments));
        if (hits.isEmpty()) {
            return "No matching notes or past days.";
        }
        return ToolJson.write(hits.stream().map(SearchMemoryTool::describe).toList());
    }

    private static int k(JsonNode arguments) {
        JsonNode node = arguments.path("k");
        if (node.isMissingNode() || node.isNull()) {
            return DEFAULT_K;
        }
        if (!node.isIntegralNumber() || node.asInt() < 1 || node.asInt() > MAX_K) {
            throw new ToolException("k must be a whole number between 1 and " + MAX_K);
        }
        return node.asInt();
    }

    private static SourceType source(JsonNode arguments) {
        String value = ToolJson.optionalText(arguments, "source", 10);
        if (value == null) {
            return null;
        }
        try {
            return SourceType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ToolException("source must be NOTE or DAY");
        }
    }

    private static Map<String, Object> describe(MemoryHit hit) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("source", hit.sourceType().name());
        json.put("title", hit.title());
        json.put("date", hit.sourceDate() == null ? null : hit.sourceDate().toString());
        json.put("text", hit.text().length() <= MAX_TEXT ? hit.text() : hit.text().substring(0, MAX_TEXT) + "...");
        json.put("score", Math.round(hit.score() * 100) / 100.0);
        return json;
    }
}
```

- [ ] **Step 4: Run and commit**

Run: `cd backend && ./mvnw -q test -Dtest=ReadToolsTest` — Expected: 8 pass.

```bash
git add backend/src/main/java/com/lifeorganizer/ai/agent backend/src/test/java/com/lifeorganizer/ai/agent
git commit -m "feat: add the chat agent's read tools" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: `simulate_changes` and `propose_changes`

**Files:**
- Create in `backend/src/main/java/com/lifeorganizer/ai/agent/tools/`: `ChangeSimulator.java`, `SimulateChangesTool.java`, `ProposeChangesTool.java`
- Create: `backend/src/test/java/com/lifeorganizer/ai/agent/tools/ChangeToolsTest.java`

**Interfaces:**
- Consumes: `PlanService.getOrGenerate(LocalDate)`, `PlanService.simulateEvents(List<PlanEvent>, LocalDate) : AppliedEvent`, `PlanDtoMapper.toResponse(DayPlan) : TodayPlanResponse`, `ProposedEventValidator.validate` and `PlanDiff.lines` (Task 2), `TurnContext.propose`, `Proposal`, `ToolJson`, `ToolException` (Task 4).
- Produces:
  - `@Component ChangeSimulator` — `Simulation simulate(JsonNode arguments, LocalDate today)`; nested `record Simulation(List<ApplyEventRequest> requests, List<String> diff, List<String> engineMessages, TodayPlanResponse preview)`
  - `@Component SimulateChangesTool` (`simulate_changes`), `@Component ProposeChangesTool` (`propose_changes`)

- [ ] **Step 1: Failing tests**

`ChangeToolsTest.java`:

```java
package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.Proposal;
import com.lifeorganizer.ai.agent.TurnContext;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.engine.CancelBlock;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.plan.AppliedEvent;
import com.lifeorganizer.plan.PlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChangeToolsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);
    private static final Block GYM = new Block("gym-1", TODAY, LocalTime.of(7, 0), LocalTime.of(8, 0), "Gym",
        BlockKind.GOAL, "health-gym", BlockStatus.PLANNED, 0, false, null);
    private static final Block GYM_CANCELLED = new Block("gym-1", TODAY, LocalTime.of(7, 0), LocalTime.of(8, 0), "Gym",
        BlockKind.GOAL, "health-gym", BlockStatus.CANCELLED, 0, false, null);
    private static final String CANCEL_GYM = "{\"rationale\":\"Gym is off\",\"events\":[{\"type\":\"CANCEL_BLOCK\",\"blockId\":\"gym-1\"}]}";

    private PlanService plans;
    private SimulateChangesTool simulate;
    private ProposeChangesTool propose;

    private static JsonNode args(String json) {
        return ToolJson.MAPPER.readTree(json);
    }

    @BeforeEach
    void setUp() {
        plans = mock(PlanService.class);
        when(plans.getOrGenerate(TODAY)).thenReturn(new DayPlan(TODAY, List.of(GYM), List.of()));
        when(plans.simulateEvents(List.of(new CancelBlock("gym-1")), TODAY))
            .thenReturn(new AppliedEvent(new DayPlan(TODAY, List.of(GYM_CANCELLED), List.of()),
                List.of("Cancelled Gym - slot is now free")));
        ChangeSimulator simulator = new ChangeSimulator(plans);
        simulate = new SimulateChangesTool(simulator);
        propose = new ProposeChangesTool(simulator);
    }

    @Test
    void simulateShowsTheDiffAndEngineMessagesWithoutProposing() {
        TurnContext context = new TurnContext(TODAY);

        JsonNode result = ToolJson.MAPPER.readTree(simulate.execute(args(CANCEL_GYM), context));

        assertThat(result.path("diff").get(0).asString()).isEqualTo("Gym 07:00 -> CANCELLED 07:00");
        assertThat(result.path("engineMessages").get(0).asString()).isEqualTo("Cancelled Gym - slot is now free");
        assertThat(context.proposal()).isEmpty();
    }

    @Test
    void proposeRecordsTheProposalOnTheTurn() {
        TurnContext context = new TurnContext(TODAY);

        String result = propose.execute(args(CANCEL_GYM), context);

        Proposal proposal = context.proposal().orElseThrow();
        assertThat(proposal.rationale()).isEqualTo("Gym is off");
        assertThat(proposal.events()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("CANCEL_BLOCK");
            assertThat(event.blockId()).isEqualTo("gym-1");
        });
        assertThat(proposal.changes()).containsExactly("Cancelled Gym - slot is now free");
        assertThat(proposal.preview().blocks().get(0).status()).isEqualTo("CANCELLED");
        assertThat(result).contains("\"recorded\":true");
    }

    @Test
    void aSecondProposalInTheSameTurnIsRefused() {
        TurnContext context = new TurnContext(TODAY);
        propose.execute(args(CANCEL_GYM), context);

        assertThatThrownBy(() -> propose.execute(args(CANCEL_GYM), context)).isInstanceOf(ToolException.class)
            .hasMessage("a proposal was already made in this turn; reply to the user now");
    }

    @Test
    void proposeNeedsARationale() {
        assertThatThrownBy(() -> propose.execute(args("{\"events\":[{\"type\":\"CANCEL_BLOCK\",\"blockId\":\"gym-1\"}]}"),
            new TurnContext(TODAY))).isInstanceOf(ToolException.class).hasMessage("rationale is required");
    }

    @Test
    void eventsMustBeAnArrayOfObjects() {
        assertThatThrownBy(() -> simulate.execute(args("{\"events\":\"cancel gym\"}"), new TurnContext(TODAY)))
            .isInstanceOf(ToolException.class).hasMessage("events must be an array of event objects");
    }

    @Test
    void invalidEventsNeverReachTheEngine() {
        assertThatThrownBy(() -> simulate.execute(args("{\"events\":[{\"type\":\"CANCEL_BLOCK\",\"blockId\":\"nope\"}]}"),
            new TurnContext(TODAY))).isInstanceOf(InvalidRequestException.class);
        verify(plans, never()).simulateEvents(anyList(), org.mockito.ArgumentMatchers.any());
    }
}
```

Run: `cd backend && ./mvnw -q test -Dtest=ChangeToolsTest` — Expected: compilation failure.

- [ ] **Step 2: Implement**

`ChangeSimulator.java`:

```java
package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.PlanDiff;
import com.lifeorganizer.ai.agent.ProposedEventValidator;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.engine.PlanEvent;
import com.lifeorganizer.plan.AppliedEvent;
import com.lifeorganizer.plan.PlanDtoMapper;
import com.lifeorganizer.plan.PlanService;
import com.lifeorganizer.plan.dto.ApplyEventRequest;
import com.lifeorganizer.plan.dto.TodayPlanResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Parses, validates and dry-runs a set of events against today's plan. Never persists a change. */
@Component
public class ChangeSimulator {

    public record Simulation(List<ApplyEventRequest> requests, List<String> diff, List<String> engineMessages,
                             TodayPlanResponse preview) {
    }

    private final PlanService planService;

    public ChangeSimulator(PlanService planService) {
        this.planService = planService;
    }

    public Simulation simulate(JsonNode arguments, LocalDate today) {
        List<ApplyEventRequest> requests = parse(arguments.path("events"));
        List<Block> before = planService.getOrGenerate(today).blocksInOrder();
        List<PlanEvent> events = ProposedEventValidator.validate(requests, today, before);
        AppliedEvent simulated = planService.simulateEvents(events, today);
        return new Simulation(requests, PlanDiff.lines(before, simulated.today().blocksInOrder()),
            simulated.changes(), PlanDtoMapper.toResponse(simulated.today()));
    }

    private static List<ApplyEventRequest> parse(JsonNode events) {
        if (!events.isArray()) {
            throw new ToolException("events must be an array of event objects");
        }
        List<ApplyEventRequest> requests = new ArrayList<>();
        for (JsonNode event : events) {
            if (!event.isObject()) {
                throw new ToolException("events must be an array of event objects");
            }
            requests.add(ToolJson.MAPPER.treeToValue(event, ApplyEventRequest.class));
        }
        return requests;
    }

    /** The JSON Schema of one event, shared by both change tools. */
    static final String EVENTS_SCHEMA = """
        {"type":"array","minItems":1,"maxItems":5,"items":{"type":"object","properties":{
          "type":{"type":"string","enum":["CANCEL_BLOCK","POSTPONE_BLOCK","MARK_DONE","SET_ENERGY","ADD_ADHOC"]},
          "blockId":{"type":"string","description":"exact id from get_today_plan; not for ADD_ADHOC"},
          "reason":{"type":"string","description":"optional, POSTPONE_BLOCK only"},
          "title":{"type":"string","description":"ADD_ADHOC only"},
          "start":{"type":"string","description":"HH:mm, ADD_ADHOC only"},
          "end":{"type":"string","description":"HH:mm, ADD_ADHOC only"},
          "energy":{"type":"string","enum":["LOW","MEDIUM"],"description":"SET_ENERGY only"}
        },"required":["type"]}}
        """;
}
```

`SimulateChangesTool.java`:

```java
package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.TurnContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SimulateChangesTool implements AgentTool {

    private final ChangeSimulator simulator;

    public SimulateChangesTool(ChangeSimulator simulator) {
        this.simulator = simulator;
    }

    @Override
    public String name() {
        return "simulate_changes";
    }

    @Override
    public String description() {
        return "Dry-run a set of changes to today's plan and see what the planner would do (moved, cancelled or new "
            + "blocks, and the planner's own messages, e.g. no room to postpone). Nothing is changed or proposed.";
    }

    @Override
    public String parametersJson() {
        return "{\"type\":\"object\",\"properties\":{\"events\":" + ChangeSimulator.EVENTS_SCHEMA
            + "},\"required\":[\"events\"],\"additionalProperties\":false}";
    }

    @Override
    public String execute(JsonNode arguments, TurnContext context) {
        ChangeSimulator.Simulation simulation = simulator.simulate(arguments, context.today());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("diff", simulation.diff());
        result.put("engineMessages", simulation.engineMessages());
        return ToolJson.write(result);
    }
}
```

`ProposeChangesTool.java`:

```java
package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.Proposal;
import com.lifeorganizer.ai.agent.TurnContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ProposeChangesTool implements AgentTool {

    static final int MAX_RATIONALE = 500;

    private final ChangeSimulator simulator;

    public ProposeChangesTool(ChangeSimulator simulator) {
        this.simulator = simulator;
    }

    @Override
    public String name() {
        return "propose_changes";
    }

    @Override
    public String description() {
        return "Record ONE final proposal of changes to today's plan for the user to Apply or Reject. At most once per "
            + "message. The plan does not change until the user presses Apply.";
    }

    @Override
    public String parametersJson() {
        return "{\"type\":\"object\",\"properties\":{\"rationale\":{\"type\":\"string\",\"description\":"
            + "\"one short sentence the user sees above the changes\"},\"events\":" + ChangeSimulator.EVENTS_SCHEMA
            + "},\"required\":[\"rationale\",\"events\"],\"additionalProperties\":false}";
    }

    @Override
    public String execute(JsonNode arguments, TurnContext context) {
        String rationale = ToolJson.requiredText(arguments, "rationale", MAX_RATIONALE);
        ChangeSimulator.Simulation simulation = simulator.simulate(arguments, context.today());
        context.propose(new Proposal(rationale, simulation.requests(), simulation.engineMessages(), simulation.preview()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recorded", true);
        result.put("diff", simulation.diff());
        result.put("engineMessages", simulation.engineMessages());
        result.put("next", "Reply to the user with a short summary; they will press Apply or Reject.");
        return ToolJson.write(result);
    }
}
```

- [ ] **Step 3: Run and commit**

Run: `cd backend && ./mvnw -q test -Dtest='ChangeToolsTest,ReadToolsTest'` — Expected: all pass.

```bash
git add backend/src/main/java/com/lifeorganizer/ai/agent/tools backend/src/test/java/com/lifeorganizer/ai/agent/tools
git commit -m "feat: add the chat agent's simulate and propose tools" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: `AgentRunner`

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/ai/agent/AgentRunner.java`
- Create: `backend/src/main/java/com/lifeorganizer/ai/agent/AgentTurnResult.java`
- Create: `backend/src/test/java/com/lifeorganizer/ai/agent/AgentRunnerTest.java`

**Interfaces:**
- Consumes: `AgentLlmClient` (Task 3), `AgentTool`, `TurnContext`, `ToolException` (Task 4), `AgentMessage`, `AgentReply`, `ToolCall`, `MalformedLlmReplyException`, `AiNotConfiguredException`, `InvalidRequestException`, `SetupRequiredException`, `UnknownBlockException`.
- Produces:
  - `record AgentTurnResult(String reply, Proposal proposal)` (proposal nullable)
  - `@Service AgentRunner(ObjectProvider<AgentLlmClient> clientProvider, List<AgentTool> tools, Clock clock)` — `AgentTurnResult run(List<AgentMessage> history, String userText)`; constants `MAX_MODEL_CALLS = 8`, `MAX_HISTORY = 20`, `FALLBACK = "I couldn't finish that. Try rephrasing."`, `PROPOSAL_ONLY = "Here is what I'd change."`
  - `RestClientException` from the client propagates untouched.

- [ ] **Step 1: Failing tests**

```java
package com.lifeorganizer.ai.agent;

import com.lifeorganizer.ai.agent.tools.AgentTool;
import com.lifeorganizer.ai.agent.tools.ToolException;
import com.lifeorganizer.ai.client.AgentLlmClient;
import com.lifeorganizer.ai.client.MalformedLlmReplyException;
import com.lifeorganizer.error.AiNotConfiguredException;
import com.lifeorganizer.plan.dto.TodayPlanResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunnerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);

    /** Plays back scripted replies and remembers every request. */
    static class ScriptedClient implements AgentLlmClient {
        final Deque<Object> script = new ArrayDeque<>();
        final List<List<AgentMessage>> requests = new ArrayList<>();

        ScriptedClient then(Object replyOrException) {
            script.add(replyOrException);
            return this;
        }

        @Override
        public AgentReply chat(List<AgentMessage> messages, List<ToolSpec> tools) {
            requests.add(List.copyOf(messages));
            Object next = script.isEmpty() ? new AgentReply(null, List.of(new ToolCall("loop", "echo", "{}"))) : script.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (AgentReply) next;
        }
    }

    static class FakeTool implements AgentTool {
        final String name;
        final BiFunction<JsonNode, TurnContext, String> body;

        FakeTool(String name, BiFunction<JsonNode, TurnContext, String> body) {
            this.name = name;
            this.body = body;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "fake"; }
        @Override public String parametersJson() { return "{\"type\":\"object\",\"properties\":{}}"; }
        @Override public String execute(JsonNode arguments, TurnContext context) { return body.apply(arguments, context); }
    }

    private static final Proposal PROPOSAL = new Proposal("Gym is off", List.of(), List.of(),
        new TodayPlanResponse("2026-09-30", List.of(), List.of()));

    private static final List<AgentTool> TOOLS = List.of(
        new FakeTool("echo", (args, context) -> "echo:" + args.path("x").asString("")),
        new FakeTool("broken", (args, context) -> { throw new ToolException("blockId is required"); }),
        new FakeTool("crash", (args, context) -> { throw new IllegalStateException("boom"); }),
        new FakeTool("propose", (args, context) -> { context.propose(PROPOSAL); return "recorded"; }));

    @SuppressWarnings("unchecked")
    private static AgentRunner runner(AgentLlmClient client) {
        ObjectProvider<AgentLlmClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return new AgentRunner(provider, TOOLS, CLOCK);
    }

    private static AgentReply text(String text) {
        return new AgentReply(text, List.of());
    }

    private static AgentReply calls(ToolCall... calls) {
        return new AgentReply(null, List.of(calls));
    }

    @Test
    void aTextAnswerEndsTheTurn() {
        ScriptedClient client = new ScriptedClient().then(text("Sure."));

        AgentTurnResult result = runner(client).run(List.of(AgentMessage.user("earlier"), AgentMessage.assistant("ok", List.of())), "hi");

        assertThat(result).isEqualTo(new AgentTurnResult("Sure.", null));
        List<AgentMessage> sent = client.requests.get(0);
        assertThat(sent.get(0).role()).isEqualTo(AgentMessage.Role.SYSTEM);
        assertThat(sent.get(0).content()).contains("2026-09-30");
        assertThat(sent).extracting(AgentMessage::content).endsWith("earlier", "ok", "hi");
    }

    @Test
    void toolResultsAreFedBackBeforeTheNextCall() {
        ScriptedClient client = new ScriptedClient()
            .then(calls(new ToolCall("c1", "echo", "{\"x\":\"a\"}")))
            .then(text("Done."));

        runner(client).run(List.of(), "hi");

        List<AgentMessage> second = client.requests.get(1);
        AgentMessage assistant = second.get(second.size() - 2);
        AgentMessage tool = second.get(second.size() - 1);
        assertThat(assistant.toolCalls()).extracting(ToolCall::id).containsExactly("c1");
        assertThat(tool.role()).isEqualTo(AgentMessage.Role.TOOL);
        assertThat(tool.toolCallId()).isEqualTo("c1");
        assertThat(tool.content()).isEqualTo("echo:a");
    }

    @Test
    void toolProblemsBecomeErrorTextNotExceptions() {
        ScriptedClient client = new ScriptedClient()
            .then(calls(new ToolCall("c1", "nope", "{}"), new ToolCall("c2", "echo", "not json"),
                new ToolCall("c3", "broken", "{}"), new ToolCall("c4", "crash", "{}"), new ToolCall("c5", "echo", null)))
            .then(text("Sorry."));

        AgentTurnResult result = runner(client).run(List.of(), "hi");

        assertThat(result.reply()).isEqualTo("Sorry.");
        List<AgentMessage> second = client.requests.get(1);
        assertThat(second.subList(second.size() - 5, second.size())).extracting(AgentMessage::content).containsExactly(
            "error: unknown tool 'nope'",
            "error: arguments must be a JSON object",
            "error: blockId is required",
            "error: the tool failed unexpectedly",
            "echo:");
    }

    @Test
    void aProposalMadeDuringTheTurnIsReturned() {
        ScriptedClient client = new ScriptedClient().then(calls(new ToolCall("c1", "propose", "{}"))).then(text("Proposed."));

        assertThat(runner(client).run(List.of(), "hi")).isEqualTo(new AgentTurnResult("Proposed.", PROPOSAL));
    }

    @Test
    void theLoopStopsAfterEightModelCalls() {
        ScriptedClient client = new ScriptedClient();

        AgentTurnResult result = runner(client).run(List.of(), "hi");

        assertThat(client.requests).hasSize(AgentRunner.MAX_MODEL_CALLS);
        assertThat(result).isEqualTo(new AgentTurnResult("I couldn't finish that. Try rephrasing.", null));
    }

    @Test
    void aProposalSurvivesTheCap() {
        ScriptedClient client = new ScriptedClient().then(calls(new ToolCall("c1", "propose", "{}")));

        assertThat(runner(client).run(List.of(), "hi")).isEqualTo(new AgentTurnResult("Here is what I'd change.", PROPOSAL));
    }

    @Test
    void anUnusableReplyEndsTheTurnWithTheFallback() {
        ScriptedClient client = new ScriptedClient().then(new MalformedLlmReplyException("junk"));

        assertThat(runner(client).run(List.of(), "hi").reply()).isEqualTo("I couldn't finish that. Try rephrasing.");
    }

    @Test
    void connectivityFailuresPropagate() {
        ScriptedClient client = new ScriptedClient()
            .then(calls(new ToolCall("c1", "echo", "{}")))
            .then(new ResourceAccessException("down"));

        assertThatThrownBy(() -> runner(client).run(List.of(), "hi")).isInstanceOf(RestClientException.class);
    }

    @Test
    void onlyTheLastTwentyHistoryMessagesAreSent() {
        ScriptedClient client = new ScriptedClient().then(text("ok"));
        List<AgentMessage> history = IntStream.range(0, 30).mapToObj(i -> AgentMessage.user("m" + i)).toList();

        runner(client).run(history, "hi");

        List<AgentMessage> sent = client.requests.get(0);
        assertThat(sent).hasSize(1 + 20 + 1);
        assertThat(sent.get(1).content()).isEqualTo("m10");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failsWhenNoClientIsConfigured() {
        ObjectProvider<AgentLlmClient> provider = mock(ObjectProvider.class);
        AgentRunner none = new AgentRunner(provider, TOOLS, CLOCK);

        assertThatThrownBy(() -> none.run(List.of(), "hi")).isInstanceOf(AiNotConfiguredException.class);
    }
}
```

Run: `cd backend && ./mvnw -q test -Dtest=AgentRunnerTest` — Expected: compilation failure.

- [ ] **Step 2: Implement**

`AgentTurnResult.java`:

```java
package com.lifeorganizer.ai.agent;

/** What one user turn produced: the assistant's reply and, optionally, one proposal. */
public record AgentTurnResult(String reply, Proposal proposal) {
}
```

`AgentRunner.java`:

```java
package com.lifeorganizer.ai.agent;

import com.lifeorganizer.ai.agent.tools.AgentTool;
import com.lifeorganizer.ai.agent.tools.ToolException;
import com.lifeorganizer.ai.agent.tools.ToolJson;
import com.lifeorganizer.ai.client.AgentLlmClient;
import com.lifeorganizer.ai.client.MalformedLlmReplyException;
import com.lifeorganizer.error.AiNotConfiguredException;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.error.SetupRequiredException;
import com.lifeorganizer.error.UnknownBlockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs one user turn of the chat agent: calls the model, runs the tools it asks for, feeds the results back, and
 * repeats until the model answers in text or the call budget is spent. Tools only read or simulate; the plan only
 * changes when the user applies a proposal.
 */
@Service
public class AgentRunner {

    static final int MAX_MODEL_CALLS = 8;
    static final int MAX_HISTORY = 20;
    static final String FALLBACK = "I couldn't finish that. Try rephrasing.";
    static final String PROPOSAL_ONLY = "Here is what I'd change.";

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    private static final String SYSTEM_PROMPT = """
        You are the planning assistant inside a personal day planner. Today is %s (%s).
        You help the user adjust TODAY's plan through conversation. Tools:
        - get_today_plan: today's blocks with their ids. Call it before proposing anything.
        - get_week_overview: the rest of this week, for context only.
        - list_goals: the user's goals and priorities.
        - search_memory: the user's own notes and summaries of past days. Use it when the user mentions how they
          feel, what usually works for them, or past days.
        - simulate_changes: try a set of changes and see what the planner would do.
        - propose_changes: record ONE final proposal for the user to Apply or Reject.
        Rules:
        - You cannot change the plan yourself. Never say a change is done; the user decides with Apply or Reject.
        - Only propose changes to today's blocks, using exact block ids from get_today_plan. Never target FREE
          blocks: they are open time, not commitments.
        - Allowed events: CANCEL_BLOCK, POSTPONE_BLOCK (optional reason), MARK_DONE, SET_ENERGY (energy LOW or
          MEDIUM), ADD_ADHOC (title, start and end as HH:mm, today only).
        - There is no "move" event: to move something, postpone it, or cancel it and add an ad-hoc block.
        - Call propose_changes at most once per message, with at most 5 events.
        - If a tool returns "error: ...", fix the problem and try again, or explain it to the user.
        - If the user is only talking or asking, answer without proposing changes.
        - Reply in 1 to 4 short sentences, in the user's language. After a proposal, summarize it briefly.
        """;

    private final ObjectProvider<AgentLlmClient> clientProvider;
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final List<ToolSpec> specs;
    private final Clock clock;

    public AgentRunner(ObjectProvider<AgentLlmClient> clientProvider, List<AgentTool> tools, Clock clock) {
        this.clientProvider = clientProvider;
        tools.forEach(tool -> this.tools.put(tool.name(), tool));
        this.specs = tools.stream().map(AgentTool::spec).toList();
        this.clock = clock;
    }

    public AgentTurnResult run(List<AgentMessage> history, String userText) {
        AgentLlmClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new AiNotConfiguredException("AI features are not configured");
        }
        LocalDate today = LocalDate.now(clock);
        TurnContext context = new TurnContext(today);
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.system(SYSTEM_PROMPT.formatted(today,
            today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH))));
        messages.addAll(history.subList(Math.max(0, history.size() - MAX_HISTORY), history.size()));
        messages.add(AgentMessage.user(userText));
        log.info("Agent turn for {}: text.length={} history={}", today, userText.length(), history.size());

        for (int call = 1; call <= MAX_MODEL_CALLS; call++) {
            AgentReply reply;
            try {
                reply = client.chat(messages, specs);
            } catch (MalformedLlmReplyException e) {
                log.warn("Agent turn ended: unusable model reply ({})", e.getMessage());
                return finish(context);
            }
            if (reply.toolCalls().isEmpty()) {
                if (reply.content() == null || reply.content().isBlank()) {
                    return finish(context);
                }
                log.info("Agent turn done after {} model call(s), proposal={}", call, context.proposal().isPresent());
                return new AgentTurnResult(reply.content().strip(), context.proposal().orElse(null));
            }
            messages.add(AgentMessage.assistant(reply.content(), reply.toolCalls()));
            for (ToolCall toolCall : reply.toolCalls()) {
                messages.add(AgentMessage.tool(toolCall.id(), execute(toolCall, context)));
            }
        }
        log.warn("Agent turn hit the limit of {} model calls", MAX_MODEL_CALLS);
        return finish(context);
    }

    private AgentTurnResult finish(TurnContext context) {
        return context.proposal()
            .map(proposal -> new AgentTurnResult(PROPOSAL_ONLY, proposal))
            .orElseGet(() -> new AgentTurnResult(FALLBACK, null));
    }

    private String execute(ToolCall call, TurnContext context) {
        AgentTool tool = tools.get(call.name());
        if (tool == null) {
            return "error: unknown tool '" + call.name() + "'";
        }
        JsonNode arguments;
        try {
            String raw = call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments();
            arguments = ToolJson.MAPPER.readTree(raw);
        } catch (RuntimeException e) {
            return "error: arguments must be a JSON object";
        }
        if (!arguments.isObject()) {
            return "error: arguments must be a JSON object";
        }
        try {
            String result = tool.execute(arguments, context);
            log.debug("Tool {} args={} -> {} chars", call.name(), call.arguments(), result.length());
            return result;
        } catch (ToolException | InvalidRequestException | SetupRequiredException | UnknownBlockException e) {
            log.debug("Tool {} args={} -> error: {}", call.name(), call.arguments(), e.getMessage());
            return "error: " + e.getMessage();
        } catch (RuntimeException e) {
            log.warn("Tool {} failed unexpectedly", call.name(), e);
            return "error: the tool failed unexpectedly";
        }
    }
}
```

Note: `ToolJson.MAPPER.readTree("not json")` throws a Jackson 3 `JacksonException` (a `RuntimeException`), which is caught above.

- [ ] **Step 3: Run and commit**

Run: `cd backend && ./mvnw -q test -Dtest=AgentRunnerTest` — Expected: 10 pass.

```bash
git add backend/src/main/java/com/lifeorganizer/ai/agent backend/src/test/java/com/lifeorganizer/ai/agent
git commit -m "feat: add the chat agent's tool-calling loop" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Chat persistence and `ChatService`

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__chat.sql`
- Create in `backend/src/main/java/com/lifeorganizer/ai/chat/`: `ChatMessageEntity.java`, `ChatMessageRepository.java`, `ChatStore.java`, `ChatService.java`, `ProposalStatus.java`
- Create in `backend/src/main/java/com/lifeorganizer/ai/chat/dto/`: `ChatMessageDto.java`, `ProposalDto.java`, `SendMessageRequest.java`
- Create: `backend/src/main/java/com/lifeorganizer/error/ChatMessageNotFoundException.java`, `ProposalNotAvailableException.java`
- Modify: `backend/src/main/java/com/lifeorganizer/config/GlobalExceptionHandler.java`
- Modify: `backend/src/test/java/com/lifeorganizer/persistence/SchemaMigrationTest.java` (add `"chat_message"` to the table list)
- Create: `backend/src/test/java/com/lifeorganizer/ai/chat/ChatServiceTest.java`

**Interfaces:**
- Consumes: `AgentRunner.run(List<AgentMessage>, String) : AgentTurnResult` (Task 6), `Proposal` (Task 4), `PlanService.applyEvents(List<PlanEvent>, LocalDate) : AppliedEvent`, `PlanDtoMapper.toEventsResponse(AppliedEvent) : ApplyEventsResponse`, `ApplyEventRequest.toEvent`, `Clock`.
- Produces:
  - `enum ProposalStatus { PENDING, APPLIED, REJECTED, EXPIRED }`
  - `record ChatMessageDto(long id, String date, String role, String text, ProposalDto proposal, String createdAt)`
  - `record ProposalDto(String rationale, List<ApplyEventRequest> events, List<String> changes, TodayPlanResponse preview, String status)`
  - `record SendMessageRequest(String text)`
  - `ChatService`: `List<ChatMessageDto> thread(LocalDate date)`, `List<String> days()`, `ChatMessageDto send(String text)`, `ApplyEventsResponse apply(long messageId)`, `ChatMessageDto reject(long messageId)`
  - `ChatMessageNotFoundException(long id)` → 404 "Message not found"; `ProposalNotAvailableException(String)` → 409 "Proposal not available"

- [ ] **Step 1: Migration, entity, repository, exceptions**

`V3__chat.sql`:

```sql
-- One row per chat message. The thread is the calendar day (chat_date); position orders messages in it.
-- proposal is the assistant's proposed change set as JSON (null when there is none); proposal_status tracks it.
CREATE TABLE chat_message (
    id              BIGSERIAL    PRIMARY KEY,
    chat_date       DATE         NOT NULL,
    position        INTEGER      NOT NULL,
    role            VARCHAR(10)  NOT NULL,
    text            TEXT         NOT NULL,
    proposal        TEXT         NULL,
    proposal_status VARCHAR(10)  NULL,
    created_at      TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX idx_chat_message_thread ON chat_message (chat_date, position);
```

`ProposalStatus.java`:

```java
package com.lifeorganizer.ai.chat;

public enum ProposalStatus { PENDING, APPLIED, REJECTED, EXPIRED }
```

`ChatMessageEntity.java`:

```java
package com.lifeorganizer.ai.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "chat_message")
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_date")
    private LocalDate chatDate;

    private int position;

    private String role;

    @Column(columnDefinition = "text")
    private String text;

    @Column(columnDefinition = "text")
    private String proposal;

    @Column(name = "proposal_status")
    private String proposalStatus;

    @Column(name = "created_at")
    private Instant createdAt;

    protected ChatMessageEntity() {
    }

    public ChatMessageEntity(LocalDate chatDate, int position, String role, String text, String proposal,
                             String proposalStatus, Instant createdAt) {
        this.chatDate = chatDate;
        this.position = position;
        this.role = role;
        this.text = text;
        this.proposal = proposal;
        this.proposalStatus = proposalStatus;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public LocalDate getChatDate() { return chatDate; }
    public int getPosition() { return position; }
    public String getRole() { return role; }
    public String getText() { return text; }
    public String getProposal() { return proposal; }
    public String getProposalStatus() { return proposalStatus; }
    public Instant getCreatedAt() { return createdAt; }

    public void setProposalStatus(String proposalStatus) {
        this.proposalStatus = proposalStatus;
    }
}
```

`ChatMessageRepository.java`:

```java
package com.lifeorganizer.ai.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    List<ChatMessageEntity> findByChatDateOrderByPositionAsc(LocalDate chatDate);

    List<ChatMessageEntity> findByChatDateAndProposalStatus(LocalDate chatDate, String proposalStatus);

    @Query("select distinct m.chatDate from ChatMessageEntity m order by m.chatDate desc")
    List<LocalDate> findChatDates();
}
```

`ChatMessageNotFoundException.java` and `ProposalNotAvailableException.java` (package `com.lifeorganizer.error`):

```java
package com.lifeorganizer.error;

public class ChatMessageNotFoundException extends RuntimeException {

    public ChatMessageNotFoundException(long id) {
        super("No chat message with id " + id);
    }
}
```

```java
package com.lifeorganizer.error;

/** The proposal was already applied, rejected or expired, or belongs to another day's thread. */
public class ProposalNotAvailableException extends RuntimeException {

    public ProposalNotAvailableException(String message) {
        super(message);
    }
}
```

In `GlobalExceptionHandler` add (with imports):

```java
    @ExceptionHandler(ChatMessageNotFoundException.class)
    public ProblemDetail chatMessageNotFound(ChatMessageNotFoundException e) {
        return problem(404, "Message not found", e.getMessage());
    }

    @ExceptionHandler(ProposalNotAvailableException.class)
    public ProblemDetail proposalNotAvailable(ProposalNotAvailableException e) {
        return problem(409, "Proposal not available", e.getMessage());
    }
```

Add `"chat_message"` to `SchemaMigrationTest`'s expected tables.

- [ ] **Step 2: DTOs**

```java
package com.lifeorganizer.ai.chat.dto;

import com.lifeorganizer.plan.dto.ApplyEventRequest;
import com.lifeorganizer.plan.dto.TodayPlanResponse;

import java.util.List;

public record ProposalDto(String rationale, List<ApplyEventRequest> events, List<String> changes,
                          TodayPlanResponse preview, String status) {
}
```

```java
package com.lifeorganizer.ai.chat.dto;

/** date is the thread's day (yyyy-MM-dd); role is USER or ASSISTANT; proposal is null when there is none. */
public record ChatMessageDto(long id, String date, String role, String text, ProposalDto proposal, String createdAt) {
}
```

```java
package com.lifeorganizer.ai.chat.dto;

public record SendMessageRequest(String text) {
}
```

- [ ] **Step 3: Failing service test**

`ChatServiceTest.java`:

```java
package com.lifeorganizer.ai.chat;

import com.lifeorganizer.ai.agent.AgentMessage;
import com.lifeorganizer.ai.agent.AgentRunner;
import com.lifeorganizer.ai.agent.AgentTurnResult;
import com.lifeorganizer.ai.agent.Proposal;
import com.lifeorganizer.ai.chat.dto.ChatMessageDto;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.engine.CancelBlock;
import com.lifeorganizer.error.ChatMessageNotFoundException;
import com.lifeorganizer.error.ProposalNotAvailableException;
import com.lifeorganizer.error.UnknownBlockException;
import com.lifeorganizer.plan.AppliedEvent;
import com.lifeorganizer.plan.PlanService;
import com.lifeorganizer.plan.dto.ApplyEventRequest;
import com.lifeorganizer.plan.dto.ApplyEventsResponse;
import com.lifeorganizer.plan.dto.TodayPlanResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({ChatService.class, ChatStore.class, ChatServiceTest.Beans.class})
class ChatServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    @TestConfiguration
    static class Beans {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    ChatService chat;

    @Autowired
    ChatMessageRepository repository;

    @MockitoBean
    AgentRunner runner;

    @MockitoBean
    PlanService planService;

    private static final ApplyEventRequest CANCEL_GYM =
        new ApplyEventRequest("CANCEL_BLOCK", "gym-1", null, null, null, null, null, null);
    private static final Proposal PROPOSAL = new Proposal("Gym is off", List.of(CANCEL_GYM),
        List.of("Cancelled Gym - slot is now free"), new TodayPlanResponse("2026-09-30", List.of(), List.of()));

    private void agentAnswers(String reply, Proposal proposal) {
        when(runner.run(anyList(), anyString())).thenReturn(new AgentTurnResult(reply, proposal));
    }

    @Test
    void sendStoresBothMessagesAndReturnsTheAssistantOne() {
        agentAnswers("I'd cancel the gym.", PROPOSAL);

        ChatMessageDto reply = chat.send("gym is canceled");

        assertThat(reply.role()).isEqualTo("ASSISTANT");
        assertThat(reply.text()).isEqualTo("I'd cancel the gym.");
        assertThat(reply.date()).isEqualTo("2026-09-30");
        assertThat(reply.proposal().status()).isEqualTo("PENDING");
        assertThat(reply.proposal().events()).containsExactly(CANCEL_GYM);
        assertThat(chat.thread(TODAY)).extracting(ChatMessageDto::role, ChatMessageDto::text).containsExactly(
            org.assertj.core.groups.Tuple.tuple("USER", "gym is canceled"),
            org.assertj.core.groups.Tuple.tuple("ASSISTANT", "I'd cancel the gym."));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAgentSeesEarlierMessagesOfTodaysThread() {
        agentAnswers("First.", null);
        chat.send("one");
        agentAnswers("Second.", null);

        chat.send("two");

        ArgumentCaptor<List<AgentMessage>> history = ArgumentCaptor.forClass(List.class);
        verify(runner).run(history.capture(), eq("two"));
        assertThat(history.getValue()).extracting(AgentMessage::content).containsExactly("one", "First.");
    }

    @Test
    void aNewMessageExpiresTheOlderPendingProposal() {
        agentAnswers("Proposal.", PROPOSAL);
        long first = chat.send("cancel gym").id();
        agentAnswers("Just chatting.", null);

        chat.send("never mind");

        assertThat(chat.thread(TODAY).get(1).proposal().status()).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> chat.apply(first)).isInstanceOf(ProposalNotAvailableException.class);
    }

    @Test
    void applyReplaysTheEventsAndMarksTheProposalApplied() {
        agentAnswers("Proposal.", PROPOSAL);
        long id = chat.send("cancel gym").id();
        when(planService.applyEvents(List.of(new CancelBlock("gym-1")), TODAY))
            .thenReturn(new AppliedEvent(new DayPlan(TODAY, List.of(), List.of()), List.of("Cancelled Gym")));

        ApplyEventsResponse applied = chat.apply(id);

        assertThat(applied.changes()).containsExactly("Cancelled Gym");
        assertThat(chat.thread(TODAY).get(1).proposal().status()).isEqualTo("APPLIED");
        assertThatThrownBy(() -> chat.apply(id)).isInstanceOf(ProposalNotAvailableException.class);
    }

    @Test
    void anEngineRefusalLeavesTheProposalPending() {
        agentAnswers("Proposal.", PROPOSAL);
        long id = chat.send("cancel gym").id();
        when(planService.applyEvents(anyList(), eq(TODAY))).thenThrow(new UnknownBlockException("No block with id 'gym-1'"));

        assertThatThrownBy(() -> chat.apply(id)).isInstanceOf(UnknownBlockException.class);

        assertThat(repository.findById(id).orElseThrow().getProposalStatus()).isEqualTo("PENDING");
    }

    @Test
    void rejectMarksTheProposalRejected() {
        agentAnswers("Proposal.", PROPOSAL);
        long id = chat.send("cancel gym").id();

        assertThat(chat.reject(id).proposal().status()).isEqualTo("REJECTED");
        assertThatThrownBy(() -> chat.reject(id)).isInstanceOf(ProposalNotAvailableException.class);
    }

    @Test
    void yesterdaysProposalCanNoLongerBeAppliedOrRejected() {
        ChatMessageEntity old = repository.save(new ChatMessageEntity(TODAY.minusDays(1), 1, "ASSISTANT", "old",
            "{\"rationale\":\"x\",\"events\":[],\"changes\":[],\"preview\":null}", "PENDING", Instant.EPOCH));

        assertThatThrownBy(() -> chat.apply(old.getId())).isInstanceOf(ProposalNotAvailableException.class);
        assertThatThrownBy(() -> chat.reject(old.getId())).isInstanceOf(ProposalNotAvailableException.class);
    }

    @Test
    void aMessageWithoutAProposalCannotBeApplied() {
        agentAnswers("Hi!", null);
        long id = chat.send("hello").id();

        assertThatThrownBy(() -> chat.apply(id)).isInstanceOf(ProposalNotAvailableException.class);
    }

    @Test
    void unknownMessagesAreNotFound() {
        assertThatThrownBy(() -> chat.apply(999)).isInstanceOf(ChatMessageNotFoundException.class);
        assertThatThrownBy(() -> chat.reject(999)).isInstanceOf(ChatMessageNotFoundException.class);
    }

    @Test
    void aFailedAgentTurnSavesNothing() {
        when(runner.run(anyList(), anyString())).thenThrow(new ResourceAccessException("down"));

        assertThatThrownBy(() -> chat.send("hello")).isInstanceOf(ResourceAccessException.class);

        assertThat(chat.thread(TODAY)).isEmpty();
    }

    @Test
    void daysListsThreadsNewestFirst() {
        repository.save(new ChatMessageEntity(TODAY.minusDays(2), 0, "USER", "a", null, null, Instant.EPOCH));
        repository.save(new ChatMessageEntity(TODAY.minusDays(1), 0, "USER", "b", null, null, Instant.EPOCH));

        assertThat(chat.days()).containsExactly("2026-09-29", "2026-09-28");
        assertThat(chat.thread(TODAY.minusDays(1))).extracting(ChatMessageDto::text).containsExactly("b");
    }
}
```

Run: `cd backend && ./mvnw -q test -Dtest=ChatServiceTest` — Expected: compilation failure.

- [ ] **Step 4: Implement `ChatStore` and `ChatService`**

`ChatStore.java`:

```java
package com.lifeorganizer.ai.chat;

import com.lifeorganizer.ai.agent.AgentTurnResult;
import com.lifeorganizer.ai.agent.Proposal;
import com.lifeorganizer.ai.agent.tools.ToolJson;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Short database transactions around the chat thread (the agent turn itself runs outside any transaction). */
@Component
public class ChatStore {

    private final ChatMessageRepository repository;

    public ChatStore(ChatMessageRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<ChatMessageEntity> thread(LocalDate date) {
        return repository.findByChatDateOrderByPositionAsc(date);
    }

    /** Stores the user message and the assistant reply, expiring any proposal still pending in that thread. */
    @Transactional
    public ChatMessageEntity appendTurn(LocalDate date, String userText, AgentTurnResult result, Instant now) {
        for (ChatMessageEntity pending : repository.findByChatDateAndProposalStatus(date, ProposalStatus.PENDING.name())) {
            pending.setProposalStatus(ProposalStatus.EXPIRED.name());
        }
        int next = repository.findByChatDateOrderByPositionAsc(date).size();
        repository.save(new ChatMessageEntity(date, next, "USER", userText, null, null, now));
        Proposal proposal = result.proposal();
        return repository.save(new ChatMessageEntity(date, next + 1, "ASSISTANT", result.reply(),
            proposal == null ? null : ToolJson.write(proposal),
            proposal == null ? null : ProposalStatus.PENDING.name(), now));
    }
}
```

`ChatService.java`:

```java
package com.lifeorganizer.ai.chat;

import com.lifeorganizer.ai.agent.AgentMessage;
import com.lifeorganizer.ai.agent.AgentRunner;
import com.lifeorganizer.ai.agent.AgentTurnResult;
import com.lifeorganizer.ai.agent.Proposal;
import com.lifeorganizer.ai.agent.tools.ToolJson;
import com.lifeorganizer.ai.chat.dto.ChatMessageDto;
import com.lifeorganizer.ai.chat.dto.ProposalDto;
import com.lifeorganizer.engine.PlanEvent;
import com.lifeorganizer.error.ChatMessageNotFoundException;
import com.lifeorganizer.error.ProposalNotAvailableException;
import com.lifeorganizer.plan.PlanDtoMapper;
import com.lifeorganizer.plan.PlanService;
import com.lifeorganizer.plan.dto.ApplyEventsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The per-day chat thread. send() runs the agent OUTSIDE any transaction (a turn takes seconds of LLM calls) and
 * only then stores the turn; apply() replays a pending proposal of today's thread through the engine.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatStore store;
    private final ChatMessageRepository repository;
    private final AgentRunner runner;
    private final PlanService planService;
    private final Clock clock;

    public ChatService(ChatStore store, ChatMessageRepository repository, AgentRunner runner, PlanService planService,
                       Clock clock) {
        this.store = store;
        this.repository = repository;
        this.runner = runner;
        this.planService = planService;
        this.clock = clock;
    }

    public List<ChatMessageDto> thread(LocalDate date) {
        return store.thread(date).stream().map(ChatService::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<String> days() {
        return repository.findChatDates().stream().map(LocalDate::toString).toList();
    }

    public ChatMessageDto send(String text) {
        LocalDate today = LocalDate.now(clock);
        List<AgentMessage> history = store.thread(today).stream().map(ChatService::toAgentMessage).toList();
        AgentTurnResult result = runner.run(history, text);
        return toDto(store.appendTurn(today, text, result, Instant.now(clock)));
    }

    @Transactional
    public ApplyEventsResponse apply(long messageId) {
        ChatMessageEntity message = pendingProposalOfToday(messageId);
        LocalDate today = LocalDate.now(clock);
        Proposal proposal = decode(message.getProposal());
        List<PlanEvent> events = proposal.events().stream().map(event -> event.toEvent(today)).toList();
        // Joins this transaction: if the engine refuses (e.g. a block no longer exists), the status stays PENDING.
        ApplyEventsResponse applied = PlanDtoMapper.toEventsResponse(planService.applyEvents(events, today));
        message.setProposalStatus(ProposalStatus.APPLIED.name());
        log.info("Applied chat proposal {} ({} events)", messageId, events.size());
        return applied;
    }

    @Transactional
    public ChatMessageDto reject(long messageId) {
        ChatMessageEntity message = pendingProposalOfToday(messageId);
        message.setProposalStatus(ProposalStatus.REJECTED.name());
        return toDto(message);
    }

    private ChatMessageEntity pendingProposalOfToday(long messageId) {
        ChatMessageEntity message = repository.findById(messageId)
            .orElseThrow(() -> new ChatMessageNotFoundException(messageId));
        if (message.getProposal() == null) {
            throw new ProposalNotAvailableException("This message has no proposal");
        }
        if (!message.getChatDate().equals(LocalDate.now(clock))) {
            throw new ProposalNotAvailableException("This proposal is from another day and can no longer be used");
        }
        if (!ProposalStatus.PENDING.name().equals(message.getProposalStatus())) {
            throw new ProposalNotAvailableException(
                "This proposal is " + message.getProposalStatus().toLowerCase() + " and can no longer be used");
        }
        return message;
    }

    /** History for the model: text only, plus a note about any proposal and what became of it. */
    private static AgentMessage toAgentMessage(ChatMessageEntity message) {
        if ("USER".equals(message.getRole())) {
            return AgentMessage.user(message.getText());
        }
        String text = message.getText();
        if (message.getProposal() != null) {
            text += "\n[proposal " + message.getProposalStatus() + ": " + decode(message.getProposal()).rationale() + "]";
        }
        return AgentMessage.assistant(text, List.of());
    }

    private static Proposal decode(String json) {
        return ToolJson.MAPPER.readValue(json, Proposal.class);
    }

    private static ChatMessageDto toDto(ChatMessageEntity message) {
        ProposalDto proposal = null;
        if (message.getProposal() != null) {
            Proposal decoded = decode(message.getProposal());
            proposal = new ProposalDto(decoded.rationale(), decoded.events(), decoded.changes(), decoded.preview(),
                message.getProposalStatus());
        }
        return new ChatMessageDto(message.getId(), message.getChatDate().toString(), message.getRole(),
            message.getText(), proposal, message.getCreatedAt().toString());
    }
}
```

- [ ] **Step 5: Run and commit**

Run: `cd backend && ./mvnw -q test -Dtest='ChatServiceTest,SchemaMigrationTest,GlobalExceptionHandlerTest'` — Expected: all pass. (If `status.toLowerCase()` trips a locale check in review, use `toLowerCase(Locale.ROOT)`.)

```bash
git add backend/src/main/resources/db/migration/V3__chat.sql backend/src/main/java/com/lifeorganizer/ai/chat backend/src/main/java/com/lifeorganizer/error backend/src/main/java/com/lifeorganizer/config/GlobalExceptionHandler.java backend/src/test/java/com/lifeorganizer/ai/chat backend/src/test/java/com/lifeorganizer/persistence/SchemaMigrationTest.java
git commit -m "feat: store the per-day chat thread and apply or reject its proposals" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: `ChatController` and end-to-end test

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/ai/chat/ChatController.java`
- Create: `backend/src/test/java/com/lifeorganizer/ai/chat/ChatControllerTest.java`
- Create: `backend/src/test/java/com/lifeorganizer/ai/chat/ChatControllerNoAiTest.java`
- Create: `backend/src/test/java/com/lifeorganizer/ChatApiEndToEndTest.java`

**Interfaces:**
- Consumes: `ChatService` (Task 7), `AgentLlmClient` (Task 3), `TimeFormats.parseDate(String, String)`, `AssistantUnavailableException`, `AiNotConfiguredException`, `InvalidRequestException`.
- Produces: the five endpoints from Global Constraints.

- [ ] **Step 1: Failing controller tests**

`ChatControllerTest.java`:

```java
package com.lifeorganizer.ai.chat;

import com.lifeorganizer.ai.chat.dto.ChatMessageDto;
import com.lifeorganizer.ai.client.AgentLlmClient;
import com.lifeorganizer.error.ProposalNotAvailableException;
import com.lifeorganizer.plan.dto.ApplyEventsResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
@Import(ChatControllerTest.FixedClock.class)
class ChatControllerTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    private static final ChatMessageDto REPLY =
        new ChatMessageDto(7, "2026-09-30", "ASSISTANT", "Hi!", null, "2026-09-30T10:00:00Z");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ChatService chat;

    @MockitoBean
    AgentLlmClient client;

    @Test
    void threadDefaultsToToday() throws Exception {
        when(chat.thread(LocalDate.of(2026, 9, 30))).thenReturn(List.of(REPLY));

        mvc.perform(get("/api/assistant/chat"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].text").value("Hi!"));
    }

    @Test
    void threadOfAnotherDayAndBadDates() throws Exception {
        when(chat.thread(LocalDate.of(2026, 9, 28))).thenReturn(List.of());

        mvc.perform(get("/api/assistant/chat").param("date", "2026-09-28")).andExpect(status().isOk());
        mvc.perform(get("/api/assistant/chat").param("date", "yesterday")).andExpect(status().isBadRequest());
    }

    @Test
    void daysAreListed() throws Exception {
        when(chat.days()).thenReturn(List.of("2026-09-30", "2026-09-29"));

        mvc.perform(get("/api/assistant/chat/days")).andExpect(jsonPath("$[1]").value("2026-09-29"));
    }

    @Test
    void sendReturnsTheAssistantMessage() throws Exception {
        when(chat.send("hello")).thenReturn(REPLY);

        mvc.perform(post("/api/assistant/chat/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"  hello \"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(7));
    }

    @Test
    void sendValidatesTheText() throws Exception {
        mvc.perform(post("/api/assistant/chat/messages").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\" \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("text is required"));
        mvc.perform(post("/api/assistant/chat/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + "a".repeat(2001) + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("text must be at most 2000 characters"));
    }

    @Test
    void anUnreachableAssistantIs502() throws Exception {
        when(chat.send("hello")).thenThrow(new ResourceAccessException("down"));

        mvc.perform(post("/api/assistant/chat/messages").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hello\"}"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.title").value("Assistant unavailable"));
    }

    @Test
    void applyAndReject() throws Exception {
        when(chat.apply(7)).thenReturn(new ApplyEventsResponse("2026-09-30", List.of(), List.of(), List.of("Cancelled Gym")));
        when(chat.reject(8)).thenThrow(new ProposalNotAvailableException("This proposal is applied and can no longer be used"));

        mvc.perform(post("/api/assistant/chat/messages/7/apply"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changes[0]").value("Cancelled Gym"));
        mvc.perform(post("/api/assistant/chat/messages/8/reject"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.title").value("Proposal not available"));
    }
}
```

`ChatControllerNoAiTest.java`:

```java
package com.lifeorganizer.ai.chat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** No AgentLlmClient bean (no GROQ_API_KEY): every chat endpoint answers 503. */
@WebMvcTest(ChatController.class)
class ChatControllerNoAiTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ChatService chat;

    @Test
    void everyEndpointIs503() throws Exception {
        mvc.perform(get("/api/assistant/chat")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/assistant/chat/days")).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/assistant/chat/messages").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hi\"}"))
            .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/assistant/chat/messages/1/apply")).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/assistant/chat/messages/1/reject")).andExpect(status().isServiceUnavailable());
    }
}
```

(`ChatControllerNoAiTest` needs a `Clock` bean too: the web slice does not load `EngineConfig`. Add the same `@TestConfiguration FixedClock` + `@Import` as in `ChatControllerTest`.)

Run: `cd backend && ./mvnw -q test -Dtest='ChatController*Test'` — Expected: compilation failure.

- [ ] **Step 2: Implement the controller**

```java
package com.lifeorganizer.ai.chat;

import com.lifeorganizer.ai.chat.dto.ChatMessageDto;
import com.lifeorganizer.ai.chat.dto.SendMessageRequest;
import com.lifeorganizer.ai.client.AgentLlmClient;
import com.lifeorganizer.api.TimeFormats;
import com.lifeorganizer.error.AiNotConfiguredException;
import com.lifeorganizer.error.AssistantUnavailableException;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.plan.dto.ApplyEventsResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/assistant/chat")
public class ChatController {

    static final int MAX_TEXT_LENGTH = 2000;

    private final ChatService chat;
    private final ObjectProvider<AgentLlmClient> client;
    private final Clock clock;

    public ChatController(ChatService chat, ObjectProvider<AgentLlmClient> client, Clock clock) {
        this.chat = chat;
        this.client = client;
        this.clock = clock;
    }

    @GetMapping
    public List<ChatMessageDto> thread(@RequestParam(required = false) String date) {
        requireAi();
        LocalDate day = date == null || date.isBlank() ? LocalDate.now(clock) : TimeFormats.parseDate(date, "date");
        return chat.thread(day);
    }

    @GetMapping("/days")
    public List<String> days() {
        requireAi();
        return chat.days();
    }

    @PostMapping("/messages")
    public ChatMessageDto send(@RequestBody SendMessageRequest request) {
        requireAi();
        String text = request.text() == null ? "" : request.text().strip();
        if (text.isEmpty()) {
            throw new InvalidRequestException("text is required");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new InvalidRequestException("text must be at most " + MAX_TEXT_LENGTH + " characters");
        }
        try {
            return chat.send(text);
        } catch (RestClientException e) {
            throw new AssistantUnavailableException("The assistant is unavailable right now", e);
        }
    }

    @PostMapping("/messages/{id}/apply")
    public ApplyEventsResponse apply(@PathVariable long id) {
        requireAi();
        return chat.apply(id);
    }

    @PostMapping("/messages/{id}/reject")
    public ChatMessageDto reject(@PathVariable long id) {
        requireAi();
        return chat.reject(id);
    }

    private void requireAi() {
        if (client.getIfAvailable() == null) {
            throw new AiNotConfiguredException("AI features are not configured");
        }
    }
}
```

Run: `cd backend && ./mvnw -q test -Dtest='ChatController*Test'` — Expected: pass.

- [ ] **Step 3: End-to-end test (real agent loop, tools, engine and database; scripted model)**

`ChatApiEndToEndTest.java`:

```java
package com.lifeorganizer;

import com.jayway.jsonpath.JsonPath;
import com.lifeorganizer.ai.agent.AgentMessage;
import com.lifeorganizer.ai.agent.AgentReply;
import com.lifeorganizer.ai.agent.ToolCall;
import com.lifeorganizer.ai.client.AgentLlmClient;
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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole chat flow with a scripted model: read today's plan, propose cancelling or postponing the first goal
 * block, answer in text; then apply the proposal through the API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({ChatApiEndToEndTest.FixedClock.class, ChatApiEndToEndTest.ScriptedModel.class})
class ChatApiEndToEndTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    /** "Today" is Monday 2026-09-14. */
    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC);
        }
    }

    /**
     * Call 1: get_today_plan. Call 2: propose_changes on the first GOAL block (postpone if the user said
     * "postpone", else cancel). Call 3: a text answer.
     */
    @TestConfiguration
    static class ScriptedModel {
        @Bean
        @Primary
        AgentLlmClient scriptedAgentClient() {
            return (messages, tools) -> {
                List<AgentMessage> toolResults = messages.stream().filter(m -> m.role() == AgentMessage.Role.TOOL).toList();
                if (toolResults.isEmpty()) {
                    return new AgentReply(null, List.of(new ToolCall("c1", "get_today_plan", "{}")));
                }
                if (toolResults.size() == 1) {
                    List<String> goalIds = JsonPath.read(toolResults.get(0).content(), "$.blocks[?(@.kind == 'GOAL')].id");
                    String userText = messages.get(messages.size() - 3).content();
                    String type = userText.contains("postpone") ? "POSTPONE_BLOCK" : "CANCEL_BLOCK";
                    return new AgentReply(null, List.of(new ToolCall("c2", "propose_changes",
                        "{\"rationale\":\"Taking it off today\",\"events\":[{\"type\":\"" + type + "\",\"blockId\":\""
                            + goalIds.get(0) + "\"}]}")));
                }
                return new AgentReply("I proposed a change.", List.of());
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table chat_message, day_warning, block, plan_constraint, goal, category restart identity");
    }

    private static String jsonString(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private void saveExampleSetup() throws Exception {
        String setupJson = "{\"goalsText\":" + jsonString(ExampleSetup.GOALS_TEXT)
            + ",\"constraintsText\":" + jsonString(ExampleSetup.CONSTRAINTS_TEXT) + "}";
        String parsed = mvc.perform(post("/api/setup/parse").contentType(MediaType.APPLICATION_JSON).content(setupJson))
            .andReturn().getResponse().getContentAsString();
        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON).content(parsed)).andExpect(status().isOk());
    }

    private String send(String text) throws Exception {
        return mvc.perform(post("/api/assistant/chat/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    @Test
    void aProposalCanBeAppliedAndTheThreadIsKept() throws Exception {
        saveExampleSetup();

        String reply = send("my first session is off today");
        Integer id = JsonPath.read(reply, "$.id");
        String blockId = JsonPath.read(reply, "$.proposal.events[0].blockId");
        mvc.perform(get("/api/assistant/chat"))
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[1].proposal.status").value("PENDING"))
            .andExpect(jsonPath("$[1].text").value("I proposed a change."));

        mvc.perform(post("/api/assistant/chat/messages/" + id + "/apply"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changes[0]").value(containsString("Cancelled")));

        mvc.perform(get("/api/plan/today"))
            .andExpect(jsonPath("$.blocks[?(@.id == '" + blockId + "')].status").value("CANCELLED"));
        mvc.perform(get("/api/assistant/chat")).andExpect(jsonPath("$[1].proposal.status").value("APPLIED"));
        mvc.perform(post("/api/assistant/chat/messages/" + id + "/apply")).andExpect(status().isConflict());
        mvc.perform(get("/api/assistant/chat/days")).andExpect(jsonPath("$[0]").value("2026-09-14"));
    }

    @Test
    void proposingAPostponeAgainstAFreshWeekWorks() throws Exception {
        saveExampleSetup();
        // Only today exists; the postpone simulation must lazily generate the rest of the week (the Phase 2
        // read-only-transaction bug would 500 here).
        mvc.perform(get("/api/plan/today")).andExpect(status().isOk());

        String reply = send("please postpone my first session");

        org.assertj.core.api.Assertions.assertThat((String) JsonPath.read(reply, "$.proposal.events[0].type"))
            .isEqualTo("POSTPONE_BLOCK");
    }
}
```

Note: `JsonPath.read(..., "$.blocks[?(...)].status")` returns a list; if `.value("CANCELLED")` does not match a single-element list in this Spring version, assert with `.value(org.hamcrest.Matchers.contains("CANCELLED"))` instead.

- [ ] **Step 4: Run and commit**

Run: `cd backend && ./mvnw -q test -Dtest='ChatController*Test,ChatApiEndToEndTest'` — Expected: all pass.

```bash
git add backend/src/main/java/com/lifeorganizer/ai/chat/ChatController.java backend/src/test/java/com/lifeorganizer/ai/chat backend/src/test/java/com/lifeorganizer/ChatApiEndToEndTest.java
git commit -m "feat: add the chat API for the planning agent" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Frontend `ChatPanel` replaces `AssistantBox`

**Files:**
- Modify: `frontend/src/api/types.ts`, `frontend/src/api/client.ts`, `frontend/src/api/client.test.ts`
- Modify: `frontend/src/components/ProposalCard.tsx` (optional `status` prop)
- Create: `frontend/src/components/ChatPanel.tsx`, `frontend/src/components/ChatPanel.test.tsx`
- Modify: `frontend/src/pages/TodayPage.tsx`, `frontend/src/pages/TodayPage.test.tsx`
- Delete: `frontend/src/components/AssistantBox.tsx`, `frontend/src/components/AssistantBox.test.tsx`
- Modify: `frontend/src/index.css`

**Interfaces:**
- Consumes: the five chat endpoints (Task 8), `ApplyEventsResponse`, `BlockDto`, `ProposedEventDto`, `TodayPlanResponse` types.
- Produces: `api.getChat(date?)`, `api.getChatDays()`, `api.sendChatMessage({text})`, `api.applyProposal(id)`, `api.rejectProposal(id)`; types `ProposalStatus`, `ProposalDto`, `ChatMessageDto`, `SendMessageRequest`; `ChatPanel` props `{ today: string; currentBlocks: BlockDto[]; busy: boolean; onApplied: (result: ApplyEventsResponse) => void }`.

- [ ] **Step 1: Types and client**

In `types.ts`, delete `CommandRequest` and `CommandResponse`, keep `ProposedEventDto` (change its doc comment to "One event the assistant proposes; the exact shape the backend replays on Apply."), and add:

```ts
export type ProposalStatus = 'PENDING' | 'APPLIED' | 'REJECTED' | 'EXPIRED'

export interface ProposalDto {
  rationale: string
  events: ProposedEventDto[]
  changes: string[]
  preview: TodayPlanResponse
  status: ProposalStatus
}

export interface ChatMessageDto {
  id: number
  /** the thread's day, yyyy-MM-dd */
  date: string
  role: 'USER' | 'ASSISTANT'
  text: string
  proposal: ProposalDto | null
  createdAt: string
}

export interface SendMessageRequest {
  text: string
}
```

In `client.ts`, remove `proposeCommand` and the `CommandRequest`/`CommandResponse` imports; import `ChatMessageDto`, `SendMessageRequest`; add:

```ts
  getChat: (date?: string) =>
    send<ChatMessageDto[]>('GET', date ? `/api/assistant/chat?date=${date}` : '/api/assistant/chat'),
  getChatDays: () => send<string[]>('GET', '/api/assistant/chat/days'),
  sendChatMessage: (request: SendMessageRequest) => send<ChatMessageDto>('POST', '/api/assistant/chat/messages', request),
  applyProposal: (messageId: number) =>
    send<ApplyEventsResponse>('POST', `/api/assistant/chat/messages/${messageId}/apply`),
  rejectProposal: (messageId: number) =>
    send<ChatMessageDto>('POST', `/api/assistant/chat/messages/${messageId}/reject`),
```

In `client.test.ts`, replace the test `posts a command as JSON and returns the proposal` with:

```ts
  it('posts a chat message as JSON', async () => {
    const reply = { id: 7, date: '2026-09-14', role: 'ASSISTANT', text: 'Hi', proposal: null, createdAt: 'x' }
    const fetchMock = mockApi({ 'POST /api/assistant/chat/messages': () => jsonResponse(reply) })

    expect(await api.sendChatMessage({ text: 'padel got canceled' })).toEqual(reply)
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(JSON.parse(init.body as string)).toEqual({ text: 'padel got canceled' })
  })
```

- [ ] **Step 2: `ProposalCard` status**

Add an optional prop `status?: ProposalStatus` (import the type). When `status` is set and not `'PENDING'`, render `<p className="muted proposal-status">{STATUS_TEXT[status]}</p>` instead of the Apply/Reject buttons:

```tsx
const STATUS_TEXT: Record<Exclude<ProposalStatus, 'PENDING'>, string> = {
  APPLIED: 'Applied',
  REJECTED: 'Rejected',
  EXPIRED: 'Expired (a newer message came in)',
}
```

```tsx
      {status && status !== 'PENDING' ? (
        <p className="muted proposal-status">{STATUS_TEXT[status]}</p>
      ) : (
        <div className="block-actions">
          {/* existing Apply / Reject buttons unchanged */}
        </div>
      )}
```

Existing `ProposalCard` tests keep passing (they never pass `status`).

- [ ] **Step 3: Failing `ChatPanel` tests**

`ChatPanel.test.tsx`:

```tsx
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ChatMessageDto } from '../api/types'
import { TODAY } from '../test/fixtures'
import { jsonResponse, mockApi, problemResponse } from '../test/mockApi'
import { ChatPanel } from './ChatPanel'

afterEach(() => vi.unstubAllGlobals())

const GYM_ID = TODAY.blocks[0].id

function message(overrides: Partial<ChatMessageDto>): ChatMessageDto {
  return { id: 1, date: '2026-09-14', role: 'USER', text: 'hi', proposal: null, createdAt: '2026-09-14T08:00:00Z', ...overrides }
}

const PENDING: ChatMessageDto = message({
  id: 2,
  role: 'ASSISTANT',
  text: "I'd cancel the gym.",
  proposal: {
    rationale: 'Gym is off',
    events: [{ type: 'CANCEL_BLOCK', blockId: GYM_ID, reason: null, date: null, title: null, start: null, end: null, energy: null }],
    changes: ['Cancelled Gym - slot is now free'],
    preview: { ...TODAY, blocks: TODAY.blocks.map((b) => (b.id === GYM_ID ? { ...b, status: 'CANCELLED' } : b)) },
    status: 'PENDING',
  },
})

const THREAD = [message({ id: 1, text: 'gym is off' }), PENDING]

function renderPanel(onApplied = vi.fn()) {
  render(<ChatPanel today="2026-09-14" currentBlocks={TODAY.blocks} busy={false} onApplied={onApplied} />)
  return onApplied
}

describe('ChatPanel', () => {
  it("shows today's thread with the pending proposal", async () => {
    mockApi({
      'GET /api/assistant/chat': () => jsonResponse(THREAD),
      'GET /api/assistant/chat/days': () => jsonResponse(['2026-09-14']),
    })

    renderPanel()

    const conversation = await screen.findByRole('list', { name: 'Conversation' })
    expect(within(conversation).getByText('gym is off')).toBeInTheDocument()
    expect(within(conversation).getByText("I'd cancel the gym.")).toBeInTheDocument()
    expect(within(conversation).getByText('Gym is off')).toBeInTheDocument()
    expect(within(conversation).getByRole('button', { name: 'Apply' })).toBeEnabled()
    expect(screen.queryByLabelText('Earlier chats')).not.toBeInTheDocument()
  })

  it('sends a message, shows it while the agent thinks, then shows the saved thread', async () => {
    const user = userEvent.setup()
    let thread: ChatMessageDto[] = []
    let finish: (response: Response) => void = () => {}
    const fetchMock = mockApi({
      'GET /api/assistant/chat': () => jsonResponse(thread),
      'GET /api/assistant/chat/days': () => jsonResponse([]),
      'POST /api/assistant/chat/messages': () => new Promise<Response>((resolve) => (finish = resolve)),
    })
    renderPanel()
    await screen.findByLabelText('Message the assistant')

    await user.type(screen.getByLabelText('Message the assistant'), 'gym is off')
    await user.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText('Thinking…')).toBeInTheDocument()
    expect(screen.getByText('gym is off')).toBeInTheDocument()
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')!
    expect(JSON.parse(post[1]!.body as string)).toEqual({ text: 'gym is off' })

    thread = THREAD
    finish(jsonResponse(PENDING))

    expect(await screen.findByText("I'd cancel the gym.")).toBeInTheDocument()
    expect(screen.queryByText('Thinking…')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Message the assistant')).toHaveValue('')
  })

  it('keeps the text and shows the error when the assistant is unavailable', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/assistant/chat': () => jsonResponse([]),
      'GET /api/assistant/chat/days': () => jsonResponse([]),
      'POST /api/assistant/chat/messages': () =>
        problemResponse(502, 'Assistant unavailable', 'The assistant is unavailable right now'),
    })
    renderPanel()

    await user.type(await screen.findByLabelText('Message the assistant'), 'gym is off')
    await user.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('The assistant is unavailable right now')
    expect(screen.getByLabelText('Message the assistant')).toHaveValue('gym is off')
  })

  it('applies a proposal and reports the new plan', async () => {
    const user = userEvent.setup()
    let thread = THREAD
    const result = { ...TODAY, changes: ['Cancelled Gym - slot is now free'] }
    mockApi({
      'GET /api/assistant/chat': () => jsonResponse(thread),
      'GET /api/assistant/chat/days': () => jsonResponse(['2026-09-14']),
      'POST /api/assistant/chat/messages/2/apply': () => {
        thread = [THREAD[0], { ...PENDING, proposal: { ...PENDING.proposal!, status: 'APPLIED' } }]
        return jsonResponse(result)
      },
    })
    const onApplied = renderPanel()

    await user.click(await screen.findByRole('button', { name: 'Apply' }))

    await waitFor(() => expect(onApplied).toHaveBeenCalledWith(result))
    expect(await screen.findByText('Applied')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Apply' })).not.toBeInTheDocument()
  })

  it('rejects a proposal', async () => {
    const user = userEvent.setup()
    let thread = THREAD
    mockApi({
      'GET /api/assistant/chat': () => jsonResponse(thread),
      'GET /api/assistant/chat/days': () => jsonResponse(['2026-09-14']),
      'POST /api/assistant/chat/messages/2/reject': () => {
        thread = [THREAD[0], { ...PENDING, proposal: { ...PENDING.proposal!, status: 'REJECTED' } }]
        return jsonResponse(thread[1])
      },
    })
    renderPanel()

    await user.click(await screen.findByRole('button', { name: 'Reject' }))

    expect(await screen.findByText('Rejected')).toBeInTheDocument()
  })

  it('shows an earlier day read-only', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/assistant/chat': () => jsonResponse([]),
      'GET /api/assistant/chat/days': () => jsonResponse(['2026-09-14', '2026-09-13']),
      'GET /api/assistant/chat?date=2026-09-13': () =>
        jsonResponse([message({ date: '2026-09-13', text: 'old question' }), { ...PENDING, date: '2026-09-13' }]),
    })
    renderPanel()

    await user.selectOptions(await screen.findByLabelText('Earlier chats'), '2026-09-13')

    expect(await screen.findByText('old question')).toBeInTheDocument()
    expect(screen.getByText('Expired (a newer message came in)')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Apply' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Message the assistant')).not.toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Earlier chats'), '')
    expect(await screen.findByLabelText('Message the assistant')).toBeInTheDocument()
  })
})
```

Run: `cd frontend && npx vitest run src/components/ChatPanel.test.tsx` — Expected: fails (module not found).

- [ ] **Step 4: Implement `ChatPanel`**

```tsx
import { useEffect, useId, useState } from 'react'
import { api, errorMessage } from '../api/client'
import type { ApplyEventsResponse, BlockDto, ChatMessageDto } from '../api/types'
import { useApi } from '../api/useApi'
import { ProposalCard } from './ProposalCard'

interface Props {
  /** today's date (yyyy-MM-dd), from the plan on screen */
  today: string
  currentBlocks: BlockDto[]
  /** True while the page's own actions are busy - Send / Apply are disabled too. */
  busy: boolean
  onApplied: (result: ApplyEventsResponse) => void
}

/** The assistant: today's saved conversation with the planning agent, plus earlier days read-only. */
export function ChatPanel({ today, currentBlocks, busy: pageBusy, onApplied }: Props) {
  const thread = useApi(api.getChat)
  const days = useApi(api.getChatDays)
  const [input, setInput] = useState('')
  const [sending, setSending] = useState<string | null>(null)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [pastDay, setPastDay] = useState('')
  const [pastThread, setPastThread] = useState<ChatMessageDto[] | null>(null)
  const inputId = useId()
  const pickerId = useId()
  const busy = pageBusy || sending !== null || working

  useEffect(() => {
    if (!pastDay) {
      setPastThread(null)
      return
    }
    let cancelled = false
    api.getChat(pastDay).then(
      (messages) => {
        if (!cancelled) setPastThread(messages)
      },
      (failure: unknown) => {
        if (!cancelled) setError(errorMessage(failure))
      },
    )
    return () => {
      cancelled = true
    }
  }, [pastDay])

  async function reloadThread() {
    thread.setData(await api.getChat())
  }

  async function send() {
    const text = input.trim()
    if (!text) return
    setSending(text)
    setError(null)
    try {
      await api.sendChatMessage({ text })
      setInput('')
      await reloadThread()
      days.refetch()
    } catch (failure) {
      setError(errorMessage(failure)) // the text stays in the box
    } finally {
      setSending(null)
    }
  }

  async function act(action: () => Promise<void>) {
    setWorking(true)
    setError(null)
    try {
      await action()
      await reloadThread()
    } catch (failure) {
      setError(errorMessage(failure))
    } finally {
      setWorking(false)
    }
  }

  const earlierDays = (days.data ?? []).filter((day) => day !== today)
  const readOnly = pastDay !== ''
  const messages = readOnly ? (pastThread ?? []) : (thread.data ?? [])

  return (
    <section className="assistant">
      <h3>Assistant</h3>
      {earlierDays.length > 0 && (
        <div className="field chat-days">
          <label htmlFor={pickerId}>Earlier chats</label>
          <select id={pickerId} value={pastDay} onChange={(event) => setPastDay(event.target.value)}>
            <option value="">Today</option>
            {earlierDays.map((day) => (
              <option key={day} value={day}>
                {day}
              </option>
            ))}
          </select>
        </div>
      )}

      <ul className="chat-messages" aria-label="Conversation">
        {messages.map((message) => (
          <li key={message.id} className={message.role === 'USER' ? 'chat-user' : 'chat-assistant'}>
            <p>{message.text}</p>
            {message.proposal && (
              <ProposalCard
                rationale={message.proposal.rationale}
                events={message.proposal.events}
                preview={message.proposal.preview}
                // only a live proposal is compared with the plan on screen
                currentBlocks={!readOnly && message.proposal.status === 'PENDING' ? currentBlocks : message.proposal.preview.blocks}
                changes={message.proposal.changes}
                status={readOnly && message.proposal.status === 'PENDING' ? 'EXPIRED' : message.proposal.status}
                busy={busy}
                onApply={() =>
                  act(async () => {
                    onApplied(await api.applyProposal(message.id))
                  })
                }
                onReject={() =>
                  act(async () => {
                    await api.rejectProposal(message.id)
                  })
                }
              />
            )}
          </li>
        ))}
        {!readOnly && sending !== null && (
          <>
            <li className="chat-user">
              <p>{sending}</p>
            </li>
            <li className="chat-assistant muted">
              <p>Thinking…</p>
            </li>
          </>
        )}
      </ul>
      {error && <p role="alert">{error}</p>}

      {!readOnly && (
        <form
          onSubmit={(event) => {
            event.preventDefault()
            send()
          }}
        >
          <div className="field">
            <label htmlFor={inputId}>Message the assistant</label>
            <input
              id={inputId}
              type="text"
              maxLength={2000}
              value={input}
              disabled={busy}
              onChange={(event) => setInput(event.target.value)}
            />
          </div>
          <button type="submit" disabled={busy || !input.trim()}>
            Send
          </button>
        </form>
      )}
    </section>
  )
}
```

Append to `index.css`:

```css
.chat-messages {
  list-style: none;
  padding: 0;
  margin: 0 0 0.75rem;
}

.chat-messages li {
  margin-bottom: 0.5rem;
}

.chat-messages p {
  margin: 0.25rem 0;
}

.chat-user p {
  font-weight: 600;
}

.chat-days select {
  max-width: 12rem;
}
```

- [ ] **Step 5: Wire into `TodayPage`; remove `AssistantBox`**

In `TodayPage.tsx`: import `ChatPanel` instead of `AssistantBox`, and replace the `AssistantBox` line with:

```tsx
      {config?.aiEnabled && (
        <ChatPanel today={data.date} currentBlocks={data.blocks} busy={busy} onApplied={onAssistantApplied} />
      )}
```

Delete `components/AssistantBox.tsx` and `components/AssistantBox.test.tsx`.

In `TodayPage.test.tsx`, the two assistant tests become:

```tsx
  it('shows the assistant chat only when the backend reports aiEnabled', async () => {
    mockApi({
      'GET /api/plan/today': () => jsonResponse(TODAY),
      'GET /api/config': () => jsonResponse({ aiEnabled: true, ragEnabled: true }),
      'GET /api/assistant/chat': () => jsonResponse([]),
      'GET /api/assistant/chat/days': () => jsonResponse([]),
    })

    renderToday()

    expect(await screen.findByLabelText('Message the assistant')).toBeInTheDocument()
  })

  it('does not show the assistant chat when aiEnabled is false', async () => {
    mockApi({
      'GET /api/plan/today': () => jsonResponse(TODAY),
      'GET /api/config': () => jsonResponse({ aiEnabled: false, ragEnabled: false }),
    })

    renderToday()

    await screen.findByRole('heading', { name: "Today's plan" })
    expect(screen.queryByLabelText('Message the assistant')).not.toBeInTheDocument()
  })
```

- [ ] **Step 6: Run and commit**

Run: `cd frontend && npx vitest run && npm run build` — Expected: all tests pass, build clean.

```bash
git add -A frontend/src
git commit -m "feat: replace the command box with the assistant chat on Today" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Remove the one-shot command path; docs

**Files:**
- Delete (backend main): `ai/controller/AssistantController.java`, `ai/dto/CommandRequest.java`, `ai/dto/CommandResponse.java`, `ai/service/CommandInterpreter.java`, `ai/client/LlmCommandClient.java`, `ai/client/GroqCommandClient.java`, `ai/model/InterpretedCommand.java`, `ai/model/LlmCommandResult.java`, `ai/model/ProposedEvent.java`, `ai/model/ProposedEventType.java` (all under `backend/src/main/java/com/lifeorganizer/`)
- Delete (backend test): `ai/controller/AssistantControllerTest.java`, `ai/service/CommandInterpreterTest.java`, `ai/client/GroqCommandClientTest.java`, `ai/LiveGroqSmokeTest.java`, `AssistantApiEndToEndTest.java`
- Modify: `backend/src/main/java/com/lifeorganizer/ai/client/LlmClientConfig.java` (remove the `llmCommandClient` bean), `backend/src/main/java/com/lifeorganizer/config/ConfigController.java` (`aiEnabled` from `ObjectProvider<AgentLlmClient>`), `backend/src/test/java/com/lifeorganizer/ai/client/LlmClientConfigTest.java`, `backend/src/test/java/com/lifeorganizer/config/ConfigControllerAiEnabledTest.java`
- Modify: `README.md`, `CLAUDE.md`

**Interfaces:**
- Consumes: `AgentLlmClient` (Task 3), chat endpoints (Task 8).
- Produces: `/api/assistant/command` no longer exists; `aiEnabled` reflects the `AgentLlmClient` bean.

- [ ] **Step 1: Delete and rewire**

Delete the files listed above (`git rm`). In `LlmClientConfig`, remove the `llmCommandClient` bean and update the class Javadoc to name `AgentLlmClient` and `SetupExtractionClient`. In `ConfigController`, replace `ObjectProvider<LlmCommandClient>` with `ObjectProvider<AgentLlmClient>` (field name `agentLlmClient`). In `LlmClientConfigTest`, delete the two `LlmCommandClient` tests (the `AgentLlmClient` ones from Task 3 stay). Rename `ConfigControllerAiEnabledTest.reportsAiEnabledWhenAnLlmCommandClientBeanExists` to `reportsAiEnabledWhenTheAgentClientBeanExists`.

Then search for leftovers: `grep -rn "LlmCommand\|CommandInterpreter\|GroqCommandClient\|ProposedEvent\b\|assistant/command" backend/src frontend/src` — Expected: no hits except `ProposedEventValidator` / `ProposedEventDto`.

- [ ] **Step 2: Docs**

README.md:
- API table: replace the `/api/assistant/command` row with the five chat rows:

```
| GET | `/api/assistant/chat?date=` | a day's chat thread (default today) |
| GET | `/api/assistant/chat/days` | days that have a chat thread, newest first |
| POST | `/api/assistant/chat/messages` | `{text}` → runs the planning agent on today's thread, returns its reply (and a proposal, if any) |
| POST | `/api/assistant/chat/messages/{id}/apply` | apply that message's pending proposal to today's plan |
| POST | `/api/assistant/chat/messages/{id}/reject` | reject that message's pending proposal |
```

- Error list: add `409` proposal not available.
- Rewrite the "Assistant (optional)" section: the chat at the bottom of Today; the agent looks at today's plan, the week, your goals and your memory (notes and past days) with tools, can simulate changes, and proposes one change set per message that you Apply or Reject; conversations are saved one per day, earlier days viewable under "Earlier chats"; it never changes the plan itself; key and model as before.
- Status line: Phase 3b's chat agent is done; weekly prioritization and conflict resolution come later.

CLAUDE.md — in the `ai/` bullet, replace the command-box description with: `AgentRunner` (tool-calling loop, max 8 model calls, tools in `ai/agent/tools`, validation in `ProposedEventValidator`), `ChatService` / `ChatStore` (per-day `chat_message` thread, proposal statuses, send runs outside any transaction), and that proposals are applied via `/api/assistant/chat/messages/{id}/apply`. Update the frontend bullet: `ChatPanel` instead of `AssistantBox`.

- [ ] **Step 3: Run and commit**

Run: `cd backend && ./mvnw -q test -Dtest='ConfigController*Test,LlmClientConfigTest,Chat*Test,AgentRunnerTest'` and `cd frontend && npx vitest run && npm run build` — Expected: all pass.

```bash
git add -A backend/src README.md CLAUDE.md
git commit -m "refactor: remove the one-shot command path now that the chat replaces it" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Full suite

- [ ] **Step 1: Run everything**

Run: `docker compose up -d` (if not running), `cd backend && ./mvnw test`, then `cd frontend && npx vitest run && npm run build`.
Expected: all green. Report exact counts per module. If `GROQ_API_KEY` is set in the environment, also report `LiveGroqAgentSmokeTest`'s result (the first real tool-calling call). On any failure, report the failing test names and the decisive error lines; do not fix them in this task.

(The manual in-app check — "padel got canceled and I'm tired" → proposal → Apply → reload → thread still there; yesterday read-only — is done by the controller in the browser afterwards.)
