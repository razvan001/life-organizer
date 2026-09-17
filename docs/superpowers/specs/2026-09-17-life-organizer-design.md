# Life Organizer — Design Spec

- **Date:** 2026-09-17
- **Status:** Approved design, pending spec review
- **Stack:** React (TypeScript) + Spring Boot (Java 21) + Postgres/pgvector + Claude API + Voyage AI embeddings

## Context
Greenfield app (empty folder `C:\Users\razva\projects\life-organizer`, no git yet). User wants a personal planner that doubles as an AI/RAG showcase (their own goals include "Claude certification" and "RAG showcase"):
- Enter a **goals tree** (Category → Goal) and **constraints** (fixed hours + weekly quotas), as structured text or as a free-form brain dump.
- Get a **"Today's plan"** timeline (07:00 Gym, 08:30 Work, 17:00 Dinner, 18:00 Claude cert 60m, 19:00 Free, 20:00 Social, 22:30 Wind down).
- **Replan** when the day changes (padel canceled) and **postpone** tasks, still respecting goals.
- AI layer: LLM extraction, planning agent, prioritization, conflict resolution, natural-language commands, RAG over personal notes and plan history.

Decisions made with the user:
| Topic | Choice |
|---|---|
| Scheduling core | Deterministic Java engine does all time placement and conflict detection |
| AI role | Claude planning agent **orchestrates the engine through tools**. It never writes the schedule directly; every change is a proposal the user applies |
| Users/deploy | Single user, localhost, no auth |
| Goal time split | Priority-weighted split of category quota + deadline boost |
| Postpone | Later today → next day with room this week → flag after 3 postpones, suggest 25m version |
| RAG | Postgres + pgvector, Voyage AI embeddings. Sources: in-app notes + plan history |
| Phasing | Phase 1 engine MVP → Phase 2 LLM extraction + NL commands → Phase 3 RAG + planning agent |

## Stack
- **Backend:** Java 21, Spring Boot (current from start.spring.io), Maven, Spring Web, Data JPA, Flyway, Validation, `@Scheduled`.
- **DB:** Postgres 17 + pgvector via `docker-compose.yml` (image `pgvector/pgvector:pg17`). Testcontainers in tests.
- **Claude:** `com.anthropic:anthropic-java` (2.34.0 or latest). Model `claude-opus-5` (config `app.ai.model`); effort `low` for extraction, `medium` for the agent; server-side refusal fallbacks on; check `stop_reason` before reading output.
- **Embeddings:** Voyage AI REST (`POST https://api.voyageai.com/v1/embeddings`) through Spring `RestClient`. Model + dimension configurable (`app.rag.embedding-model`, `app.rag.dimensions`, default 1024). Verify current model name at build time.
- **Frontend:** plain React + TypeScript. No data-fetching or state library: `api/client.ts` wraps `fetch`, pages use `useState`/`useEffect`, and a small `useApi` hook handles loading/error. React Router for 3 pages, plain CSS. Vite is used only as dev server/build (`npm run dev`, `npm run build`), with its proxy sending `/api` → `:8080`. The README explains these commands for someone new to the tooling.
- **Tests:** JUnit 5 + AssertJ, `@WebMvcTest`, Testcontainers (Postgres/pgvector), Vitest + React Testing Library (Vitest comes with the Vite setup).

## Layout
```
life-organizer/
├── docker-compose.yml
├── backend/src/main/java/com/lifeorganizer/
│   ├── domain/   Goal, Category, Task, Constraint (sealed), Block, DayPlan, Note, Conflict
│   ├── engine/   pure Java: GoalWeighting, WeekAllocator, DayScheduler, Replanner,
│   │             PostponePolicy, ConflictDetector, PlanEvent (sealed), EngineSettings
│   ├── setup/    GoalTreeParser, ConstraintParser, SetupService, SetupController
│   ├── plan/     PlanService, PlanController, ProposalService, JPA entities/repos
│   ├── ai/       ClaudeClientConfig, SetupExtractor, CommandInterpreter,
│   │             agent/PlanningAgent, agent/tools/*
│   └── rag/      VoyageEmbeddingClient, Chunker, MemoryIndexer, MemorySearch,
│                 PlanHistorySummarizer, NoteController
├── backend/src/main/resources/db/migration/V1__core.sql, V2__tasks.sql, V3__rag.sql
├── frontend/src/ pages/SetupPage, TodayPage, NotesPage; components/Timeline, BlockCard,
│                 AssistantPanel, ProposalCard, ConflictCard; api/client.ts, api/useApi.ts,
│                 api/types.ts (TypeScript mirrors of backend DTOs)
└── docs/superpowers/specs/2026-09-17-life-organizer-design.md
```

