# Phase 3a RAG Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Notes page with CRUD plus semantic search over notes and past-day summaries, backed by local MiniLM embeddings stored in pgvector.

**Architecture:** New backend package `com.lifeorganizer.memory` (embedding, store, chunking, notes, history, search). Vectors live in a `memory_chunk` table in the existing Postgres (pgvector), queried with plain `JdbcTemplate`. Embeddings come from an in-process quantized all-MiniLM-L6-v2 model behind a `TextEmbedder` interface. A new React `NotesPage` edits notes and runs searches.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Flyway, Postgres 17 + pgvector, `dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2-q:1.20.2-beta30`, JUnit 5/AssertJ/Mockito, Testcontainers; React 19 + TypeScript + Vite + Vitest.

**Spec:** `docs/superpowers/specs/2026-09-27-life-organizer-phase3a-rag-memory-design.md`

## Global Constraints

- Embedding dimension is exactly 384 everywhere (`VECTOR(384)`, `TextEmbedder.DIMENSIONS`).
- Embedding dependency: `dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2-q:1.20.2-beta30`, class `dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel` (quantized, ~16 MB jar; the non-quantized jar is ~83 MB). Verified on Maven Central 2026-09-28.
- Our interface is named `TextEmbedder`, not `EmbeddingModel`, to avoid a clash with langchain4j's `dev.langchain4j.model.embedding.EmbeddingModel` (deviation from the spec's name, same contract).
- No pgvector Java library: vectors go to SQL as `'[f1,f2,...]'` strings cast with `::vector`.
- Source types are exactly `NOTE` and `DAY`.
- Note title required, at most 255 characters; body at most 20 000 characters. Search `q` required, at most 500 characters; `k` default 8, range 1–20.
- Chunks: at most 120 words, 20-word overlap when a paragraph is split.
- Plan history covers dates before today, back 90 days, and is built only through `PlanStore.findDay`. Never call `PlanService.getOrGenerate` from memory code.
- Every Testcontainers test uses `new PostgreSQLContainer("pgvector/pgvector:pg17")`.
- Every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. `git commit` prompts the user (project setting); that is expected.
- Keep shell commands ASCII-only (the Bash tool wrapper breaks on non-ASCII in heredocs); write files with the Write/Edit tools.

## Review Focus

1. A note with an empty or whitespace-only body must still be saved and found by its title (no zero-chunk note, no crash). Pinned in Task 4.
2. Searching when nothing is indexed returns 200 with an empty list, and the page says "Nothing found." Pinned in Tasks 2, 8, 9.
3. Text with quotes, apostrophes and non-ASCII characters ("O'Brien's café") round-trips through the store unchanged. Pinned in Task 2.
4. A past day that used to have a summary and now has none (or has only FREE/ROUTINE/FIXED blocks) must not keep a stale DAY entry. Pinned in Task 7.
5. A failure while indexing plan history at startup must not stop the application from starting. Pinned in Task 7.

---

### Task 1: Embedding model and `ragEnabled`

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/lifeorganizer/memory/embedding/TextEmbedder.java`
- Create: `backend/src/main/java/com/lifeorganizer/memory/embedding/MiniLmTextEmbedder.java`
- Create: `backend/src/main/java/com/lifeorganizer/memory/MemoryConfig.java`
- Modify: `backend/src/main/java/com/lifeorganizer/config/ConfigController.java`
- Create: `backend/src/test/java/com/lifeorganizer/memory/embedding/MiniLmTextEmbedderTest.java`
- Create: `backend/src/test/java/com/lifeorganizer/support/HashingTextEmbedder.java`
- Modify: `backend/src/test/java/com/lifeorganizer/config/ConfigControllerTest.java`

**Interfaces:**
- Produces: `interface TextEmbedder { int DIMENSIONS = 384; float[] embed(String text); }`; `MiniLmTextEmbedder implements TextEmbedder`; bean `TextEmbedder textEmbedder()` in `MemoryConfig`; test helper `com.lifeorganizer.support.HashingTextEmbedder implements TextEmbedder` (deterministic, word-overlap similarity).

- [ ] **Step 1: Add the dependency**

In `backend/pom.xml`, inside `<dependencies>` after the `postgresql` dependency:

```xml
		<dependency>
			<groupId>dev.langchain4j</groupId>
			<artifactId>langchain4j-embeddings-all-minilm-l6-v2-q</artifactId>
			<version>1.20.2-beta30</version>
		</dependency>
```

Run: `cd backend && ./mvnw -q dependency:tree -Dincludes=dev.langchain4j,com.fasterxml.jackson.core`
Expected: the langchain4j artifacts resolve. Jackson 2 (`com.fasterxml`) may appear transitively; it lives in different packages from the Jackson 3 (`tools.jackson`) the app uses, so leave it unless compilation fails.

- [ ] **Step 2: Write the failing real-model test**

`backend/src/test/java/com/lifeorganizer/memory/embedding/MiniLmTextEmbedderTest.java`:

```java
package com.lifeorganizer.memory.embedding;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Loads the real ONNX model once (about a second). No Docker, no network. */
class MiniLmTextEmbedderTest {

    private static MiniLmTextEmbedder embedder;

    @BeforeAll
    static void loadModel() {
        embedder = new MiniLmTextEmbedder();
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    @Test
    void producesVectorsOfTheConfiguredDimension() {
        assertThat(embedder.embed("hello world")).hasSize(TextEmbedder.DIMENSIONS);
    }

    @Test
    void similarMeaningsAreCloserThanUnrelatedOnes() {
        float[] note = embedder.embed("after gym I can't focus on study");
        float[] query = embedder.embed("tired after a workout");
        float[] unrelated = embedder.embed("the invoice for the car repair is due on Friday");

        assertThat(cosine(note, query)).isGreaterThan(cosine(note, unrelated));
    }
}
```

- [ ] **Step 3: Run it to confirm it fails**

Run: `cd backend && ./mvnw -q test -Dtest=MiniLmTextEmbedderTest`
Expected: compilation failure, `MiniLmTextEmbedder` not found.

- [ ] **Step 4: Implement**

`backend/src/main/java/com/lifeorganizer/memory/embedding/TextEmbedder.java`:

```java
package com.lifeorganizer.memory.embedding;

/** Turns text into a fixed-size vector. Implementations must be thread-safe. */
public interface TextEmbedder {

    /** Must match the VECTOR(384) column in V2__memory.sql. */
    int DIMENSIONS = 384;

    float[] embed(String text);
}
```

`backend/src/main/java/com/lifeorganizer/memory/embedding/MiniLmTextEmbedder.java`:

```java
package com.lifeorganizer.memory.embedding;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;

/**
 * all-MiniLM-L6-v2 (quantized ONNX) running inside the JVM: no API key, no network. The model reads at most
 * about 256 tokens per text, which is why notes are chunked before embedding.
 */
public class MiniLmTextEmbedder implements TextEmbedder {

    private final EmbeddingModel model = new AllMiniLmL6V2QuantizedEmbeddingModel();

    @Override
    public float[] embed(String text) {
        return model.embed(text).content().vector();
    }
}
```

`backend/src/main/java/com/lifeorganizer/memory/MemoryConfig.java`:

```java
package com.lifeorganizer.memory;

import com.lifeorganizer.memory.embedding.MiniLmTextEmbedder;
import com.lifeorganizer.memory.embedding.TextEmbedder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The embedding model is always available: it runs locally and needs no key. */
@Configuration
public class MemoryConfig {

    @Bean
    public TextEmbedder textEmbedder() {
        return new MiniLmTextEmbedder();
    }
}
```

- [ ] **Step 5: Run the test**

Run: `cd backend && ./mvnw -q test -Dtest=MiniLmTextEmbedderTest`
Expected: 2 tests pass. If the similarity test fails, report the two scores; do not weaken the assertion without asking.

- [ ] **Step 6: Test helper and `ragEnabled`**

`backend/src/test/java/com/lifeorganizer/support/HashingTextEmbedder.java`:

```java
package com.lifeorganizer.support;

import com.lifeorganizer.memory.embedding.TextEmbedder;

import java.util.Locale;

/**
 * Deterministic stand-in for the real model: each word adds 1 to one of 384 buckets, so texts that share
 * words point in similar directions. Never returns a zero vector (cosine distance would be NaN).
 */
public final class HashingTextEmbedder implements TextEmbedder {

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        boolean any = false;
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.isEmpty()) {
                continue;
            }
            vector[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1f;
            any = true;
        }
        if (!any) {
            vector[0] = 1f;
        }
        return vector;
    }
}
```

Change `ConfigController` so `ragEnabled` reports whether a `TextEmbedder` bean exists:

```java
package com.lifeorganizer.config;

import com.lifeorganizer.ai.client.LlmCommandClient;
import com.lifeorganizer.memory.embedding.TextEmbedder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells the UI which optional features exist. */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    public record ConfigResponse(boolean aiEnabled, boolean ragEnabled) {
    }

    private final ObjectProvider<LlmCommandClient> llmCommandClient;
    private final ObjectProvider<TextEmbedder> textEmbedder;

    public ConfigController(ObjectProvider<LlmCommandClient> llmCommandClient,
                            ObjectProvider<TextEmbedder> textEmbedder) {
        this.llmCommandClient = llmCommandClient;
        this.textEmbedder = textEmbedder;
    }

    @GetMapping
    public ConfigResponse config() {
        return new ConfigResponse(llmCommandClient.getIfAvailable() != null, textEmbedder.getIfAvailable() != null);
    }
}
```

The existing `ConfigControllerTest` keeps passing: a `@WebMvcTest` slice has no `TextEmbedder` bean, so `ragEnabled` is still false there. Create `backend/src/test/java/com/lifeorganizer/config/ConfigControllerRagEnabledTest.java`:

```java
package com.lifeorganizer.config;

