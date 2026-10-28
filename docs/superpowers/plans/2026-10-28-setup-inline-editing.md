# Edit a Saved Setup Inline — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** With a saved setup, the Setup page becomes an editor: add and remove goals and constraints, edit constraint values, add from the brain dump, one Save. No text boxes.

**Architecture:** Frontend only. Pure, tested functions in `src/setup/` do every change to the setup (ids, add/remove, validation, brain-dump merge). A new `SetupEditor` component renders the editable setup and calls them; `SetupPage` picks the first-visit flow (unchanged) or the editor, and owns Save / Discard. `PUT /api/setup` already replaces the whole setup.

**Tech Stack:** React 19 + TypeScript + Vite, Vitest + Testing Library, `test/mockApi.ts`.

**Spec:** `docs/superpowers/specs/2026-10-28-setup-inline-editing-design.md`

## Global Constraints

- Frontend only (`frontend/src`). No backend change, no new endpoint, no new library. `api/client.ts` stays the only place that knows URLs.
- Work on `master`. Never `git commit` without the user's OK: stage, run `git diff --cached --stat`, wait. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Ids follow the backend's `Slug.of` exactly: lower case, trim, every run of characters outside `a-z0-9` becomes one `-`, leading/trailing `-` removed, empty becomes `item`. Category id = `slug(name)`; goal id = `<category id>-<slug(goal name)>`.
- New goal defaults: priority 3, deadline null, `defaultBlockMinutes` 60, energy `MEDIUM`, preferredTime `ANY`.
- Text limit 255 characters (labels, goal and category names).
- At most one quota per type and target: one `FREQUENCY_QUOTA` per goal, one `DURATION_QUOTA` and one `EVENING_QUOTA` per category.
- Settings the user did not touch must reach `PUT /api/setup` unchanged (energy, preferredTime, defaultBlockMinutes, sessionMinutes, fixed-block days, ids).
- Keep the note "Days that are already planned keep their plan. Days that are not planned yet use this setup." next to Save. After a successful Save, navigate to `/`.
- Run commands from `frontend/`. Checks: `npx vitest run <file>`, `npm test`, `npm run build` (the only type-check).

## Review Focus

1. Removing a goal that has a times-per-week quota, then saving: the PUT body must not contain that quota (the backend rejects a dangling `goalId` with 400). Test in Task 1 and Task 5.
2. Adding a goal whose name slugs to an existing id under the same category, e.g. "gym!" next to "Gym": refused, not a 400 on Save. Test in Task 1.
3. Clearing a number or time field (empty input): Save is disabled and the field shows a message; nothing like `NaN` or `""` is sent. Test in Task 4.
4. Brain dump returns a goal the setup already has, plus its quota: the existing goal and its quota stay unchanged, the extracted ones are skipped with a warning. Test in Task 2.
5. A category whose last goal is removed while an hours-per-week quota still points to it: kept on Save; one with nothing pointing to it: dropped. Test in Task 1.

---

## File Structure

| File | Responsibility |
|------|----------------|
| `src/setup/slug.ts` (new) | `slug`, `categoryIdFor`, `goalIdFor` |
| `src/setup/editSetup.ts` (new) | draft type and every pure change: goals, categories, constraints, validation, payload for Save |
| `src/setup/mergeExtraction.ts` (new) | brain-dump result → added to the draft, with warnings |
| `src/components/SetupEditor.tsx` (new) | renders the draft: goal rows, add-goal form, constraint rows, add-constraint form |
| `src/components/SetupEditor.css` (new) | editor layout only |
| `src/pages/SetupPage.tsx` | loading / first visit / editor; Save, Discard, brain dump |
| `src/components/SetupPreview.tsx` | unchanged (first-visit preview) |
| `README.md` | Setup step in "Using it" |

---

### Task 1: Ids and goal/category changes

**Files:**
- Create: `src/setup/slug.ts`, `src/setup/editSetup.ts`
- Test: `src/setup/slug.test.ts`, `src/setup/editSetup.test.ts`

**Interfaces:**
- Produces (in `slug.ts`):
  - `slug(text: string): string`
  - `categoryIdFor(name: string): string` (= `slug(name)`)
  - `goalIdFor(categoryId: string, goalName: string): string`
