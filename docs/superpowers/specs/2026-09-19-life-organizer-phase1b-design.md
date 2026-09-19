# Life Organizer — Phase 1b Design Spec

- **Date:** 2026-09-19
- **Status:** Approved design, pending spec review
- **Builds on:** Phase 1a engine (`docs/superpowers/specs/2026-09-17-life-organizer-design.md`, `docs/superpowers/plans/2026-09-17-life-organizer-engine.md`) — pure-Java domain model, parsers, and scheduling engine, already implemented and tested (73 tests) in `backend/src/main/java/com/lifeorganizer/{domain,setup,engine}`.

## Context

Phase 1a built the scheduling engine as pure Java: given a `GoalTree` and a list of `Constraint`s, it can weight goals by priority, allocate weekly quotas into daily `Unit`s, schedule a day's `Unit`s into free time around fixed/routine blocks, detect conflicts, and apply postpone/cancel/mark-done/add-adhoc events to an in-memory `Map<LocalDate, DayPlan>`. None of that is reachable yet outside a JUnit test — there is no database, no HTTP endpoint, and no UI.

Phase 1b turns that engine into the actual app described in the original design spec's Phase 1 API/UI section: paste goals + constraints once, see today's plan, and interact with it (Done / Postpone / Cancel) through a browser. This is the first version of the app a person can actually open and use day to day.

## Decisions made with the user

