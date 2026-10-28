# One Visual Style Across Pages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Setup and Notes the same controls and cards as the Today page, add styled `Select` / `DateInput` /
`TimeInput` components, and document the rules in `frontend/UI.md`.

**Architecture:** Design tokens and zero-specificity base element rules live in `frontend/src/index.css`; three thin
wrapper components over native controls live in `frontend/src/components/form/`; pages only gain wrappers and
classes. No behavior change.

**Tech Stack:** React 19, TypeScript, plain CSS, Vitest + Testing Library (jsdom, `css: false`).

**Spec:** `docs/superpowers/specs/2026-10-28-shared-ui-style-design.md`

## Global Constraints

- Frontend only (`frontend/src`, `frontend/UI.md`, `CLAUDE.md`). No new npm dependency. No backend change.
- Existing tests pass **without edits**. Classes tests assert stay: `badge`, `toast`, `toast-error`,
  `visually-hidden`, `status-pill`, `status-*`, `block-title`.
- No new hex color in a component CSS file; use the tokens from spec 5.1 (exact names and values there).
- No layout-order, wording, heading-level or validation change. Today page gets no new wrappers.
- Base element rules (`button`, `input`, `select`, `textarea`) are written inside `:where(...)` so they have zero
  specificity and every existing class rule (`.sticky-card`, `.chat-toggle`, `.chat-send`, `.toast button`,
  `.search-field input`, `.chat-input input`) still wins. (Without this, `button:hover` would grey out sticky notes.)
- Working agreement: before each commit, stage, show `git diff --cached --stat`, wait for the user's approval.
  Work on `master`.

## Review Focus

1. Firefox shows its own calendar button inside `<input type="date">` and no `::-webkit-calendar-picker-indicator`:
   the drawn icon must be hidden there so there are not two icons → Task 2 CSS uses
   `@supports not selector(::-webkit-calendar-picker-indicator)` to hide `.date-field-icon`.
2. Long option text in a narrow `Select` must not run under the chevron → Task 2 CSS: `padding-right` on the
   select ≥ chevron width + gap; `text-overflow: ellipsis`.
3. A caller's `className` and sizing (`.chat-days select { max-width: 10rem }`) must still reach the native element
   → Task 2 test `passes className to the native select`.
4. Dark surfaces: `.toast button` must stay transparent with white text on hover, not turn `--color-surface-alt`
   → guaranteed by `:where()` base rules; Task 1 manual check hovers the toast's × button.
5. Disabled controls while saving must look disabled (select chevron dimmed too) → Task 2 CSS
   `.select:has(select:disabled) svg { opacity: .5 }`; Task 2 test asserts `disabled` reaches the native element.

---

### Task 1: Tokens, base controls and cards

**Files:**
- Modify: `frontend/src/index.css`
- Modify: `frontend/src/components/ChatPanel.css`, `ProposalCard.css`, `SetupEditor.css`,
  `frontend/src/pages/NotesPage.css`

**Interfaces:**
- Produces: CSS custom properties from spec 5.1 (`--color-*`, `--focus-ring`, `--radius-card`, `--radius-input`,
  `--radius-pill`); classes `.btn-primary`, `.btn-danger`, `.btn-small`, `.card`, `.card-header`.

- [ ] **Step 1: Add tokens to `:root`** in `index.css` with the exact names and values of spec 5.1. Point existing
  `:root` `color` / `background` at `--color-text` / `--color-bg`.
- [ ] **Step 2: Rewrite base element rules inside `:where()`**
  - `:where(button)`: `--radius-pill`, padding `0.35rem 1rem`, border `1px solid var(--color-input-border)`,
    background `--color-surface`, keep `margin-right: 0.4rem`, `font: inherit`, `cursor: pointer`.
    `:where(button:hover:not(:disabled))` → `--color-surface-alt`. `:where(button:focus-visible)` → no outline,
    `box-shadow: var(--focus-ring)`, border `--color-focus`. Keep the existing `button:disabled` rule.
  - `.btn-primary` (+ hover/disabled): copy of today's `.proposal-actions .proposal-apply` rules using tokens.
    `.btn-danger`: text and border `--color-danger`, white background. `.btn-small`: padding `0.15rem 0.7rem`,
    `font-size: 0.85rem`.
  - `:where(input:not([type='checkbox'], [type='radio']), select, textarea)`: background `--color-surface`, border
    `1px solid var(--color-input-border)`, `--radius-input`, padding `0.4rem 0.7rem`, `font: inherit`,
    `color: inherit`. Focus: no outline, border `--color-focus`, `box-shadow: var(--focus-ring)`. Disabled:
    `--color-surface-alt`. `[aria-invalid='true']`: border `--color-danger`.
  - Keep the existing `textarea` rule (monospace, full width, `margin-top`). `input[type='checkbox']`:
    `accent-color: var(--color-accent)`.