- Produces (in `editSetup.ts`):
  - `interface DraftConstraint { key: number; isNew: boolean; value: ConstraintDto }`. `key` is unique within a draft and is the React key.
  - `interface DraftSetup { categories: CategoryDto[]; goals: GoalDto[]; constraints: DraftConstraint[] }`
  - `toDraft(saved: SetupPayload): DraftSetup` (keys 0..n-1, `isNew` false)
  - `toPayload(draft: DraftSetup): SetupPayload`: strips the wrappers and drops categories with no goal that no constraint's `categoryId` points to
  - `type CategoryChoice = { existingId: string } | { newName: string }`
  - `type EditResult = { ok: true; draft: DraftSetup } | { ok: false; error: string }`
  - `addGoal(draft: DraftSetup, name: string, category: CategoryChoice): EditResult`
  - `removeGoal(draft: DraftSetup, goalId: string): DraftSetup`: also removes `FREQUENCY_QUOTA` constraints with that `goalId`
  - `constraintsRemovedWith(draft: DraftSetup, goalId: string): ConstraintDto[]` (for the "also removes" hint)
  - `updateGoal(draft: DraftSetup, goalId: string, changes: Partial<Pick<GoalDto, 'priority' | 'deadline'>>): DraftSetup`
  - `isChanged(draft: DraftSetup, saved: SetupPayload): boolean`: compares `toPayload(draft)` with `saved` (JSON equality)

- [ ] **Step 1: Write failing tests** in `slug.test.ts`

```ts
expect(slug('Padel / football')).toBe('padel-football')
expect(slug('  Side project!  ')).toBe('side-project')
expect(slug('---')).toBe('item')
expect(goalIdFor('career', 'Claude certification')).toBe('career-claude-certification')
expect(categoryIdFor('Side Project')).toBe('side-project')
```

- [ ] **Step 2: Write failing tests** in `editSetup.test.ts`. Test files in this plan use `const { warnings: _warnings, ...SAVED } = PARSED` (`PARSED` from `test/fixtures`) and `d = toDraft(SAVED)`:
  - `addGoal adds a goal with the defaults under an existing category`: `addGoal(d, 'Azure AI', { existingId: 'career' })` → goal `{ id: 'career-azure-ai', categoryId: 'career', name: 'Azure AI', priority: 3, deadline: null, defaultBlockMinutes: 60, energy: 'MEDIUM', preferredTime: 'ANY' }`.
  - `addGoal creates a new category`: `{ newName: 'Hobbies' }` → category `{ id: 'hobbies', name: 'Hobbies' }` and goal id `hobbies-chess`.
  - `addGoal trims the name`: `'  Chess  '` → name `'Chess'`.
  - refusals, each `ok: false` with this exact error:
    - `''` or spaces → `'Enter a goal name.'`
    - 256 characters → `'A goal name can have at most 255 characters.'`
    - `'gym!'` under `health` (slug clash with Gym) → `'Health already has the goal "Gym".'`
    - `{ newName: 'career' }` → `'The category "Career" already exists. Pick it from the list.'`
    - `{ newName: '' }` → `'Enter a category name.'`; 256 characters → `'A category name can have at most 255 characters.'`
  - `removeGoal also removes its times-per-week quota`: removing `health-gym` leaves only the Work constraint, and `constraintsRemovedWith(d, 'health-gym')` returns the Gym quota.
  - `toPayload drops a category left without goals`: remove `career-claude-certification` → payload categories are `[health]`.
  - `toPayload keeps an empty category a quota points to`: add a `DURATION_QUOTA` with `categoryId: 'career'` to the draft, remove the career goal → `career` stays.
  - `toPayload returns untouched settings unchanged`: `toPayload(toDraft(SAVED))` deep-equals `SAVED`.
  - `isChanged` is false for `toDraft(SAVED)`, true after `updateGoal(d, 'health-gym', { priority: 5 })`.

- [ ] **Step 3: Run** `npx vitest run src/setup/slug.test.ts src/setup/editSetup.test.ts`. Expected: FAIL (modules missing).

