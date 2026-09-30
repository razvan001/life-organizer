# Life Organizer — Phase 3b: Planning Agent (Chat for Today) — Design Spec

- **Date:** 2026-09-30
- **Status:** Design approved by the user; spec awaiting review
- **Builds on:** Phase 2 natural-language commands (Groq) and Phase 3a RAG memory
  (`2026-09-27-life-organizer-phase3a-rag-memory-design.md`).
- **Original design:** `2026-09-17-life-organizer-design.md`, section "Phase 3 — RAG + planning agent".

## 1. Context and scope

Phase 3a shipped RAG memory: notes plus past-day summaries, with semantic search. The original design's Phase 3b
is a planning agent that orchestrates the engine through tools. It has three capabilities: chat for today,
weekly prioritization, and conflict resolution. They are split. This spec covers only the **chat agent for
today**.

Decisions made with the user:

| Topic | Decision |
|---|---|
| First slice | A multi-turn chat that reads today's plan, searches memory, simulates changes and proposes them (Apply / Reject) |
| Command box | The chat **replaces** the one-sentence command box on the Today page: one assistant, not two |
| History | Saved in the database, **one thread per day**. Today's thread opens automatically; past days' threads are read-only |
| Chat as memory | Saved chats are **not** indexed into RAG memory; memory stays notes + days |
| Change scope | The agent may only propose changes to **today** (the same five events as now). It may read the week and memory |

Assumptions (stated to the user, not contradicted):

- The LLM stays Groq (`openai/gpt-oss-120b`), through its OpenAI-compatible tool-calling API, still gated on
  `GROQ_API_KEY`. With no key the chat is hidden and its endpoints return 503.
- Weekly prioritization and conflict resolution are later slices.

**Success:** on Today, type "padel got canceled and I'm tired". The agent reads today's plan, finds a note such as
"after gym I can't focus on study" through `search_memory`, simulates, and answers with a short explanation and a
proposal card (for example: cancel Padel, swap study for something light). Apply changes the plan. After a page
reload the conversation and the applied proposal are still there. Yesterday's chat opens read-only.

**Out of scope:** weekly prioritization, priority-change proposals, conflict resolution and `ConflictDetector`
wiring, proposals on other days, indexing chats into memory, streaming responses.

## 2. Approach

A **server-side tool-calling loop** on Groq's native `tools` / `tool_calls` API. The agent only reads and
simulates. The only thing that changes the plan is the user pressing Apply.

Rejected:

- Pre-fetch everything into one prompt and ask for strict JSON (what Phase 2 does): simpler, but it is not an
  agent. It cannot decide to look something up in memory or try an alternative.
- A client-side loop: it exposes tool plumbing to the browser and brings nothing for a single-user app.

## 3. Backend — packages `com.lifeorganizer.ai.agent` and `com.lifeorganizer.ai.chat`

### 3.1 LLM client

Extend the Groq layer with a tool-capable call.

- Request: `messages` (system; user; assistant with `tool_calls`; `tool` results) + `tools` (function name,
  description, JSON-schema parameters) + `tool_choice: "auto"`.
- Response: assistant `content` and/or `tool_calls[{id, function{name, arguments}}]`.
- New interface `AgentLlmClient`, bean gated on `GROQ_API_KEY` like the other AI beans; the Groq implementation
  follows `GroqChatClient`'s HTTP and logging conventions.
- Tool arguments arrive as a JSON string and are parsed defensively.

### 3.2 Tools

Each tool is a small class behind one `AgentTool` interface: name, description, parameter schema,
`execute(json) → result text`. All tools are read-only or simulate-only.

| Tool | Returns |
|---|---|
| `get_today_plan` | today's blocks: id, title, kind, status, start, end |
| `get_week_overview` | Mon–Sun of this week, stored days only (through `PlanStore.findDay`, never generating), one line per day with its GOAL and ADHOC blocks and their status; days not planned yet say so |
| `list_goals` | goals with category, priority, deadline, preferred time, energy (`SetupStore.loadTree`) |
| `search_memory(query, k?, source?)` | `MemorySearch` hits: type, title or date, snippet, score |
| `simulate_changes(events)` | the engine preview: what would change plus the engine's refusal messages, or a validation error |
| `propose_changes(rationale, events)` | validates and simulates; on success it becomes the turn's proposal (at most one per turn) |

