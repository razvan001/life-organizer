# Life Organizer — Phase 2 (Natural-Language Commands) Design Spec

- **Date:** 2026-09-22
- **Status:** Approved design, pending spec review
- **Builds on:** Phase 1a engine and Phase 1b backend/frontend (`docs/superpowers/specs/2026-09-17-life-organizer-design.md`, `docs/superpowers/specs/2026-09-19-life-organizer-phase1b-design.md`) — the persisted setup, the lazy per-day plan, the REST API and the React UI are done and in daily use.

## Context

The original design's Phase 2 has two fairly independent pieces: brain-dump extraction (free-form text → structured setup) and natural-language commands (a sentence → a reviewable plan change). This spec covers **only natural-language commands** — the more distinctive half, and the one that demonstrates the project's core AI idea: the engine places time and enforces the rules; Claude only *proposes* changes, which the user reviews and applies. Brain-dump extraction gets its own spec later.

Today, changing the plan because something changed in real life ("padel got canceled", "I'm tired, push the gym session") means finding the right block in the Today timeline and clicking Postpone/Cancel/Done by hand. This phase adds a text box: describe what happened in plain language, get back a proposed change with a one-line reason, and apply it with one click — or reject it and nothing happens.

## Decisions made with the user

| Topic | Choice |
|---|---|
| Phase 2 split | This spec covers NL commands only; brain-dump extraction is a separate later spec |
| Proposal storage | **Stateless.** The server computes a proposal per request and returns it; nothing is written to the database until Apply. No `Proposal` entity, no status column, no apply/reject endpoints keyed by id. |
| Command scope | Only the 5 events the engine already supports, applied to **today's plan**: `CANCEL_BLOCK`, `POSTPONE_BLOCK`, `MARK_DONE`, `SET_ENERGY`, `ADD_ADHOC`. No goal-priority edits (that belongs with the Phase 3 planning agent's weekly review, not a one-off command). |
| Safety | Claude never mutates anything. A command produces events; the events are simulated (not saved) to build the proposal; only an explicit Apply call persists them, and it persists exactly the events the user was shown. |
| Apply mechanism | A new, generically useful `POST /api/plan/today/events/batch` endpoint applies a list of events atomically (all or nothing), reused by both a possible future multi-event UI action and this feature's Apply button. |
| AI gating | Unchanged from the original spec: no `ANTHROPIC_API_KEY` → `GET /api/config` reports `aiEnabled:false` → the assistant box does not render → Phase 1 flows are untouched. |
| Model | `claude-sonnet-5`, configurable via `app.ai.model`. Verified against the current Anthropic Java SDK in a scratch project before the implementation plan is written (the same risk-verification approach used for Phase 1b's Boot 4/Testcontainers assumptions) — this spec describes the contract, not the exact SDK call syntax. |
| Event volume | A proposal is capped at 5 events; more is treated as "not understood" (bounds the blast radius of one sentence). |

## Backend design

### Package layout

The rest of the backend is organized by feature (`plan/`, `setup/`, `persistence/`) and stays that way — this spec doesn't touch it. The new `ai/` package alone is organized by technical layer (controller → service → client), so the Anthropic SDK never leaks past `ai/client/`:

```
backend/src/main/java/com/lifeorganizer/
├── domain/, setup/, engine/, persistence/, plan/, setup/api/, config/   (unchanged, feature-organized)
└── ai/
    ├── controller/
    │   └── AssistantController        — HTTP layer only: POST /api/assistant/command, delegates below
    ├── service/
    │   └── CommandInterpreter         — business logic: builds the prompt/context, calls ClaudeCommandClient,
    │                                     validates the result (event count cap, known block ids), never touches HTTP
    ├── client/
    │   ├── ClaudeClientConfig         — Spring @Configuration: builds the Anthropic SDK client bean from
    │   │                                 ANTHROPIC_API_KEY; no key, no bean
    │   └── ClaudeCommandClient        — interface + implementation wrapping the one Anthropic SDK call this
    │                                     feature needs; the only place the SDK's types appear. CommandInterpreterTest
    │                                     stubs this interface, so no real network call happens in the test suite.
    ├── model/
    │   └── InterpretedCommand         — record: understood (boolean), rationale (String, nullable), events (List<PlanEvent>)
    └── dto/
        └── CommandRequest, CommandResponse
```

`ApplyEventsRequest`/`ApplyEventsResponse` (the new batch-apply shapes) live in `plan/dto/` alongside the existing `ApplyEventRequest`/`ApplyEventResponse`, since they belong to `PlanController`, not to the assistant feature — the batch endpoint is generic, plain-engine functionality that the assistant happens to be the first caller of.

### `PlanService` additions

```java
AppliedEvent simulateEvents(List<PlanEvent> events, LocalDate today)
```
Loads the days the events could touch exactly like `applyEvent` does today (reusing `loadDaysFor` per event, in sequence, threading the updated `Map<LocalDate, DayPlan>` from one `Replanner.apply` call into the next), but never calls `planStore.applyChanges`. Returns the would-be resulting today's plan plus the concatenated change messages from all events. Nothing is persisted; a second call with the same events, or a plain `getOrGenerate`, sees the plan unchanged.

```java
AppliedEvent applyEvents(List<PlanEvent> events, LocalDate today)
```
Same loading and chaining, but calls `planStore.applyChanges` for each event's diff inside one `@Transactional` method, so a failure partway through (e.g. the 3rd of 4 events references a block id that a concurrent change removed) rolls back every earlier event in the same call. Returns the same shape as `simulateEvents`.

Both reuse the existing per-event validation and exceptions (`UnknownBlockException` → 422, refusal messages from `PostponePolicy`/`Replanner` → carried in `changes`, exactly as today).

### `ai/client/ClaudeCommandClient`

The only class that imports the Anthropic SDK. Its method takes the already-built prompt/context and returns the raw structured result (or throws on a network/API failure) — it does no validation and knows nothing about `PlanEvent` beyond deserializing into the same shape. This is what makes `CommandInterpreter` unit-testable without a real API call: tests provide a stub/fake implementation of this interface.

### `ai/service/CommandInterpreter`

Input: the user's text, today's date, and today's blocks reduced to `{id, title, kind, status, start, end}` (no other days — commands act on today only, matching engine scope). Builds the prompt, calls `ClaudeCommandClient`, and turns the result into one `InterpretedCommand`:

- `understood: false, message: "..."` when the text doesn't map to any of the 5 event types against a real block (wrong title, ambiguous target, nothing to do), **or**
- `understood: true, rationale: "one sentence", events: [...]` where each event has exactly the shape `ApplyEventRequest` already parses (`type`, `blockId`, `reason`, `date`, `title`, `start`, `end`, `energy`) and every `blockId` is one of the ids Claude was given.

This is where validation of the model's *content* happens, not in the client: reject (fall back to `understood:false`) if any `blockId` wasn't in the context list, if there are more than 5 events, or if the response doesn't parse — a malformed or empty model response is a "not understood" outcome, never a crash or a silently-empty proposal. A `ClaudeCommandClient` failure (network error, rate limit, auth, timeout) is a different kind of problem — connectivity, not content — so `CommandInterpreter` does **not** catch it; it propagates to the controller, which is the only place that turns it into the distinct "assistant unavailable" response (below). This keeps "I couldn't understand your sentence" and "the assistant is unreachable right now" from ever being confused for each other.

### `ai/controller/AssistantController`

Thin HTTP layer: maps the request/response DTOs and status codes, delegates everything else to `CommandInterpreter` and `PlanService`. No business logic here.

- `POST /api/assistant/command` — body `{text}` → `CommandResponse{understood, rationale, message, events[], preview: TodayPlanResponse, changes[]}`. When `understood` is false, `rationale`/`events`/`preview`/`changes` are absent/empty and `message` explains why. Calls `CommandInterpreter` then, if understood, `PlanService.simulateEvents`.
- Errors: `ClaudeCommandClient` throws (network, rate limit, auth) → `502` `ProblemDetail` "Assistant unavailable" — this is a connectivity failure, kept distinct from `CommandInterpreter`'s ordinary "not understood" outcome above. No `ANTHROPIC_API_KEY` configured (defensive — the UI shouldn't call this when `aiEnabled:false`) → `503` "AI features are not configured".