- [ ] **Step 4: Implement** `slug.ts` and the goal part of `editSetup.ts` with the signatures above. A new goal's id is checked against all goal ids (ids are global keys). A new category's id is checked against existing category ids. All functions return new objects and never mutate.

- [ ] **Step 5: Run** the same command. Expected: PASS.

- [ ] **Step 6: Stage** and show `git diff --cached --stat`; wait for the OK to commit `feat(setup): pure helpers to add and remove goals`.

---

### Task 2: Constraint changes, validation and brain-dump merge

**Files:**
- Modify: `src/setup/editSetup.ts`
- Create: `src/setup/mergeExtraction.ts`
- Test: `src/setup/editSetup.test.ts`, `src/setup/mergeExtraction.test.ts`

**Interfaces:**
- Consumes: Task 1 (`DraftSetup`, `DraftConstraint`, `slug`, `categoryIdFor`, `goalIdFor`).
- Produces (in `editSetup.ts`):
  - `hasQuota(draft: DraftSetup, type: ConstraintType, targetId: string): boolean` (`goalId` for `FREQUENCY_QUOTA`, `categoryId` for the other two; always false for `FIXED_BLOCK`)
  - `addConstraint(draft: DraftSetup, constraint: ConstraintDto): DraftSetup` (key = max key + 1, `isNew` true)
  - `updateConstraint(draft: DraftSetup, key: number, value: ConstraintDto): DraftSetup`
  - `removeConstraint(draft: DraftSetup, key: number): DraftSetup`
  - `constraintProblem(constraint: ConstraintDto): string | null`: the first problem, or null
  - `hasProblems(draft: DraftSetup): boolean`: any constraint with a problem
- Produces (in `mergeExtraction.ts`):
  - `mergeExtraction(draft: DraftSetup, extracted: SetupParseResponse): { draft: DraftSetup; warnings: string[]; addedGoalIds: string[] }`

- [ ] **Step 1: Write failing tests** for constraints in `editSetup.test.ts`:
  - `hasQuota`: true for (`FREQUENCY_QUOTA`, `health-gym`), false for (`FREQUENCY_QUOTA`, `career-claude-certification`).
  - `addConstraint` gives a new key and `isNew: true`; `removeConstraint` and `updateConstraint` touch only that key.
  - `constraintProblem`, exact messages:
    - label `''` → `'Enter a label.'`; 256 characters → `'A label can have at most 255 characters.'`
    - fixed block start `''` or `'8:3'` → `'Enter a start time.'`; end the same → `'Enter an end time.'`
    - fixed block `days: []` → `'Pick at least one day.'`
    - `timesPerWeek` 0, `NaN` or 1.5 → `'Times per week must be a whole number of at least 1.'`
    - `sessionMinutes` same rule → `'Minutes per session must be a whole number of at least 1.'`
    - `minutesPerWeek` < 1 or `NaN` → `'Hours per week must be more than 0.'`
    - `eveningsPerWeek` same rule as times → `'Evenings per week must be a whole number of at least 1.'`
    - every `PARSED` constraint → `null`.

- [ ] **Step 2: Write failing tests** in `mergeExtraction.test.ts`. Draft = `toDraft(SAVED)`. The extracted answer has model-made ids:
  - `adds a new goal under an existing category with the slug id`: extracted goal `{ id: 'azure', categoryId: 'career', name: 'Azure AI', priority: 4, … }` → added as `career-azure-ai`, priority 4 kept, `addedGoalIds` `['career-azure-ai']`.
  - `adds a new category with the slug id and rewrites references`: extracted category `{ id: 'fun-stuff', name: 'Hobbies' }`, goal `{ id: 'g1', categoryId: 'fun-stuff', name: 'Chess' }`, quota `FREQUENCY_QUOTA goalId: 'g1'`, `EVENING_QUOTA categoryId: 'fun-stuff'` → category `hobbies`, goal `hobbies-chess`, both quotas point to the new ids and are `isNew`.
  - `an extracted category whose slug is an existing id joins it`: `{ id: 'x', name: 'career' }` → no new category; its goals go under `career`.
  - `skips a goal that already exists and keeps the saved one`: extracted `{ id: 'gym', categoryId: 'health', name: 'Gym', priority: 1 }` plus its quota → saved Gym goal and saved Gym quota unchanged; warnings include `'Skipped goal "Gym": it already exists.'` and `'Skipped "Gym: 3× per week, 45 min each": Gym already has a times-per-week quota.'` (the quoted part is `describeConstraint` of the skipped constraint).
  - `skips a second hours-per-week quota for a category`: draft has a `DURATION_QUOTA` on `career`; extracted one on `career` → skipped with `'Skipped "<describeConstraint>": Career already has an hours-per-week quota.'`; evenings: `'… already has an evenings-per-week quota.'`
  - `skips a constraint whose target is missing`: `FREQUENCY_QUOTA goalId: 'nope'` → `'Skipped "<describeConstraint>": its goal is not in the setup.'`; category variant `'… its category is not in the setup.'`
  - `keeps the model's warnings first`: `extracted.warnings` come before the merge's own warnings.

