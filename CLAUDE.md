# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Personal day planner: goals + constraints in, a daily time-blocked plan out, re-planned on events; an optional
LLM layer (Groq) and a RAG memory (local embeddings + pgvector). Single user, localhost, no auth. Spring Boot 4.1 /
Java 21 backend in `backend/`, React 19 + TypeScript + Vite frontend in `frontend/`. `README.md` has the full API
table and user-facing behavior.

## Commands

```bash
docker compose up -d                          # Postgres 17 + pgvector on 5432 (needed to run the app AND for tests)
cd backend && ./mvnw spring-boot:run          # http://localhost:8080 (Windows PowerShell: .\mvnw.cmd)
cd backend && ./mvnw test                     # full suite; Testcontainers, so Docker must be running
cd backend && ./mvnw test -Dtest=NoteServiceTest              # one class (quote globs: -Dtest='Memory*Test')
cd backend && ./mvnw test -Dtest='NoteServiceTest#anEmptyBodyIsRejected'   # one method

cd frontend && npm install && npm run dev     # http://localhost:5173; Vite proxies /api to :8080 (no CORS)
cd frontend && npm test                       # Vitest once
cd frontend && npx vitest run src/pages/NotesPage.test.tsx    # one file (add -t "name" for one test)
cd frontend && npm run build                  # tsc -b type-check + production bundle; the only "lint"
```

- `GROQ_API_KEY` in the backend's environment turns on the AI features; without it they are hidden and return 503.
- Tests that need no Docker: engine/domain/parser unit tests, `@WebMvcTest` controller tests, Mockito tests,
  `MiniLmTextEmbedderTest`. Everything named `*Repositor*`, `*Store*`, `*EndToEnd*`, `@DataJpaTest` or
  `@SpringBootTest` needs Docker.
- `LiveGroqSmokeTest` is skipped unless a real key is set.

## Backend architecture (`backend/src/main/java/com/lifeorganizer`)

- `domain/` and `engine/` are framework-free. The pipeline is `WeekAllocator` (weekly quotas → units per day,
  priority-weighted with deadline boost) → `DayScheduler` (places units into free time, leftover gaps become
  `FREE` blocks) → `Replanner` (applies one `PlanEvent`: `CancelBlock`, `PostponeBlock` via `PostponePolicy`,
  `MarkDone`, `SetEnergy` (a no-op today), `AddAdhoc`). Wired as beans in `config/EngineConfig`, which also
  provides the `Clock` bean; always use the injected `Clock` for "today".
- `plan/PlanService` orchestrates engine + persistence. **Days are generated lazily** on first request
  (`getOrGenerate`, guarded by a Postgres advisory lock) and never regenerated (except `replanUntouchedDays`, which re-generates untouched days later this week after a priority change); `loadDaysFor` also generates the
  rest of the week for a postpone. Consequence: any method whose call chain can reach `getOrGenerate`/
  `loadDaysFor` must NOT be `@Transactional(readOnly = true)` — Postgres rejects the insert (this shipped as a
  500 once). `simulateEvents` (preview, never persists) and `applyEvents` (atomic batch) are both plain
  `@Transactional`. Replan results are persisted by diffing old vs new blocks (`persistence/PlanStore`).
- `block.id` is a **global** primary key (ids embed the date, e.g. `free-2026-09-27-08:00`); duplicate ids get
  `~2`, `~3` suffixes. Test fixtures must not reuse a block id across dates.
- `setup/` parses the pasted text (`GoalTreeParser`, `ConstraintParser`); `setup/api/SetupDtoMapper` is the
  DTO ↔ domain boundary and enforces the 255-char column limits.
- `persistence/` — JPA entities + `SetupStore` / `PlanStore`. Schema is Flyway (`resources/db/migration`),
  Hibernate runs `ddl-auto=validate`, so every schema change needs a new `V<n>__*.sql`.
