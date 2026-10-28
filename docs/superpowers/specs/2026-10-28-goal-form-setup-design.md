# Life Organizer — Goal Form Setup — Design Spec

- **Date:** 2026-10-28
- **Status:** Approved 2026-10-28.
- **Replaces:** the quota model of the original design (`2026-09-17-life-organizer-design.md`), the text setup of
  Phase 1b (`2026-09-19-life-organizer-phase1b-design.md`), brain-dump extraction
  (`2026-09-26-brain-dump-extraction-design.md`), the priority proposals of the weekly review
  (`2026-10-01-life-organizer-phase3b-weekly-review-design.md`), and the editor of
  `2026-10-28-setup-inline-editing-design.md`.

## 1. Why

Goal setup is hard to understand. Goals and the constraints that give them time live in two separate lists. Some
constraints point at one goal (`Gym: 4x/week`), some at a whole category (`Learning: 5h/week`), and the text names
reach their target through hidden aliases ("Learning" means Career, "Side project" means Projects). A goal that no
constraint points at is never scheduled, and nothing says so: in the example setup, Azure AI, System design,
Investing and Chess never get a block. Priority and deadline only matter inside a shared category budget, which is
also not visible.

Target: every goal says, in one place, how much time it gets. One form per goal: name, category, hours per week.

## 2. Goals

- The Setup page has two cards:
  - **Goals**: goals grouped by category. Each goal row shows its name, an hours-per-week field and a Remove
    button. Below the list, a form adds a goal: name, category (an existing one or a new one), hours per week.
  - **Fixed blocks**: one row per fixed block (label, start, end, days) and a form to add one, as today.
- One Save for the whole setup. Discard changes returns to the saved setup.
- A goal with 0 hours per week shows a muted hint "won't be scheduled".
- The engine gives each goal time from its own hours per week, and from nothing else.
- A first visit (nothing saved) shows the same two cards, empty.

## 3. Non-goals

- No AI on the Setup page. No new AI features anywhere.
- No session length, time of day or energy fields in the form. They stay hidden with their current values; a new
  goal gets session length 60 minutes and the same defaults for time of day and energy that a parsed goal gets
  today.
- No renaming of goals or categories (as today).
- No change to fixed-block behavior, the Today page timeline and its actions, the postpone policy, notes or memory
  search.

## 4. Anti-goals (a failure even if it works)

- A goal kept across the change gets a new id. Past blocks and memory chunks refer to goal ids.
- Fixed blocks change in the migration.
- Days that are already planned change. They keep their blocks; only days generated after the change use the new
  setup.
- A chat thread that contains an old priority proposal fails to load or render.
- A goal is silently never scheduled without the "won't be scheduled" hint.

## 5. Behavior

### 5.1 Data model

- `Goal` loses `priority` and `deadline` and gains `minutesPerWeek` (integer, at least 0). Hours are entered in
  the form and stored as minutes, as the hours-per-week quota does today. `category`, `defaultBlockMinutes`,
  `energy` and `preferredTime` stay.
- Only one constraint type remains: `FixedBlock`. `FrequencyQuota`, `DurationQuota` and `EveningQuota` are
  deleted from the domain, the engine, persistence and the API.
- Flyway migration `V4__goal_hours.sql`:
  - add `goal.minutes_per_week INTEGER NOT NULL DEFAULT 0`;
  - drop `goal.priority` and `goal.deadline`;
  - delete `plan_constraint` rows whose `type` is not the fixed-block type, then drop the quota-only columns
    (`times_per_week`, `session_minutes`, `goal_id`, `minutes_per_week`, `category_id`, `evenings_per_week`);
  - set `proposal_status` to `EXPIRED` for chat messages whose proposal is still `PENDING` and is a priority
    proposal.
- Existing goals start at 0 hours per week (the user chose "start fresh"). Goals, categories and fixed blocks are
  kept with their ids. Goals that got evening blocks from an evenings-per-week quota lose that evening placement;
  they follow their own time-of-day setting like every other goal.
- A category with no goals left is dropped on Save, as today. *(My call.)*

### 5.2 Engine

- `WeekAllocator` takes each goal's `minutesPerWeek` and cuts it into chunks of at most the goal's
  `defaultBlockMinutes`, at most one chunk per goal per day, spread over the week with the shared day cursor the
  hours-per-week quota uses today. Minutes that do not fit in 7 days give the existing "could not fit" warning.
  A goal with 0 minutes gets no units and no warning (the Setup page already shows the hint).
