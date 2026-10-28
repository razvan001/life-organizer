# UI guide

Every page shares one look: the one the Today page's Assistant panel set. Read this before adding or restyling UI.
Design background: `docs/superpowers/specs/2026-10-28-shared-ui-style-design.md`.

## Design tokens (`src/index.css`, `:root`)

| Token | Value | Use for |
| --- | --- | --- |
| `--color-text` | `#1f2933` | body text |
| `--color-ink` | `#243b53` | strong secondary text |
| `--color-muted` | `#627d98` | `.muted`, hints |
| `--color-subtle` | `#829ab1` | placeholders, icons |
| `--color-border` | `#d9e2ec` | card and block borders |
| `--color-input-border` | `#bcccdc` | control borders |
| `--color-focus` | `#486581` | focused control border |
| `--color-surface` / `--color-surface-alt` | `#fff` / `#f0f4f8` | cards, controls / hover, disabled |
| `--color-bg` | `#f5f7fa` | page background |
| `--color-accent` / `--color-accent-hover` | `#534ab7` / `#3c3489` | primary buttons, highlights |
| `--color-accent-soft` / `--color-accent-border` | `#eeedfe` / `#cecbf6` | chips, avatar |
| `--color-danger` | `#b42318` | errors, danger buttons |
| `--focus-ring` | `0 0 0 3px rgba(72, 101, 129, 0.2)` | every focused control |
| `--radius-card` / `--radius-input` / `--radius-pill` | `12px` / `8px` / `999px` | cards / inputs / buttons, chips |

Colors with one local meaning (sticky-note palettes, status pills, block kinds) may stay as literals.

## Base controls (`src/index.css`)

Plain `<button>`, `<input>`, `<select>` and `<textarea>` are already styled: pill buttons, rounded inputs, focus
ring, disabled and `aria-invalid` looks. These rules sit inside `:where(...)`, so they have **zero specificity**:
any class rule wins without `!important` (that is how `.sticky-card`, `.chat-send`, `.toast button` and
`.search-field input` keep their own look). Keep new base rules inside `:where()`.

Button variants, combinable:

```tsx
<button className="btn-primary">Save</button>               // the main action of a section
<button className="btn-danger">Delete note</button>         // removes or discards something
<button className="btn-danger btn-small">Remove Gym</button> // inside a list row
```

## Cards

```tsx
<div className="card">
  <h4>Goals</h4>  {/* a first-child heading has no top margin */}
  ...
</div>
```

`.card-header` is a flex row for a title on the left and actions on the right.

## Form components (`src/components/form/FormControls.tsx`)

| Use | Instead of |
| --- | --- |
| `<Select aria-label="Priority" value={…} onChange={…}>{options}</Select>` | `<select>` |
| `<DateInput aria-label="Deadline" value={…} onChange={…} />` | `<input type="date">` |
| `<TimeInput aria-label="Start" value={…} onChange={…} />` | `<input type="time">` |

They take every prop of the native element (except `type`) and pass it through, `className` included. They are
**styled native** controls: the closed control gets a chevron / calendar / clock icon, the popup (option list,
calendar, clock) stays the browser's own. That keeps keyboard use, screen readers, mobile pickers and
`userEvent.selectOptions` in tests working. Firefox keeps its own date / time button and the drawn icon is hidden.

## Rules

- Use tokens. Never add a new hex value to a component CSS file.
- Never write a raw `<select>`, `<input type="date">` or `<input type="time">` outside `components/form/`.
  `src/test/noRawControls.test.ts` fails if you do.
- Every control has an accessible name: `<label htmlFor>` or `aria-label`. Icons are `aria-hidden`.
- A component's CSS file sits next to it and is imported from its `.tsx`.
- Keep the classes tests assert on: `badge`, `toast`, `toast-error`, `visually-hidden`, `status-pill`,
  `status-*`, `block-title`.