## Domain model
- **Goal**: id, category, name, priority 1–5, deadline?, defaultBlockMinutes (60), energy HIGH/MEDIUM/LOW, preferredTime MORNING/EVENING/ANY.
- **Task** (Phase 2): one-off item under a goal (for example "Write RAG README"), estimateMinutes, dueDate?, status. Scheduled as units inside the goal's time budget, earliest due first.
- **Constraint** (sealed): `FixedBlock(label,start,end,days)`, `FrequencyQuota(label,timesPerWeek,sessionMinutes,goalId)`, `DurationQuota(label,minutesPerWeek,categoryId)`, `EveningQuota(label,eveningsPerWeek,categoryId)`.
- **Routine defaults** (EngineSettings): Dinner 60m after work, Wind down 60m before sleep, min block 30m, 30m grid.
- **Block**: id, date, start, end, title, kind FIXED/ROUTINE/GOAL/TASK/FREE/ADHOC, goalId?, taskId?, status PLANNED/DONE/CANCELLED/POSTPONED, postponeCount, flagged, reason?.
- **DayPlan**: date, blocks, warnings. **Conflict**: type (OVERLAP, QUOTA_UNFITTABLE, DEADLINE_AT_RISK), affected items, numbers.
- **BlockEvent log**: every Done/Postpone/Cancel with timestamp + optional reason (feeds plan history RAG).
- **Proposal**: id, source (agent/command), events[], priorityChanges[], rationale, simulated plan diff, status PENDING/APPLIED/REJECTED.

## Phase 1 — Engine MVP (no AI needed)
**Parsers:** `GoalTreeParser` reads `├──/└──/│` or indent trees (depth 1 category, depth 2 goal). `ConstraintParser` regexes: `HH:MM[–-]HH:MM` → FixedBlock (Work Mon–Fri, others daily), `(\d+)x/week`, `(\d+(\.\d+)?)h/week`, `(\d+) evenings?/week`; label → category by keyword map (learning→Career, side project→Projects, gym→Health/Gym, social→Social) or name match. Unmatched lines → warnings (Phase 2 sends them to the LLM).

**Engine** (pure functions, injected `Clock`):
1. **GoalWeighting** — weight = priority × boost (×2 deadline ≤ 7d, ×1.5 ≤ 30d); split category minutes on 30m grid, largest remainder.
2. **WeekAllocator** (Mon–Sun) — quotas → units, assigned to days by capacity. Frequency units avoid consecutive days. Duration units spread evenly, max one block per goal per day, bigger chunks to high-capacity days. DONE units count against quota.
3. **DayScheduler** — sort units by deficit ratio, weight, postponeCount; place by preferredTime then energy; leftover gaps ≥ 30m become FREE.
4. **ConflictDetector** — overlaps, unfittable quotas, deadlines at risk → `Conflict` list (Phase 1 shows as warnings; Phase 3 the agent resolves).
5. **Replanner** — minimal change: DONE/fixed/past blocks never move, unaffected blocks stay. Events: `CancelBlock`, `PostponeBlock(reason?)` (via PostponePolicy), `MarkDone`, `SetEnergy(LOW|NORMAL)`, `AddAdhoc(title,start,end)`. Returns new plan + change list. `Replanner.simulate(events)` = same without saving (used by agent).

**Golden test:** user's exact goals + constraints on a weekday with gym + social allocated produce the exact example timeline.

**API:** `POST /api/setup/parse`, `PUT/GET /api/setup`, `GET /api/plan/today`, `POST /api/plan/today/events`, `GET /api/config` (`aiEnabled`, `ragEnabled`). Errors → ProblemDetail.

**UI:** SetupPage (two textareas prefilled with the example, parse preview with priority selects and category dropdown for unmapped labels, Save). TodayPage (timeline; GOAL/TASK blocks: Done / Postpone (optional reason) / Cancel; energy toggle; Add event form; change toast; flagged "Postponed 3× — try 25m?").

## Phase 2 — LLM extraction + natural-language commands
- **SetupExtractor**: "Brain dump" textarea → Claude structured output (`MessageCreateParams.builder().outputConfig(ExtractedSetup.class)`) → goals, constraints, tasks with deadlines. Result goes into the **same parse preview** as the regex parser, so the user confirms before save. Regex parser stays first for well-formed text; LLM handles unmatched lines and free-form input.
- **CommandInterpreter**: `POST /api/assistant/command {text}` → structured `PlanEvent[]` + task/goal edits, with context = now + today's blocks (ids, times, titles). Server validates ids/times, runs `Replanner.simulate`, stores a **Proposal** with a diff. UI ProposalCard: "Cancel Padel 19:00 · energy low → Claude cert moved to Thu, Chess 19:00" → Apply / Reject. `POST /api/proposals/{id}/apply|reject`.
- No `ANTHROPIC_API_KEY` → `aiEnabled:false`, AI UI hidden, Phase 1 flows unchanged.

