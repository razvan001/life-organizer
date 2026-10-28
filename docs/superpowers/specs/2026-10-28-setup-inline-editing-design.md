# Life Organizer — Edit a Saved Setup Inline — Design Spec

- **Date:** 2026-10-28
- **Status:** Approved 2026-10-28.
- **Builds on:** Phase 1b Setup page (`2026-09-19-life-organizer-phase1b-design.md`), brain-dump extraction
  (`2026-09-26-brain-dump-extraction-design.md`).

## 1. Why

The user opens the Setup page mostly to change priorities or update goals: add or drop a goal, change a deadline,
change how often something happens. Today the page always shows the Goals / Constraints text boxes. With a saved
setup, the boxes are emptied and the only way to add a goal or change a constraint is to retype the whole tree and
press Parse. That is too much work for a small change, and it is easy to lose settings on the way.

Target: once a setup is saved, the page is an editor for that setup. Every change happens directly in the list on
screen, then one Save.

## 2. Goals

With a saved setup, the user can, directly in the list:

- change a goal's priority and deadline (as today);
- remove a goal;
- add a goal, under an existing category or a new category named on the spot;
- edit each constraint's values;
- remove a constraint;
- add a constraint of any of the four types;
- add goals and constraints by describing them in the brain-dump box (when the assistant is configured);
- save all changes at once.

## 3. Non-goals

- **No renaming** of goals or categories. A goal is added or removed, never renamed.
- **No editing a goal's other settings** (block length, energy, preferred time) in this change. They keep their
  saved values; new goals get defaults.
- **No text boxes for a saved setup.** The Goals / Constraints boxes, Parse and "Use example text" are gone once a
  setup is saved. There is no "rewrite everything" button.
- **No backend change.** `PUT /api/setup` already replaces the whole setup and validates it.
- No change to how already planned days behave: they keep their plan, days not planned yet use the new setup.

## 4. Anti-goals (a failure even if it works)

- Settings the user did not touch change on Save (priority, deadline, block length, energy, preferred time,
  session minutes, fixed-block days).
- A goal the user kept gets a new id, so its history no longer matches.
- Save fails with a backend validation error the page could have caught first (empty name, duplicate id,
  constraint pointing to a removed goal).
- The brain dump replaces the saved setup instead of adding to it.

## 5. Behavior

### 5.1 Two states of the page

- **No saved setup** (`GET /api/setup` returns no goals, or fails): unchanged from today. Text boxes with the
  example, Parse, "Use example text", brain dump, preview, Save. After the first Save the page goes to Today.
- **Saved setup**: the editor below. No text boxes. While `GET /api/setup` is loading, show "Loading…" only, so
  the page never flashes the boxes before the saved setup arrives.

### 5.2 Goals

- Shown grouped by category, as in today's preview. Each goal row keeps priority and deadline editable.
- Each goal row has a **Remove** button. Removing a goal also removes every constraint that points to it
  (`FREQUENCY_QUOTA` with that `goalId`). The page says so next to the button or in the row, for example
  "also removes: Gym 4× per week".
- **Add goal**: a goal name and a category. The category is a choice of the existing categories plus "New
  category…", which shows a name field.
  - New goal id: `<category id>-<slug of goal name>`; new category id: `<slug of category name>`. The slug rule is
    the backend's `Slug.of`: lower case, every run of characters outside `a-z0-9` becomes one `-`, leading and
    trailing `-` removed, empty becomes `item`. These are the ids the text parser would have made.
  - A new goal gets the parser defaults: priority 3, no deadline, 60-minute blocks, energy MEDIUM, preferred time
    ANY. The user can change priority and deadline in its row right away.
  - The add is refused, with a message, when the name is empty, longer than 255 characters, or its id already
    exists. The same for a new category whose id already exists (the user should pick it from the list instead).
- A category with no goals left is removed on Save, unless a constraint still points to it. In that case it stays
  and is shown with "no goals".

### 5.3 Constraints