- `ai/` — optional Groq layer. `LlmClientConfig` creates the client beans only when `GROQ_API_KEY` is set, so
  consumers inject `ObjectProvider<...>` and throw `AiNotConfiguredException` (503) when absent.
  `GroqChatClient` is the single place that speaks Groq's OpenAI-compatible HTTP API (plain `RestClient`, no
  SDK) with a **hand-written strict JSON schema** per use (strict mode: every property listed in `required`,
  nullable via `["type","null"]`). Error split: `RestClientException` → `AssistantUnavailableException` (502);
  an unusable model reply (`MalformedLlmReplyException`) is downgraded to a normal 200 "not understood" /
  warning, never an HTTP error. Model output is never trusted: `ProposedEventValidator` validates block ids
  (only today's, never `FREE` blocks) and runs each event through `ApplyEventRequest.toEvent`;
  `SetupExtractionService` defaults or drops invalid values with warnings. The planning agent is
  `AgentRunner` (tool-calling loop, max 8 model calls, tools in `ai/agent/tools`, validation in
  `ProposedEventValidator`). Every model call resends the prompt and all tool definitions (about 2.5K tokens)
  and Groq's free tier allows 8K tokens per minute, so model calls per turn are scarce: a tool with a
  `contextLabel()` (today's plan, week progress) is run up front and put into a "Current data" message instead
  of being offered to the model, and `ReplyGuard` sends back a reply that claims or spells out a change without
  a recorded proposal. `ChatService` / `ChatStore` keep a per-day `chat_message` thread with proposal
  statuses, and `send` runs outside any transaction (LLM calls take seconds). Proposals are applied via
  `POST /api/assistant/chat/messages/{id}/apply` (propose = `simulateEvents`). A proposal is `PLAN` (events on
  today) or `PRIORITIES` (weekly review via `propose_priorities`; Apply runs `SetupStore.updatePriorities` then
  `PlanService.replanUntouchedDays`, which drops and regenerates stored days later this week that have only
  untouched PLANNED engine blocks).
- `memory/` — RAG. `TextEmbedder` (384 dims) is implemented by `MiniLmTextEmbedder` (quantized all-MiniLM-L6-v2
  via langchain4j ONNX, in-process, always on). `MemoryChunkStore` is plain `JdbcTemplate` SQL on the pgvector
  `memory_chunk` table (vectors passed as `'[..]'::vector` literals, cosine `<=>`, HNSW index); its writes join
  the surrounding JPA transaction, so a failed index rolls back a note save. `Chunker` → `MemoryIndexer`
  (notes re-indexed on every write; days skipped when the content hash is unchanged). `PlanHistoryIndexer`
  summarizes past days (`DaySummarizer`) at startup and nightly; it must read via `PlanStore.findDay` only and
  never call `PlanService.getOrGenerate`. `MemorySearch` backs the planning agent's
  `search_memory` tool; filtered searches use pgvector iterative scan, and results are one hit per source.
- Errors: `config/GlobalExceptionHandler` maps app exceptions to RFC 7807 problem+json (400 invalid, 404 setup
  required / note not found, 409 proposal not available, 422 unknown block, 502, 503) and extends `ResponseEntityExceptionHandler` so
  framework errors keep their status.
- JSON is Jackson 3 (`tools.jackson.*`); langchain4j brings Jackson 2 (`com.fasterxml.*`) transitively — use
  the Jackson 3 packages in app code.

## Frontend architecture (`frontend/src`)

- No state library, no data-fetching library. `api/client.ts` is the only place that knows URLs (`api.*`
  functions over a `send<T>` fetch wrapper that throws `ApiError` from problem+json; 204 → `undefined`);
  `errorMessage()` turns failures into UI text. `api/types.ts` mirrors backend DTOs by hand.
- `api/useApi.ts` loads data on mount (`{data, error, loading, refetch, setData}`) and drops stale responses;
  pass it a stable function such as `api.getToday`.
- Pages: `TodayPage` (timeline, block actions, `ChatPanel` when `config.aiEnabled`), `SetupPage` (regex
  parse, saved setup, and brain-dump extraction all feed one `SetupPreview`), `NotesPage` (notes CRUD + memory
  search). Confirmations use the shared `ChangeToast`.
- Tests: Vitest + Testing Library; `test/mockApi.ts` stubs `fetch` with routes keyed `"METHOD /path?query"`
  and fails on any unexpected request, so a page that also fetches `/api/config` needs that route too.

## Testing conventions

- Every Testcontainers test uses `new PostgreSQLContainer("pgvector/pgvector:pg17")` with `@ServiceConnection`.
  `@DataJpaTest` tests roll back per test; `@SpringBootTest` tests truncate tables in `@BeforeEach`.
- Fix "today" with a `@TestConfiguration` `@Primary Clock` bean (see `ApiEndToEndTest`).
- Memory tests use the deterministic test-only `support/HashingTextEmbedder` instead of the real model;
  `MiniLmTextEmbedderTest` and `MemoryApiEndToEndTest` exercise the real model.
- A `@Transactional` rollback or `readOnly` problem cannot be observed inside a `@DataJpaTest` (its ambient
  transaction masks it); prove such behavior in a `@SpringBootTest`.

## Working agreements

- The user wants to see changes before every commit: stage, show `git diff --cached --stat`, and wait for
  approval (`git commit` is on the ask-list in `.claude/settings.local.json`). Work happens on `master`.
- Features go through `docs/superpowers/specs/` (design) and `docs/superpowers/plans/` (task plans); read the
  relevant spec before changing a feature. Phase 2 docs still say Claude/Anthropic; the backend was migrated to
  Groq afterwards.
- Next planned work: Phase 3b slice 3, conflict resolution (`ConflictDetector` is not wired yet; see the original
  design spec `2026-09-17-life-organizer-design.md`).
