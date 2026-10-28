# Life Organizer — One Visual Style Across Pages — Design Spec

- **Date:** 2026-10-28
- **Status:** Approved 2026-10-28.
- **Builds on:** Phase 1b frontend (`2026-09-19-life-organizer-phase1b-design.md`), the Phase 3b chat panel
  (`2026-09-30-life-organizer-phase3b-chat-agent-design.md`), inline setup editing
  (`2026-10-28-setup-inline-editing-design.md`).

## 1. Why

The Today page has a finished look: the Assistant panel is a white card with rounded corners, its input is a pill
with a soft focus ring, the main action is a filled purple pill button, and shortcuts are chips. The Setup and
Notes pages still use bare browser controls: default `<select>` boxes, plain `<input type="date">` and
`<input type="time">`, square grey buttons and unstyled text inputs. The pages look like two different apps.

The colors are also written out by hand in every CSS file (`#534ab7`, `#d9e2ec`, `#486581`, ...), so a new
component has to copy hex values from somewhere and easily drifts.

Target: every page uses the same controls and cards as the Today page, there are ready-made styled components for
dropdowns and date / time pickers, and a short guide tells the next change which ones to use.

## 2. Goals

- One set of design tokens (CSS custom properties) for colors, radii and the focus ring, used by every CSS file.
- Buttons, text inputs, text areas, selects and checkboxes look the same on every page, without per-page CSS.
- Button variants for the main action (primary), destructive actions (danger) and compact rows (small).
- A `.card` container that matches the Assistant panel, used to group sections on Setup and Notes.
- Three new components: `Select`, `DateInput`, `TimeInput`, styled to match, used everywhere a dropdown or date /
  time field appears today.
- A UI guide, `frontend/UI.md`, referenced from `CLAUDE.md`.

## 3. Non-goals

- **No custom popups.** The list of a dropdown and the calendar / clock of a date or time field stay the
  browser's own. Only the closed control is styled.
- No dark mode.
- No change to layout order, page behavior, wording or validation. Only wrappers and classes are added.
- No backend change. No new npm dependency.
- The sticky-note board on the Notes page and the timeline blocks on the Today page keep their own look.

## 4. Anti-goals (a failure even if it works)

- An existing test has to change because a label, role or class it relies on disappeared. Classes tests assert
  on today stay: `badge`, `toast`, `toast-error`, `visually-hidden`, `status-pill`, `status-*`, `block-title`.
- A styled control loses its accessible name, its keyboard behavior, or the native picker on mobile.
- The Today page visibly changes (beyond buttons gaining the shared pill look).
- A new hex color appears in a component CSS file instead of a token.

## 5. Design

### 5.1 Design tokens — `frontend/src/index.css`

`:root` custom properties, taken from values the app already uses so the rendered colors do not change:

| Token | Value | Used for |
| --- | --- | --- |
| `--color-text` | `#1f2933` | body text |
| `--color-ink` | `#243b53` | strong secondary text |
| `--color-muted` | `#627d98` | `.muted`, hints |
| `--color-subtle` | `#829ab1` | placeholders, icons |
| `--color-border` | `#d9e2ec` | card and block borders |
| `--color-input-border` | `#bcccdc` | control borders |
| `--color-focus` | `#486581` | focused control border |
| `--color-surface` | `#fff` | cards, controls |
| `--color-surface-alt` | `#f0f4f8` | hover, disabled controls |
| `--color-bg` | `#f5f7fa` | page background |
| `--color-accent` | `#534ab7` | primary buttons, highlights |
| `--color-accent-hover` | `#3c3489` | primary hover |
| `--color-accent-soft` | `#eeedfe` | chips, avatar |
| `--color-accent-border` | `#cecbf6` | chip border |
| `--color-danger` | `#b42318` | errors, danger buttons |
| `--focus-ring` | `0 0 0 3px rgba(72, 101, 129, 0.2)` | every focused control |
| `--radius-card` | `12px` | cards |
| `--radius-input` | `8px` | inputs, selects, text areas |
| `--radius-pill` | `999px` | buttons, chips, search |

`ChatPanel.css`, `ProposalCard.css`, `NotesPage.css` and `SetupEditor.css` swap their hex values for these tokens.
Colors with a single, local meaning (sticky-note palettes, status pill colors, block kind colors) may stay as
they are.

### 5.2 Base controls — `frontend/src/index.css`

- `button`: pill shape, white background, `--color-input-border` border, hover `--color-surface-alt`,
  `:focus-visible` shows `--focus-ring`. The existing small right margin stays so inline button rows keep their
  spacing.
- `.btn-primary`: accent background, white text, accent hover. `.proposal-apply` uses the same rules.
- `.btn-danger`: danger text and border on white; for Remove, Delete note and Discard changes.
- `.btn-small`: smaller padding and font, for the buttons inside goal and constraint rows and on timeline blocks.
- Text inputs (every `input` except checkbox and radio), `textarea`, `select`: white background,
  `--color-input-border` border, `--radius-input`, `font: inherit`, comfortable padding. On focus: border
  `--color-focus` plus `--focus-ring`, no outline. Disabled: `--color-surface-alt`. `[aria-invalid='true']`:
  danger border. The setup text areas keep their monospace font and full width.
- Checkboxes: `accent-color: var(--color-accent)`.
- Inputs that already have their own look (`.search-field input`, `.chat-input input`) keep it; their rules are
  more specific and win.

### 5.3 Cards — `frontend/src/index.css`

- `.card`: white surface, `--color-border` border, `--radius-card`, padding `1rem`, space below.
- `.card-header`: a flex row, title on the left and actions on the right; its heading has no top margin.
- A heading that is the first child of a card has no top margin.
- The Assistant panel (`.chat-card`) uses the same tokens, so it and the new cards look alike.