import com.lifeorganizer.memory.embedding.TextEmbedder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(ConfigController.class)
class ConfigControllerRagEnabledTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TextEmbedder textEmbedder;

    @Test
    void reportsRagEnabledWhenAnEmbedderExists() throws Exception {
        mvc.perform(get("/api/config")).andExpect(jsonPath("$.ragEnabled").value(true));
    }
}
```

Rename the existing test method in `ConfigControllerTest` from `reportsThatNoAiOrRagFeaturesAreEnabledYet` to `reportsNothingEnabledWhenNoOptionalBeansExist` (behavior unchanged).

- [ ] **Step 7: Run and commit**

Run: `cd backend && ./mvnw -q test -Dtest='MiniLmTextEmbedderTest,ConfigController*Test'`
Expected: all pass.

```bash
git add backend/pom.xml backend/src/main/java/com/lifeorganizer/memory backend/src/main/java/com/lifeorganizer/config/ConfigController.java backend/src/test/java/com/lifeorganizer/memory backend/src/test/java/com/lifeorganizer/support/HashingTextEmbedder.java backend/src/test/java/com/lifeorganizer/config
git commit -m "feat: add local MiniLM text embedder and report ragEnabled"
```

---

### Task 2: `V2__memory.sql` and `MemoryChunkStore`

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__memory.sql`
- Create: `backend/src/main/java/com/lifeorganizer/memory/store/SourceType.java`
- Create: `backend/src/main/java/com/lifeorganizer/memory/store/NewChunk.java`
- Create: `backend/src/main/java/com/lifeorganizer/memory/store/MemoryHit.java`
- Create: `backend/src/main/java/com/lifeorganizer/memory/store/MemoryChunkStore.java`
- Modify: `backend/src/test/java/com/lifeorganizer/persistence/SchemaMigrationTest.java`
- Create: `backend/src/test/java/com/lifeorganizer/memory/store/MemoryChunkStoreTest.java`

**Interfaces:**
- Consumes: `TextEmbedder.DIMENSIONS` (Task 1), `HashingTextEmbedder` (Task 1, tests only).
- Produces:
  - `enum SourceType { NOTE, DAY }`
  - `record NewChunk(String text, float[] embedding)`
  - `record MemoryHit(SourceType sourceType, String sourceId, LocalDate sourceDate, String title, String text, double score)`
  - `MemoryChunkStore` (`@Component`): `void replace(SourceType type, String sourceId, LocalDate sourceDate, String title, String contentHash, List<NewChunk> chunks)`, `void delete(SourceType type, String sourceId)`, `Optional<String> contentHash(SourceType type, String sourceId)`, `List<MemoryHit> search(float[] query, int k, SourceType filterOrNull)`.

- [ ] **Step 1: Write the migration**

`backend/src/main/resources/db/migration/V2__memory.sql`:

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE note (
    id         BIGSERIAL    PRIMARY KEY,
    title      VARCHAR(255) NOT NULL,
    body       TEXT         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

-- One row per embedded chunk. source_id is the note id, or the ISO date for a DAY summary.
-- Not a foreign key: DAY rows have no parent table, and note deletes remove their chunks in code.
CREATE TABLE memory_chunk (
    id           BIGSERIAL    PRIMARY KEY,
    source_type  VARCHAR(10)  NOT NULL,
    source_id    VARCHAR(255) NOT NULL,
    source_date  DATE         NULL,
    title        VARCHAR(255) NOT NULL,
    text         TEXT         NOT NULL,
    content_hash VARCHAR(64)  NOT NULL,
    embedding    VECTOR(384)  NOT NULL
);
CREATE INDEX idx_memory_chunk_source ON memory_chunk (source_type, source_id);
CREATE INDEX idx_memory_chunk_embedding ON memory_chunk USING hnsw (embedding vector_cosine_ops);
```

In `SchemaMigrationTest`, extend the table assertion to `contains("category", "goal", "plan_constraint", "block", "day_warning", "note", "memory_chunk")`.

- [ ] **Step 2: Write the failing store test**

`backend/src/test/java/com/lifeorganizer/memory/store/MemoryChunkStoreTest.java`:

```java
package com.lifeorganizer.memory.store;

import com.lifeorganizer.support.HashingTextEmbedder;
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

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(MemoryChunkStore.class)
class MemoryChunkStoreTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private final HashingTextEmbedder embedder = new HashingTextEmbedder();

    @Autowired
    MemoryChunkStore store;

    private NewChunk chunk(String text) {
        return new NewChunk(text, embedder.embed(text));
    }

    @Test
    void searchOnAnEmptyStoreReturnsNothing() {
        assertThat(store.search(embedder.embed("anything"), 5, null)).isEmpty();
    }

    @Test
    void closestChunkComesFirstWithAScoreNearOne() {
        store.replace(SourceType.NOTE, "1", null, "Gym", "h1", List.of(chunk("gym makes me tired")));
        store.replace(SourceType.NOTE, "2", null, "Car", "h2", List.of(chunk("car invoice due friday")));

        List<MemoryHit> hits = store.search(embedder.embed("gym makes me tired"), 5, null);

        assertThat(hits).extracting(MemoryHit::sourceId).containsExactly("1", "2");
        assertThat(hits.get(0).score()).isGreaterThan(0.99);
        assertThat(hits.get(0).score()).isGreaterThan(hits.get(1).score());
    }

    @Test
    void kLimitsTheNumberOfHits() {
        for (int i = 0; i < 5; i++) {
            store.replace(SourceType.NOTE, "n" + i, null, "T", "h", List.of(chunk("word" + i)));
        }
        assertThat(store.search(embedder.embed("word1"), 3, null)).hasSize(3);
    }

    @Test
    void sourceFilterKeepsOnlyThatType() {
        LocalDate day = LocalDate.of(2026, 9, 24);
        store.replace(SourceType.NOTE, "1", null, "Note", "h", List.of(chunk("gym postponed")));
        store.replace(SourceType.DAY, day.toString(), day, "Thu 2026-09-24", "h", List.of(chunk("gym postponed")));

        List<MemoryHit> hits = store.search(embedder.embed("gym postponed"), 5, SourceType.DAY);

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.sourceType()).isEqualTo(SourceType.DAY);
            assertThat(hit.sourceDate()).isEqualTo(day);
        });
    }

    @Test
    void replaceSwapsAllChunksOfThatSourceOnly() {
        store.replace(SourceType.NOTE, "1", null, "A", "old", List.of(chunk("alpha"), chunk("beta")));
        store.replace(SourceType.NOTE, "2", null, "B", "keep", List.of(chunk("gamma")));

        store.replace(SourceType.NOTE, "1", null, "A", "new", List.of(chunk("delta")));

        assertThat(store.search(embedder.embed("alpha beta delta gamma"), 10, null))
            .extracting(MemoryHit::text).containsExactlyInAnyOrder("delta", "gamma");
        assertThat(store.contentHash(SourceType.NOTE, "1")).contains("new");
    }

    @Test
    void deleteRemovesTheSource() {
        store.replace(SourceType.NOTE, "1", null, "A", "h", List.of(chunk("alpha")));

        store.delete(SourceType.NOTE, "1");

        assertThat(store.search(embedder.embed("alpha"), 5, null)).isEmpty();
        assertThat(store.contentHash(SourceType.NOTE, "1")).isEmpty();
    }

    @Test
    void quotesAndNonAsciiTextRoundTripUnchanged() {
        String text = "O'Brien's café \"notes\" -- 100% ✓";
        store.replace(SourceType.NOTE, "1", null, "Title with 'quotes'", "h", List.of(chunk(text)));

        MemoryHit hit = store.search(embedder.embed(text), 1, null).get(0);

        assertThat(hit.text()).isEqualTo(text);
        assertThat(hit.title()).isEqualTo("Title with 'quotes'");
    }
}
```

- [ ] **Step 3: Run it to confirm it fails**

Run: `cd backend && ./mvnw -q test -Dtest=MemoryChunkStoreTest`
Expected: compilation failure (types missing).

- [ ] **Step 4: Implement**

`SourceType.java`:

```java
package com.lifeorganizer.memory.store;

/** What a memory chunk came from. */
public enum SourceType {
    NOTE,
    DAY
}
```

`NewChunk.java`:

```java
package com.lifeorganizer.memory.store;

/** One chunk of text to store, with its embedding. */
public record NewChunk(String text, float[] embedding) {
}
```

`MemoryHit.java`:

```java
package com.lifeorganizer.memory.store;

import java.time.LocalDate;

/** One search result. score is cosine similarity: 1 means same direction. */
public record MemoryHit(SourceType sourceType, String sourceId, LocalDate sourceDate, String title, String text,
                        double score) {
}
```

`MemoryChunkStore.java`:

```java
package com.lifeorganizer.memory.store;

import com.lifeorganizer.memory.embedding.TextEmbedder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The pgvector table behind memory search. Plain SQL on purpose: the vector operators are the point.
 * Vectors are sent as '[f1,f2,...]' strings and cast with ::vector, so no pgvector Java library is needed.
 */
@Component
public class MemoryChunkStore {

    private static final RowMapper<MemoryHit> HIT = (rs, row) -> {
        Date date = rs.getDate("source_date");
        return new MemoryHit(SourceType.valueOf(rs.getString("source_type")), rs.getString("source_id"),
            date == null ? null : date.toLocalDate(), rs.getString("title"), rs.getString("text"),
            rs.getDouble("score"));
    };

    private final JdbcTemplate jdbc;

    public MemoryChunkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Deletes every chunk of this source, then stores the given ones. */
    @Transactional
    public void replace(SourceType type, String sourceId, LocalDate sourceDate, String title, String contentHash,
                        List<NewChunk> chunks) {
        delete(type, sourceId);
        for (NewChunk chunk : chunks) {
            jdbc.update("""
                    insert into memory_chunk (source_type, source_id, source_date, title, text, content_hash, embedding)
                    values (?, ?, ?, ?, ?, ?, ?::vector)
                    """,
                type.name(), sourceId, sourceDate == null ? null : Date.valueOf(sourceDate), title, chunk.text(),
                contentHash, literal(chunk.embedding()));
        }
    }

    @Transactional
    public void delete(SourceType type, String sourceId) {
        jdbc.update("delete from memory_chunk where source_type = ? and source_id = ?", type.name(), sourceId);
    }

    @Transactional(readOnly = true)
    public Optional<String> contentHash(SourceType type, String sourceId) {
        return jdbc.queryForList(
                "select content_hash from memory_chunk where source_type = ? and source_id = ? limit 1",
                String.class, type.name(), sourceId)
            .stream().findFirst();
    }

