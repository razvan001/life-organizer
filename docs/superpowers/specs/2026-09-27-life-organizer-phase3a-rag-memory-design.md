# Life Organizer — Phase 3a: RAG Memory (Notes, Plan History, Semantic Search) — Design Spec

- **Date:** 2026-09-27
- **Status:** Design approved by the user; spec awaiting review
- **Builds on:** Phases 1a/1b (engine, persisted plan, REST API, React UI), Phase 2 (natural-language commands, Groq), brain-dump extraction (`2026-09-26-brain-dump-extraction-design.md`).
- **Original design:** `2026-09-17-life-organizer-design.md`, section "Phase 3 — RAG + planning agent".

## 1. Context and scope

The original Phase 3 has two subsystems: RAG memory and a planning agent. They are split. This spec covers **3a, RAG memory**. The agent (3b) gets its own spec later and will use this memory through a `search_memory` tool.

Decisions made with the user:

| Topic | Decision |
|---|---|
| Order | RAG memory first, agent later |
| Consumer before the agent exists | Semantic search only, no LLM: a search box and result list on a new Notes page |
| Embeddings | Local in-JVM model (all-MiniLM-L6-v2 via ONNX). No API key, no network. Groq has no embeddings API |
| Testing | Docker works: real Testcontainers pgvector tests, full suite runs |

Assumptions (stated to the user, not contradicted):

- Sources are in-app notes and plan history (one summary per past day).
- Plan history is built from existing `block` rows (status, reason, postpone count). No event-log table.
- Notes are title + body. No tags.
- Single user, no auth.

**Success:** a note "after gym I can't focus on study" ranks first for the search "tired after workout". Past days are searchable ("Thu 2026-09-24: Gym done 07:00; Claude certification postponed 18:00, reason: tired").

**Out of scope:** planning agent, LLM answers over memory, feeding memory into the Today assistant, tags, re-ranking.

## 2. Approach

pgvector inside the existing Postgres (the compose file and all Testcontainers tests already use `pgvector/pgvector:pg17`). Vector SQL through plain `JdbcTemplate`. Embedding model behind an interface.

Rejected:

- In-memory vector index: no pgvector showcase, lost on restart.
- Spring AI vector-store abstraction: heavy, Spring Boot 4.1 compatibility unverified, and it hides the SQL that this showcase is meant to show.

## 3. Backend — package `com.lifeorganizer.memory`

### 3.1 Embedding

- `EmbeddingModel` interface: `float[] embed(String text)`, `int dimensions()`.
- `LocalMiniLmEmbeddingModel`: all-MiniLM-L6-v2 in-process, 384 dimensions. Candidate dependency `dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2`; verify coordinates, version and Java 21 compatibility during implementation.
- The model is always available, so `GET /api/config` returns `ragEnabled: true`.
- Tests use a deterministic hash-based fake embedder, so most tests never load the model. One dedicated test loads the real model.