- [ ] **Step 3: Run** `npx vitest run src/setup`. Expected: the new tests FAIL.

- [ ] **Step 4: Implement** the constraint functions and `mergeExtraction`. Merge order: categories (map model id → final id: an existing id stays; otherwise `categoryIdFor(name)`, joining an existing category when equal), then goals (final id `goalIdFor(finalCategoryId, name)`; skip when the id is already in the draft or added earlier in this merge), then constraints (rewrite `goalId`/`categoryId` through the maps; skip on missing target or `hasQuota`). A category is only added when at least one goal or constraint that was added uses it.

- [ ] **Step 5: Run** `npx vitest run src/setup`. Expected: PASS.

- [ ] **Step 6: Stage**, show stat, wait for the OK to commit `feat(setup): constraint helpers and brain-dump merge`.

---

### Task 3: SetupEditor — goals

**Files:**
- Create: `src/components/SetupEditor.tsx`, `src/components/SetupEditor.css`
- Test: `src/components/SetupEditor.test.tsx`

**Interfaces:**
- Consumes: Task 1 helpers; `describeConstraint` (`src/setup/describeConstraint.ts`).
- Produces: `SetupEditor(props: { draft: DraftSetup; onChange: (next: DraftSetup) => void; onGoalAdded: (goalId: string) => void; newGoalIds: string[]; warnings: string[]; disabled: boolean })`. `newGoalIds` are goals to mark as new (added by hand or by the brain dump; the list is owned by `SetupPage`, which appends on `onGoalAdded`). `disabled` disables every input and button (while saving).