| Topic | Choice |
|---|---|
| Persistence granularity | **Persist generated `Block` rows**, not just Goals/Constraints. Once a day is scheduled, its blocks (with status/postponeCount/flagged) are stored and only change via replan events — a postpone/cancel/done survives a restart and doesn't "un-happen" on refresh. |
| Database | **Postgres via Docker** (`docker-compose.yml`), matching the original spec (Postgres+pgvector is needed later for Phase 3's RAG) — avoids a later migration. Testcontainers (Postgres) in tests. |
| Generation trigger | **Lazy, per-day.** The first `GET /api/plan/today` (or any date) that finds no persisted `Block` rows for that date runs the Phase 1a engine for that single day and persists the result. A day that already has rows is never regenerated or silently discarded — editing Setup only affects days not yet generated. |
| Constraint storage | One `constraint` table with a `type` discriminator column, via JPA single-table inheritance — mirrors the sealed `Constraint` interface (`FixedBlock`/`FrequencyQuota`/`DurationQuota`/`EveningQuota`) directly. |
| Mapping layer | Small static mapper classes (domain record ⇄ JPA entity), no MapStruct — keeps the dependency list unchanged from Phase 1a's stack. |
| API surface | Unchanged from the original spec: `POST /api/setup/parse`, `PUT/GET /api/setup`, `GET /api/plan/today`, `POST /api/plan/today/events`, `GET /api/config`. Errors → RFC 7807 `ProblemDetail`. |
| Frontend | Unchanged from the original spec: plain React + TypeScript, no state/data-fetching library, `api/client.ts` + a small `useApi` hook, React Router, plain CSS. Vite only as dev server/build. |
| UI scope | `SetupPage` (paste → parse preview → save) and `TodayPage` (timeline with Done/Postpone/Cancel, energy toggle, add-event form, change toast) — the two pages already scoped for Phase 1, nothing more (no Week view, no Notes, no assistant chat — those are Phase 2/3). |

## Backend design

### Package layout

```
backend/src/main/java/com/lifeorganizer/
├── domain/, setup/, engine/     (Phase 1a — unchanged)
├── persistence/
│   ├── CategoryEntity, GoalEntity
│   ├── ConstraintEntity (+ FixedBlockEntity, FrequencyQuotaEntity, DurationQuotaEntity, EveningQuotaEntity — single-table inheritance)
│   ├── BlockEntity
│   ├── CategoryRepository, GoalRepository, ConstraintRepository, BlockRepository (Spring Data JPA)
│   └── mapper/ — GoalMapper, ConstraintMapper, BlockMapper (static methods, record ⇄ entity, both directions)
├── plan/
│   ├── PlanService — orchestrates: load persisted Goals/Constraints, lazy-generate a day via the Phase 1a engine, persist the result, apply replan events and persist the diff
│   ├── PlanController — `GET /api/plan/today`, `POST /api/plan/today/events`
│   ├── dto/ — TodayPlanResponse, ApplyEventRequest, ApplyEventResponse (JSON shapes, not domain records)
├── setup/api/ *(new sub-package, distinct from Phase 1a's `com.lifeorganizer.setup` parsers)*
│   ├── SetupService — runs `GoalTreeParser`/`ConstraintParser`, persists on save, loads for `GET`
│   ├── SetupController — `POST /api/setup/parse`, `PUT/GET /api/setup`
│   └── dto/ — SetupParseRequest, SetupParseResponse, SetupSaveRequest
├── config/
│   ├── ConfigController — `GET /api/config`
│   └── GlobalExceptionHandler — `@RestControllerAdvice`, maps exceptions to `ProblemDetail`
└── BackendApplication (existing)
```

### Schema (Flyway `V1__phase1b_schema.sql`)

- `category(id VARCHAR PK, name VARCHAR)`
- `goal(id VARCHAR PK, category_id FK, name VARCHAR, priority INT, deadline DATE NULL, default_block_minutes INT, energy VARCHAR, preferred_time VARCHAR)`
- `constraint(id BIGSERIAL PK, type VARCHAR, label VARCHAR, start_time TIME NULL, end_time TIME NULL, days VARCHAR NULL` *(comma-joined `DayOfWeek` names, only for `FIXED_BLOCK`)*`, times_per_week INT NULL, session_minutes INT NULL, goal_id FK NULL, minutes_per_week INT NULL, category_id FK NULL, evenings_per_week INT NULL)` — nullable columns unused by a given `type` stay null; this is the standard single-table-inheritance shape and is deliberately a little wide rather than four separate joined tables, since there are only four variants and no query needs to join across them.
- `block(id VARCHAR PK, date DATE, start_time TIME, end_time TIME, title VARCHAR, kind VARCHAR, goal_id FK NULL, status VARCHAR, postpone_count INT, flagged BOOLEAN, reason VARCHAR NULL)`

Single-user app: no `user_id` column anywhere in Phase 1b (matches "Users/deploy: single user, local, no auth" from the original spec). A `user_id` column can be added later without a rewrite if that ever changes.

### PlanService — the core of this phase

```java
DayPlan getOrGenerate(LocalDate date)
```
1. Load persisted `Block`s for `date`. If any exist, map to a `DayPlan` and return it.
2. Otherwise: load all `Goal`s and `Constraint`s, run `WeekAllocator.allocate(goals, constraints, mondayOf(date))` to get the week's `Unit`s, run `DayScheduler.schedule(date, unitsFor(date), constraints, List.of())`, persist every resulting `Block`, return the `DayPlan`.

```java
ReplanResult applyEvent(PlanEvent event)
```
1. Load the persisted `Block`s for every date the event could touch: the event's own date, plus (for `PostponeBlock`) every later date in the current week, generating any not-yet-generated day first via `getOrGenerate` so `Replanner` always sees a complete `Map<LocalDate, DayPlan>` for the week it's working in.
2. Call `Replanner.apply(weekPlans, event)`.
3. Diff the returned plans against what was loaded and persist inserts/updates/deletes of `Block` rows accordingly (a postpone can add a new row, mark the old one `POSTPONED`, and split a `FREE` row — all three need to be written).
4. Return the `ReplanResult` (used by `PlanController` to build the HTTP response).

Only the days actually touched are written — this keeps the "lazy, per-day" guarantee: `applyEvent` on Monday doesn't force-generate Friday unless a postpone genuinely needs to look there for room.

### API contracts

- `POST /api/setup/parse` — body `{goalsText, constraintsText}` → `SetupParseResponse{tree: {categories, goals}, constraints, warnings}`. Pure parse, no save.
- `PUT /api/setup` — body is the (possibly user-edited) parsed tree + constraints → persists Goals/Categories/Constraints, replacing whatever was there before. Does **not** touch any already-generated `Block` rows.
- `GET /api/setup` — returns the currently persisted tree + constraints, to prefill the Setup page's textareas/preview on revisit.
- `GET /api/plan/today` — `PlanService.getOrGenerate(today)`, mapped to `TodayPlanResponse{date, blocks[], warnings[]}`.
- `POST /api/plan/today/events` — body is one JSON-tagged `PlanEvent` (`{"type":"POSTPONE_BLOCK","blockId":"...","reason":"..."}` etc.) → `PlanService.applyEvent`, returns `{date, blocks[], changes[]}`.
- `GET /api/config` — `{aiEnabled: false, ragEnabled: false}` (both hardcoded false until Phase 2/3 add the Claude/Voyage clients — this is what lets the frontend hide AI-only UI).

Errors: no Setup saved yet → `GET /api/plan/today` returns `404` with a `ProblemDetail` the frontend uses to redirect to Setup. A `PlanEvent` referencing an unknown `blockId` → `422` `ProblemDetail`.

## Frontend design

```
frontend/src/
├── api/
│   ├── client.ts       — fetch wrapper (base URL, JSON, error → typed ApiError)
│   ├── useApi.ts        — small hook: {data, loading, error} + a refetch function
│   └── types.ts         — TS mirrors of the backend DTOs above
├── pages/
│   ├── SetupPage.tsx     — two textareas (prefilled with the example on first visit), Parse button → preview panel (categories/goals with priority <select>, constraints list, warnings, a category <select> for any unmapped label), Save button
│   └── TodayPage.tsx     — fetches `/api/plan/today` on mount; if 404, redirects to Setup
├── components/
│   ├── Timeline.tsx      — renders blocks sorted by start time
│   ├── BlockCard.tsx     — one block; Done/Postpone/Cancel buttons for GOAL/ADHOC kinds, Postpone opens a small reason `<input>`
│   └── ChangeToast.tsx   — shows `changes[]` from the last event response for a few seconds
├── App.tsx               — React Router: `/setup`, `/` (Today)
└── main.tsx
```

**Scope note:** no energy toggle or "Add event" form in this first slice of Phase 1b. `SetEnergy` and `AddAdhoc` already exist in the Phase 1a engine and `POST /api/plan/today/events` accepts both event types from day one — only their UI affordances (a toggle, a small form) are deferred to a quick follow-up once Done/Postpone/Cancel are proven working end-to-end in the browser. This keeps the first vertical slice small without narrowing the backend contract.

## Testing

- `PlanServiceTest` — `@DataJpaTest` or Testcontainers-backed, proving: first `getOrGenerate` call persists rows and a second call returns the same rows without re-running the engine; `applyEvent(PostponeBlock)` correctly persists the new/updated/split rows across two different dates.
- `@WebMvcTest` for `SetupController`/`PlanController`/`ConfigController` — mock `PlanService`/`SetupService`, verify JSON shapes and status codes (200/404/422).
- Mapper unit tests (`GoalMapperTest`, `ConstraintMapperTest`, `BlockMapperTest`) — round-trip a domain record through `toEntity`/`toDomain` and assert equality, one test per `Constraint` subtype.
- Frontend: Vitest + React Testing Library for `Timeline`/`BlockCard` button actions (mocked `fetch`).
- End-to-end (manual, browser pane): `docker compose up -d`, `./mvnw spring-boot:run`, `npm run dev` — paste the example goals/constraints, Save, confirm Today matches the Phase 1a golden-test shape, click Postpone on a block, confirm it moves and the toast shows the change, click Cancel on an ad-hoc block, confirm a Free slot appears in its place, restart the backend, reload Today, confirm state survived.

## Verification

- `docker compose up -d && cd backend && ./mvnw test` — all Phase 1a tests still green, plus the new Phase 1b tests above.
- `cd frontend && npm test`.
- The manual end-to-end steps above.