### 3.2 Storage — Flyway `V2__memory.sql`

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE note (
    id         BIGSERIAL PRIMARY KEY,
    title      VARCHAR(255) NOT NULL,
    body       TEXT         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

CREATE TABLE memory_chunk (
    id           BIGSERIAL PRIMARY KEY,
    source_type  VARCHAR(10)  NOT NULL,   -- NOTE | DAY
    source_id    VARCHAR(255) NOT NULL,   -- note id, or ISO date for DAY
    source_date  DATE         NULL,
    title        VARCHAR(255) NOT NULL,
    text         TEXT         NOT NULL,
    content_hash VARCHAR(64)  NOT NULL,
    embedding    VECTOR(384)  NOT NULL
);
CREATE INDEX idx_memory_chunk_source ON memory_chunk (source_type, source_id);
CREATE INDEX idx_memory_chunk_embedding ON memory_chunk USING hnsw (embedding vector_cosine_ops);
```

`MemoryChunkStore` (JdbcTemplate):

- `replace(sourceType, sourceId, chunks)`: delete that source's rows, insert the new ones.
- `delete(sourceType, sourceId)`.
- `contentHash(sourceType, sourceId)`: the stored hash, if any.
- `search(vector, k, sourceType)`: `ORDER BY embedding <=> ?::vector LIMIT k`, optional `WHERE source_type = ?`, score = 1 − cosine distance.
- Vectors are passed as `'[0.1,0.2,...]'` literals cast with `::vector`. No extra pgvector Java library.

### 3.3 Chunking

`Chunker`: split on blank-line paragraphs, then pack paragraphs into chunks of at most ~120 words, with ~20 words of overlap when a paragraph has to be split. MiniLM truncates input near 256 tokens, which is why chunks stay small. The title is prepended to each chunk's text before embedding.

### 3.4 Notes

- `NoteEntity`, `NoteRepository`, `NoteService`, `NoteController`.
- `GET /api/notes` (newest updated first), `POST /api/notes`, `PUT /api/notes/{id}`, `DELETE /api/notes/{id}`.
- Every write re-indexes that note's chunks in the same transaction. A delete removes its chunks.
- Validation: title required and at most 255 characters; body required and at most 500 characters → 400. Unknown id → 404. (Changed on 2026-09-30 at the user's request; the first version allowed an empty body of up to 20 000 characters.)
- The form shows a character counter under each field and a "required" message once the user leaves a required field empty.

### 3.5 Plan history

- `DaySummarizer` (pure function): one past `DayPlan` → one summary line, or empty when the day has nothing worth remembering. It includes GOAL and ADHOC blocks with their outcome: done, postponed (with reason), cancelled, or not marked. It skips FREE, ROUTINE and FIXED blocks.
- `PlanHistoryIndexer` runs on `ApplicationReadyEvent` and nightly (`@Scheduled`, 00:05). For each date before today that has block rows, within the last 90 days: summarize, hash, and re-index only if the hash changed.
- It reads through `PlanStore.findDay` only and **never** calls `PlanService.getOrGenerate`. Indexing must never generate a missing day. This also keeps it clear of the lazy-generation-under-read-only-transaction problem found in Phase 2.
- Needs `@EnableScheduling` and a `BlockRepository` query for distinct past block dates.

### 3.6 Search

- `MemorySearch.search(query, k, sourceType)`: embed the query, search the store, map rows to results.
- `MemoryController`: `GET /api/memory/search?q=&k=&source=` returns `[{sourceType, sourceId, title, date, snippet, score}]`.
- Blank `q` → 400. `k` defaults to 8, maximum 20. Unknown `source` → 400.
- Logging in the style of the AI package: query length, hit count, top score, indexing counts.

## 4. Frontend

- New `/notes` route and nav link, `NotesPage`:
  - A search box at the top. Results show a source badge (Note / Day), the title or date, a snippet and the score. Clicking a note result opens that note in the editor.
  - A notes list (title, last updated), a create/edit form (title + body), and delete with a confirm step.
- Additions to `api/types.ts` and `api/client.ts`; reuse `useApi` and `errorMessage`.

## 5. Testing

- Unit: `Chunker`, `DaySummarizer`, `NoteService` indexing with the fake embedder, `@WebMvcTest` controller tests (validation, 404, 400).
- Testcontainers (pgvector): Flyway V2 applies; chunk store round trip; search ordering with fixed vectors; source filter; note update replaces its chunks; note delete removes them; `PlanHistoryIndexer` indexes past days, skips unchanged hashes and never generates a day.
- Real model: sentences with similar meaning score higher than unrelated ones.
- Frontend (Vitest): notes CRUD, search result rendering, error display.
- Full `./mvnw test` (Docker), `npm test`, `npm run build`.

## 6. Verification

- `docker compose up -d`, `cd backend && ./mvnw test`, `cd frontend && npm test && npm run build`.
- Manual: run backend and frontend. Add the note "after gym I can't focus on study" and search "tired after workout": the note is first. Restart the backend and confirm past days were indexed (log line, and DAY results for a search like "postponed").