- Event validation moves out of `CommandInterpreter` into a shared `ProposedEventValidator`: today only, known
  block ids, never FREE blocks, at most 5 events, and `ApplyEventRequest.toEvent` must accept each event. Both
  `simulate_changes` and `propose_changes` use it.
- A tool failure (bad arguments, unknown block, validation error) goes back to the model as the tool result
  text (`error: …`) so the model can correct itself. It is never an HTTP error.

### 3.3 Agent loop (`AgentRunner`)

One user turn:

1. Messages: the system prompt (role, today's date, "never assume a change is applied; propose, the user
   decides", the tools) + today's thread history (the last 20 user and assistant messages, text only) + the new
   user message.
2. Loop: call the model, run the tools it asks for, append the results, repeat. At most **8 model calls per
   turn**.
3. The turn ends when the model answers with text. When the cap is reached, or the reply is unusable, the
   assistant message is "I couldn't finish that. Try rephrasing."

Tool calls and results live only for the turn; they are logged at DEBUG and not stored. No database transaction
is held across LLM calls; each tool runs in its own short transaction.

### 3.4 Memory carry-overs from Phase 3a

Required now, because the agent filters memory by source:

- `MemoryChunkStore.search` with a `source` filter sets `SET LOCAL hnsw.iterative_scan = relaxed_order`
  (pgvector 0.8 or later), so a filtered search still returns k hits on a large table. Results are re-sorted by
  score.
- `MemorySearch` keeps only the best chunk per source, so one long note no longer fills every result slot.

### 3.5 Persistence — Flyway `V3__chat.sql`

Table `chat_message`: id, chat_date (the thread), position, role (`USER` | `ASSISTANT`), text, proposal (JSON
text, nullable: rationale, events, engine changes, preview blocks, status `PENDING` | `APPLIED` | `REJECTED` |
`EXPIRED`), created_at.

Sending a new message expires any proposal still pending in today's thread, because the plan may have moved on.

### 3.6 API (replaces `POST /api/assistant/command`)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/assistant/chat?date=` | a day's thread (default today) |
| GET | `/api/assistant/chat/days` | dates that have a thread, newest first |
| POST | `/api/assistant/chat/messages` | `{text}` (at most 2000 characters): runs one agent turn on today's thread and returns the assistant message |
| POST | `/api/assistant/chat/messages/{id}/apply` | applies a PENDING proposal of today's thread through `PlanService.applyEvents`, marks it APPLIED, returns the new plan and changes |
| POST | `/api/assistant/chat/messages/{id}/reject` | marks it REJECTED |

Errors:

- Groq unreachable → 502, and nothing is saved (the text stays in the input).
- No key → 503.
- Applying a proposal that is not pending, or belongs to a past day → 409.
- A proposal whose blocks changed since it was made → the engine's 422.

Removed once the chat replaces the command box: the `AssistantController` command endpoint,
`CommandInterpreter` (its validation lives on in `ProposedEventValidator`), `LlmCommandClient` /
`GroqCommandClient`, and their tests.

## 4. Frontend

- `ChatPanel` replaces `AssistantBox` on Today, still shown only when `aiEnabled`:
  - the message list (user and assistant);
  - an assistant message with a proposal renders the existing `ProposalCard` (Apply / Reject), and afterwards a
    status label;
  - an input and Send, with "Thinking…" while a turn runs (a turn can take several seconds).
- "Earlier chats": a small day picker (from `/chat/days`) that shows a past day's thread read-only.
- Apply updates Today's timeline and shows the change toast, as today.

## 5. Testing

- `AgentRunner` with a scripted fake `AgentLlmClient`: tool dispatch, results fed back, the iteration cap, bad tool
  arguments becoming error results, at most one proposal per turn, a text-only answer.
- Each tool against real services (Testcontainers where needed); `get_week_overview` never generates a day.
- `ProposedEventValidator` unit tests (moved from `CommandInterpreterTest`).
- The Groq tool-calling client with `MockRestServiceServer`: the request carries `tools`, `tool_calls` are parsed.
- Chat persistence and endpoints: history, expiry of pending proposals, apply and reject states, 409 / 502 / 503.
- End-to-end `@SpringBootTest` with the fake LLM: message → proposal → apply → plan changed → thread reloaded.
- Memory carry-overs: a filtered search returns k hits on a skewed large table; one hit per source.
- A live smoke test gated on `GROQ_API_KEY`: the first real check that `gpt-oss-120b` tool calling works.
- Frontend (Vitest): ChatPanel send, proposal, apply, reject, error, past-day read-only.