- [ ] **Step 3: Add `.card`, `.card-header`, `.card > :is(h3, h4):first-child { margin-top: 0 }`** per spec 5.3.
  `.warnings` gets `border-radius: var(--radius-input)`.
- [ ] **Step 4: Swap hex values for tokens** in the four component CSS files where a token has the same value.
  `.proposal-actions .proposal-apply` rules now reference `--color-accent` / `--color-accent-hover`. `.chat-card`
  uses `--color-surface`, `--color-border`, `--radius-card`. Leave sticky palettes, status pill and block-kind
  colors.
- [ ] **Step 5: Verify**
  Run: `cd frontend && npm test && npm run build` → all pass, no test edited.
  Run: `grep -nE '#534ab7|#d9e2ec|#bcccdc|#486581' frontend/src/components/*.css frontend/src/pages/*.css` → no
  output.
- [ ] **Step 6: Stage, show `git diff --cached --stat`, wait for approval, commit**
  `style(frontend): design tokens, shared controls and cards`

### Task 2: `Select`, `DateInput`, `TimeInput`

**Files:**
- Create: `frontend/src/components/form/FormControls.tsx`, `FormControls.css`, `FormControls.test.tsx`

**Interfaces:**
- Produces:
  - `export function Select(props: ComponentProps<'select'>): JSX.Element` → `<span className="select"><select
    {...props} /><svg className="select-chevron" aria-hidden="true" …/></span>`
  - `export function DateInput(props: Omit<ComponentProps<'input'>, 'type'>)` → `<span className="date-field">
    <input type="date" {...props} /><svg className="date-field-icon" aria-hidden="true" …/></span>`
  - `export function TimeInput(props: Omit<ComponentProps<'input'>, 'type'>)` → same with `type="time"` and a
    clock icon.

- [ ] **Step 1: Write failing tests** in `FormControls.test.tsx`:
  - `Select renders a native select with its options and label` — `render(<Select aria-label="Priority"
    defaultValue="2"><option value="1">1</option><option value="2">2</option></Select>)`;
    `expect(screen.getByLabelText('Priority')).toHaveValue('2')`; tagName `SELECT`.
  - `Select calls onChange on selectOptions` — `await user.selectOptions(getByLabelText('Priority'), '1')`;
    `expect(onChange).toHaveBeenCalledTimes(1)`.
  - `passes className, id and disabled to the native select` — `className="x" id="p" disabled` → native element
    `toHaveClass('x')`, `toHaveAttribute('id','p')`, `toBeDisabled()`; wrapper `toHaveClass('select')`.
  - `DateInput renders an input of type date` — `aria-label="Deadline"` → `toHaveAttribute('type','date')`;
    `fireEvent.change(el, { target: { value: '2026-11-01' } })` → `onChange` called, `toHaveValue('2026-11-01')`
    when controlled via a small wrapper with `useState`.
  - `TimeInput renders an input of type time` — same with `'09:30'`.
  - `icons are hidden from assistive tech` — for each component, `container.querySelector('svg')` has
    `aria-hidden="true"`.
- [ ] **Step 2: Run** `cd frontend && npx vitest run src/components/form/FormControls.test.tsx` → FAIL (module
  not found).
- [ ] **Step 3: Implement** the three components (signatures above) and import `./FormControls.css`. Icons: 24×24
  viewBox, rendered 16×16, `fill: none; stroke: currentColor; stroke-width: 1.8; round caps/joins` like
  `.chat-toggle svg`. Chevron path `M6 9l6 6 6-6`. Calendar: rect + two top ticks + one line. Clock: circle +
  hands.
- [ ] **Step 4: Write `FormControls.css`**
  - `.select, .date-field { position: relative; display: inline-flex; align-items: center; }`
  - `.select select { appearance: none; padding-right: 2rem; text-overflow: ellipsis; }`
  - `.select-chevron, .date-field-icon { position: absolute; right: 0.6rem; pointer-events: none; color:
    var(--color-subtle); }`; focus-within → `--color-focus`;
    `.select:has(select:disabled) .select-chevron { opacity: 0.5 }`.
  - `.date-field input { padding-right: 2rem; font-variant-numeric: tabular-nums; }`
  - `.date-field input::-webkit-calendar-picker-indicator { position: absolute; right: 0.4rem; width: 1.25rem;
    height: 1.25rem; opacity: 0; cursor: pointer; }`
  - `@supports not selector(::-webkit-calendar-picker-indicator) { .date-field-icon { display: none } }`
    (Firefox keeps its own button; Review Focus 1).
- [ ] **Step 5: Run** the test file → PASS; `npm run build` → passes.
- [ ] **Step 6: Stage, show stat, wait for approval, commit** `feat(frontend): styled Select, DateInput and TimeInput`

### Task 3: Use the components and button variants