### 5.4 Form components — `frontend/src/components/form/`

`FormControls.tsx` with its own `FormControls.css`, imported from the `.tsx` file as `ChatPanel.tsx` does.

- **`Select`** takes every prop of a native `<select>` (`ComponentProps<'select'>`) and passes them through,
  children included. It renders the `<select>` inside a `<span className="select">` wrapper. The CSS removes the
  browser's arrow (`appearance: none`), leaves room on the right and draws a chevron as an inline SVG
  (`aria-hidden`, `pointer-events: none`) in the same stroke style as the Assistant collapse button.
- **`DateInput`** and **`TimeInput`** take every prop of a native `<input>` except `type`
  (`Omit<ComponentProps<'input'>, 'type'>`) and render `<input type="date">` / `<input type="time">` inside a
  `<span className="date-field">` wrapper with a calendar or clock SVG icon. The browser's own picker button
  (`::-webkit-calendar-picker-indicator`) is made transparent and placed over the drawn icon, so a click on the
  icon still opens the native picker. Digits use tabular numerals.
- `className` given by the caller goes on the native element, so existing sizing rules (`.chat-days select`)
  keep working. `ref` is an ordinary prop in React 19; no `forwardRef`.

Why styled native instead of custom popups: native controls keep keyboard support, screen reader support and the
mobile date / time wheels for free, and every existing test (`userEvent.selectOptions`, `getByLabelText`,
`toHaveValue`) keeps working unchanged. A custom listbox and calendar would mean owning all of that.

### 5.5 Where they are used

- `SetupEditor.tsx`: goal priority (`Select`), goal deadline (`DateInput`), new-goal category (`Select`).
- `SetupPreview.tsx`: goal priority (`Select`), goal deadline (`DateInput`).
- `SetupConstraints.tsx`: fixed block start and end (`TimeInput`), constraint type and target (`Select`).
- `ChatPanel.tsx`: the earlier-chats picker (`Select`).
- After this change there is no raw `<select>`, `<input type="date">` or `<input type="time">` left in
  `frontend/src`.

Button variants:

- Primary: Parse, Save (both setup flows), Extract goals and constraints, Add from text, Add goal,
  Add constraint, Save note, Search, Confirm postpone.
- Danger: Remove (goal, constraint), Delete note, Discard changes.
- Small: buttons inside goal and constraint rows, and the Done / Postpone / Cancel buttons on timeline blocks.

### 5.6 Card grouping per page

Cards only wrap existing content; the order of elements and the heading levels stay the same.

- **Setup, first visit:** one card for the Goals and Constraints text boxes with Parse and Use example text; one
  card for the brain dump (when the assistant is configured); one card for the parsed preview, the planned-days
  note and Save.
- **Setup, saved:** a Goals card (goal list and the add-goal form) and a Constraints card (constraint list and
  the add-constraint form) inside `SetupEditor`; a card for the brain dump; Save and Discard changes below the
  cards. The "Not added" warnings stay above the cards.
- **Notes:** the search field keeps its pill look; search results go in a card; the note editor (heading, title,
  body, Save note, Delete note) goes in a card. The sticky board stays as it is.
- **Today:** no new wrappers. (The page tests count list items; nothing is added to the timeline.)

### 5.7 UI guide — `frontend/UI.md`

A short guide (about one screen) with: the token table, the base control rules, the button variants, `.card` and
`.card-header`, the three form components and when to use them, and these rules:

- use tokens, never a new hex value in a component CSS file;
- never write a raw `<select>`, `<input type="date">` or `<input type="time">`; use the components;
- every control has an accessible name (`<label htmlFor>` or `aria-label`);
- a component's CSS file sits next to it and is imported from its `.tsx`;
- keep the classes that tests assert on (list in section 4).

`CLAUDE.md`, Frontend architecture: one bullet pointing to `frontend/UI.md`, the tokens in `index.css` and the
components in `components/form/`.

## 6. Accessibility

- The components pass every prop through, so labels (`aria-label`, `id` with `<label htmlFor>`) work as before.
- The chevron, calendar and clock icons are decorative and `aria-hidden`.
- Every focusable control shows `--focus-ring` on keyboard focus.
- The native popups keep their keyboard handling and the mobile pickers.
- Contrast: primary buttons are white on `#534ab7`; danger text is `#b42318` on white. Both pass WCAG AA for
  normal text.
- `prefers-reduced-motion`: no new animations are added.

## 7. Constraints on the build

- Frontend only (`frontend/src`, plus `frontend/UI.md` and `CLAUDE.md`). No new libraries.
- Vitest + Testing Library, `test/mockApi.ts` as today. Existing tests must pass without edits.

## 8. Testing

- New `FormControls.test.tsx`: each component renders its native element with the right `type`; `aria-label`,
  `id`, `value`, `disabled` and `className` reach the native element; `userEvent.selectOptions` on `Select` and
  typing into `DateInput` / `TimeInput` call `onChange`; the icon is hidden from the accessibility tree.
- `npm test`: the whole suite passes, existing tests unchanged.
- `npm run build`: type check passes.
- Manual check in the running app: Today, Setup (first visit and saved editor) and Notes show the cards and pill
  buttons; a select opens the native list; the date and time icons open the native picker; Tab shows the focus
  ring; controls look disabled while saving.

## 9. Left to the builder

- Exact padding and font sizes, within the look of the Assistant panel.
- SVG paths of the calendar and clock icons.
- Whether `.chat-card` is rewritten on top of `.card` or only switched to tokens.