UI (labels the tests use; layout is the builder's call):
- Warnings box (class `warnings`, heading `Not added`) when `warnings` is not empty.
- Per category an `h5` with the name; an empty category shows `no goals`.
- Goal row: name, `Priority for <name>` select (1–5), `Deadline for <name>` date input (as `SetupPreview`), a `new` tag when in `newGoalIds`, button `Remove <name>`. When `constraintsRemovedWith` is not empty, the row shows `also removes: <describeConstraint …>` (joined with `; `).
- Add-goal form: input `New goal name`, select `Category for the new goal` (existing categories by name, plus `New category…`), input `New category name` (only when `New category…` is chosen), button `Add goal`. A refused `addGoal` shows its error with `role="alert"` next to the form; success clears the inputs.

- [ ] **Step 1: Write failing tests** (render with `toDraft(SAVED)` and a `vi.fn()` `onChange`):
  - `changes a priority`: select 5 for Gym → `onChange` draft has `health-gym` priority 5.
  - `removes a goal and says which quota goes with it`: the Gym row shows `also removes: Gym: 4× per week, 60 min each`; clicking `Remove Gym` → `onChange` draft has no `health-gym` and no Gym quota.
  - `adds a goal under an existing category`: type `Azure AI`, pick `Career`, `Add goal` → `onChange` draft contains `career-azure-ai`; `onGoalAdded` called with it.
  - `adds a goal under a new category`: pick `New category…`, type `Hobbies`, goal `Chess` → contains category `hobbies` and goal `hobbies-chess`.
  - `shows why a goal cannot be added`: goal `Gym` under Health → alert `Health already has the goal "Gym".`, `onChange` not called.
  - `marks new goals and shows warnings`: `newGoalIds={['health-gym']}` shows `new` in the Gym row; `warnings={['Skipped goal "Gym": it already exists.']}` shows it under `Not added`.
  - `shows an empty category`: a draft whose `career` has no goals shows `no goals` under Career.

- [ ] **Step 2: Run** `npx vitest run src/components/SetupEditor.test.tsx`. Expected: FAIL.

- [ ] **Step 3: Implement** the goal part of `SetupEditor`. Plain controlled inputs; the form keeps its own input state with `useState`; reuse the `goal-list` / `goal-name` classes from `index.css`.

- [ ] **Step 4: Run** the test file. Expected: PASS.

- [ ] **Step 5: Stage**, show stat, wait for the OK to commit `feat(setup): editor component for goals`.

---

### Task 4: SetupEditor — constraints

**Files:**
- Modify: `src/components/SetupEditor.tsx`, `src/components/SetupEditor.css`
- Test: `src/components/SetupEditor.test.tsx`

**Interfaces:**
- Consumes: Task 2 (`addConstraint`, `updateConstraint`, `removeConstraint`, `hasQuota`, `constraintProblem`).

UI, one row per constraint under `h4` `Constraints`; `<label>` is the constraint label, or the goal/category name for quotas:
- Fixed block: inputs `Label`, `Start for <label>` / `End for <label>` (`type="time"`), 7 checkboxes `Monday for <label>` … `Sunday for <label>` (days in `MONDAY`…`SUNDAY` order).
- Times per week: goal name as text, `Times per week for <goal>`, `Minutes per session for <goal>` (`type="number"`, `min=1`, `step=1`).
- Hours per week: category name, `Hours per week for <category>` (`type="number"`, `min=0`, `step="any"`); shown as `minutesPerWeek / 60`, stored as `Math.round(hours * 60)`.
- Evenings per week: category name, `Evenings per week for <category>`.
- An empty number input is stored as `NaN` so `constraintProblem` reports it; a cleared time input as `''`.
- `constraintProblem` is shown under the row with `role="alert"`.
- Button `Remove <label>`; `isNew` rows show a `new` tag.
- Add-constraint form: select `Constraint type` (`Fixed block`, `Times per week`, `Hours per week`, `Evenings per week`), then the fields of that type. Targets: select `Goal for the new constraint` / `Category for the new constraint` listing only goals/categories where `hasQuota` is false; when none is left, show `Every goal already has one.` / `Every category already has one.` and no Add button. A fixed block starts with days Monday–Friday checked. Button `Add constraint`, disabled while `constraintProblem` of the new constraint is not null. A new quota's label is the target's name.

- [ ] **Step 1: Write failing tests**:
  - `edits the times of a fixed block`: change `Start for Work` to `09:00` → Work constraint `start: '09:00'`, `days` unchanged.
  - `edits the days of a fixed block`: uncheck `Friday for Work` → days Monday–Thursday.
  - `edits times per week and keeps the session minutes`: `Times per week for Gym` = 3 → `timesPerWeek: 3, sessionMinutes: 60`.
  - `stores hours as minutes`: draft with a career `DURATION_QUOTA` of 300 shows `5`; typing `1.5` → `minutesPerWeek: 90`.
  - `reports an emptied field`: clear `Times per week for Gym` → alert `Times per week must be a whole number of at least 1.` (rendered from the `onChange` draft; use a small stateful wrapper in the test).
  - `removes a constraint`: `Remove Work` → no fixed block left.
  - `adds a fixed block`: type `Fixed block`, label `Commute`, start `07:30`, end `08:15`, `Add constraint` → new constraint `{ type: 'FIXED_BLOCK', label: 'Commute', start: '07:30', end: '08:15', days: MONDAY…FRIDAY }`, `isNew`.
  - `offers only goals without a times-per-week quota`: type `Times per week` → `Goal for the new constraint` lists `Claude certification` and not `Gym`.
  - `says when every goal has a quota`: draft where both goals have one → `Every goal already has one.`, no `Add constraint` button.

- [ ] **Step 2: Run** `npx vitest run src/components/SetupEditor.test.tsx`. Expected: new tests FAIL.

- [ ] **Step 3: Implement** the constraint rows and the add form.

- [ ] **Step 4: Run** the test file. Expected: PASS.

- [ ] **Step 5: Stage**, show stat, wait for the OK to commit `feat(setup): edit, add and remove constraints in the editor`.

---

### Task 5: SetupPage — editor mode, brain dump, Save and Discard

**Files:**
- Modify: `src/pages/SetupPage.tsx`, `src/pages/SetupPage.test.tsx`, `README.md` (step 1 of "Using it")

**Interfaces:**
- Consumes: Tasks 1–4 (`toDraft`, `toPayload`, `isChanged`, `hasProblems`, `mergeExtraction`, `SetupEditor`).

Behavior:
- State `mode: 'loading' | 'first' | 'saved'`. While loading render `h2 Setup` and `Loading…` only. `GET /api/setup` with goals → `saved` with `draft = toDraft(setup)`; no goals or a failure → `first`.
- `first`: today's flow, unchanged (boxes, Parse, Use example text, brain dump, `SetupPreview`, stale logic, Save). Remove `savedHint`, the box-emptying code and its comments: they only existed for a saved setup.
- `saved`: `SetupEditor` with the draft, `newGoalIds`, `warnings`; brain-dump box (label `Describe goals to add in your own words`, button `Add from text`) when `aiEnabled`; on success `mergeExtraction` → new draft, its warnings replace the shown warnings, its `addedGoalIds` are added to `newGoalIds`, the text box is cleared. Then the planned-days note, `Save` (disabled while busy or `hasProblems(draft)`) and `Discard changes` (only when `isChanged(draft, saved)`; resets draft, `newGoalIds` and warnings).
- Save in `saved` mode sends `toPayload(draft)`; success → `navigate('/')`; failure → alert, edits stay.

- [ ] **Step 1: Update tests.** Make `renderSetup` async and wait until `Loading…` is gone; `await` it everywhere. Delete the tests for the old saved-setup behavior: `shows the saved setup on a return visit`, `empties the boxes on a return visit…`, `enables Parse again once goals are typed…`, `keeps what the user typed when the saved setup arrives later`, `puts the example back into the boxes on a return visit…`. In `keeps the example and an enabled Parse on a first visit…` drop the `Your saved setup is shown below` assertion. Add:
  - `shows a saved setup as an editor without text boxes`: no `Goals` textbox, no `Parse`; `Remove Gym` and `Save` present; no `Discard changes`.
  - `saves a removed goal without its quota`: `Remove Gym`, `Save` → PUT body goals `[career-claude-certification]`, constraints `[Work]`, categories `[career]`; then `Today screen`.
  - `discards changes`: change Gym priority, `Discard changes` → priority back to 3, button gone.
  - `blocks Save while a field is invalid`: clear `Times per week for Gym` → `Save` disabled.
  - `keeps the edits when saving fails`: PUT → 400 problem → alert with its detail; Gym still removed.
  - `adds goals from the brain dump`: `aiEnabled`, `POST /api/setup/extract` returns a new goal `Azure AI` under `career` and a duplicate `Gym` → `Azure AI` listed with `new`; warning `Skipped goal "Gym": it already exists.` shown; PUT after Save contains `career-azure-ai` and the unchanged saved Gym.
  - `opens the first-visit flow when the saved setup cannot be loaded`: GET → 500 → `Goals` box with `EXAMPLE_GOALS`, no alert.

- [ ] **Step 2: Run** `npx vitest run src/pages/SetupPage.test.tsx`. Expected: new tests FAIL.

- [ ] **Step 3: Implement** the page as described.

- [ ] **Step 4: Run** `npm test` then `npm run build`. Expected: all tests pass, build clean.

- [ ] **Step 5: Update `README.md`** step 1: first visit as today; once saved, the page lists the setup to edit in place (add/remove goals and constraints, change priorities, deadlines and constraint values, add goals from a description) and one **Save**.

- [ ] **Step 6: Manual check** (`docker compose up -d`, backend `.\mvnw.cmd spring-boot:run`, `npm run dev`): with a saved setup, remove a goal, add a goal under a new category, add a fixed block, Save → Today loads; reopen Setup → changes are there, untouched goals keep energy/preferred time (check `GET /api/setup`).

- [ ] **Step 7: Stage**, show stat, wait for the OK to commit `feat(setup): edit a saved setup in place`.