## Phase 3 — RAG + planning agent
**RAG (`rag/`):**
- `notes` table (id, title, body, tags, createdAt) + NotesPage (CRUD).
- `memory_chunks` table: id, sourceType NOTE/PLAN_HISTORY, sourceId, date, text, `embedding vector(1024)`, HNSW index (cosine).
- `MemoryIndexer`: on note save → `Chunker` (~400 tokens, 50 overlap) → Voyage embeddings (`input_type: document`) → upsert chunks; delete old chunks for that note.
- `PlanHistorySummarizer`: `@Scheduled` nightly (and catch-up on startup) builds a deterministic text summary per past day from BlockEvent log ("Thu 2026-09-17: Gym done 07:00; Claude cert postponed 18:00, reason: tired after gym; Social done") → indexed.
- `MemorySearch.search(query, k, sourceTypes?, dateFrom?)`: Voyage query embedding (`input_type: query`) → pgvector cosine top-k with metadata filters.

**Planning agent (`ai/agent/`):** Claude tool-use loop (SDK tool runner or manual loop, max 10 iterations, token usage logged). System prompt: role, "never assume an applied change; always simulate, then propose", today's date. Tools:
| Tool | Purpose |
|---|---|
| `get_today_plan` / `get_week_progress` | Current blocks, quota done vs target, postpone stats |
| `list_goals_and_tasks` | Goals with priority, deadlines, open tasks |
| `search_memory(query, k, source?, since?)` | RAG over notes + plan history, retrieved before planning |
| `detect_conflicts` | ConflictDetector output for today/week |
| `simulate_events(events)` | Engine dry run → plan diff, new conflicts, quota impact |
| `propose(events, priorityChanges, rationale)` | Terminal tool: stores a Proposal, ends loop |

Capabilities it covers:
- **NL commands:** Assistant panel chat ("padel canceled, feeling tired") → searches memory (for example the note "tired → chess or light reading works"), simulates alternatives, proposes.
- **Prioritization:** "Plan my week" / weekly review → reads progress + deadlines + history ("Claude cert postponed 5× on Fridays") → proposes priority changes and task ordering, with rationale.
- **Conflict resolution:** when `detect_conflicts` is non-empty, TodayPage shows ConflictCard with "Ask assistant". The agent returns 2–3 simulated options (drop 1 gym, shorten learning to 45m, move social to Sat) as separate proposals; the user picks one.
- Endpoint `POST /api/assistant/chat {conversationId, message}` → assistant text + proposals. Conversation history kept in memory per session (single user), append-only.

**Guardrails:** agent tools are read-only or simulate-only; only `/apply` mutates. Tool inputs validated against engine rules; invalid → `tool_result` with `is_error`. Missing `VOYAGE_API_KEY` → `ragEnabled:false`, `search_memory` tool not registered.

## Build order
0. `git init`, write spec to `docs/superpowers/specs/2026-09-17-life-organizer-design.md`, commit, user reviews spec, then superpowers:writing-plans for a task-level plan per phase.
1. Scaffold: docker-compose (pgvector), Spring Boot backend, Vite frontend, `.gitignore`, README with run commands.
2. Phase 1: domain + parsers (TDD with user's texts) → engine (golden test, replan, postpone, conflicts) → Flyway V1 + services + controllers → Setup + Today UI.
3. Phase 2: Task entity (V2) → SetupExtractor → CommandInterpreter + Proposal flow → brain dump UI + ProposalCard.
4. Phase 3: V3 pgvector tables → Voyage client + indexer + search → notes UI → plan history summarizer → agent tools + loop → AssistantPanel + ConflictCard.

## Verification
- `docker compose up -d` then `cd backend && ./mvnw test`: parser tests, golden day test, replan tests (cancel padel fills slot; postpone later today then next day; 3rd postpone flags; DONE never moves), conflict detection tests, Proposal apply/reject, invalid AI event rejected.
- Agent tests with a stubbed Claude client returning scripted `tool_use` blocks: verifies tool dispatch, simulate-before-propose, iteration cap, `is_error` on invalid input.
- RAG tests (Testcontainers pgvector, stubbed embedding client with fixed vectors): chunking, upsert/delete on note edit, top-k ordering, source/date filters.
- Optional live smoke tests gated by `ANTHROPIC_API_KEY` / `VOYAGE_API_KEY` env vars.
- `cd frontend && npm test`: timeline actions, ProposalCard apply/reject.
- End-to-end in browser pane (`./mvnw spring-boot:run` + `npm run dev`, `http://localhost:5173`):
  - Phase 1: paste example texts, Today matches example. Add Padel 19:00–20:30, cancel it, and confirm the slot fills. Postpone 3× and confirm the flag.
  - Phase 2: brain dump extracts goals + tasks. "padel canceled, feeling tired" shows a proposal that you can Apply.
  - Phase 3: add note "after gym I can't focus on study", ask "plan my evening". The answer cites the note, and the proposal avoids study after gym. Force a quota overflow and confirm ConflictCard shows 3 options.
