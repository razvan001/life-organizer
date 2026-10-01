# Life Organizer — Phase 3b slice 2: Weekly Review ("plan my week") — Design Spec

- **Date:** 2026-10-01
- **Status:** Design approved by the user; spec awaiting review
- **Builds on:** Phase 3b slice 1, the chat agent for today
  (`2026-09-30-life-organizer-phase3b-chat-agent-design.md`).
- **Original design:** `2026-09-17-life-organizer-design.md`, section "Phase 3 — RAG + planning agent".

## 1. Context and scope

Slice 1 shipped the chat agent for today: it reads the plan and memory, simulates, and proposes changes to today
that the user applies or rejects. The original 3b design also has **weekly prioritization**: look back at the
week, then shift goal priorities so the rest of the week (and later weeks) follows what matters now. This slice
adds that to the same chat.

**What the user decided:**

- The review may change **goal priorities only** (1–5). No quota, deadline or category edits.
- It lives **in today's chat**, with a **"Review my week" shortcut** that sends a fixed prompt. A priority
  proposal shows as a card with Apply / Reject, like plan proposals.
- Scope: **this week so far** is reviewed; new priorities shape **days not planned yet** (rest of this week and
  later weeks).
- On Apply, **only untouched future days** are re-planned: days after today that are already stored but that the
  user has not acted on are dropped and re-generated with the new priorities. Today and any day the user touched
  stay as they are.

**Assumptions:**

- A day is **touched** if any of its blocks is not PLANNED, is ADHOC, or is a postponed copy
  (`postponeCount > 0`). A touched day keeps its plan.
- Re-planning is limited to the rest of **this** week. Later weeks are not stored yet, so they pick up the new
  priorities when they are first generated.
- One proposal per turn stays: a turn proposes either plan changes or priority changes, never both.
- The known engine limitation stays: done work is not subtracted from the weekly allocation. Re-generated days
  get a fresh share by priority, not "what is left".

**Success:** press "Review my week". The agent reads the week's progress (for example Study planned 6h, done 1h,
postponed 3 times; Gym done every time), searches notes, and answers in plain text with a card:
"Study 3 → 5 (exam Friday, behind), Padel 4 → 3". Apply. Thursday and Friday (untouched) are re-planned with more
Study; Wednesday, where the user cancelled a block, stays. The goals page shows the new priorities.

**Out of scope:** quota, deadline or category edits; re-planning today or touched days; subtracting done work
from the weekly allocation; conflict resolution (slice 3); scheduled or automatic weekly reviews.

## 2. Approach

Extend the slice-1 agent with **two new tools** and a **second proposal kind** on the same chat message. The loop,
the Apply / Reject flow and the 409 / expiry rules stay the same.

Rejected:

- A separate "weekly review" endpoint with a one-shot JSON prompt: it duplicates the chat and cannot answer
  follow-ups ("why lower Padel?").
- Letting the agent change priorities directly: it breaks the slice-1 rule that only Apply changes data.

## 3. Backend

### 3.1 Tool `get_week_progress`

Read-only. It never generates days: it reads stored days through `PlanStore.findDay` only, like
`get_week_overview`. For Monday..today of this week it returns one line per goal: planned minutes, done minutes,
cancelled and postponed counts, current priority and deadline. Days that are not stored count as "not planned"
and are listed as such.

### 3.2 Tool `propose_priorities(rationale, changes[{goalId, priority, reason}])`

- Validation; each failure goes back to the model as "error: ..." (never an HTTP error):
  - every `goalId` is a known goal;
  - `priority` is 1–5;
  - each goal appears at most once;
  - the new value differs from the current priority;
  - 1–8 changes;
  - `rationale` is required.
- On success it records a priority proposal in `TurnContext`. The existing one-proposal-per-turn rule covers both
  kinds: `propose_changes` after `propose_priorities` fails, and the reverse.
- The tool result lists each change (`"Study: 3 -> 5 (exam Friday)"`) and the future days that Apply would
  re-plan ("Thu 2026-10-02, Fri 2026-10-03"; "none" when every later day is touched or not stored).

### 3.3 Proposal shape

`Proposal` gets:

- `kind`: `PLAN` | `PRIORITIES`;
- `priorityChanges`: `List<PriorityChange(goalId, goalName, from, to, reason)>`.

For `PRIORITIES`, `events` is empty and `preview` is null. Stored JSON without `kind` reads as `PLAN`, so slice-1
rows keep working. No Flyway migration is needed: the `proposal` column already holds JSON text.

### 3.4 Apply

`ChatService.apply` runs in one transaction.

- `PLAN`: unchanged.
- `PRIORITIES`:
  1. **Stale check.** Every goal still exists and still has the `from` priority. Otherwise the proposal is marked
     EXPIRED and the call fails with 409 `ProposalNotAvailableException` ("Your goals changed since this
     proposal").
  2. **Update.** `SetupStore.updatePriorities(Map<String, Integer>)` is a new targeted update through
     `GoalRepository`. It does not use the whole-tree `replace`, so blocks, constraints and the rest of the setup
     stay intact.
  3. **Re-plan.** `PlanService.replanUntouchedDays(today)` walks tomorrow..Sunday of this week. For each stored,
     untouched day it calls `PlanStore.deleteDay(date)` (blocks and warnings), then `getOrGenerate(date)`. It
     takes the same advisory lock as generation and returns the re-planned dates.
  4. **Result.** The proposal is marked APPLIED. The response is the existing `ApplyEventsResponse`: today's plan
     plus change lines ("Study: priority 3 -> 5", "Re-planned Thu, Fri"), so the frontend toast works unchanged.
- The history note for the model stays `[proposal STATUS: rationale]`.

### 3.5 System prompt

Add the two tools and these rules:

- For a weekly review, call `get_week_progress` (and `search_memory` when useful) before proposing.
- Priority proposals change priorities only, each 1–5; explain each change in one short clause.
- Never claim priorities changed; the user decides with Apply.
- Plain text, as in slice 1.

## 4. Frontend

- `ChatPanel`: a "Review my week" chip button above the input. It shows only on today's thread (hidden on
  read-only past days) and is disabled while busy. It sends the fixed text
  "Review my week and suggest goal priorities." through the normal `send()` path.
- `ProposalCard`: for `kind === 'PRIORITIES'` it shows the rationale, one row per change
  (`Study  3 → 5  · exam Friday`), the re-planned days line, and the same Apply / Reject / status pill. There is no
  timeline preview.
- `api/types.ts`: `ChatProposalDto` gets `kind` and `priorityChanges`; `preview` becomes optional.
- After applying a priorities proposal, Today reloads as now (`onApplied`). The Setup / Goals page reads fresh
  data on its next load.

## 5. Testing

- `WeekProgressTool`: counts come from stored days only; the tool never creates a day (Testcontainers).
- `ProposePrioritiesTool`: validation cases — unknown goal, out of range, duplicate, unchanged, too many, and a
  second proposal in the same turn.
- `SetupStore.updatePriorities`: updates only priorities; blocks and constraints are untouched.
- `PlanService.replanUntouchedDays`: an untouched future day is re-generated with the new priorities; touched days
  (cancelled, done, ad-hoc, postponed copy) are kept; today is kept; days that are not stored stay not stored;
  next week is untouched.
- `ChatService` and controller: apply PRIORITIES end to end; stale priorities give 409 and EXPIRED; slice-1 JSON
  without `kind` still loads and applies.
- `AgentRunnerTest`: the system prompt mentions the new tools.
- Vitest: the shortcut sends the fixed text and is hidden on past days; the priority card renders its rows and
  applies / rejects.