**Files:**
- Modify: `frontend/src/components/SetupEditor.tsx`, `SetupPreview.tsx`, `SetupConstraints.tsx`, `ChatPanel.tsx`,
  `BlockCard.tsx`, `frontend/src/pages/SetupPage.tsx`, `NotesPage.tsx`
- Create: `frontend/src/test/noRawControls.test.ts`

**Interfaces:**
- Consumes: `Select`, `DateInput`, `TimeInput` from `../components/form/FormControls` (Task 2); `.btn-primary`,
  `.btn-danger`, `.btn-small` (Task 1).

- [ ] **Step 1: Write failing guard test** `noRawControls.test.ts`: read every `.tsx` under `src` except
  `components/form/` and `*.test.tsx` (node `fs.readdirSync(..., { recursive: true })`, `import.meta.dirname`);
  `expect(offenders).toEqual([])` where an offender is a file matching `/<select\b|type="date"|type="time"/`.
- [ ] **Step 2: Run** `npx vitest run src/test/noRawControls.test.ts` → FAIL listing SetupEditor, SetupPreview,
  SetupConstraints, ChatPanel.
- [ ] **Step 3: Swap elements** per spec 5.5 — same props, only the tag changes (`<select` → `<Select`,
  `<input type="date"` → `<DateInput`, `<input type="time"` → `<TimeInput`, drop the `type` prop).
- [ ] **Step 4: Add button classes** per spec 5.5: primary — Parse, both Saves, Extract goals and constraints,
  Add from text, Add goal, Add constraint, Save note, Search, Confirm postpone; danger — Remove (goal, constraint),
  Delete note, Discard changes; small — Remove buttons in goal and constraint rows (`btn-danger btn-small`) and
  Done / Postpone / Cancel / Confirm postpone / Never mind in `BlockCard`.
- [ ] **Step 5: Run** `cd frontend && npm test && npm run build` → all pass, no existing test edited.
- [ ] **Step 6: Stage, show stat, wait for approval, commit** `style(frontend): use shared form controls and button variants`

### Task 4: Card grouping on Setup and Notes

**Files:**
- Modify: `frontend/src/pages/SetupPage.tsx`, `frontend/src/components/SetupEditor.tsx`,
  `SetupConstraints.tsx`, `frontend/src/pages/NotesPage.tsx`, `NotesPage.css` (only if a rule now duplicates
  `.card`)

**Interfaces:**
- Consumes: `.card` (Task 1).

- [ ] **Step 1: Wrap in `<div className="card">`** exactly as spec 5.6 lists, keeping element order and heading
  levels:
  - FirstSetup: [goals field, constraints field, Parse, Use example text]; [brain-dump field] when AI; [preview
    heading, `SetupPreview`, notes, Save]. The `role="alert"` error stays between, outside cards.
  - SetupEditor: warnings stay first, outside; [Goals h4 … `AddGoalForm`]; `SetupConstraints` returns its content
    inside one card instead of a fragment. SavedSetup: brain-dump field in a card; note, hint, Save, Discard below.
  - NotesPage: results list (when non-empty) in a card; [Edit/New note h3, title field, body field, Save note,
    Delete note] in a card. Search form, sticky board, toast untouched.
- [ ] **Step 2: Run** `cd frontend && npm test && npm run build` → all pass, no test edited.
- [ ] **Step 3: Manual check** (`docker compose up -d`; backend `.\mvnw.cmd spring-boot:run`; `npm run dev`):
  Today, Setup first visit (clear setup via a fresh DB or test data), Setup saved editor, Notes — cards present;
  pill buttons; select chevron opens native list; date/time icons open native picker in Chrome/Edge; Tab shows
  focus ring; Save shows disabled controls; hover toast × stays readable; sticky notes keep colors on hover;
  narrow window (~400px) rows wrap, no horizontal scroll.
- [ ] **Step 4: Stage, show stat, wait for approval, commit** `style(frontend): group Setup and Notes sections in cards`

### Task 5: UI guide

**Files:**
- Create: `frontend/UI.md`
- Modify: `CLAUDE.md` (Frontend architecture section)

- [ ] **Step 1: Write `frontend/UI.md`** (about one screen) with the content of spec 5.7: token table (copy of
  spec 5.1), base control rules incl. the `:where()` zero-specificity note, button variants with examples,
  `.card` / `.card-header`, `Select` / `DateInput` / `TimeInput` usage snippet and the styled-native reason, the
  five rules, and the test-asserted class list.
- [ ] **Step 2: Add one bullet to `CLAUDE.md`** under "Frontend architecture": "Styling: design tokens and shared
  control/card styles in `index.css`, form components (`Select`, `DateInput`, `TimeInput`) in
  `components/form/`; read `frontend/UI.md` before adding UI."
- [ ] **Step 3: Verify** links/paths in both files exist: `ls frontend/UI.md frontend/src/components/form/FormControls.tsx`.
- [ ] **Step 4: Stage, show stat, wait for approval, commit** `docs(frontend): UI guide for shared styles and form components`