### `PlanController` addition

- `POST /api/plan/today/events/batch` — body `{events: [ApplyEventRequest, ...]}` → `PlanService.applyEvents` → `ApplyEventsResponse{date, blocks[], warnings[], changes[]}` (same shape as today's single-event response, `changes` concatenated in event order). Errors unchanged (`422` unknown block, `400` invalid request) — whichever event fails, none of the batch's changes are persisted.

### `ConfigController`

`aiEnabled` now reflects whether the Claude client bean exists, instead of the hardcoded `false`. `ragEnabled` stays `false` (Phase 3).

## Frontend design

```
frontend/src/
├── api/types.ts        — + CommandRequest/Response, ApplyEventsRequest/Response, ConfigResponse
├── api/client.ts        — + api.getConfig(), api.proposeCommand(text), api.applyEvents(events)
├── components/
│   ├── AssistantBox.tsx  — text input + Send; shows the "not understood" message or a ProposalCard
│   └── ProposalCard.tsx  — rationale + one line per changed block (before → after); Apply / Reject
└── pages/TodayPage.tsx   — fetches /api/config once; renders <AssistantBox> only when aiEnabled
```

- `AssistantBox` owns its own text/proposal/busy state, independent of `TodayPage`'s action state, since a command's propose step never touches the persisted plan.
- **Not understood:** the message renders inline under the input; the typed text stays in the box so the user can edit and resend.
- **Proposal shown:** rationale text, then a diff line per block that differs between the current plan and `preview` (compare by id: new status, new start time, or "new block" for an ADHOC addition) — reusing the same block-status vocabulary `BlockCard` already renders (Planned/Done/Postponed/Cancelled).
- **Apply:** `POST /api/plan/today/events/batch` with the proposal's `events` verbatim (not recomputed from the diff). On success, `TodayPage`'s existing `setData`/`ChangeToast` path takes over exactly as an ordinary action does today; `AssistantBox` clears. On failure (stale block, engine refusal), the existing bottom error box shows the message; the proposal card stays so the user can Reject or retype.
- **Reject:** clears the proposal and the input; no request sent.
- Busy disables Send, Apply and Reject, matching the disabled-while-busy pattern already used for block actions.

## Known limitations (carried forward, not fixed here)

- A proposal can go stale between propose and apply if the user takes another action in between (e.g. postpones the very block the proposal targets); Apply then surfaces the existing `422 Unknown block` or an engine refusal message rather than silently doing something else. Acceptable for a single-user app; the user just asks again.
- No conversation memory — every command is independent, with only today's blocks as context. Multi-turn ("no, the other one") is Phase 3's assistant chat, not this feature.
- No cost/usage display. Token usage isn't logged in this phase (the original spec's "log usage" note applies to the Phase 3 agent's longer tool loop, not a single-shot call).

## Testing

- `CommandInterpreterTest` — a fake `ClaudeCommandClient` (no real network calls in the suite, no Anthropic SDK types in the test): a well-formed response parses into `InterpretedCommand`; a response naming an unknown block id is downgraded to not-understood; more than 5 events is downgraded to not-understood; a malformed/empty response is not-understood, not a crash; a fake client that throws propagates the exception out of `CommandInterpreter` unchanged (it is not swallowed into "not understood" — the controller layer handles it, per `AssistantControllerTest` below); the prompt/context built for the fake client includes today's date and every block's id/title/kind/status/start/end.
- `AssistantControllerTest` — mocked `CommandInterpreter` and `PlanService` (no fake client needed at this layer): confirms the controller distinguishes `CommandInterpreter` throwing (→ 502) from `CommandInterpreter` returning `understood:false` (→ 200 with a message), since both can look similar from inside the service but must not from the client's perspective.
- `PlanServiceTest` additions — `simulateEvents` never persists (`getOrGenerate` immediately after shows the plan unchanged, same as before the call); `applyEvents` is atomic (a scripted second event that fails leaves the first event's would-be change unpersisted too).
- `@WebMvcTest` for `AssistantController` — 200 understood (with a stubbed interpreter/service), 200 not-understood, 502 when the interpreter throws, 503 when no Claude bean exists.
- `@WebMvcTest` for the new batch endpoint on `PlanController` — 200 with concatenated changes, 422 on an unknown block anywhere in the batch, 400 on a malformed request body.
- Frontend: Vitest for `AssistantBox` and `ProposalCard` with mocked `fetch` — not-understood message shown and editable; proposal rendered from a canned response; Apply posts the exact `events` array and clears on success; Reject clears without a request; busy disables the buttons; the box does not render when `/api/config` reports `aiEnabled:false`.
- One optional **live smoke test**, annotated to run only when `ANTHROPIC_API_KEY` is set in the environment (skipped otherwise, never runs in CI without a key): sends a real command, asserts the response parses and contains a plausible event for an obvious case ("mark the gym block done").
- Manual end-to-end in the browser pane (same style as Phase 1b): with a real API key, type "padel got canceled" against a plan that has a Padel block, see a real Claude-produced proposal, Apply, confirm the block is cancelled and the toast shows the change; type nonsense, confirm a "not understood" message with no proposal; unset the key, confirm the assistant box disappears and Phase 1 flows (Setup, Done/Postpone/Cancel) are unaffected.

## Verification

- `docker compose up -d && cd backend && ./mvnw test` — all existing tests still green, plus the new ones above.
- `cd frontend && npm test`.
- The manual end-to-end steps above (the live-key parts only when a key is available).