- Units for one day keep the order of the goals in the setup. This replaces the sort by priority.
- `GoalWeighting` (priority and deadline weighting) is deleted.
- `DayScheduler`, `Replanner` and `PostponePolicy` keep their behavior; only reads of priority are removed.

### 5.3 API

- `GoalDto`: `priority` and `deadline` removed, `minutesPerWeek` added. `ConstraintDto` keeps only the
  fixed-block fields.
- `PUT /api/setup` rejects, with 400 and a message: two goals with the same id, `minutesPerWeek` below 0, above
  10080 (168 hours) or not a multiple of 30, and an invalid fixed block (as today).
  - Goal ids stay `<categoryId>-<slug(name)>`, so "Chess" and "chess!" in one category clash and are rejected,
    while "Reading" under two categories is allowed. *(My call: the check follows the id, which is what must be
    unique.)*
  - A goal removed and added again under the same name and category gets its old id back, and its past blocks
    and memory chunks attach to it again. *(My call: it is the same goal to the user.)*
- `POST /api/setup/parse` and `POST /api/setup/extract` are removed, with `GoalTreeParser`, `ConstraintParser`,
  `SetupExtractionService`, `SetupExtractionClient`, `GroqExtractionClient` and `SetupExtractionResult`.

### 5.4 Setup page

- `SetupPage` has no separate first-visit flow. It loads the saved setup (or an empty one) into one editor.
- Removed: the text boxes, Parse, Use example text, the brain-dump box, `SetupPreview`, `setup/example.ts`,
  `setup/mergeExtraction.ts`.
- `SetupEditor`: goal rows as in section 2. The hours field takes decimals in half-hour steps (1.5 = 90 minutes)
  and flags an empty, unreadable or off-step entry (1.3) before Save, as the current number fields do.
  *(My call: the engine works on a 30-minute grid and never makes blocks shorter than 30 minutes, so 1.3 h would
  leave an 18-minute chunk.)*
- Duplicate check before Save, same rule as the API (section 5.3).
- `SetupConstraints` shows fixed blocks only; its type select goes away.
- The page uses the shared controls, cards and button variants from `frontend/UI.md`.
- After Save the page goes to Today, as now.

### 5.5 Assistant

- The `propose_priorities` tool, `PriorityChange`, the priority branch of proposal apply,
  `SetupStore.updatePriorities`, `PlanService.replanUntouchedDays` and `PriorityProposalCard` are removed.
- The "Review my week" shortcut stays. Its prompt asks for a summary of the week (what got done, what slipped,
  how the hours went); the assistant proposes nothing. `ReplyGuard` must not send such a summary back as a
  "claimed change without a proposal".
- `ListGoalsTool` and `WeekProgressTool` report hours per week instead of priority.
- An old priority proposal in a saved thread is shown read-only with its stored outcome: "Applied" or "Rejected"
  as stored, and "Expired" for those the migration expired. No buttons. *(My call: the history stays truthful.)*
  The stored JSON (`kind: PRIORITIES`, `priorityChanges`) must still load, so `Proposal.Kind.PRIORITIES` and a
  read-only shape for its changes stay; only the code that creates and applies them goes. Apply on such a
  proposal returns 409, as for any proposal that is not pending.

## 6. Constraints on the build

- Schema changes only through the new Flyway migration (Hibernate runs `validate`).
- The strict JSON schemas in the Groq client change with the removed tool.
- `README.md` (API table) and `CLAUDE.md` (setup parsing, extraction, priorities, weekly-review apply) are
  updated in the same change.
- Backend tests that need Docker run as today (Testcontainers, `pgvector/pgvector:pg17`).

## 7. Left to the builder

- Layout of goal rows and the add-goal form, and the wording of hints and errors.
- Whether `NumberField` moves to `components/form/`.
- The exact summary prompt for "Review my week".

## 8. Testing

- Engine: per-goal allocation — chunk size, one chunk per goal per day, spread over the week, 0 minutes skipped,
  "could not fit" warning, setup order within a day.
- Migration: `SchemaMigrationTest` covers V4 (columns gone, `minutes_per_week` present, fixed blocks kept, quota
  rows gone, pending priority proposals expired).
- API: setup save and load with the new DTO; validation errors; parse and extract return 404.
- Frontend: Setup page and editor tests rewritten for the form; the chat panel renders an old priority proposal as
  expired.
- Tests that cover removed code are deleted with it; tests that only read priority are updated.