Shown as a list, one row per constraint, editable by type:

| Type | Fields in the row |
|------|-------------------|
| Fixed block | label, start, end (time inputs), days (7 day toggles) |
| Times per week (`FREQUENCY_QUOTA`) | goal (shown, not editable), times per week, minutes per session |
| Hours per week (`DURATION_QUOTA`) | category (shown, not editable), hours per week |
| Evenings per week (`EVENING_QUOTA`) | category (shown, not editable), evenings per week |

- Each row has a **Remove** button.
- **Add constraint**: pick the type, then fill its fields. A times-per-week constraint picks one of the current
  goals; hours-per-week and evenings-per-week pick one of the current categories. The label of a new quota is the
  goal or category name; a fixed block's label is typed by the user.
- **At most one quota per type and target.** A goal has at most one times-per-week quota; a category has at most
  one hours-per-week and at most one evenings-per-week quota. The planner counts each quota separately, so a second
  one would plan the goal twice as often. In the add form, a goal or category that already has a quota of the
  chosen type cannot be picked; the user edits the existing quota instead.
- Hours per week is entered in hours (decimals allowed) and stored as `minutesPerWeek` (rounded to whole minutes).
- Values are checked before they are accepted: label 1–255 characters, start and end as `HH:mm`, at least one day,
  counts and minutes whole numbers ≥ 1, hours > 0. Invalid input shows a message next to the field and blocks Save.

### 5.4 Brain dump with a saved setup

- The brain-dump box stays (only when `aiEnabled`), now as a way to **add**. The extraction service already sends
  the existing categories to the model and reuses their ids.
- The model sees only the existing categories, not the goals, and invents its own goal ids. So the page first
  gives every extracted goal the id rule of 5.2 (`<category id>-<slug of goal name>`) and rewrites the `goalId`
  of the extracted constraints that point to it. Extracted categories that are new get `<slug of name>` the same
  way, unless the model reused an existing category id.
- The result is then **added** to the setup on screen, never replaces it:
  - new categories are added, existing categories are kept as they are;
  - new goals are added; a goal whose id (after the rule above) already exists is skipped, and the existing goal
    is left untouched;
  - constraints are added; a constraint is skipped when it points to a goal or category that does not exist
    after the merge, or when its target already has a quota of that type (5.3);
  - every skipped item and the model's own warnings are listed as warnings above the editor.
- Known limit, accepted: the brain dump cannot add a quota to a goal that already exists ("gym 4 times a week"
  when Gym is saved). The backend drops such a quota because the model did not create that goal in its own
  answer, and fixing that needs a backend change (non-goal). The user adds it by hand.
- Nothing is saved until Save. The added items are marked as new in the list until then.

### 5.5 Save

- One **Save** button for all changes. It is disabled while a field is invalid. After a successful Save the page
  goes to Today, as now.
- **Discard changes** puts the list back to the saved setup. Shown only when something changed.
- The note "Days that are already planned keep their plan. Days that are not planned yet use this setup." stays
  next to Save.
- A backend error (for example 400) is shown as today and the edits stay on screen.

## 6. Constraints on the build

- Frontend only (`frontend/src`): `SetupPage`, `SetupPreview` (or a new editor component next to it), small
  helpers under `src/setup/`. No new libraries.
- Ids must match the backend's `Slug.of` exactly (see 5.2).
- Keep `api/client.ts` as the only place that knows URLs; no new endpoints are needed.
- Tests: Vitest + Testing Library, with `test/mockApi.ts` as today.

## 7. Left to the builder

- Layout and wording of the add and remove controls, and of the add-constraint form.
- Whether the editor is a new component or an extended `SetupPreview` (the first visit still needs the read-only
  preview after Parse).
- How "new" items and "also removes" hints look.

## 8. Work already done

A staged, uncommitted change hides the boxes behind an "Edit goals and constraints" button that writes the saved
setup back as text (`setupToText`, `keepSavedSettings`). This spec replaces that approach: the change is to be
discarded before building.