    /** The k chunks closest to the query by cosine distance, optionally only of one source type. */
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
            sql.append("where source_type = ?\n");
            args.add(filter.name());
        }
        sql.append("order by embedding <=> ?::vector limit ?");
        args.add(vector);
        args.add(k);
        return jdbc.query(sql.toString(), HIT, args.toArray());
    }

    static String literal(float[] vector) {
        if (vector.length != TextEmbedder.DIMENSIONS) {
            throw new IllegalArgumentException(
                "expected " + TextEmbedder.DIMENSIONS + " dimensions, got " + vector.length);
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `cd backend && ./mvnw -q test -Dtest='MemoryChunkStoreTest,SchemaMigrationTest'`
Expected: all pass. `@DataJpaTest` runs each test in a rolled-back transaction, so tests do not see each other's rows.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V2__memory.sql backend/src/main/java/com/lifeorganizer/memory/store backend/src/test/java/com/lifeorganizer/memory/store backend/src/test/java/com/lifeorganizer/persistence/SchemaMigrationTest.java
git commit -m "feat: add pgvector memory_chunk table and store"
```

---

### Task 3: `Chunker`

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/memory/index/Chunker.java`
- Create: `backend/src/test/java/com/lifeorganizer/memory/index/ChunkerTest.java`

**Interfaces:**
- Produces: `final class Chunker { static final int MAX_WORDS = 120; static final int OVERLAP_WORDS = 20; static List<String> chunk(String body); }`. Returns an empty list for null/blank bodies. Words inside a chunk are joined by single spaces.

- [ ] **Step 1: Write the failing test**

```java
package com.lifeorganizer.memory.index;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTest {

    private static String words(int from, int count) {
        return IntStream.range(from, from + count).mapToObj(i -> "w" + i).collect(Collectors.joining(" "));
    }

    private static int wordCount(String chunk) {
        return chunk.split(" ").length;
    }

    @Test
    void blankOrNullBodyHasNoChunks() {
        assertThat(Chunker.chunk(null)).isEmpty();
        assertThat(Chunker.chunk("  \n\n  ")).isEmpty();
    }

    @Test
    void shortParagraphsArePackedIntoOneChunk() {
        assertThat(Chunker.chunk("after gym\nI can't focus\n\non study")).containsExactly("after gym I can't focus on study");
    }

    @Test
    void paragraphsThatDoNotFitStartANewChunk() {
        String body = words(0, 100) + "\n\n" + words(100, 50);

        List<String> chunks = Chunker.chunk(body);

        assertThat(chunks).containsExactly(words(0, 100), words(100, 50));
    }

    @Test
    void aLongParagraphIsSplitWithOverlap() {
        List<String> chunks = Chunker.chunk(words(0, 250));

        assertThat(chunks).allSatisfy(chunk -> assertThat(wordCount(chunk)).isLessThanOrEqualTo(Chunker.MAX_WORDS));
        assertThat(chunks.get(0)).isEqualTo(words(0, 120));
        assertThat(chunks.get(1)).startsWith("w100 ");
        assertThat(chunks.get(chunks.size() - 1)).endsWith("w249");
    }

    @Test
    void aVeryLongNoteBecomesManyBoundedChunks() {
        List<String> chunks = Chunker.chunk(words(0, 5000));

        assertThat(chunks.size()).isGreaterThan(40);
        assertThat(chunks).allSatisfy(chunk -> assertThat(wordCount(chunk)).isLessThanOrEqualTo(Chunker.MAX_WORDS));
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `cd backend && ./mvnw -q test -Dtest=ChunkerTest`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package com.lifeorganizer.memory.index;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Splits a note body into chunks the embedding model can read whole (MiniLM stops near 256 tokens).
 * Paragraphs are packed together up to MAX_WORDS; a paragraph longer than that is cut into windows that
 * overlap by OVERLAP_WORDS so a sentence on a boundary still appears whole in one of them.
 */
public final class Chunker {

    static final int MAX_WORDS = 120;
    static final int OVERLAP_WORDS = 20;

    private Chunker() {
    }

    public static List<String> chunk(String body) {
        List<String> chunks = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return chunks;
        }
        List<String> current = new ArrayList<>();
        for (String paragraph : body.split("\\n\\s*\\n")) {
            List<String> words = words(paragraph);
            if (words.isEmpty()) {
                continue;
            }
            if (words.size() > MAX_WORDS) {
                flush(current, chunks);
                for (int start = 0; ; start += MAX_WORDS - OVERLAP_WORDS) {
                    int end = Math.min(words.size(), start + MAX_WORDS);
                    chunks.add(String.join(" ", words.subList(start, end)));
                    if (end == words.size()) {
                        break;
                    }
                }
            } else {
                if (current.size() + words.size() > MAX_WORDS) {
                    flush(current, chunks);
                }
                current.addAll(words);
            }
        }
        flush(current, chunks);
        return chunks;
    }

    private static List<String> words(String text) {
        return Arrays.stream(text.trim().split("\\s+")).filter(word -> !word.isEmpty()).toList();
    }

    private static void flush(List<String> current, List<String> chunks) {
        if (!current.isEmpty()) {
            chunks.add(String.join(" ", current));
            current.clear();
        }
    }
}
```

- [ ] **Step 4: Run and commit**

Run: `cd backend && ./mvnw -q test -Dtest=ChunkerTest` — Expected: 5 pass.

```bash
git add backend/src/main/java/com/lifeorganizer/memory/index/Chunker.java backend/src/test/java/com/lifeorganizer/memory/index/ChunkerTest.java
git commit -m "feat: add note chunker for embeddings"
```

---

### Task 4: `MemoryIndexer`

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/memory/index/MemoryIndexer.java`
- Create: `backend/src/test/java/com/lifeorganizer/memory/index/MemoryIndexerTest.java`

**Interfaces:**
- Consumes: `TextEmbedder` (Task 1), `MemoryChunkStore`, `NewChunk`, `SourceType` (Task 2), `Chunker` (Task 3).
- Produces: `MemoryIndexer` (`@Service`): `void indexNote(long noteId, String title, String body)`, `void removeNote(long noteId)`, `boolean indexDay(LocalDate date, String title, String summary)` (returns false when the stored hash already matches), `void removeDay(LocalDate date)`, `static String sha256(String text)`.

- [ ] **Step 1: Write the failing test (Mockito, no Docker)**

```java
package com.lifeorganizer.memory.index;

import com.lifeorganizer.memory.store.MemoryChunkStore;
import com.lifeorganizer.memory.store.NewChunk;
import com.lifeorganizer.memory.store.SourceType;
import com.lifeorganizer.support.HashingTextEmbedder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryIndexerTest {

    private final MemoryChunkStore store = mock(MemoryChunkStore.class);
    private final MemoryIndexer indexer = new MemoryIndexer(new HashingTextEmbedder(), store);

    @SuppressWarnings("unchecked")
    private List<NewChunk> storedChunks(SourceType type, String sourceId) {
        ArgumentCaptor<List<NewChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).replace(eq(type), eq(sourceId), any(), anyString(), anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    void aNoteIsStoredAsItsChunks() {
        indexer.indexNote(7, "Gym", "after gym\n\nI can't focus on study");

        List<NewChunk> chunks = storedChunks(SourceType.NOTE, "7");
        assertThat(chunks).extracting(NewChunk::text).containsExactly("after gym I can't focus on study");
        assertThat(chunks.get(0).embedding()).hasSize(384);
    }

    @Test
    void aNoteWithAnEmptyBodyIsStillFindableByItsTitle() {
        indexer.indexNote(7, "Padel ideas", "   ");

        assertThat(storedChunks(SourceType.NOTE, "7")).extracting(NewChunk::text).containsExactly("Padel ideas");
    }

    @Test
    void theTitleIsPartOfWhatGetsEmbedded() {
        indexer.indexNote(7, "Padel", "x");
        indexer.indexNote(8, "Chess", "x");

        List<NewChunk> padel = storedChunks(SourceType.NOTE, "7");
        List<NewChunk> chess = storedChunks(SourceType.NOTE, "8");
        assertThat(padel.get(0).embedding()).isNotEqualTo(chess.get(0).embedding());
    }

    @Test
    void removeNoteDeletesItsChunks() {
        indexer.removeNote(7);

        verify(store).delete(SourceType.NOTE, "7");
    }

    @Test
    void aDayIsSkippedWhenItsSummaryDidNotChange() {
        LocalDate date = LocalDate.of(2026, 9, 24);
        String summary = "Thu 2026-09-24: Gym done 07:00";
        when(store.contentHash(SourceType.DAY, "2026-09-24")).thenReturn(Optional.of(MemoryIndexer.sha256(summary)));

        assertThat(indexer.indexDay(date, "Thu 2026-09-24", summary)).isFalse();
        verify(store, never()).replace(any(), anyString(), any(), anyString(), anyString(), anyList());
    }

    @Test
    void aChangedDayIsStoredAsOneChunkWithItsDate() {
        LocalDate date = LocalDate.of(2026, 9, 24);
        when(store.contentHash(SourceType.DAY, "2026-09-24")).thenReturn(Optional.of("old"));

        assertThat(indexer.indexDay(date, "Thu 2026-09-24", "Thu 2026-09-24: Gym done 07:00")).isTrue();

        verify(store).replace(eq(SourceType.DAY), eq("2026-09-24"), eq(date), eq("Thu 2026-09-24"),
            eq(MemoryIndexer.sha256("Thu 2026-09-24: Gym done 07:00")), anyList());
    }

    @Test
    void notesHaveNoSourceDate() {
        indexer.indexNote(7, "Gym", "body");

        verify(store).replace(eq(SourceType.NOTE), eq("7"), isNull(), eq("Gym"), anyString(), anyList());
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `cd backend && ./mvnw -q test -Dtest=MemoryIndexerTest` — Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package com.lifeorganizer.memory.index;

import com.lifeorganizer.memory.embedding.TextEmbedder;
import com.lifeorganizer.memory.store.MemoryChunkStore;
import com.lifeorganizer.memory.store.NewChunk;
import com.lifeorganizer.memory.store.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

/** Turns notes and day summaries into embedded chunks in the memory store. */
@Service
public class MemoryIndexer {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndexer.class);

    private final TextEmbedder embedder;
    private final MemoryChunkStore store;

    public MemoryIndexer(TextEmbedder embedder, MemoryChunkStore store) {
        this.embedder = embedder;
        this.store = store;
    }

    /** Always re-indexes: note writes are rare and the user expects the new text to be searchable at once. */
    public void indexNote(long noteId, String title, String body) {
        List<String> texts = Chunker.chunk(body);
        if (texts.isEmpty()) {
            texts = List.of(title); // an empty note is still findable by its title
        }
        // The title goes into every embedding so a chunk that never names the topic still matches it.
        List<NewChunk> chunks = texts.stream()
            .map(text -> new NewChunk(text, embedder.embed(title + "\n" + text)))
            .toList();
        store.replace(SourceType.NOTE, Long.toString(noteId), null, title, sha256(title + "\n" + body), chunks);
        log.debug("Indexed note {} as {} chunk(s)", noteId, chunks.size());
    }

    public void removeNote(long noteId) {
        store.delete(SourceType.NOTE, Long.toString(noteId));
    }

    /** Stores one day's summary as a single chunk; returns false when the stored summary is identical. */
    public boolean indexDay(LocalDate date, String title, String summary) {
        String sourceId = date.toString();
        String hash = sha256(summary);
        if (store.contentHash(SourceType.DAY, sourceId).filter(hash::equals).isPresent()) {
            return false;
        }
        store.replace(SourceType.DAY, sourceId, date, title, hash,
            List.of(new NewChunk(summary, embedder.embed(summary))));
        return true;
    }

    public void removeDay(LocalDate date) {
        store.delete(SourceType.DAY, date.toString());
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
```

- [ ] **Step 4: Run and commit**

Run: `cd backend && ./mvnw -q test -Dtest=MemoryIndexerTest` — Expected: 7 pass.

```bash
git add backend/src/main/java/com/lifeorganizer/memory/index/MemoryIndexer.java backend/src/test/java/com/lifeorganizer/memory/index/MemoryIndexerTest.java
git commit -m "feat: add memory indexer for notes and day summaries"
```

---

### Task 5: Notes API

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/memory/notes/NoteEntity.java`
- Create: `backend/src/main/java/com/lifeorganizer/memory/notes/NoteRepository.java`
- Create: `backend/src/main/java/com/lifeorganizer/memory/notes/NoteService.java`
- Create: `backend/src/main/java/com/lifeorganizer/memory/notes/NoteController.java`
- Create: `backend/src/main/java/com/lifeorganizer/memory/notes/dto/NoteDto.java`
- Create: `backend/src/main/java/com/lifeorganizer/memory/notes/dto/NoteRequest.java`
- Create: `backend/src/main/java/com/lifeorganizer/error/NoteNotFoundException.java`
- Modify: `backend/src/main/java/com/lifeorganizer/config/GlobalExceptionHandler.java`
- Create: `backend/src/test/java/com/lifeorganizer/memory/notes/NoteServiceTest.java`
- Create: `backend/src/test/java/com/lifeorganizer/memory/notes/NoteControllerTest.java`

**Interfaces:**
- Consumes: `MemoryIndexer.indexNote/removeNote` (Task 4), `InvalidRequestException` (existing, 400), `Clock` bean (existing, `EngineConfig`).
- Produces:
  - `record NoteRequest(String title, String body)`
  - `record NoteDto(long id, String title, String body, String createdAt, String updatedAt)` (ISO-8601 instants)
  - `NoteService`: `List<NoteDto> list()`, `NoteDto create(NoteRequest)`, `NoteDto update(long id, NoteRequest)`, `void delete(long id)`, `static final int MAX_TITLE = 255, MAX_BODY = 20_000`
  - Endpoints: `GET /api/notes` (200), `POST /api/notes` (201), `PUT /api/notes/{id}` (200), `DELETE /api/notes/{id}` (204). Unknown id → 404 title "Note not found".

- [ ] **Step 1: Write the failing service test (Testcontainers, fake embedder)**

```java
package com.lifeorganizer.memory.notes;

import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.error.NoteNotFoundException;
import com.lifeorganizer.memory.index.MemoryIndexer;
import com.lifeorganizer.memory.notes.dto.NoteDto;
import com.lifeorganizer.memory.notes.dto.NoteRequest;
import com.lifeorganizer.memory.store.MemoryChunkStore;
import com.lifeorganizer.memory.store.MemoryHit;
import com.lifeorganizer.memory.embedding.TextEmbedder;
import com.lifeorganizer.support.HashingTextEmbedder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({NoteService.class, MemoryIndexer.class, MemoryChunkStore.class, NoteServiceTest.Beans.class})
class NoteServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @TestConfiguration
    static class Beans {
        @Bean
        TextEmbedder textEmbedder() {
            return new HashingTextEmbedder();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    NoteService notes;

    @Autowired
    MemoryChunkStore store;

    private final HashingTextEmbedder embedder = new HashingTextEmbedder();

    private java.util.List<MemoryHit> search(String text) {
        return store.search(embedder.embed(text), 10, null);
    }

    @Test
    void createStoresTheNoteAndMakesItSearchable() {
        NoteDto created = notes.create(new NoteRequest("Gym", "after gym I can't focus on study"));

        assertThat(created.id()).isPositive();
        assertThat(created.createdAt()).isEqualTo("2026-09-28T10:00:00Z");
        assertThat(notes.list()).extracting(NoteDto::title).containsExactly("Gym");
        assertThat(search("focus on study")).extracting(MemoryHit::sourceId).containsExactly(Long.toString(created.id()));
    }

    @Test
    void updateReplacesTheSearchableText() {
        NoteDto created = notes.create(new NoteRequest("Gym", "alpha"));

        notes.update(created.id(), new NoteRequest("Gym", "omega"));

        assertThat(search("alpha omega")).extracting(MemoryHit::text).containsExactly("omega");
    }

    @Test
    void deleteRemovesTheNoteAndItsChunks() {
        NoteDto created = notes.create(new NoteRequest("Gym", "alpha"));

        notes.delete(created.id());

        assertThat(notes.list()).isEmpty();
        assertThat(search("alpha")).isEmpty();
    }

    @Test
    void anEmptyBodyIsAllowed() {
        NoteDto created = notes.create(new NoteRequest("Padel ideas", null));

        assertThat(created.body()).isEmpty();
        assertThat(search("padel ideas")).hasSize(1);
    }

    @Test
    void unknownIdsAreNotFound() {
        assertThatThrownBy(() -> notes.update(999, new NoteRequest("x", "y"))).isInstanceOf(NoteNotFoundException.class);
        assertThatThrownBy(() -> notes.delete(999)).isInstanceOf(NoteNotFoundException.class);
    }

    @Test
    void invalidInputIsRejected() {
        assertThatThrownBy(() -> notes.create(new NoteRequest(" ", "b")))
            .isInstanceOf(InvalidRequestException.class).hasMessage("title is required");
        assertThatThrownBy(() -> notes.create(new NoteRequest("t".repeat(256), "b")))
            .isInstanceOf(InvalidRequestException.class).hasMessage("title must be at most 255 characters");
        assertThatThrownBy(() -> notes.create(new NoteRequest("t", "b".repeat(20_001))))
            .isInstanceOf(InvalidRequestException.class).hasMessage("body must be at most 20000 characters");
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `cd backend && ./mvnw -q test -Dtest=NoteServiceTest` — Expected: compilation failure.

- [ ] **Step 3: Implement the backend**

`NoteNotFoundException.java`:

```java
package com.lifeorganizer.error;

public class NoteNotFoundException extends RuntimeException {

    public NoteNotFoundException(long id) {
        super("No note with id " + id);
    }
}
```

In `GlobalExceptionHandler`, add next to the other handlers (and its import):

```java
    @ExceptionHandler(NoteNotFoundException.class)
    public ProblemDetail noteNotFound(NoteNotFoundException e) {
        return problem(404, "Note not found", e.getMessage());
    }
```

`NoteEntity.java`:

```java
package com.lifeorganizer.memory.notes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "note")
public class NoteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @Column(columnDefinition = "text")
    private String body;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected NoteEntity() {
    }

    public NoteEntity(String title, String body, Instant now) {
        this.title = title;
        this.body = body;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void edit(String title, String body, Instant now) {
        this.title = title;
        this.body = body;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
```

`NoteRepository.java`:

```java
package com.lifeorganizer.memory.notes;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NoteRepository extends JpaRepository<NoteEntity, Long> {

    List<NoteEntity> findAllByOrderByUpdatedAtDescIdDesc();
}
```

`dto/NoteRequest.java` and `dto/NoteDto.java`:

```java
package com.lifeorganizer.memory.notes.dto;

public record NoteRequest(String title, String body) {
}
```

```java
package com.lifeorganizer.memory.notes.dto;

public record NoteDto(long id, String title, String body, String createdAt, String updatedAt) {
}
```

`NoteService.java`:

```java
package com.lifeorganizer.memory.notes;

import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.error.NoteNotFoundException;
import com.lifeorganizer.memory.index.MemoryIndexer;
import com.lifeorganizer.memory.notes.dto.NoteDto;
import com.lifeorganizer.memory.notes.dto.NoteRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Note CRUD. Every write re-indexes the note in the same transaction, so a failed index rolls back the save. */
@Service
public class NoteService {

    static final int MAX_TITLE = 255;
    static final int MAX_BODY = 20_000;

    private final NoteRepository notes;
    private final MemoryIndexer indexer;
    private final Clock clock;

    public NoteService(NoteRepository notes, MemoryIndexer indexer, Clock clock) {
        this.notes = notes;
        this.indexer = indexer;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<NoteDto> list() {
        return notes.findAllByOrderByUpdatedAtDescIdDesc().stream().map(NoteService::toDto).toList();
    }

    @Transactional
    public NoteDto create(NoteRequest request) {
        String title = validTitle(request.title());
        String body = validBody(request.body());
        NoteEntity saved = notes.save(new NoteEntity(title, body, Instant.now(clock)));
        indexer.indexNote(saved.getId(), title, body);
        return toDto(saved);
    }

    @Transactional
    public NoteDto update(long id, NoteRequest request) {
        NoteEntity note = notes.findById(id).orElseThrow(() -> new NoteNotFoundException(id));
        String title = validTitle(request.title());
        String body = validBody(request.body());
        note.edit(title, body, Instant.now(clock));
        indexer.indexNote(id, title, body);
        return toDto(note);
    }

    @Transactional
    public void delete(long id) {
        if (!notes.existsById(id)) {
            throw new NoteNotFoundException(id);
        }
        notes.deleteById(id);
        indexer.removeNote(id);
    }

    private static String validTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new InvalidRequestException("title is required");
        }
        String trimmed = title.trim();
        if (trimmed.length() > MAX_TITLE) {
            throw new InvalidRequestException("title must be at most " + MAX_TITLE + " characters");
        }
        return trimmed;
    }

    private static String validBody(String body) {
        String value = body == null ? "" : body;
        if (value.length() > MAX_BODY) {
            throw new InvalidRequestException("body must be at most " + MAX_BODY + " characters");
        }
        return value;
    }

    private static NoteDto toDto(NoteEntity note) {
        return new NoteDto(note.getId(), note.getTitle(), note.getBody(),
            note.getCreatedAt().toString(), note.getUpdatedAt().toString());
    }
}
```

`NoteController.java`:

```java
package com.lifeorganizer.memory.notes;

import com.lifeorganizer.memory.notes.dto.NoteDto;
import com.lifeorganizer.memory.notes.dto.NoteRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/notes")
public class NoteController {

    private final NoteService notes;

    public NoteController(NoteService notes) {
        this.notes = notes;
    }

    @GetMapping
    public List<NoteDto> list() {
        return notes.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NoteDto create(@RequestBody NoteRequest request) {
        return notes.create(request);
    }

    @PutMapping("/{id}")
    public NoteDto update(@PathVariable long id, @RequestBody NoteRequest request) {
        return notes.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        notes.delete(id);
    }
}
```

- [ ] **Step 4: Controller test**

```java
package com.lifeorganizer.memory.notes;

import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.error.NoteNotFoundException;
import com.lifeorganizer.memory.notes.dto.NoteDto;
import com.lifeorganizer.memory.notes.dto.NoteRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NoteController.class)
class NoteControllerTest {

    private static final NoteDto GYM = new NoteDto(1, "Gym", "body", "2026-09-28T10:00:00Z", "2026-09-28T10:00:00Z");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    NoteService notes;

    @Test
    void listsNotes() throws Exception {
        when(notes.list()).thenReturn(List.of(GYM));

        mvc.perform(get("/api/notes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].title").value("Gym"))
            .andExpect(jsonPath("$[0].updatedAt").value("2026-09-28T10:00:00Z"));
    }

    @Test
    void createReturns201() throws Exception {
        when(notes.create(new NoteRequest("Gym", "body"))).thenReturn(GYM);

        mvc.perform(post("/api/notes").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Gym\",\"body\":\"body\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void updateReturnsTheNote() throws Exception {
        when(notes.update(1, new NoteRequest("Gym", "new"))).thenReturn(GYM);

        mvc.perform(put("/api/notes/1").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Gym\",\"body\":\"new\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void deleteReturns204() throws Exception {
        mvc.perform(delete("/api/notes/1")).andExpect(status().isNoContent());

        verify(notes).delete(1);
    }

    @Test
    void unknownNoteIs404() throws Exception {
        doThrow(new NoteNotFoundException(9)).when(notes).delete(9);

        mvc.perform(delete("/api/notes/9"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.title").value("Note not found"));
    }

    @Test
    void invalidInputIs400() throws Exception {
        when(notes.create(any(NoteRequest.class))).thenThrow(new InvalidRequestException("title is required"));

        mvc.perform(post("/api/notes").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("title is required"));
    }

    @Test
    void aNonNumericIdIs400() throws Exception {
        mvc.perform(delete("/api/notes/abc")).andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 5: Run and commit**

Run: `cd backend && ./mvnw -q test -Dtest='NoteServiceTest,NoteControllerTest,SchemaMigrationTest'`
Expected: all pass (Hibernate `ddl-auto=validate` accepts the `note` mapping).

```bash
git add backend/src/main/java/com/lifeorganizer/memory/notes backend/src/main/java/com/lifeorganizer/error/NoteNotFoundException.java backend/src/main/java/com/lifeorganizer/config/GlobalExceptionHandler.java backend/src/test/java/com/lifeorganizer/memory/notes
git commit -m "feat: add notes API with indexing on every write"
```

---

### Task 6: `DaySummarizer`

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/memory/history/DaySummarizer.java`
- Create: `backend/src/test/java/com/lifeorganizer/memory/history/DaySummarizerTest.java`

**Interfaces:**
- Consumes: `DayPlan.blocksInOrder()`, `Block`, `BlockKind`, `BlockStatus`, `TimeFormats.format(LocalTime)` (existing).
- Produces: `final class DaySummarizer { static String title(LocalDate date); static Optional<String> summarize(DayPlan day); }`. `title` is like `"Thu 2026-09-24"`. Summary is `title + ": " + parts joined by "; "`.

- [ ] **Step 1: Write the failing test**

```java
package com.lifeorganizer.memory.history;

import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.DayPlan;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DaySummarizerTest {

    private static final LocalDate THU = LocalDate.of(2026, 9, 24);

    private static Block block(String title, int hour, BlockKind kind, BlockStatus status, String reason) {
        return new Block(title + "-" + hour, THU, LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0), title, kind,
            null, status, 0, false, reason);
    }

    @Test
    void titleIsShortWeekdayAndDate() {
        assertThat(DaySummarizer.title(THU)).isEqualTo("Thu 2026-09-24");
    }

    @Test
    void summarizesGoalAndAdhocOutcomesInTimeOrder() {
        DayPlan day = new DayPlan(THU, List.of(
            block("Social", 20, BlockKind.GOAL, BlockStatus.CANCELLED, null),
            block("Gym", 7, BlockKind.GOAL, BlockStatus.DONE, null),
            block("Claude cert", 18, BlockKind.GOAL, BlockStatus.POSTPONED, "tired after gym"),
            block("Dentist", 12, BlockKind.ADHOC, BlockStatus.PLANNED, null)), List.of());

        assertThat(DaySummarizer.summarize(day)).contains(
            "Thu 2026-09-24: Gym done 07:00; Dentist not marked done 12:00; "
                + "Claude cert postponed 18:00 (reason: tired after gym); Social cancelled 20:00");
    }

    @Test
    void freeRoutineAndFixedBlocksAreLeftOut() {
        DayPlan day = new DayPlan(THU, List.of(
            block("Work", 9, BlockKind.FIXED, BlockStatus.PLANNED, null),
            block("Dinner", 17, BlockKind.ROUTINE, BlockStatus.PLANNED, null),
            block("Free", 19, BlockKind.FREE, BlockStatus.PLANNED, null)), List.of());

        assertThat(DaySummarizer.summarize(day)).isEmpty();
    }

    @Test
    void aCancelledBlockKeepsItsReason() {
        DayPlan day = new DayPlan(THU, List.of(block("Padel", 19, BlockKind.GOAL, BlockStatus.CANCELLED, "rain")), List.of());

        assertThat(DaySummarizer.summarize(day)).contains("Thu 2026-09-24: Padel cancelled 19:00 (reason: rain)");
    }
}
```

Before writing it, check `DayPlan`'s constructor validation (`backend/src/main/java/com/lifeorganizer/domain/DayPlan.java`): if it rejects overlapping blocks or blocks of another date, keep the test blocks as above (non-overlapping, same date).

- [ ] **Step 2: Run to confirm failure**

Run: `cd backend && ./mvnw -q test -Dtest=DaySummarizerTest` — Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package com.lifeorganizer.memory.history;

import com.lifeorganizer.api.TimeFormats;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.DayPlan;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Turns one past day into a line of plain text worth remembering: what happened to goal and ad-hoc blocks. */
public final class DaySummarizer {

    private static final Set<BlockKind> REMEMBERED = Set.of(BlockKind.GOAL, BlockKind.ADHOC);

    private DaySummarizer() {
    }

    public static String title(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + date;
    }

    public static Optional<String> summarize(DayPlan day) {
        List<String> parts = day.blocksInOrder().stream()
            .filter(block -> REMEMBERED.contains(block.kind()))
            .map(DaySummarizer::describe)
            .toList();
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(title(day.date()) + ": " + String.join("; ", parts));
    }

    private static String describe(Block block) {
        String outcome = switch (block.status()) {
            case DONE -> "done";
            case POSTPONED -> "postponed";
            case CANCELLED -> "cancelled";
            case PLANNED -> "not marked done";
        };
        String text = block.title() + " " + outcome + " " + TimeFormats.format(block.start());
        if (block.reason() != null && !block.reason().isBlank()) {
            text += " (reason: " + block.reason() + ")";
        }
        return text;
    }
}
```

- [ ] **Step 4: Run and commit**

Run: `cd backend && ./mvnw -q test -Dtest=DaySummarizerTest` — Expected: 4 pass.

```bash
git add backend/src/main/java/com/lifeorganizer/memory/history/DaySummarizer.java backend/src/test/java/com/lifeorganizer/memory/history/DaySummarizerTest.java
git commit -m "feat: summarize past days for plan-history memory"
```

---

### Task 7: `PlanHistoryIndexer`

**Files:**
- Modify: `backend/src/main/java/com/lifeorganizer/persistence/BlockRepository.java`
- Create: `backend/src/main/java/com/lifeorganizer/config/SchedulingConfig.java`
- Create: `backend/src/main/java/com/lifeorganizer/memory/history/PlanHistoryIndexer.java`
- Create: `backend/src/test/java/com/lifeorganizer/memory/history/PlanHistoryIndexerTest.java`
- Create: `backend/src/test/java/com/lifeorganizer/memory/history/PlanHistoryIndexerStartupTest.java`

**Interfaces:**
- Consumes: `PlanStore.findDay(LocalDate)`, `PlanStore.saveNewDay(DayPlan)` (tests), `DaySummarizer` (Task 6), `MemoryIndexer.indexDay/removeDay` (Task 4), `MemoryChunkStore` (Task 2), `Clock`.
- Produces: `BlockRepository.findDistinctDatesBetween(LocalDate from, LocalDate to)`; `PlanHistoryIndexer` (`@Component`): `static final int DAYS_BACK = 90`, `int indexPastDays()` (returns number of days (re)indexed), `void onStartup()` (`@EventListener(ApplicationReadyEvent.class)`, never throws), `void nightly()` (`@Scheduled(cron = "0 5 0 * * *")`).

- [ ] **Step 1: Write the failing Testcontainers test**

```java
package com.lifeorganizer.memory.history;

import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.memory.embedding.TextEmbedder;
import com.lifeorganizer.memory.index.MemoryIndexer;
import com.lifeorganizer.memory.store.MemoryChunkStore;
import com.lifeorganizer.memory.store.MemoryHit;
import com.lifeorganizer.memory.store.SourceType;
import com.lifeorganizer.persistence.PlanStore;
import com.lifeorganizer.support.HashingTextEmbedder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({PlanHistoryIndexer.class, MemoryIndexer.class, MemoryChunkStore.class, PlanStore.class,
    PlanHistoryIndexerTest.Beans.class})
class PlanHistoryIndexerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    /** "Today" is Monday 2026-09-28. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);

    @TestConfiguration
    static class Beans {
        @Bean
        TextEmbedder textEmbedder() {
            return new HashingTextEmbedder();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    PlanHistoryIndexer historyIndexer;

    @Autowired
    PlanStore planStore;

    @Autowired
    MemoryChunkStore chunks;

    @Autowired
    JdbcTemplate jdbc;

    @PersistenceContext
    EntityManager entityManager;

    private final HashingTextEmbedder embedder = new HashingTextEmbedder();

    private static Block block(String id, LocalDate date, int hour, BlockKind kind, BlockStatus status) {
        return new Block(id, date, LocalTime.of(hour, 0), LocalTime.of(hour + 1, 0), id, kind, null, status, 0,
            false, null);
    }

    private void saveDay(LocalDate date, Block... blocks) {
        planStore.saveNewDay(new DayPlan(date, List.of(blocks), List.of()));
        entityManager.flush();
        entityManager.clear();
    }

    private List<MemoryHit> dayHits() {
        return chunks.search(embedder.embed("gym done"), 20, SourceType.DAY);
    }

    @Test
    void indexesPastDaysButNotTodayOrOlderThanTheWindow() {
        saveDay(TODAY.minusDays(1), block("Gym", TODAY.minusDays(1), 7, BlockKind.GOAL, BlockStatus.DONE));
        saveDay(TODAY, block("Gym", TODAY, 7, BlockKind.GOAL, BlockStatus.DONE));
        saveDay(TODAY.minusDays(91), block("Gym", TODAY.minusDays(91), 7, BlockKind.GOAL, BlockStatus.DONE));

        assertThat(historyIndexer.indexPastDays()).isEqualTo(1);

        assertThat(dayHits()).extracting(MemoryHit::sourceId).containsExactly(TODAY.minusDays(1).toString());
    }

    @Test
    void anUnchangedDayIsNotReindexed() {
        saveDay(TODAY.minusDays(1), block("Gym", TODAY.minusDays(1), 7, BlockKind.GOAL, BlockStatus.DONE));

        assertThat(historyIndexer.indexPastDays()).isEqualTo(1);
        assertThat(historyIndexer.indexPastDays()).isZero();
    }

    @Test
    void aDayWithNothingWorthRememberingLosesItsOldEntry() {
        LocalDate yesterday = TODAY.minusDays(1);
        saveDay(yesterday, block("Gym", yesterday, 7, BlockKind.GOAL, BlockStatus.DONE));
        historyIndexer.indexPastDays();

        // The day now only has a FIXED block (e.g. the goal block was removed by a replan).
        jdbc.update("update block set kind = 'FIXED' where block_date = ?", java.sql.Date.valueOf(yesterday));
        entityManager.clear(); // otherwise findDay returns the cached GOAL entity, not the updated row
        historyIndexer.indexPastDays();

        assertThat(dayHits()).isEmpty();
    }

    @Test
    void neverGeneratesMissingDays() {
        historyIndexer.indexPastDays();

        Integer blockRows = jdbc.queryForObject("select count(*) from block", Integer.class);
        assertThat(blockRows).isZero();
    }
}
```

`PlanHistoryIndexerStartupTest.java` (Mockito, no Docker):

```java
package com.lifeorganizer.memory.history;

import com.lifeorganizer.memory.index.MemoryIndexer;
import com.lifeorganizer.persistence.BlockRepository;
import com.lifeorganizer.persistence.PlanStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanHistoryIndexerStartupTest {

    @Test
    void aFailureAtStartupIsLoggedNotThrown() {
        BlockRepository blocks = mock(BlockRepository.class);
        when(blocks.findDistinctDatesBetween(any(), any())).thenThrow(new IllegalStateException("db down"));
        PlanHistoryIndexer indexer = new PlanHistoryIndexer(blocks, mock(PlanStore.class), mock(MemoryIndexer.class),
            Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC));

        assertThatCode(indexer::onStartup).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `cd backend && ./mvnw -q test -Dtest='PlanHistoryIndexer*Test'` — Expected: compilation failure.

- [ ] **Step 3: Implement**

Add to `BlockRepository`:

```java
    @Query("select distinct b.date from BlockEntity b where b.date between :from and :to order by b.date")
    List<LocalDate> findDistinctDatesBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
```

(imports: `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`)

`SchedulingConfig.java`:

```java
package com.lifeorganizer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled jobs (the nightly plan-history indexing). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
```

`PlanHistoryIndexer.java`:

```java
package com.lifeorganizer.memory.history;

import com.lifeorganizer.memory.index.MemoryIndexer;
import com.lifeorganizer.persistence.BlockRepository;
import com.lifeorganizer.persistence.PlanStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Keeps one memory entry per past day. Reads stored days only (PlanStore.findDay) and never calls
 * PlanService.getOrGenerate: indexing must not create days that were never planned.
 */
@Component
public class PlanHistoryIndexer {

    static final int DAYS_BACK = 90;

    private static final Logger log = LoggerFactory.getLogger(PlanHistoryIndexer.class);

    private final BlockRepository blocks;
    private final PlanStore planStore;
    private final MemoryIndexer indexer;
    private final Clock clock;

    public PlanHistoryIndexer(BlockRepository blocks, PlanStore planStore, MemoryIndexer indexer, Clock clock) {
        this.blocks = blocks;
        this.planStore = planStore;
        this.indexer = indexer;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            indexPastDays();
        } catch (RuntimeException e) {
            log.warn("Plan history indexing at startup failed; search will miss past days until the next run", e);
        }
    }

    @Scheduled(cron = "0 5 0 * * *")
    public void nightly() {
        indexPastDays();
    }

    public int indexPastDays() {
        LocalDate today = LocalDate.now(clock);
        List<LocalDate> dates = blocks.findDistinctDatesBetween(today.minusDays(DAYS_BACK), today.minusDays(1));
        int indexed = 0;
        for (LocalDate date : dates) {
            Optional<String> summary = planStore.findDay(date).flatMap(DaySummarizer::summarize);
            if (summary.isEmpty()) {
                indexer.removeDay(date);
            } else if (indexer.indexDay(date, DaySummarizer.title(date), summary.get())) {
                indexed++;
            }
        }
        log.info("Plan history: {} past day(s) checked, {} (re)indexed", dates.size(), indexed);
        return indexed;
    }
}
```

- [ ] **Step 4: Run and commit**

Run: `cd backend && ./mvnw -q test -Dtest='PlanHistoryIndexer*Test'` — Expected: 5 pass.

```bash
git add backend/src/main/java/com/lifeorganizer/persistence/BlockRepository.java backend/src/main/java/com/lifeorganizer/config/SchedulingConfig.java backend/src/main/java/com/lifeorganizer/memory/history/PlanHistoryIndexer.java backend/src/test/java/com/lifeorganizer/memory/history
git commit -m "feat: index past days as plan-history memory at startup and nightly"
```

---

### Task 8: Memory search API

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/memory/search/MemorySearch.java`
- Create: `backend/src/main/java/com/lifeorganizer/memory/search/MemoryController.java`
- Create: `backend/src/main/java/com/lifeorganizer/memory/search/MemoryResultDto.java`
- Create: `backend/src/test/java/com/lifeorganizer/memory/search/MemoryControllerTest.java`
- Create: `backend/src/test/java/com/lifeorganizer/MemoryApiEndToEndTest.java`

**Interfaces:**
- Consumes: `TextEmbedder`, `MemoryChunkStore.search`, `MemoryHit`, `SourceType`.
- Produces: `MemorySearch.search(String query, int k, SourceType filterOrNull) : List<MemoryHit>`; `record MemoryResultDto(String sourceType, String sourceId, String title, String date, String snippet, double score)`; `GET /api/memory/search?q=&k=&source=`.

- [ ] **Step 1: Write the failing controller test**

```java
package com.lifeorganizer.memory.search;

import com.lifeorganizer.memory.store.MemoryHit;
import com.lifeorganizer.memory.store.SourceType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MemoryController.class)
class MemoryControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    MemorySearch search;

    @Test
    void returnsHitsWithSnippetsAndDates() throws Exception {
        when(search.search("tired", 8, null)).thenReturn(List.of(
            new MemoryHit(SourceType.NOTE, "3", null, "Gym", "after gym I can't focus", 0.81),
            new MemoryHit(SourceType.DAY, "2026-09-24", LocalDate.of(2026, 9, 24), "Thu 2026-09-24",
                "Thu 2026-09-24: Gym done 07:00", 0.52)));

        mvc.perform(get("/api/memory/search").param("q", "tired"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].sourceType").value("NOTE"))
            .andExpect(jsonPath("$[0].date").doesNotExist())
            .andExpect(jsonPath("$[0].snippet").value("after gym I can't focus"))
            .andExpect(jsonPath("$[1].date").value("2026-09-24"))
            .andExpect(jsonPath("$[1].score").value(0.52));
    }

    @Test
    void longTextIsCutToASnippet() throws Exception {
        when(search.search("x", 8, null)).thenReturn(List.of(
            new MemoryHit(SourceType.NOTE, "3", null, "T", "a".repeat(500), 0.5)));

        mvc.perform(get("/api/memory/search").param("q", "x"))
            .andExpect(jsonPath("$[0].snippet").value("a".repeat(240) + "…"));
    }

    @Test
    void passesKAndSource() throws Exception {
        when(search.search("gym", 3, SourceType.DAY)).thenReturn(List.of());

        mvc.perform(get("/api/memory/search").param("q", "gym").param("k", "3").param("source", "DAY"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
        verify(search).search("gym", 3, SourceType.DAY);
    }

    @Test
    void blankQueryIs400() throws Exception {
        mvc.perform(get("/api/memory/search").param("q", "  "))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("q is required"));
        mvc.perform(get("/api/memory/search")).andExpect(status().isBadRequest());
    }

    @Test
    void tooLongQueryIs400() throws Exception {
        mvc.perform(get("/api/memory/search").param("q", "a".repeat(501)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("q must be at most 500 characters"));
    }

    @Test
    void kOutOfRangeIs400() throws Exception {
        mvc.perform(get("/api/memory/search").param("q", "x").param("k", "0"))
            .andExpect(jsonPath("$.detail").value("k must be between 1 and 20"));
        mvc.perform(get("/api/memory/search").param("q", "x").param("k", "21")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/memory/search").param("q", "x").param("k", "abc")).andExpect(status().isBadRequest());
    }

    @Test
    void unknownSourceIs400() throws Exception {
        mvc.perform(get("/api/memory/search").param("q", "x").param("source", "EMAIL"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("source must be NOTE or DAY"));
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `cd backend && ./mvnw -q test -Dtest=MemoryControllerTest` — Expected: compilation failure.

- [ ] **Step 3: Implement**

`MemorySearch.java`:

```java
package com.lifeorganizer.memory.search;

import com.lifeorganizer.memory.embedding.TextEmbedder;
import com.lifeorganizer.memory.store.MemoryChunkStore;
import com.lifeorganizer.memory.store.MemoryHit;
import com.lifeorganizer.memory.store.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/** Semantic search over notes and past days. The agent (Phase 3b) will call this as its search_memory tool. */
@Service
public class MemorySearch {

    private static final Logger log = LoggerFactory.getLogger(MemorySearch.class);

    private final TextEmbedder embedder;
    private final MemoryChunkStore store;

    public MemorySearch(TextEmbedder embedder, MemoryChunkStore store) {
        this.embedder = embedder;
        this.store = store;
    }

    public List<MemoryHit> search(String query, int k, SourceType filter) {
        List<MemoryHit> hits = store.search(embedder.embed(query), k, filter);
        log.info("Memory search: query.length={} k={} source={} hits={} topScore={}", query.length(), k, filter,
            hits.size(), hits.isEmpty() ? "-" : String.format("%.3f", hits.get(0).score()));
        return hits;
    }
}
```

`MemoryResultDto.java`:

```java
package com.lifeorganizer.memory.search;

/** date is ISO yyyy-MM-dd for DAY results and null for notes. */
public record MemoryResultDto(String sourceType, String sourceId, String title, String date, String snippet,
                              double score) {
}
```

`MemoryController.java`:

```java
package com.lifeorganizer.memory.search;

import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.memory.store.MemoryHit;
import com.lifeorganizer.memory.store.SourceType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    static final int MAX_QUERY_LENGTH = 500;
    static final int DEFAULT_K = 8;
    static final int MAX_K = 20;
    static final int SNIPPET_LENGTH = 240;

    private final MemorySearch search;

    public MemoryController(MemorySearch search) {
        this.search = search;
    }

    @GetMapping("/search")
    public List<MemoryResultDto> search(@RequestParam(required = false) String q,
                                        @RequestParam(required = false) Integer k,
                                        @RequestParam(required = false) String source) {
        if (q == null || q.isBlank()) {
            throw new InvalidRequestException("q is required");
        }
        if (q.length() > MAX_QUERY_LENGTH) {
            throw new InvalidRequestException("q must be at most " + MAX_QUERY_LENGTH + " characters");
        }
        int limit = k == null ? DEFAULT_K : k;
        if (limit < 1 || limit > MAX_K) {
            throw new InvalidRequestException("k must be between 1 and " + MAX_K);
        }
        return search.search(q.trim(), limit, sourceType(source)).stream().map(MemoryController::toDto).toList();
    }

    private static SourceType sourceType(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        try {
            return SourceType.valueOf(source);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("source must be NOTE or DAY");
        }
    }

    private static MemoryResultDto toDto(MemoryHit hit) {
        String text = hit.text();
        String snippet = text.length() <= SNIPPET_LENGTH ? text : text.substring(0, SNIPPET_LENGTH) + "…";
        return new MemoryResultDto(hit.sourceType().name(), hit.sourceId(), hit.title(),
            hit.sourceDate() == null ? null : hit.sourceDate().toString(), snippet, hit.score());
    }
}
```

Note: the controller calls `search.search(q.trim(), ...)`. In `MemoryControllerTest`, queries have no surrounding whitespace, so the stubs above match.

- [ ] **Step 4: End-to-end test (full context, real model)**

`backend/src/test/java/com/lifeorganizer/MemoryApiEndToEndTest.java`:

```java
package com.lifeorganizer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Notes API + real MiniLM model + pgvector: the spec's success example. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MemoryApiEndToEndTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("truncate table memory_chunk, note restart identity");
    }

    private void note(String title, String body) throws Exception {
        mvc.perform(post("/api/notes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"body\":\"" + body + "\"}"))
            .andExpect(status().isCreated());
    }

    @Test
    void theGymNoteRanksFirstForATiredQuery() throws Exception {
        note("Energy", "after gym I can't focus on study");
        note("Car", "the invoice for the car repair is due on Friday");
        note("Recipes", "lentil soup needs cumin and lemon");

        mvc.perform(get("/api/memory/search").param("q", "tired after a workout"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].title").value("Energy"))
            .andExpect(jsonPath("$[0].sourceType").value("NOTE"));
    }

    @Test
    void searchWithNothingIndexedIsAnEmptyList() throws Exception {
        mvc.perform(get("/api/memory/search").param("q", "anything"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void configReportsRagEnabled() throws Exception {
        mvc.perform(get("/api/config")).andExpect(jsonPath("$.ragEnabled").value(true));
    }
}
```

- [ ] **Step 5: Run and commit**

Run: `cd backend && ./mvnw -q test -Dtest='MemoryControllerTest,MemoryApiEndToEndTest'` — Expected: all pass.

```bash
git add backend/src/main/java/com/lifeorganizer/memory/search backend/src/test/java/com/lifeorganizer/memory/search backend/src/test/java/com/lifeorganizer/MemoryApiEndToEndTest.java
git commit -m "feat: add semantic memory search endpoint"
```

---

### Task 9: Frontend Notes page

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/client.ts`
- Create: `frontend/src/pages/NotesPage.tsx`
- Create: `frontend/src/pages/NotesPage.test.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/App.test.tsx`
- Modify: `frontend/src/index.css`

**Interfaces:**
- Consumes: `GET/POST /api/notes`, `PUT/DELETE /api/notes/{id}`, `GET /api/memory/search?q=` (Tasks 5, 8).
- Produces: `api.listNotes`, `api.createNote`, `api.updateNote`, `api.deleteNote`, `api.searchMemory`; types `NoteDto`, `NoteRequest`, `MemorySourceType`, `MemoryResult`; `NotesPage` at `/notes`.

- [ ] **Step 1: Types and client**

Append to `frontend/src/api/types.ts`:

```ts
export interface NoteDto {
  id: number
  title: string
  body: string
  createdAt: string
  updatedAt: string
}

export interface NoteRequest {
  title: string
  body: string
}

export type MemorySourceType = 'NOTE' | 'DAY'

export interface MemoryResult {
  sourceType: MemorySourceType
  sourceId: string
  title: string
  /** yyyy-MM-dd for DAY results, null for notes */
  date: string | null
  snippet: string
  score: number
}
```

In `frontend/src/api/client.ts`: add `MemoryResult`, `NoteDto`, `NoteRequest` to the type import; in `send`, before `return (await response.json()) as T`, add:

```ts
  if (response.status === 204) {
    return undefined as T
  }
```

and add to `api`:

```ts
  listNotes: () => send<NoteDto[]>('GET', '/api/notes'),
  createNote: (note: NoteRequest) => send<NoteDto>('POST', '/api/notes', note),
  updateNote: (id: number, note: NoteRequest) => send<NoteDto>('PUT', `/api/notes/${id}`, note),
  deleteNote: (id: number) => send<void>('DELETE', `/api/notes/${id}`),
  searchMemory: (q: string) => send<MemoryResult[]>('GET', `/api/memory/search?q=${encodeURIComponent(q)}`),
```

- [ ] **Step 2: Write the failing page test**

`frontend/src/pages/NotesPage.test.tsx`:

```tsx
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { MemoryResult, NoteDto } from '../api/types'
import { jsonResponse, mockApi, problemResponse } from '../test/mockApi'
import { NotesPage } from './NotesPage'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

const GYM: NoteDto = {
  id: 1,
  title: 'Gym',
  body: "after gym I can't focus on study",
  createdAt: '2026-09-28T10:00:00Z',
  updatedAt: '2026-09-28T10:00:00Z',
}

const RESULTS: MemoryResult[] = [
  { sourceType: 'NOTE', sourceId: '1', title: 'Gym', date: null, snippet: "after gym I can't focus", score: 0.81 },
  {
    sourceType: 'DAY',
    sourceId: '2026-09-24',
    title: 'Thu 2026-09-24',
    date: '2026-09-24',
    snippet: 'Thu 2026-09-24: Gym done 07:00',
    score: 0.52,
  },
]

describe('NotesPage', () => {
  it('lists saved notes', async () => {
    mockApi({ 'GET /api/notes': () => jsonResponse([GYM]) })

    render(<NotesPage />)

    expect(await screen.findByRole('button', { name: 'Gym' })).toBeInTheDocument()
  })

  it('creates a note and shows it in the list', async () => {
    const user = userEvent.setup()
    let notes: NoteDto[] = []
    const fetchMock = mockApi({
      'GET /api/notes': () => jsonResponse(notes),
      'POST /api/notes': () => {
        notes = [GYM]
        return jsonResponse(GYM, 201)
      },
    })
    render(<NotesPage />)

    await user.type(screen.getByLabelText('Title'), 'Gym')
    await user.type(screen.getByLabelText('Body'), "after gym I can't focus on study")
    await user.click(screen.getByRole('button', { name: 'Save note' }))

    expect(await screen.findByRole('button', { name: 'Gym' })).toBeInTheDocument()
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')!
    expect(JSON.parse(post[1]!.body as string)).toEqual({ title: 'Gym', body: "after gym I can't focus on study" })
    expect(screen.getByRole('heading', { name: 'Edit note' })).toBeInTheDocument()
  })

  it('edits an existing note with PUT', async () => {
    const user = userEvent.setup()
    const fetchMock = mockApi({
      'GET /api/notes': () => jsonResponse([GYM]),
      'PUT /api/notes/1': () => jsonResponse({ ...GYM, body: 'new body' }),
    })
    render(<NotesPage />)
    await user.click(await screen.findByRole('button', { name: 'Gym' }))

    const body = screen.getByLabelText('Body')
    await user.clear(body)
    await user.type(body, 'new body')
    await user.click(screen.getByRole('button', { name: 'Save note' }))

    await waitFor(() => expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(true))
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')!
    expect(JSON.parse(put[1]!.body as string)).toEqual({ title: 'Gym', body: 'new body' })
  })

  it('deletes after confirming', async () => {
    const user = userEvent.setup()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    let notes = [GYM]
    mockApi({
      'GET /api/notes': () => jsonResponse(notes),
      'DELETE /api/notes/1': () => {
        notes = []
        return new Response(null, { status: 204 })
      },
    })
    render(<NotesPage />)
    await user.click(await screen.findByRole('button', { name: 'Gym' }))

    await user.click(screen.getByRole('button', { name: 'Delete note' }))

    expect(await screen.findByText('No notes yet.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'New note' })).toBeInTheDocument()
  })

  it('does not delete when the confirm is cancelled', async () => {
    const user = userEvent.setup()
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const fetchMock = mockApi({ 'GET /api/notes': () => jsonResponse([GYM]) })
    render(<NotesPage />)
    await user.click(await screen.findByRole('button', { name: 'Gym' }))

    await user.click(screen.getByRole('button', { name: 'Delete note' }))

    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'DELETE')).toBe(false)
  })

  it('shows search results with their source and opens a note result', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/notes': () => jsonResponse([GYM]),
      'GET /api/memory/search?q=tired%20after%20workout': () => jsonResponse(RESULTS),
    })
    render(<NotesPage />)

    await user.type(screen.getByLabelText('Search your notes and past days'), 'tired after workout')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    const list = await screen.findByRole('list', { name: 'Search results' })
    const items = within(list).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(within(items[0]).getByText('Note')).toBeInTheDocument()
    expect(within(items[1]).getByText('Day')).toBeInTheDocument()
    expect(within(items[1]).getByText('Thu 2026-09-24: Gym done 07:00')).toBeInTheDocument()
    expect(within(items[0]).getByText('0.81')).toBeInTheDocument()

    await user.click(within(items[0]).getByRole('button', { name: 'Open' }))
    expect(screen.getByLabelText('Title')).toHaveValue('Gym')
  })

  it('says when nothing was found', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/notes': () => jsonResponse([]),
      'GET /api/memory/search?q=xyz': () => jsonResponse([]),
    })
    render(<NotesPage />)

    await user.type(screen.getByLabelText('Search your notes and past days'), 'xyz')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(await screen.findByText('Nothing found.')).toBeInTheDocument()
  })

  it('shows a search error', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/notes': () => jsonResponse([]),
      'GET /api/memory/search?q=xyz': () => problemResponse(400, 'Invalid request', 'q must be at most 500 characters'),
    })
    render(<NotesPage />)

    await user.type(screen.getByLabelText('Search your notes and past days'), 'xyz')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('q must be at most 500 characters')
  })

  it('disables Save until a title is typed', async () => {
    mockApi({ 'GET /api/notes': () => jsonResponse([]) })

    render(<NotesPage />)

    expect(await screen.findByText('No notes yet.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save note' })).toBeDisabled()
  })
})
```

Run: `cd frontend && npx vitest run src/pages/NotesPage.test.tsx` — Expected: fails (module not found).

- [ ] **Step 3: Implement `NotesPage`**

`frontend/src/pages/NotesPage.tsx`:

```tsx
import { useState } from 'react'
import { api, errorMessage } from '../api/client'
import type { MemoryResult, NoteDto } from '../api/types'
import { useApi } from '../api/useApi'

interface Draft {
  id: number | null
  title: string
  body: string
}

const EMPTY: Draft = { id: null, title: '', body: '' }

function toDraft(note: NoteDto): Draft {
  return { id: note.id, title: note.title, body: note.body }
}

/** Notes you write yourself, plus semantic search over them and over past days. */
export function NotesPage() {
  const { data: notes, refetch } = useApi(api.listNotes)
  const [draft, setDraft] = useState<Draft>(EMPTY)
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<MemoryResult[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function run(action: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (failure) {
      setError(errorMessage(failure))
    } finally {
      setBusy(false)
    }
  }

  function search() {
    return run(async () => setResults(await api.searchMemory(query.trim())))
  }

  function save() {
    return run(async () => {
      const request = { title: draft.title, body: draft.body }
      const saved = draft.id === null ? await api.createNote(request) : await api.updateNote(draft.id, request)
      setDraft(toDraft(saved))
      refetch()
    })
  }

  function remove() {
    if (draft.id === null || !window.confirm('Delete this note?')) return
    const id = draft.id
    return run(async () => {
      await api.deleteNote(id)
      setDraft(EMPTY)
      refetch()
    })
  }

  function open(id: string) {
    const note = notes?.find((candidate) => String(candidate.id) === id)
    if (note) setDraft(toDraft(note))
  }

  return (
    <section>
      <h2>Notes</h2>

      <form
        onSubmit={(event) => {
          event.preventDefault()
          search()
        }}
      >
        <div className="field">
          <label htmlFor="memory-query">Search your notes and past days</label>
          <input id="memory-query" type="search" maxLength={500} value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
        <button type="submit" disabled={busy || query.trim() === ''}>
          Search
        </button>
      </form>
      {error && <p role="alert">{error}</p>}

      {results && results.length === 0 && <p className="muted">Nothing found.</p>}
      {results && results.length > 0 && (
        <ul className="memory-results" aria-label="Search results">
          {results.map((result) => (
            <li key={`${result.sourceType}-${result.sourceId}-${result.snippet}`}>
              <span className="badge">{result.sourceType === 'NOTE' ? 'Note' : 'Day'}</span>{' '}
              <strong>{result.title}</strong> <span className="muted">{result.score.toFixed(2)}</span>
              <p>{result.snippet}</p>
              {result.sourceType === 'NOTE' && (
                <button type="button" onClick={() => open(result.sourceId)}>
                  Open
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      <h3>Your notes</h3>
      {notes && notes.length === 0 && <p className="muted">No notes yet.</p>}
      {notes && notes.length > 0 && (
        <ul className="note-list">
          {notes.map((note) => (
            <li key={note.id}>
              <button type="button" onClick={() => setDraft(toDraft(note))}>
                {note.title}
              </button>{' '}
              <span className="muted">{note.updatedAt.slice(0, 10)}</span>
            </li>
          ))}
        </ul>
      )}
      <button type="button" disabled={busy} onClick={() => setDraft(EMPTY)}>
        New note
      </button>

      <h3>{draft.id === null ? 'New note' : 'Edit note'}</h3>
      <div className="field">
        <label htmlFor="note-title">Title</label>
        <input
          id="note-title"
          type="text"
          maxLength={255}
          value={draft.title}
          onChange={(e) => setDraft({ ...draft, title: e.target.value })}
        />
      </div>
      <div className="field">
        <label htmlFor="note-body">Body</label>
        <textarea
          id="note-body"
          rows={10}
          maxLength={20000}
          value={draft.body}
          onChange={(e) => setDraft({ ...draft, body: e.target.value })}
        />
      </div>
      <button type="button" disabled={busy || draft.title.trim() === ''} onClick={save}>
        Save note
      </button>{' '}
      {draft.id !== null && (
        <button type="button" disabled={busy} onClick={remove}>
          Delete note
        </button>
      )}
    </section>
  )
}
```

Append to `frontend/src/index.css`:

```css
.memory-results,
.note-list {
  list-style: none;
  padding: 0;
}

.memory-results li {
  margin-bottom: 0.75rem;
}

.memory-results p {
  margin: 0.25rem 0;
}
```

- [ ] **Step 4: Route and nav**

In `App.tsx`: import `NotesPage`, add `<NavLink to="/notes">Notes</NavLink>` after Setup, and `<Route path="/notes" element={<NotesPage />} />` before the catch-all. In `App.test.tsx`'s first test, add:

```tsx
    expect(screen.getByRole('link', { name: 'Notes' })).toHaveAttribute('href', '/notes')
```

- [ ] **Step 5: Run everything frontend and commit**

Run: `cd frontend && npx vitest run && npm run build`
Expected: all tests pass (previous 112 + 9 new), build succeeds.

```bash
git add frontend/src
git commit -m "feat: add Notes page with semantic memory search"
```

---

### Task 10: README, full suite, manual check

**Files:**
- Modify: `README.md`

- [ ] **Step 1: README**

Add rows to the API table:

```
| GET | `/api/notes` | list notes, newest first |
| POST | `/api/notes` | create a note `{title, body}` (201) |
| PUT | `/api/notes/{id}` | update a note |
| DELETE | `/api/notes/{id}` | delete a note (204) |
| GET | `/api/memory/search?q=&k=&source=` | semantic search over notes and past days (`k` 1-20, default 8; `source` NOTE or DAY) |
```

Change the `/api/config` row so `ragEnabled` reads "true when the local embedding model is loaded (always, unless removed)". Add a section:

```
## Memory (RAG)

The Notes page stores your own notes and searches them together with a one-line summary of every past day
(what you did, postponed or cancelled, and why). Search is semantic: "tired after a workout" finds a note
that says "after gym I can't focus on study".

Embeddings come from all-MiniLM-L6-v2 (quantized) running inside the backend. No API key, no network.
Vectors live in Postgres through the pgvector extension (table `memory_chunk`, HNSW cosine index).
Past days are indexed when the backend starts and every night at 00:05.
```

- [ ] **Step 2: Full backend and frontend suites**

Run: `docker compose up -d` (if not already running), then `cd backend && ./mvnw test`, then `cd frontend && npx vitest run && npm run build`.
Expected: everything passes, including `AssistantApiEndToEndTest` (never run since the Groq migration). Report exact counts. If anything fails, stop and report the failure output; do not paper over it.

- [ ] **Step 3: Manual check (real app)**

Start backend (`cd backend && ./mvnw spring-boot:run`) and frontend (`cd frontend && npm run dev`). On `http://localhost:5173/notes`:
1. Create "Energy" / "after gym I can't focus on study" and "Car" / "invoice due Friday".
2. Search "tired after a workout": Energy is first.
3. Check backend log for `Plan history: N past day(s) checked` at startup; if past days exist, search "postponed" and see Day results.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: document notes and memory search"
```
