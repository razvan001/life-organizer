# Life Organizer Phase 1b Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A React UI for the finished Phase 1b backend: a Setup page (paste goals and constraints, preview, edit priorities and deadlines, save) and a Today page (timeline with Done / Postpone / Cancel and a change toast).

**Architecture:** Plain React + TypeScript built by Vite. `api/client.ts` wraps `fetch` and turns RFC 7807 error bodies into a typed `ApiError`; a small `useApi` hook gives pages `{data, loading, error}`. Pages hold their own state with `useState`. React Router has two routes. Vite proxies `/api` to Spring Boot on port 8080, so the browser only ever talks to one origin and the backend needs no CORS setup. Tests use Vitest + React Testing Library with `fetch` replaced by a small route-based mock.

**Tech Stack:** Node 22.12+ (Vitest 5 requires it; Vite alone needs 20.19+) (tested with Node 24, npm 11), React 19.2, react-router-dom 7, Vite 8, Vitest 5 (jsdom), @testing-library/react 16 + user-event 14 + jest-dom 7, TypeScript ~6.0. All versions are the caret ranges in Task 1's `package.json`; they were installed and exercised together in a scratch project (57 tests + `npm run build` green) before this plan was written.

## Global Constraints

- **No data-fetching or state library.** No TanStack Query, no Redux, no Axios. `fetch` + `useState` + `useEffect` only. (User is new to the React tooling: keep the code plain and commented where a concept is non-obvious.)
- **Router:** `react-router-dom` with exactly two routes: `/` (Today) and `/setup` (Setup). **Styling:** one plain CSS file, no CSS framework.
- **Same origin:** the code calls relative URLs (`/api/...`). Never hard-code `http://localhost:8080` in source; the Vite dev proxy in `vite.config.ts` forwards `/api` to `http://localhost:8080`. The backend has no CORS configuration and must not get one.
- **Keep React `<StrictMode>` in `main.tsx`.** It fires the first `GET /api/plan/today` twice in development; the backend's advisory lock makes that safe (regression-tested there) and `useApi` ignores the stale answer.
- **Wire format (verbatim from the backend DTOs):** times are `"HH:mm"`, dates are `"yyyy-MM-dd"`, enums are upper-case strings, optional values are `null`. **A goal without a deadline must be sent as `deadline: null`** (an empty date input yields `""`; convert it). Only send events `MARK_DONE`, `CANCEL_BLOCK`, `POSTPONE_BLOCK` (`reason` omitted when blank).
- **Error shape:** every backend error is `application/problem+json` `{status, title, detail}`. `404` with title `"Setup required"` on `GET /api/plan/today` means: redirect to `/setup`. Other errors show `detail`. A network failure shows "Could not reach the server. Is the backend running on port 8080?".
- **Write files with the file-writing tool, not shell heredocs or `echo`:** several files contain non-ASCII characters (`×`, `–`, `├──`) that break the shell wrapper. Keep shell commands ASCII-only.
- **Shell-agnostic commands:** run npm through `npm --prefix frontend ...` from the repository root (works in PowerShell 5.1 and Git Bash, which has no `&&`).
- **Commit messages** end with the trailer line `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`.
- **Known limitation carried from the backend (do not "fix" in the UI):** saving a changed setup does not touch days that are already planned; the Setup page says so.

## Spec deltas (vs. `docs/superpowers/specs/2026-09-19-life-organizer-phase1b-design.md`, "Frontend design")

| Spec says | This plan does | Why |
|---|---|---|
| Preview has "a category `<select>` for any unmapped label" | Lines the parser could not map are shown as warnings ("Not understood (these lines are ignored)"); the user edits the text and parses again | The finished backend drops an unmappable quota line and only returns a warning: there is no constraint object to attach a category to |
| Preview edits priority | Also edits each goal's deadline (`<input type="date">`) | The deadline boosts a goal's weight; it is one small input and it exercises the `null`-deadline contract end to end |
| `GET /api/config` "lets the frontend hide AI-only UI" | The UI does not call `/api/config` | This slice has no AI-only UI to hide |
| Timeline "sorted by start time" | Sorted client-side as well, although the backend already sends sorted blocks | Cheap, and keeps the component correct on its own |
| No energy toggle / add-event form | Same: deferred | Unchanged scope note in the spec |

## File Structure

```
frontend/
├── package.json, package-lock.json, tsconfig*.json, vite.config.ts, index.html
└── src/
    ├── main.tsx                     StrictMode + BrowserRouter
    ├── App.tsx                      header, nav, the two routes
    ├── index.css                    all styling
    ├── api/
    │   ├── types.ts                 TS mirrors of the backend DTOs
    │   ├── client.ts                fetch wrapper, ApiError, isSetupRequired, errorMessage, the `api` object
    │   └── useApi.ts                {data, loading, error, refetch, setData}
    ├── components/
    │   ├── BlockCard.tsx            one block + Done / Postpone (reason) / Cancel
    │   ├── Timeline.tsx             blocks sorted by start
    │   ├── ChangeToast.tsx          the last event's `changes[]`, auto-hides
    │   └── SetupPreview.tsx         parsed goals (priority, deadline), constraints, warnings
    ├── pages/
    │   ├── TodayPage.tsx
    │   └── SetupPage.tsx
    ├── setup/
    │   ├── example.ts               the example goals/constraints that prefill the page
    │   └── describeConstraint.ts    one readable line per constraint
    └── test/
        ├── setup.ts                 jest-dom matchers + cleanup
        ├── mockApi.ts               route-based fetch mock
        └── fixtures.ts              block / plan / parse-response test data
```

---

### Task 1: Project scaffold and API layer

**Files:**
- Create: `frontend/package.json`, `frontend/tsconfig.json`, `frontend/tsconfig.app.json`, `frontend/tsconfig.node.json`, `frontend/vite.config.ts`, `frontend/index.html`
- Create: `frontend/src/main.tsx`, `frontend/src/App.tsx` (placeholder, replaced in Task 5), `frontend/src/index.css`
- Create: `frontend/src/test/setup.ts`, `frontend/src/test/mockApi.ts`, `frontend/src/test/fixtures.ts`
- Create: `frontend/src/api/types.ts`, `frontend/src/api/client.ts`, `frontend/src/api/useApi.ts`
- Test: `frontend/src/api/client.test.ts`, `frontend/src/api/useApi.test.tsx`

**Interfaces:**
- Consumes: the backend REST API (already running code; nothing from earlier frontend tasks).
- Produces (later tasks rely on these exact names):
  - `api/types.ts`: `Energy`, `PreferredTime`, `CategoryDto`, `GoalDto`, `ConstraintType`, `ConstraintDto`, `SetupPayload`, `SetupParseRequest`, `SetupParseResponse`, `BlockKind`, `BlockStatus`, `BlockDto`, `TodayPlanResponse`, `ApplyEventResponse`, `PlanEventRequest`.
  - `api/client.ts`: `class ApiError extends Error {status, title, detail}`; `api.parseSetup(request)`, `api.getSetup()`, `api.saveSetup(setup)`, `api.getToday()`, `api.applyEvent(event)` (all return promises; `api.getToday` and the others are stable references); `isSetupRequired(error: unknown): boolean`; `errorMessage(error: unknown): string`.
  - `api/useApi.ts`: `useApi<T>(load: () => Promise<T>)` returning `{data: T | null, error: unknown, loading: boolean, refetch(): void, setData(data: T): void}`.
  - `test/mockApi.ts`: `jsonResponse(body, status?)`, `problemResponse(status, title, detail)`, `mockApi(routes)` (keys are `"METHOD /path"`; returns the `vi.fn` mock; tests call `vi.unstubAllGlobals()` afterwards).
  - `test/fixtures.ts`: `makeBlock(overrides?)`, `TODAY` (`TodayPlanResponse` with Gym 07:00, Work 08:30 FIXED, Claude certification 18:00 id `career-claude-certification-2026-09-14`), `PARSED` (`SetupParseResponse` with categories career/health, goals Claude certification (priority 3, deadline `2026-10-01`) and Gym (priority 3, deadline null), constraints Work + Gym, one warning).

- [ ] **Step 1: Write the project files**

Use the file-writing tool for every file. `frontend/package.json`:

```json
{
  "name": "life-organizer-frontend",
  "private": true,
  "version": "0.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "react": "^19.2.8",
    "react-dom": "^19.2.8",
    "react-router-dom": "^7.18.4"
  },
  "devDependencies": {
    "@testing-library/jest-dom": "^7.0.1",
    "@testing-library/react": "^16.3.3",
    "@testing-library/user-event": "^14.6.7",
    "@types/node": "^24.13.3",
    "@types/react": "^19.2.18",
    "@types/react-dom": "^19.2.7",
    "@vitejs/plugin-react": "^6.1.1",
    "jsdom": "^29.1.1",
    "typescript": "~6.0.2",
    "vite": "^8.3.0",
    "vitest": "^5.0.1"
  }
}
```

`frontend/tsconfig.json`:

```json
{
  "files": [],
  "references": [
    { "path": "./tsconfig.app.json" },
    { "path": "./tsconfig.node.json" }
  ]
}
```

`frontend/tsconfig.app.json`:

```json
{
  "compilerOptions": {
    "tsBuildInfoFile": "./node_modules/.tmp/tsconfig.app.tsbuildinfo",
    "target": "es2023",
    "lib": ["ES2023", "DOM"],
    "module": "esnext",
    "types": ["vite/client"],
    "allowArbitraryExtensions": true,
    "skipLibCheck": true,

    /* Bundler mode */
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "verbatimModuleSyntax": true,
    "moduleDetection": "force",
    "noEmit": true,
    "jsx": "react-jsx",

    /* Linting */
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "erasableSyntaxOnly": true,
    "noFallthroughCasesInSwitch": true
  },
  "include": ["src"]
}
```

`frontend/tsconfig.node.json`:

```json
{
  "compilerOptions": {
    "tsBuildInfoFile": "./node_modules/.tmp/tsconfig.node.tsbuildinfo",
    "target": "es2023",
    "lib": ["ES2023"],
    "types": ["node"],
    "skipLibCheck": true,

    /* Bundler mode */
    "module": "nodenext",
    "allowImportingTsExtensions": true,
    "verbatimModuleSyntax": true,
    "moduleDetection": "force",
    "noEmit": true,

    /* Linting */
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "erasableSyntaxOnly": true,
    "noFallthroughCasesInSwitch": true
  },
  "include": ["vite.config.ts"]
}
```

`frontend/vite.config.ts` (Vite dev proxy for `/api`, plus the Vitest settings):

```ts
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // The browser talks to Vite (same origin); Vite forwards /api calls to Spring Boot.
    // This is why the backend needs no CORS configuration.
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
})
```

`frontend/index.html`:

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Life Organizer</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

`frontend/src/main.tsx`:

```tsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App.tsx'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
)
```

`frontend/src/App.tsx` (placeholder; Task 5 replaces it with the routed version):

```tsx
export default function App() {
  return <h1>Life Organizer</h1>
}
```

`frontend/src/index.css`:

```css
:root {
  font-family: system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif;
  color: #1f2933;
  background: #f5f7fa;
  line-height: 1.4;
}

body {
  margin: 0;
}

.app {
  max-width: 44rem;
  margin: 0 auto;
  padding: 1rem;
}

header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  border-bottom: 1px solid #d9e2ec;
  margin-bottom: 1rem;
}

header h1 {
  font-size: 1.25rem;
  margin: 0 0 0.5rem;
}

nav a {
  margin-left: 1rem;
  color: #486581;
  text-decoration: none;
}

nav a.active {
  color: #102a43;
  font-weight: 600;
  border-bottom: 2px solid #102a43;
}

button {
  font: inherit;
  padding: 0.3rem 0.8rem;
  margin-right: 0.4rem;
  border: 1px solid #9fb3c8;
  border-radius: 4px;
  background: #fff;
  cursor: pointer;
}

button:disabled {
  opacity: 0.5;
  cursor: default;
}

.muted {
  color: #627d98;
  font-size: 0.9rem;
}

.field {
  margin-bottom: 1rem;
}

.field label {
  display: block;
  font-weight: 600;
}

textarea {
  display: block;
  width: 100%;
  box-sizing: border-box;
  margin-top: 0.3rem;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 0.9rem;
}

.warnings {
  background: #fff8e1;
  border: 1px solid #f0d78c;
  border-radius: 4px;
  padding: 0.5rem 1rem;
}

[role='alert'] {
  color: #a61b1b;
}

.goal-list {
  list-style: none;
  padding: 0;
}

.goal-list li {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  margin-bottom: 0.3rem;
}

.goal-name {
  flex: 1;
}

.timeline {
  list-style: none;
  padding: 0;
}

.block {
  background: #fff;
  border: 1px solid #d9e2ec;
  border-left: 4px solid #9fb3c8;
  border-radius: 4px;
  padding: 0.5rem 0.75rem;
  margin-bottom: 0.5rem;
}

.block-goal {
  border-left-color: #2680c2;
}

.block-adhoc {
  border-left-color: #7b4bd3;
}

.block-free {
  background: transparent;
  border-style: dashed;
  color: #627d98;
}

.block.status-done,
.block.status-cancelled,
.block.status-postponed {
  opacity: 0.6;
}

.block.status-done .block-title,
.block.status-cancelled .block-title {
  text-decoration: line-through;
}

.block-line {
  display: flex;
  gap: 0.75rem;
  align-items: baseline;
}

.block-time {
  font-variant-numeric: tabular-nums;
  color: #486581;
}

.block-title {
  flex: 1;
  font-weight: 600;
}

.badge {
  font-size: 0.75rem;
  background: #e4e7eb;
  border-radius: 999px;
  padding: 0.1rem 0.5rem;
}

.block-reason {
  margin: 0.25rem 0 0;
  font-style: italic;
  color: #627d98;
}

.block-flag {
  margin: 0.25rem 0 0;
  color: #b44d12;
  font-weight: 600;
}

.block-actions {
  margin-top: 0.5rem;
}

.toast {
  position: fixed;
  bottom: 1rem;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  gap: 0.75rem;
  align-items: flex-start;
  max-width: 34rem;
  background: #102a43;
  color: #fff;
  border-radius: 6px;
  padding: 0.5rem 1rem;
}

.toast ul {
  margin: 0;
  padding-left: 1.1rem;
}

.toast button {
  margin: 0;
  color: #fff;
  background: transparent;
  border-color: #486581;
}
```

`frontend/src/test/setup.ts`:

```ts
import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// Vitest has no global afterEach here, so Testing Library cannot clean up on its own.
afterEach(() => cleanup())
```

- [ ] **Step 2: Install dependencies**

Run: `npm --prefix frontend install`
Expected: ends with `found 0 vulnerabilities`; creates `frontend/node_modules` and `frontend/package-lock.json`. (`node_modules` is already git-ignored by the root `.gitignore`.)

- [ ] **Step 3: Write the test helpers and the failing tests**

`frontend/src/test/mockApi.ts`:

```ts
import { vi } from 'vitest'

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** An RFC 7807 error answer, the shape the backend's GlobalExceptionHandler produces. */
export function problemResponse(status: number, title: string, detail: string): Response {
  return new Response(JSON.stringify({ status, title, detail }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })
}

type Handler = (init: RequestInit | undefined) => Response | Promise<Response>

/**
 * Replaces the global fetch. `routes` maps "METHOD /path" to a function that builds the answer
 * (a function, because a Response body can only be read once). Any other request fails the test.
 * Returns the mock so a test can inspect the calls. Call `vi.unstubAllGlobals()` afterwards.
 */
export function mockApi(routes: Record<string, Handler>) {
  const fetchMock = vi.fn(async (path: string, init?: RequestInit) => {
    const key = `${init?.method ?? 'GET'} ${path}`
    const handler = routes[key]
    if (!handler) {
      throw new Error(`Unexpected request: ${key}`)
    }
    return handler(init)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}
```

`frontend/src/test/fixtures.ts`:

```ts
import type { BlockDto, ConstraintDto, GoalDto, SetupParseResponse, TodayPlanResponse } from '../api/types'

export function makeBlock(overrides: Partial<BlockDto> = {}): BlockDto {
  return {
    id: 'career-claude-certification-2026-09-14',
    date: '2026-09-14',
    start: '18:00',
    end: '19:00',
    title: 'Claude certification',
    kind: 'GOAL',
    goalId: 'career-claude-certification',
    status: 'PLANNED',
    postponeCount: 0,
    flagged: false,
    reason: null,
    ...overrides,
  }
}

/** A short Monday: gym, work (fixed), the certification session. */
export const TODAY: TodayPlanResponse = {
  date: '2026-09-14',
  warnings: [],
  blocks: [
    makeBlock({ id: 'health-gym-2026-09-14', start: '07:00', end: '08:00', title: 'Gym', goalId: 'health-gym' }),
    makeBlock({
      id: 'fixed-work-2026-09-14',
      start: '08:30',
      end: '17:00',
      title: 'Work',
      kind: 'FIXED',
      goalId: null,
    }),
    makeBlock(),
  ],
}

const WORK: ConstraintDto = {
  type: 'FIXED_BLOCK',
  label: 'Work',
  start: '08:30',
  end: '17:00',
  days: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'],
  timesPerWeek: null,
  sessionMinutes: null,
  goalId: null,
  minutesPerWeek: null,
  categoryId: null,
  eveningsPerWeek: null,
}

const GYM: ConstraintDto = {
  ...WORK,
  type: 'FREQUENCY_QUOTA',
  label: 'Gym',
  start: null,
  end: null,
  days: null,
  timesPerWeek: 4,
  sessionMinutes: 60,
  goalId: 'health-gym',
}

const CERTIFICATION: GoalDto = {
  id: 'career-claude-certification',
  categoryId: 'career',
  name: 'Claude certification',
  priority: 3,
  deadline: '2026-10-01',
  defaultBlockMinutes: 60,
  energy: 'MEDIUM',
  preferredTime: 'ANY',
}

const GYM_GOAL: GoalDto = {
  ...CERTIFICATION,
  id: 'health-gym',
  categoryId: 'health',
  name: 'Gym',
  deadline: null,
}

/** What POST /api/setup/parse answers for a small setup, with one warning. */
export const PARSED: SetupParseResponse = {
  categories: [
    { id: 'career', name: 'Career' },
    { id: 'health', name: 'Health' },
  ],
  goals: [CERTIFICATION, GYM_GOAL],
  constraints: [WORK, GYM],
  warnings: ['No goal found for frequency constraint: Padel'],
}
```

`frontend/src/api/client.test.ts`:

```ts
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PARSED, TODAY } from '../test/fixtures'
import { jsonResponse, mockApi, problemResponse } from '../test/mockApi'
import { api, ApiError, errorMessage, isSetupRequired } from './client'

afterEach(() => vi.unstubAllGlobals())

describe('api client', () => {
  it('returns the parsed JSON body of a successful call', async () => {
    mockApi({ 'GET /api/plan/today': () => jsonResponse(TODAY) })

    expect(await api.getToday()).toEqual(TODAY)
  })

  it('turns a problem+json answer into an ApiError', async () => {
    mockApi({
      'GET /api/plan/today': () => problemResponse(404, 'Setup required', 'Paste your goals first'),
    })

    const error = await api.getToday().catch((failure: unknown) => failure)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 404, title: 'Setup required', detail: 'Paste your goals first' })
    expect((error as ApiError).message).toBe('Paste your goals first')
  })

  it('falls back to the HTTP status text when the error body is not JSON', async () => {
    mockApi({
      'GET /api/plan/today': () => new Response('<html>bad gateway</html>', { status: 502, statusText: 'Bad Gateway' }),
    })

    const error = await api.getToday().catch((failure: unknown) => failure)

    expect(error).toMatchObject({ status: 502, title: 'Bad Gateway', detail: '' })
  })

  it('posts an event as JSON', async () => {
    const fetchMock = mockApi({
      'POST /api/plan/today/events': () => jsonResponse({ ...TODAY, changes: ['Postponed Gym'] }),
    })

    const result = await api.applyEvent({ type: 'POSTPONE_BLOCK', blockId: 'b1', reason: 'tired' })

    expect(result.changes).toEqual(['Postponed Gym'])
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(init.method).toBe('POST')
    expect(init.headers).toEqual({ 'Content-Type': 'application/json' })
    expect(JSON.parse(init.body as string)).toEqual({ type: 'POSTPONE_BLOCK', blockId: 'b1', reason: 'tired' })
  })

  it('saves the setup with PUT and keeps a missing deadline as null', async () => {
    const fetchMock = mockApi({ 'PUT /api/setup': () => jsonResponse(PARSED) })
    const { warnings: _warnings, ...setup } = PARSED

    await api.saveSetup(setup)

    const sent = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string)
    expect(sent.goals[1].deadline).toBeNull()
  })
})

describe('isSetupRequired', () => {
  it('is true only for the 404 "Setup required" problem', () => {
    expect(isSetupRequired(new ApiError(404, 'Setup required', ''))).toBe(true)
    expect(isSetupRequired(new ApiError(404, 'Not Found', ''))).toBe(false)
    expect(isSetupRequired(new ApiError(500, 'Setup required', ''))).toBe(false)
    expect(isSetupRequired(new TypeError('Failed to fetch'))).toBe(false)
    expect(isSetupRequired(null)).toBe(false)
  })
})

describe('errorMessage', () => {
  it('shows the backend detail for an ApiError', () => {
    expect(errorMessage(new ApiError(400, 'Invalid request', 'priority must be between 1 and 5'))).toBe(
      'priority must be between 1 and 5',
    )
  })

  it('shows the backend detail of a real 500 problem body', () => {
    expect(errorMessage(new ApiError(500, 'Unexpected error', 'Something went wrong'))).toBe('Something went wrong')
  })

  it('falls back to the title when a client error has no detail', () => {
    expect(errorMessage(new ApiError(404, 'Not Found', ''))).toBe('Not Found')
  })

  it('explains an unreachable backend when the dev proxy answers 5xx with no problem body', () => {
    expect(errorMessage(new ApiError(500, 'Internal Server Error', ''))).toMatch(/backend/i)
  })

  it('explains an unreachable backend when fetch itself fails', () => {
    expect(errorMessage(new TypeError('Failed to fetch'))).toMatch(/backend/i)
  })
})
```

`frontend/src/api/useApi.test.tsx`:

```tsx
import { act, render, renderHook, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { useApi } from './useApi'

function deferred<T>() {
  let resolve: (value: T) => void = () => {}
  const promise = new Promise<T>((done) => (resolve = done))
  return { promise, resolve }
}

function Probe({ load }: { load: () => Promise<string> }) {
  const { data, loading } = useApi(load)
  return <p>{loading ? 'loading' : data}</p>
}

describe('useApi', () => {
  it('starts loading, then exposes the data', async () => {
    const load = vi.fn().mockResolvedValue('plan')

    const { result } = renderHook(() => useApi(load))

    expect(result.current.loading).toBe(true)
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.data).toBe('plan')
    expect(result.current.error).toBeNull()
  })

  it('exposes the error and no data when the call fails', async () => {
    const failure = new Error('boom')
    const load = vi.fn().mockRejectedValue(failure)

    const { result } = renderHook(() => useApi(load))

    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.error).toBe(failure)
    expect(result.current.data).toBeNull()
  })

  it('setData replaces the data without another request', async () => {
    const load = vi.fn().mockResolvedValue('old')
    const { result } = renderHook(() => useApi(load))
    await waitFor(() => expect(result.current.data).toBe('old'))

    act(() => result.current.setData('new'))

    expect(result.current.data).toBe('new')
    expect(load).toHaveBeenCalledTimes(1)
  })

  it('refetch loads again', async () => {
    const load = vi.fn().mockResolvedValueOnce('first').mockResolvedValueOnce('second')
    const { result } = renderHook(() => useApi(load))
    await waitFor(() => expect(result.current.data).toBe('first'))

    act(() => result.current.refetch())

    await waitFor(() => expect(result.current.data).toBe('second'))
    expect(load).toHaveBeenCalledTimes(2)
  })

  it('under StrictMode (two loads), keeps the answer of the second load even if the first arrives last', async () => {
    const first = deferred<string>()
    const second = deferred<string>()
    const load = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)

    // render() rather than renderHook(): only render() reliably applies the StrictMode double effect
    render(
      <StrictMode>
        <Probe load={load} />
      </StrictMode>,
    )
    expect(load).toHaveBeenCalledTimes(2)
    await act(async () => {
      second.resolve('fresh')
      first.resolve('stale')
    })

    expect(await screen.findByText('fresh')).toBeInTheDocument()
    expect(screen.queryByText('stale')).not.toBeInTheDocument()
  })
})
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `npm --prefix frontend test`
Expected: FAIL. Both test files fail to load with an import resolution error for `./client` / `./useApi` (the modules do not exist yet).

- [ ] **Step 5: Write the API layer**

`frontend/src/api/types.ts`:

```ts
// TypeScript mirrors of the backend's JSON shapes (Java records under com.lifeorganizer.*.dto).
// Times are "HH:mm", dates are "yyyy-MM-dd". Optional values arrive as null.

export type Energy = 'LOW' | 'MEDIUM' | 'HIGH'
export type PreferredTime = 'MORNING' | 'EVENING' | 'ANY'

export interface CategoryDto {
  id: string
  name: string
}

export interface GoalDto {
  id: string
  categoryId: string
  name: string
  priority: number
  deadline: string | null
  defaultBlockMinutes: number
  energy: Energy
  preferredTime: PreferredTime
}

export type ConstraintType = 'FIXED_BLOCK' | 'FREQUENCY_QUOTA' | 'DURATION_QUOTA' | 'EVENING_QUOTA'

/** One flat shape for all four constraint types; fields a type does not use are null. */
export interface ConstraintDto {
  type: ConstraintType
  label: string
  start: string | null
  end: string | null
  days: string[] | null
  timesPerWeek: number | null
  sessionMinutes: number | null
  goalId: string | null
  minutesPerWeek: number | null
  categoryId: string | null
  eveningsPerWeek: number | null
}

/** What GET /api/setup returns and PUT /api/setup accepts. */
export interface SetupPayload {
  categories: CategoryDto[]
  goals: GoalDto[]
  constraints: ConstraintDto[]
}

export interface SetupParseRequest {
  goalsText: string
  constraintsText: string
}

export interface SetupParseResponse extends SetupPayload {
  warnings: string[]
}

export type BlockKind = 'FIXED' | 'ROUTINE' | 'GOAL' | 'FREE' | 'ADHOC'
export type BlockStatus = 'PLANNED' | 'DONE' | 'CANCELLED' | 'POSTPONED'

export interface BlockDto {
  id: string
  date: string
  start: string
  end: string
  title: string
  kind: BlockKind
  goalId: string | null
  status: BlockStatus
  postponeCount: number
  flagged: boolean
  reason: string | null
}

export interface TodayPlanResponse {
  date: string
  blocks: BlockDto[]
  warnings: string[]
}

export interface ApplyEventResponse extends TodayPlanResponse {
  changes: string[]
}

/**
 * The events this UI sends. The backend also accepts SET_ENERGY and ADD_ADHOC, but this
 * slice has no controls for them.
 */
export type PlanEventRequest =
  | { type: 'MARK_DONE'; blockId: string }
  | { type: 'CANCEL_BLOCK'; blockId: string }
  | { type: 'POSTPONE_BLOCK'; blockId: string; reason?: string }
```

`frontend/src/api/client.ts`:

```ts
import type {
  ApplyEventResponse,
  PlanEventRequest,
  SetupParseRequest,
  SetupParseResponse,
  SetupPayload,
  TodayPlanResponse,
} from './types'

/** A non-2xx answer from the backend, read from its RFC 7807 problem body. */
export class ApiError extends Error {
  readonly status: number
  readonly title: string
  readonly detail: string

  constructor(status: number, title: string, detail: string) {
    super(detail || title)
    this.name = 'ApiError'
    this.status = status
    this.title = title
    this.detail = detail
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const problem = (await response.json()) as { title?: string; detail?: string }
    return new ApiError(response.status, problem.title ?? response.statusText, problem.detail ?? '')
  } catch {
    // the body was not JSON (for example a proxy error page)
    return new ApiError(response.status, response.statusText || 'Request failed', '')
  }
}

async function send<T>(method: string, path: string, body?: unknown): Promise<T> {
  const init: RequestInit = { method }
  if (body !== undefined) {
    init.headers = { 'Content-Type': 'application/json' }
    init.body = JSON.stringify(body)
  }
  const response = await fetch(path, init)
  if (!response.ok) {
    throw await toApiError(response)
  }
  return (await response.json()) as T
}

/** The one place that knows the URLs. Paths are relative: Vite proxies /api to the backend. */
export const api = {
  parseSetup: (request: SetupParseRequest) => send<SetupParseResponse>('POST', '/api/setup/parse', request),
  getSetup: () => send<SetupPayload>('GET', '/api/setup'),
  saveSetup: (setup: SetupPayload) => send<SetupPayload>('PUT', '/api/setup', setup),
  getToday: () => send<TodayPlanResponse>('GET', '/api/plan/today'),
  applyEvent: (event: PlanEventRequest) => send<ApplyEventResponse>('POST', '/api/plan/today/events', event),
}

/** True when the backend says no setup is saved yet: the UI then sends the user to /setup. */
export function isSetupRequired(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404 && error.title === 'Setup required'
}

export function errorMessage(error: unknown): string {
  // A 5xx without a problem+json body did not come from our backend: it is the Vite dev proxy
  // answering (with an empty 500) for a backend that is not running.
  if (error instanceof ApiError && (error.status < 500 || error.detail !== '')) {
    return error.message
  }
  return 'Could not reach the server. Is the backend running on port 8080?'
}
```

`frontend/src/api/useApi.ts`:

```ts
import { useCallback, useEffect, useState } from 'react'

interface State<T> {
  data: T | null
  error: unknown
  loading: boolean
}

/**
 * Runs `load` when the component mounts and exposes {data, loading, error}.
 * `load` must be a stable function (for example `api.getToday`), not one created on every render.
 * `setData` lets a page replace the data after an action without fetching again.
 */
export function useApi<T>(load: () => Promise<T>) {
  const [state, setState] = useState<State<T>>({ data: null, error: null, loading: true })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    // `cancelled` drops the answer of a request whose component already unmounted or re-ran the effect
    // (React <StrictMode> runs every effect twice in development).
    let cancelled = false
    setState((current) => ({ ...current, error: null, loading: true }))
    load().then(
      (data) => {
        if (!cancelled) setState({ data, error: null, loading: false })
      },
      (error: unknown) => {
        if (!cancelled) setState({ data: null, error, loading: false })
      },
    )
    return () => {
      cancelled = true
    }
  }, [load, attempt])

  const refetch = useCallback(() => setAttempt((n) => n + 1), [])
  const setData = useCallback((data: T) => setState({ data, error: null, loading: false }), [])

  return { ...state, refetch, setData }
}
```

- [ ] **Step 6: Run the tests and the production build**

Run: `npm --prefix frontend test`
Expected: PASS, 2 test files, 16 tests.

Run: `npm --prefix frontend run build`
Expected: `tsc -b` prints nothing, then Vite prints `✓ built in ...` and lists `dist/index.html`, one `.css` and one `.js` file. (`frontend/dist` is git-ignored.)

- [ ] **Step 7: Commit**

```bash
git add frontend
git status --short
git commit -m "feat: scaffold React frontend with API client and useApi hook"
```

`git status --short` must list only files under `frontend/` (no `node_modules`, no `dist`).

---

### Task 2: Timeline, BlockCard and ChangeToast

**Files:**
- Create: `frontend/src/components/BlockCard.tsx`, `frontend/src/components/Timeline.tsx`, `frontend/src/components/ChangeToast.tsx`
- Test: `frontend/src/components/BlockCard.test.tsx`, `frontend/src/components/Timeline.test.tsx`, `frontend/src/components/ChangeToast.test.tsx`

**Interfaces:**
- Consumes: `BlockDto`, `BlockStatus`, `PlanEventRequest` from `api/types.ts`; `makeBlock` from `test/fixtures.ts`.
- Produces:
  - `BlockCard({block: BlockDto, busy: boolean, onAction: (event: PlanEventRequest) => void})`. Renders an `<li>`; buttons `Done`, `Postpone`, `Cancel` only for `status === 'PLANNED'` and `kind` `GOAL` or `ADHOC`. `Postpone` opens a form (label `Reason (optional)`, buttons `Confirm postpone` and `Never mind`). A blank reason is omitted from the event.
  - `Timeline({blocks, busy, onAction})`: an `<ol class="timeline">` of `BlockCard`s sorted by `start`.
  - `ChangeToast({changes: string[], onDismiss: () => void})` and the constant `TOAST_MILLIS = 6000`. Renders nothing for an empty list; role `status`; a button named `Dismiss`. `onDismiss` must be a stable function (a `useCallback`) or the auto-hide timer restarts on every render.

- [ ] **Step 1: Write the failing tests**

`frontend/src/components/BlockCard.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { makeBlock } from '../test/fixtures'
import { BlockCard } from './BlockCard'

function renderCard(block = makeBlock(), busy = false) {
  const onAction = vi.fn()
  render(
    <ol>
      <BlockCard block={block} busy={busy} onAction={onAction} />
    </ol>,
  )
  return onAction
}

describe('BlockCard', () => {
  it('shows time, title and the three actions for a planned goal block', () => {
    renderCard()

    expect(screen.getByText('18:00–19:00')).toBeInTheDocument()
    expect(screen.getByText('Claude certification')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Done' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Postpone' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
  })

  it('sends MARK_DONE and CANCEL_BLOCK with the block id', async () => {
    const user = userEvent.setup()
    const onAction = renderCard()

    await user.click(screen.getByRole('button', { name: 'Done' }))
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(onAction).toHaveBeenNthCalledWith(1, { type: 'MARK_DONE', blockId: 'career-claude-certification-2026-09-14' })
    expect(onAction).toHaveBeenNthCalledWith(2, { type: 'CANCEL_BLOCK', blockId: 'career-claude-certification-2026-09-14' })
  })

  it('asks for an optional reason before postponing and sends it trimmed', async () => {
    const user = userEvent.setup()
    const onAction = renderCard()

    await user.click(screen.getByRole('button', { name: 'Postpone' }))
    expect(onAction).not.toHaveBeenCalled()
    await user.type(screen.getByLabelText('Reason (optional)'), '  too tired  ')
    await user.click(screen.getByRole('button', { name: 'Confirm postpone' }))

    expect(onAction).toHaveBeenCalledWith({
      type: 'POSTPONE_BLOCK',
      blockId: 'career-claude-certification-2026-09-14',
      reason: 'too tired',
    })
    // the form closes again
    expect(screen.getByRole('button', { name: 'Postpone' })).toBeInTheDocument()
  })

  it('omits the reason when it is left blank, and Enter confirms', async () => {
    const user = userEvent.setup()
    const onAction = renderCard()

    await user.click(screen.getByRole('button', { name: 'Postpone' }))
    await user.type(screen.getByLabelText('Reason (optional)'), '   {Enter}')

    expect(onAction).toHaveBeenCalledWith({ type: 'POSTPONE_BLOCK', blockId: 'career-claude-certification-2026-09-14' })
  })

  it('lets the user back out of a postpone', async () => {
    const user = userEvent.setup()
    const onAction = renderCard()

    await user.click(screen.getByRole('button', { name: 'Postpone' }))
    await user.click(screen.getByRole('button', { name: 'Never mind' }))

    expect(onAction).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Done' })).toBeInTheDocument()
  })

  it('has no actions for fixed, routine or free blocks', () => {
    for (const kind of ['FIXED', 'ROUTINE', 'FREE'] as const) {
      const { unmount } = render(
        <ol>
          <BlockCard block={makeBlock({ kind })} busy={false} onAction={vi.fn()} />
        </ol>,
      )
      expect(screen.queryByRole('button')).not.toBeInTheDocument()
      unmount()
    }
  })

  it('has actions for an ad-hoc block', () => {
    renderCard(makeBlock({ kind: 'ADHOC', goalId: null }))

    expect(screen.getByRole('button', { name: 'Done' })).toBeInTheDocument()
  })

  it('shows a status badge and no actions once a block is no longer planned', () => {
    renderCard(makeBlock({ status: 'DONE' }))

    expect(screen.getByText('Done')).toHaveClass('badge')
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('shows the postponement reason', () => {
    renderCard(makeBlock({ status: 'POSTPONED', reason: 'tired' }))

    expect(screen.getByText('Postponed')).toBeInTheDocument()
    expect(screen.getByText('tired')).toBeInTheDocument()
  })

  it('suggests a shorter session for a flagged block', () => {
    renderCard(makeBlock({ flagged: true, postponeCount: 3 }))

    expect(screen.getByText('Postponed 3× — try a 25 minute version?')).toBeInTheDocument()
  })

  it('disables every action while a request is running', () => {
    renderCard(makeBlock(), true)

    for (const button of screen.getAllByRole('button')) {
      expect(button).toBeDisabled()
    }
  })
})
```

`frontend/src/components/Timeline.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { makeBlock } from '../test/fixtures'
import { Timeline } from './Timeline'

describe('Timeline', () => {
  it('lists blocks in start-time order whatever order they arrive in', () => {
    const blocks = [
      makeBlock({ id: 'c', start: '18:00', title: 'Evening' }),
      makeBlock({ id: 'a', start: '07:00', title: 'Morning' }),
      makeBlock({ id: 'b', start: '08:30', title: 'Midday' }),
    ]

    render(<Timeline blocks={blocks} busy={false} onAction={vi.fn()} />)

    const titles = screen.getAllByRole('listitem').map((item) => item.querySelector('.block-title')?.textContent)
    expect(titles).toEqual(['Morning', 'Midday', 'Evening'])
  })

  it('does not reorder the array it was given', () => {
    const blocks = [makeBlock({ id: 'b', start: '18:00' }), makeBlock({ id: 'a', start: '07:00' })]

    render(<Timeline blocks={blocks} busy={false} onAction={vi.fn()} />)

    expect(blocks.map((block) => block.id)).toEqual(['b', 'a'])
  })
})
```

`frontend/src/components/ChangeToast.test.tsx`:

```tsx
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ChangeToast, TOAST_MILLIS } from './ChangeToast'

afterEach(() => vi.useRealTimers())

describe('ChangeToast', () => {
  it('renders nothing when there are no changes', () => {
    const { container } = render(<ChangeToast changes={[]} onDismiss={vi.fn()} />)

    expect(container).toBeEmptyDOMElement()
  })

  it('lists every change', () => {
    render(<ChangeToast changes={['Postponed Claude certification', 'Moved to 21:00']} onDismiss={vi.fn()} />)

    expect(screen.getByRole('status')).toHaveTextContent('Postponed Claude certification')
    expect(screen.getByRole('status')).toHaveTextContent('Moved to 21:00')
  })

  it('can be dismissed by hand', async () => {
    const user = userEvent.setup()
    const onDismiss = vi.fn()
    render(<ChangeToast changes={['x']} onDismiss={onDismiss} />)

    await user.click(screen.getByRole('button', { name: 'Dismiss' }))

    expect(onDismiss).toHaveBeenCalledTimes(1)
  })

  it('dismisses itself after a few seconds, not before', () => {
    vi.useFakeTimers()
    const onDismiss = vi.fn()
    render(<ChangeToast changes={['x']} onDismiss={onDismiss} />)

    act(() => vi.advanceTimersByTime(TOAST_MILLIS - 1))
    expect(onDismiss).not.toHaveBeenCalled()
    act(() => vi.advanceTimersByTime(1))
    expect(onDismiss).toHaveBeenCalledTimes(1)
  })
})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npm --prefix frontend test -- src/components`
Expected: FAIL. Three test files fail to load: cannot resolve `./BlockCard`, `./Timeline`, `./ChangeToast`.

- [ ] **Step 3: Write the components**

`frontend/src/components/BlockCard.tsx`:

```tsx
import { useId, useState } from 'react'
import type { BlockDto, BlockStatus, PlanEventRequest } from '../api/types'

interface Props {
  block: BlockDto
  /** True while a request is running; every action button is disabled. */
  busy: boolean
  onAction: (event: PlanEventRequest) => void
}

const STATUS_LABEL: Record<BlockStatus, string> = {
  PLANNED: 'Planned',
  DONE: 'Done',
  CANCELLED: 'Cancelled',
  POSTPONED: 'Postponed',
}

// Fixed and routine blocks are not movable, and free time is not a task.
const ACTIONABLE_KINDS = ['GOAL', 'ADHOC']

export function BlockCard({ block, busy, onAction }: Props) {
  const [postponing, setPostponing] = useState(false)
  const [reason, setReason] = useState('')
  const reasonId = useId()

  const actionable = block.status === 'PLANNED' && ACTIONABLE_KINDS.includes(block.kind)

  function confirmPostpone() {
    const trimmed = reason.trim()
    onAction(
      trimmed
        ? { type: 'POSTPONE_BLOCK', blockId: block.id, reason: trimmed }
        : { type: 'POSTPONE_BLOCK', blockId: block.id },
    )
    setPostponing(false)
    setReason('')
  }

  return (
    <li className={`block block-${block.kind.toLowerCase()} status-${block.status.toLowerCase()}`}>
      <div className="block-line">
        <span className="block-time">
          {block.start}–{block.end}
        </span>
        <span className="block-title">{block.title}</span>
        {block.status !== 'PLANNED' && <span className="badge">{STATUS_LABEL[block.status]}</span>}
      </div>
      {block.reason && <p className="block-reason">{block.reason}</p>}
      {block.flagged && (
        <p className="block-flag">Postponed {block.postponeCount}× — try a 25 minute version?</p>
      )}

      {actionable && !postponing && (
        <div className="block-actions">
          <button type="button" disabled={busy} onClick={() => onAction({ type: 'MARK_DONE', blockId: block.id })}>
            Done
          </button>
          <button type="button" disabled={busy} onClick={() => setPostponing(true)}>
            Postpone
          </button>
          <button type="button" disabled={busy} onClick={() => onAction({ type: 'CANCEL_BLOCK', blockId: block.id })}>
            Cancel
          </button>
        </div>
      )}

      {actionable && postponing && (
        <form
          className="block-actions"
          onSubmit={(event) => {
            event.preventDefault()
            confirmPostpone()
          }}
        >
          <label htmlFor={reasonId}>Reason (optional)</label>
          <input
            id={reasonId}
            type="text"
            value={reason}
            maxLength={500}
            onChange={(event) => setReason(event.target.value)}
          />
          <button type="submit" disabled={busy}>
            Confirm postpone
          </button>
          <button type="button" onClick={() => setPostponing(false)}>
            Never mind
          </button>
        </form>
      )}
    </li>
  )
}
```

`frontend/src/components/Timeline.tsx`:

```tsx
import type { BlockDto, PlanEventRequest } from '../api/types'
import { BlockCard } from './BlockCard'

interface Props {
  blocks: BlockDto[]
  busy: boolean
  onAction: (event: PlanEventRequest) => void
}

/** Blocks in time order. "HH:mm" strings sort correctly as text; the sort is stable for equal starts. */
export function Timeline({ blocks, busy, onAction }: Props) {
  const sorted = [...blocks].sort((a, b) => a.start.localeCompare(b.start))
  return (
    <ol className="timeline">
      {sorted.map((block) => (
        <BlockCard key={block.id} block={block} busy={busy} onAction={onAction} />
      ))}
    </ol>
  )
}
```

`frontend/src/components/ChangeToast.tsx`:

```tsx
import { useEffect } from 'react'

interface Props {
  changes: string[]
  onDismiss: () => void
}

export const TOAST_MILLIS = 6000

/** Shows what the last action changed, then hides itself. Renders nothing for an empty list. */
export function ChangeToast({ changes, onDismiss }: Props) {
  const visible = changes.length > 0

  useEffect(() => {
    if (!visible) return
    const timer = setTimeout(onDismiss, TOAST_MILLIS)
    return () => clearTimeout(timer)
  }, [changes, visible, onDismiss])

  if (!visible) return null
  return (
    <div className="toast" role="status">
      <ul>
        {changes.map((change, index) => (
          <li key={index}>{change}</li>
        ))}
      </ul>
      <button type="button" onClick={onDismiss} aria-label="Dismiss">
        ×
      </button>
    </div>
  )
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npm --prefix frontend test`
Expected: PASS, 5 test files, 33 tests (16 from Task 1 + 17 new: BlockCard 11, Timeline 2, ChangeToast 4).

Run: `npm --prefix frontend run build`
Expected: builds without type errors.

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat: add Timeline, BlockCard and ChangeToast components"
```

---

### Task 3: TodayPage

**Files:**
- Create: `frontend/src/pages/TodayPage.tsx`
- Test: `frontend/src/pages/TodayPage.test.tsx`

**Interfaces:**
- Consumes: `api`, `errorMessage`, `isSetupRequired` (`api/client.ts`); `useApi` (`api/useApi.ts`); `Timeline`, `ChangeToast` (Task 2); `PlanEventRequest`; test helpers `mockApi`, `jsonResponse`, `problemResponse`, fixtures `TODAY`, `makeBlock`.
- Produces: `TodayPage()` (no props; rendered at route `/`). Behaviour: loads `GET /api/plan/today`; on `404 "Setup required"` renders `<Navigate to="/setup" replace />`; other load errors render `role="alert"` text; an action posts the event, replaces the shown plan with the response's `blocks`/`warnings` and shows `changes` in a `ChangeToast`; a failed action keeps the plan and shows the error as `role="alert"`; all action buttons are disabled while a request runs.

- [ ] **Step 1: Write the failing test**

`frontend/src/pages/TodayPage.test.tsx`:

```tsx
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { makeBlock, TODAY } from '../test/fixtures'
import { jsonResponse, mockApi, problemResponse } from '../test/mockApi'
import { TodayPage } from './TodayPage'

afterEach(() => vi.unstubAllGlobals())

function renderToday() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="/" element={<TodayPage />} />
        <Route path="/setup" element={<p>Setup screen</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

const CERT_ID = 'career-claude-certification-2026-09-14'

describe('TodayPage', () => {
  it("shows today's blocks in order", async () => {
    mockApi({ 'GET /api/plan/today': () => jsonResponse(TODAY) })

    renderToday()

    expect(await screen.findByRole('heading', { name: "Today's plan" })).toBeInTheDocument()
    expect(screen.getByText('2026-09-14')).toBeInTheDocument()
    const titles = screen.getAllByRole('listitem').map((item) => item.querySelector('.block-title')?.textContent)
    expect(titles).toEqual(['Gym', 'Work', 'Claude certification'])
  })

  it('shows the plan warnings', async () => {
    mockApi({ 'GET /api/plan/today': () => jsonResponse({ ...TODAY, warnings: ['Learning quota does not fit'] }) })

    renderToday()

    expect(await screen.findByText('Learning quota does not fit')).toBeInTheDocument()
  })

  it('sends the user to Setup when no setup is saved yet', async () => {
    mockApi({
      'GET /api/plan/today': () => problemResponse(404, 'Setup required', 'No setup saved yet'),
    })

    renderToday()

    expect(await screen.findByText('Setup screen')).toBeInTheDocument()
  })

  it('shows other load errors', async () => {
    mockApi({ 'GET /api/plan/today': () => problemResponse(500, 'Unexpected error', 'Something went wrong') })

    renderToday()

    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong')
  })

  it('marks a block done and shows the new plan and what changed', async () => {
    const user = userEvent.setup()
    const fetchMock = mockApi({
      'GET /api/plan/today': () => jsonResponse(TODAY),
      'POST /api/plan/today/events': () =>
        jsonResponse({
          date: '2026-09-14',
          warnings: [],
          changes: ['Marked done Claude certification'],
          blocks: TODAY.blocks.map((block) => (block.id === CERT_ID ? { ...block, status: 'DONE' } : block)),
        }),
    })
    renderToday()

    const certification = (await screen.findByText('Claude certification')).closest('li')!
    await user.click(within(certification).getByRole('button', { name: 'Done' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Marked done Claude certification')
    expect(within(certification).getByText('Done')).toHaveClass('badge')
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')!
    expect(JSON.parse(post[1]!.body as string)).toEqual({ type: 'MARK_DONE', blockId: CERT_ID })
  })

  it('postpones with a reason and renders the moved block', async () => {
    const user = userEvent.setup()
    const moved = makeBlock({ id: `${CERT_ID}-p1`, start: '21:00', end: '22:00', postponeCount: 1 })
    mockApi({
      'GET /api/plan/today': () => jsonResponse(TODAY),
      'POST /api/plan/today/events': () =>
        jsonResponse({
          date: '2026-09-14',
          warnings: [],
          changes: ['Moved Claude certification to 21:00'],
          blocks: [
            ...TODAY.blocks.map((block) => (block.id === CERT_ID ? { ...block, status: 'POSTPONED', reason: 'tired' } : block)),
            moved,
          ],
        }),
    })
    renderToday()

    const certification = (await screen.findByText('Claude certification')).closest('li')!
    await user.click(within(certification).getByRole('button', { name: 'Postpone' }))
    await user.type(within(certification).getByLabelText('Reason (optional)'), 'tired')
    await user.click(within(certification).getByRole('button', { name: 'Confirm postpone' }))

    expect(await screen.findByText('21:00–22:00')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Moved Claude certification to 21:00')
    expect(screen.getByText('Postponed')).toBeInTheDocument()
  })

  it('keeps the plan and shows the error when an action fails', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/plan/today': () => jsonResponse(TODAY),
      'POST /api/plan/today/events': () => problemResponse(422, 'Unknown block', "No block with id 'x'"),
    })
    renderToday()

    await user.click((await screen.findAllByRole('button', { name: 'Done' }))[0])

    expect(await screen.findByRole('alert')).toHaveTextContent("No block with id 'x'")
    expect(screen.getByText('Claude certification')).toBeInTheDocument()
  })

  it('disables the actions while a request is running', async () => {
    const user = userEvent.setup()
    let finish: (response: Response) => void = () => {}
    mockApi({
      'GET /api/plan/today': () => jsonResponse(TODAY),
      'POST /api/plan/today/events': () => new Promise<Response>((resolve) => (finish = resolve)),
    })
    renderToday()

    await user.click((await screen.findAllByRole('button', { name: 'Done' }))[0])

    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Done' })[0]).toBeDisabled())
    finish(jsonResponse({ ...TODAY, changes: [] }))
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Done' })[0]).toBeEnabled())
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm --prefix frontend test -- src/pages/TodayPage.test.tsx`
Expected: FAIL: cannot resolve `./TodayPage`.

- [ ] **Step 3: Write the page**

`frontend/src/pages/TodayPage.tsx`:

```tsx
import { useCallback, useState } from 'react'
import { Navigate } from 'react-router-dom'
import { api, errorMessage, isSetupRequired } from '../api/client'
import type { PlanEventRequest } from '../api/types'
import { useApi } from '../api/useApi'
import { ChangeToast } from '../components/ChangeToast'
import { Timeline } from '../components/Timeline'

export function TodayPage() {
  const { data, loading, error, setData } = useApi(api.getToday)
  const [changes, setChanges] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const dismissToast = useCallback(() => setChanges([]), [])

  async function act(event: PlanEventRequest) {
    setBusy(true)
    setActionError(null)
    try {
      const result = await api.applyEvent(event)
      setData({ date: result.date, blocks: result.blocks, warnings: result.warnings })
      setChanges(result.changes)
    } catch (failure) {
      setActionError(errorMessage(failure))
    } finally {
      setBusy(false)
    }
  }

  if (isSetupRequired(error)) {
    return <Navigate to="/setup" replace />
  }
  if (loading && !data) {
    return <p>Loading today's plan…</p>
  }
  if (error || !data) {
    return <p role="alert">{errorMessage(error)}</p>
  }

  return (
    <section>
      <h2>Today's plan</h2>
      <p className="muted">{data.date}</p>

      {data.warnings.length > 0 && (
        <ul className="warnings">
          {data.warnings.map((warning) => (
            <li key={warning}>{warning}</li>
          ))}
        </ul>
      )}
      {actionError && <p role="alert">{actionError}</p>}

      <Timeline blocks={data.blocks} busy={busy} onAction={act} />
      <ChangeToast changes={changes} onDismiss={dismissToast} />
    </section>
  )
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npm --prefix frontend test`
Expected: PASS, 6 test files, 41 tests (TodayPage adds 8).

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat: add Today page with timeline actions and change toast"
```

---

### Task 4: SetupPage

**Files:**
- Create: `frontend/src/setup/example.ts`, `frontend/src/setup/describeConstraint.ts`, `frontend/src/components/SetupPreview.tsx`, `frontend/src/pages/SetupPage.tsx`
- Test: `frontend/src/setup/describeConstraint.test.ts`, `frontend/src/pages/SetupPage.test.tsx`

**Interfaces:**
- Consumes: `api`, `errorMessage` (`api/client.ts`); types `ConstraintDto`, `GoalDto`, `SetupParseResponse`; test helpers and fixture `PARSED`.
- Produces:
  - `EXAMPLE_GOALS`, `EXAMPLE_CONSTRAINTS` (strings) from `setup/example.ts`.
  - `describeConstraint(constraint: ConstraintDto): string`, for example `Work: 08:30–17:00, Mon–Fri`, `Gym: 4× per week, 60 min each`, `Learning: 5h per week`, `Social: 2 evenings per week`.
  - `SetupPreview({value: SetupParseResponse, onChange: (next: SetupParseResponse) => void})`: warnings list, goals grouped by category with a priority `<select aria-label="Priority for <goal>">` (1–5) and a deadline `<input type="date" aria-label="Deadline for <goal>">` (empty becomes `null`), constraints list.
  - `SetupPage()` (route `/setup`): two textareas (labels `Goals`, `Constraints (one per line)`) prefilled with the example; `Parse` button calls `POST /api/setup/parse`; on a return visit shows the saved setup (`GET /api/setup`) under the heading `Saved setup`; `Save` sends `PUT /api/setup` with `{categories, goals, constraints}` only (no `warnings`), then navigates to `/`. `Save` is disabled while busy or when the preview has no goals.

- [ ] **Step 1: Write the failing tests**

`frontend/src/setup/describeConstraint.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import type { ConstraintDto } from '../api/types'
import { describeConstraint } from './describeConstraint'

const BASE: ConstraintDto = {
  type: 'FIXED_BLOCK',
  label: 'Work',
  start: '08:30',
  end: '17:00',
  days: null,
  timesPerWeek: null,
  sessionMinutes: null,
  goalId: null,
  minutesPerWeek: null,
  categoryId: null,
  eveningsPerWeek: null,
}

describe('describeConstraint', () => {
  it('names weekdays as a range', () => {
    const days = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY']
    expect(describeConstraint({ ...BASE, days })).toBe('Work: 08:30–17:00, Mon–Fri')
  })

  it('says every day for all seven days', () => {
    const days = ['SUNDAY', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY']
    expect(describeConstraint({ ...BASE, label: 'Sleep', start: '23:30', end: '07:00', days })).toBe(
      'Sleep: 23:30–07:00, every day',
    )
  })

  it('lists other day sets in week order', () => {
    expect(describeConstraint({ ...BASE, days: ['SATURDAY', 'MONDAY'] })).toBe('Work: 08:30–17:00, Mon, Sat')
  })

  it('describes a frequency quota', () => {
    const gym = { ...BASE, type: 'FREQUENCY_QUOTA' as const, label: 'Gym', timesPerWeek: 4, sessionMinutes: 60 }
    expect(describeConstraint(gym)).toBe('Gym: 4× per week, 60 min each')
  })

  it('describes a duration quota in hours when it is a whole number of hours', () => {
    const learning = { ...BASE, type: 'DURATION_QUOTA' as const, label: 'Learning', minutesPerWeek: 300 }
    expect(describeConstraint(learning)).toBe('Learning: 5h per week')
    expect(describeConstraint({ ...learning, minutesPerWeek: 90 })).toBe('Learning: 90 min per week')
  })

  it('describes an evening quota', () => {
    const social = { ...BASE, type: 'EVENING_QUOTA' as const, label: 'Social', eveningsPerWeek: 2 }
    expect(describeConstraint(social)).toBe('Social: 2 evenings per week')
  })
})
```

`frontend/src/pages/SetupPage.test.tsx`:

```tsx
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { PARSED } from '../test/fixtures'
import { jsonResponse, mockApi, problemResponse } from '../test/mockApi'
import { SetupPage } from './SetupPage'

afterEach(() => vi.unstubAllGlobals())

const EMPTY_SETUP = { categories: [], goals: [], constraints: [] }

function renderSetup() {
  return render(
    <MemoryRouter initialEntries={['/setup']}>
      <Routes>
        <Route path="/setup" element={<SetupPage />} />
        <Route path="/" element={<p>Today screen</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('SetupPage', () => {
  it('starts with the example text so a first visit needs no typing', () => {
    mockApi({ 'GET /api/setup': () => jsonResponse(EMPTY_SETUP) })

    renderSetup()

    // jest-dom's toHaveValue does not take partial matchers, so compare the raw values
    expect((screen.getByLabelText('Goals') as HTMLTextAreaElement).value).toContain('Claude certification')
    expect((screen.getByLabelText('Constraints (one per line)') as HTMLTextAreaElement).value).toContain(
      'Work:       08:30-17:00',
    )
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument()
  })

  it('parses the text and shows goals, constraints and warnings', async () => {
    const user = userEvent.setup()
    const fetchMock = mockApi({
      'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
      'POST /api/setup/parse': () => jsonResponse(PARSED),
    })
    renderSetup()

    await user.click(screen.getByRole('button', { name: 'Parse' }))

    expect(await screen.findByText('Parsed setup (not saved yet)')).toBeInTheDocument()
    expect(screen.getByText('Career')).toBeInTheDocument()
    expect(screen.getByText('Claude certification')).toBeInTheDocument()
    expect(screen.getByLabelText('Priority for Gym')).toHaveValue('3')
    expect(screen.getByText('Work: 08:30–17:00, Mon–Fri')).toBeInTheDocument()
    expect(screen.getByText('Gym: 4× per week, 60 min each')).toBeInTheDocument()
    expect(screen.getByText('No goal found for frequency constraint: Padel')).toBeInTheDocument()
    const parse = fetchMock.mock.calls.find(([path]) => path === '/api/setup/parse')!
    expect(JSON.parse(parse[1]!.body as string)).toMatchObject({
      goalsText: expect.stringContaining('Gym'),
      constraintsText: expect.stringContaining('Work'),
    })
  })

  it('shows the backend message when parsing fails', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
      'POST /api/setup/parse': () => problemResponse(400, 'Invalid request', 'goalsText must not be empty'),
    })
    renderSetup()

    await user.click(screen.getByRole('button', { name: 'Parse' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('goalsText must not be empty')
  })

  it('saves the edited priorities, sends null for a cleared deadline, then opens Today', async () => {
    const user = userEvent.setup()
    const fetchMock = mockApi({
      'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
      'POST /api/setup/parse': () => jsonResponse(PARSED),
      'PUT /api/setup': () => jsonResponse(EMPTY_SETUP),
    })
    renderSetup()
    await user.click(screen.getByRole('button', { name: 'Parse' }))

    await user.selectOptions(await screen.findByLabelText('Priority for Claude certification'), '5')
    // an empty date input reports "" - the page must turn that into null
    fireEvent.change(screen.getByLabelText('Deadline for Claude certification'), { target: { value: '' } })
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Today screen')).toBeInTheDocument()
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')!
    const sent = JSON.parse(put[1]!.body as string)
    expect(sent).not.toHaveProperty('warnings')
    expect(sent.categories).toEqual(PARSED.categories)
    expect(sent.constraints).toEqual(PARSED.constraints)
    expect(sent.goals[0]).toMatchObject({ id: 'career-claude-certification', priority: 5, deadline: null })
    expect(sent.goals[1]).toEqual(PARSED.goals[1])
  })

  it('sets a deadline typed into the date field', async () => {
    const user = userEvent.setup()
    const fetchMock = mockApi({
      'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
      'POST /api/setup/parse': () => jsonResponse(PARSED),
      'PUT /api/setup': () => jsonResponse(EMPTY_SETUP),
    })
    renderSetup()
    await user.click(screen.getByRole('button', { name: 'Parse' }))

    fireEvent.change(await screen.findByLabelText('Deadline for Gym'), { target: { value: '2026-12-31' } })
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await screen.findByText('Today screen')
    const put = fetchMock.mock.calls.find(([, init]) => init?.method === 'PUT')!
    expect(JSON.parse(put[1]!.body as string).goals[1].deadline).toBe('2026-12-31')
  })

  it('stays on the page and shows the message when saving fails', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
      'POST /api/setup/parse': () => jsonResponse(PARSED),
      'PUT /api/setup': () => problemResponse(400, 'Invalid request', "goal 'gym': priority must be between 1 and 5"),
    })
    renderSetup()
    await user.click(screen.getByRole('button', { name: 'Parse' }))

    await user.click(await screen.findByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('priority must be between 1 and 5')
    expect(screen.queryByText('Today screen')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
  })

  it('shows the saved setup on a return visit', async () => {
    const { warnings: _warnings, ...saved } = PARSED
    mockApi({ 'GET /api/setup': () => jsonResponse(saved) })

    renderSetup()

    expect(await screen.findByText('Saved setup')).toBeInTheDocument()
    expect(within(screen.getByText('Career').parentElement!).getByText('Claude certification')).toBeInTheDocument()
    expect(screen.getByLabelText('Deadline for Claude certification')).toHaveValue('2026-10-01')
    expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled()
  })

  it('cannot save a parse that found no goals', async () => {
    const user = userEvent.setup()
    mockApi({
      'GET /api/setup': () => jsonResponse(EMPTY_SETUP),
      'POST /api/setup/parse': () => jsonResponse({ ...EMPTY_SETUP, warnings: ['Unrecognized line: ???'] }),
    })
    renderSetup()

    await user.click(screen.getByRole('button', { name: 'Parse' }))

    expect(await screen.findByText('Unrecognized line: ???')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled())
  })
})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npm --prefix frontend test -- src/setup src/pages/SetupPage.test.tsx`
Expected: FAIL: cannot resolve `./describeConstraint` and `./SetupPage`.

- [ ] **Step 3: Write the setup helpers**

`frontend/src/setup/example.ts`:

```ts
// The example from the design spec. It prefills the Setup page so a first visit works with one click.

export const EXAMPLE_GOALS = [
  'Goals',
  '├── Career',
  '│   ├── Claude certification',
  '│   ├── Azure AI',
  '│   └── System design',
  '├── Projects',
  '│   └── RAG showcase',
  '├── Health',
  '│   ├── Gym',
  '│   └── Padel / football',
  '├── Social',
  '│   └── Dating / events',
  '├── Finance',
  '│   └── Investing',
  '└── Hobbies',
  '    └── Chess',
].join('\n')

export const EXAMPLE_CONSTRAINTS = [
  'Work:       08:30-17:00',
  'Gym:        4x/week',
  'Sleep:      23:30-07:00',
  'Learning:   5h/week',
  'Side project: 4h/week',
  'Social:     2 evenings/week',
].join('\n')
```

`frontend/src/setup/describeConstraint.ts`:

```ts
import type { ConstraintDto } from '../api/types'

const WEEK = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']
const SHORT: Record<string, string> = {
  MONDAY: 'Mon',
  TUESDAY: 'Tue',
  WEDNESDAY: 'Wed',
  THURSDAY: 'Thu',
  FRIDAY: 'Fri',
  SATURDAY: 'Sat',
  SUNDAY: 'Sun',
}

function describeDays(days: string[] | null): string {
  const selected = WEEK.filter((day) => days?.includes(day))
  if (selected.length === 7) return 'every day'
  if (selected.join() === WEEK.slice(0, 5).join()) return 'Mon–Fri'
  return selected.map((day) => SHORT[day]).join(', ')
}

function describeMinutes(minutes: number): string {
  const hours = minutes / 60
  return Number.isInteger(hours) ? `${hours}h` : `${minutes} min`
}

/** One human-readable line per constraint, for the Setup preview. */
export function describeConstraint(constraint: ConstraintDto): string {
  switch (constraint.type) {
    case 'FIXED_BLOCK':
      return `${constraint.label}: ${constraint.start}–${constraint.end}, ${describeDays(constraint.days)}`
    case 'FREQUENCY_QUOTA':
      return `${constraint.label}: ${constraint.timesPerWeek}× per week, ${constraint.sessionMinutes} min each`
    case 'DURATION_QUOTA':
      return `${constraint.label}: ${describeMinutes(constraint.minutesPerWeek ?? 0)} per week`
    case 'EVENING_QUOTA':
      return `${constraint.label}: ${constraint.eveningsPerWeek} evenings per week`
  }
}
```

- [ ] **Step 4: Write the preview component and the page**

`frontend/src/components/SetupPreview.tsx`:

```tsx
import type { GoalDto, SetupParseResponse } from '../api/types'
import { describeConstraint } from '../setup/describeConstraint'

interface Props {
  value: SetupParseResponse
  onChange: (next: SetupParseResponse) => void
}

/** The parsed setup. Priority and deadline of each goal can be edited before saving. */
export function SetupPreview({ value, onChange }: Props) {
  function updateGoal(id: string, changes: Partial<GoalDto>) {
    onChange({
      ...value,
      goals: value.goals.map((goal) => (goal.id === id ? { ...goal, ...changes } : goal)),
    })
  }

  return (
    <div className="preview">
      {value.warnings.length > 0 && (
        <div className="warnings">
          <h4>Not understood (these lines are ignored)</h4>
          <ul>
            {value.warnings.map((warning) => (
              <li key={warning}>{warning}</li>
            ))}
          </ul>
        </div>
      )}

      <h4>Goals</h4>
      <p className="muted">Priority: 1 = least important, 5 = most important. A deadline gives a goal more time.</p>
      {value.categories.map((category) => (
        <div key={category.id}>
          <h5>{category.name}</h5>
          <ul className="goal-list">
            {value.goals
              .filter((goal) => goal.categoryId === category.id)
              .map((goal) => (
                <li key={goal.id}>
                  <span className="goal-name">{goal.name}</span>
                  <select
                    aria-label={`Priority for ${goal.name}`}
                    value={goal.priority}
                    onChange={(event) => updateGoal(goal.id, { priority: Number(event.target.value) })}
                  >
                    {[1, 2, 3, 4, 5].map((priority) => (
                      <option key={priority} value={priority}>
                        {priority}
                      </option>
                    ))}
                  </select>
                  <input
                    type="date"
                    aria-label={`Deadline for ${goal.name}`}
                    value={goal.deadline ?? ''}
                    // an empty date input gives "", but the backend wants null for "no deadline"
                    onChange={(event) => updateGoal(goal.id, { deadline: event.target.value || null })}
                  />
                </li>
              ))}
          </ul>
        </div>
      ))}

      <h4>Constraints</h4>
      <ul>
        {value.constraints.map((constraint, index) => (
          <li key={index}>{describeConstraint(constraint)}</li>
        ))}
      </ul>
    </div>
  )
}
```

`frontend/src/pages/SetupPage.tsx`:

```tsx
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import type { SetupParseResponse } from '../api/types'
import { SetupPreview } from '../components/SetupPreview'
import { EXAMPLE_CONSTRAINTS, EXAMPLE_GOALS } from '../setup/example'

export function SetupPage() {
  const navigate = useNavigate()
  const [goalsText, setGoalsText] = useState(EXAMPLE_GOALS)
  const [constraintsText, setConstraintsText] = useState(EXAMPLE_CONSTRAINTS)
  const [preview, setPreview] = useState<SetupParseResponse | null>(null)
  const [previewTitle, setPreviewTitle] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // On a return visit, show what is already saved. A failure here is not worth an error message:
  // the Parse button reports an unreachable backend the moment it is used.
  useEffect(() => {
    let cancelled = false
    api.getSetup().then(
      (saved) => {
        if (cancelled || saved.goals.length === 0) return
        // do not overwrite a parse the user already started
        setPreview((current) => current ?? { ...saved, warnings: [] })
        setPreviewTitle((current) => current || 'Saved setup')
      },
      () => {},
    )
    return () => {
      cancelled = true
    }
  }, [])

  async function parse() {
    setBusy(true)
    setError(null)
    try {
      setPreview(await api.parseSetup({ goalsText, constraintsText }))
      setPreviewTitle('Parsed setup (not saved yet)')
    } catch (failure) {
      setError(errorMessage(failure))
    } finally {
      setBusy(false)
    }
  }

  async function save() {
    if (!preview) return
    setBusy(true)
    setError(null)
    try {
      await api.saveSetup({
        categories: preview.categories,
        goals: preview.goals,
        constraints: preview.constraints,
      })
      navigate('/')
    } catch (failure) {
      setError(errorMessage(failure))
      setBusy(false)
    }
  }

  return (
    <section>
      <h2>Setup</h2>

      <div className="field">
        <label htmlFor="goals-text">Goals</label>
        <textarea id="goals-text" rows={16} value={goalsText} onChange={(event) => setGoalsText(event.target.value)} />
      </div>
      <div className="field">
        <label htmlFor="constraints-text">Constraints (one per line)</label>
        <textarea
          id="constraints-text"
          rows={7}
          value={constraintsText}
          onChange={(event) => setConstraintsText(event.target.value)}
        />
      </div>

      <button type="button" disabled={busy} onClick={parse}>
        Parse
      </button>
      {error && <p role="alert">{error}</p>}

      {preview && (
        <>
          <h3>{previewTitle}</h3>
          <SetupPreview value={preview} onChange={setPreview} />
          <p className="muted">
            Days that are already planned keep their plan. Days that are not planned yet use this setup.
          </p>
          <button type="button" disabled={busy || preview.goals.length === 0} onClick={save}>
            Save
          </button>
        </>
      )}
    </section>
  )
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `npm --prefix frontend test`
Expected: PASS, 8 test files, 55 tests (describeConstraint 6 and SetupPage 8 added).

Run: `npm --prefix frontend run build`
Expected: builds without type errors.

- [ ] **Step 6: Commit**

```bash
git add frontend
git commit -m "feat: add Setup page with parse preview, editable priority and deadline, save"
```

---

### Task 5: Routing, README and end-to-end check

**Files:**
- Modify: `frontend/src/App.tsx` (replace the Task 1 placeholder)
- Test: `frontend/src/App.test.tsx`
- Modify: `README.md` (repository root)

**Interfaces:**
- Consumes: `TodayPage` (Task 3), `SetupPage` (Task 4), `main.tsx` from Task 1 (already wraps `<App />` in `<BrowserRouter>`).
- Produces: `App` (default export): header with `NavLink`s `Today` (to `/`, `end`) and `Setup` (to `/setup`) and the two routes.

- [ ] **Step 1: Write the failing test**

`frontend/src/App.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { TODAY } from './test/fixtures'
import { jsonResponse, mockApi } from './test/mockApi'

afterEach(() => vi.unstubAllGlobals())

describe('App', () => {
  it('shows Today at / with a link to Setup', async () => {
    mockApi({ 'GET /api/plan/today': () => jsonResponse(TODAY) })

    render(
      <MemoryRouter initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    )

    expect(await screen.findByRole('heading', { name: "Today's plan" })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Setup' })).toHaveAttribute('href', '/setup')
    expect(screen.getByRole('link', { name: 'Today' })).toHaveAttribute('href', '/')
  })

  it('shows Setup at /setup', () => {
    mockApi({ 'GET /api/setup': () => jsonResponse({ categories: [], goals: [], constraints: [] }) })

    render(
      <MemoryRouter initialEntries={['/setup']}>
        <App />
      </MemoryRouter>,
    )

    expect(screen.getByRole('heading', { name: 'Setup' })).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm --prefix frontend test -- src/App.test.tsx`
Expected: FAIL: the placeholder `App` has no Today heading or links.

- [ ] **Step 3: Replace `frontend/src/App.tsx`**

```tsx
import { NavLink, Route, Routes } from 'react-router-dom'
import { SetupPage } from './pages/SetupPage'
import { TodayPage } from './pages/TodayPage'

export default function App() {
  return (
    <div className="app">
      <header>
        <h1>Life Organizer</h1>
        <nav>
          <NavLink to="/" end>
            Today
          </NavLink>
          <NavLink to="/setup">Setup</NavLink>
        </nav>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<TodayPage />} />
          <Route path="/setup" element={<SetupPage />} />
        </Routes>
      </main>
    </div>
  )
}
```

- [ ] **Step 4: Run all tests and the build**

Run: `npm --prefix frontend test`
Expected: PASS, 9 test files, 57 tests.

Run: `npm --prefix frontend run build`
Expected: builds without type errors.

- [ ] **Step 5: Update the root `README.md`**

Make these three edits (keep everything else as it is):

1. Replace the status paragraph

```
**Status:** the scheduling engine (Phase 1a) and the backend — Postgres persistence and the REST API
(Phase 1b) — are done. The React UI is the next plan. Until then you can drive the whole app with `curl`.
```

with

```
**Status:** the scheduling engine (Phase 1a), the backend (Phase 1b: Postgres persistence and the REST API)
and the React UI are done. Later phases add the AI layer (LLM extraction, planning agent, RAG).
```

2. In the `## What you need` list add a line after the Docker line:

```
- Node.js 22.12 or newer (tested with 24) — for the React UI
```

3. Insert this section immediately before the `## Try the API` heading:

````markdown
## Run the UI

With the database and the backend running (previous section), in a second terminal:

```bash
cd frontend
npm install        # once
npm run dev        # http://localhost:5173
```

Open http://localhost:5173.

1. **Setup** — the two boxes are prefilled with an example. Press **Parse**, adjust priorities or deadlines
   if you like, press **Save**. Lines the parser did not understand are listed as "not understood" and are ignored;
   edit the text and press Parse again.
2. **Today** — your timeline. **Done** / **Postpone** (optionally with a reason) / **Cancel** apply to goal blocks
   and ad-hoc events; a toast shows what the replanner changed. A block postponed three times is flagged.

How it fits together: `npm run dev` starts Vite, a development web server. The browser talks only to Vite;
Vite forwards every `/api/...` request to the Spring Boot backend on port 8080 (`frontend/vite.config.ts`),
so there is no CORS configuration.

Other commands (run inside `frontend/`):

| Command | What it does |
|---|---|
| `npm test` | runs the Vitest tests once (`npm run test:watch` re-runs on change) |
| `npm run build` | type-checks with `tsc`, then writes the production bundle to `frontend/dist` |
````

- [ ] **Step 6: Commit**

```bash
git add frontend README.md
git commit -m "feat: add app routing and document running the UI"
```

- [ ] **Step 7: Manual end-to-end check (skipped by automated executors; the controller runs it in the browser pane)**

Prerequisite: Docker Desktop running. From the repository root: `docker compose up -d`; in `backend/`: `.\mvnw.cmd spring-boot:run`; in `frontend/`: `npm run dev`. Open http://localhost:5173.

1. Fresh database: `/` redirects to `/setup` (no plan yet).
2. Press **Parse**: goals grouped by category, constraints such as `Work: 08:30–17:00, Mon–Fri`, no warnings for the example. Change a priority, press **Save**: lands on Today.
3. Today shows the example timeline for the current day. Press **Postpone** on a goal block, type a reason, **Confirm postpone**: the block is marked Postponed with its reason and reappears later with a toast describing the move. Postpone the moved block twice more: the block is flagged "Postponed 3× — try a 25 minute version?".
4. **Done** on another goal block: badge Done, buttons gone.
5. Restart the backend, reload Today: statuses survived.
6. Visit `/setup`: the saved setup is shown under "Saved setup". Clear a deadline and save: no error (deadline sent as `null`).
7. Stop the backend, press Parse: "Could not reach the server. Is the backend running on port 8080?".

---

## Addendum: changes made after the final review

The code blocks in Tasks 1-5 above are what was first built and reviewed per task. The final whole-branch review found issues that the per-task reviews could not see; they were fixed in commits `04189f1` (during Task 1), `a39f91a` and `a58ad2f`. Where the code blocks above differ from the repository, the repository is right. Behaviour that changed:

- **Backend-down hint** (`api/client.ts`): a 5xx answer without a problem+json body (what the Vite proxy sends when Spring Boot is not running) shows "Could not reach the server...". A real backend 500 keeps its own detail text.
- **Setup page**: editing either textarea after Parse marks the preview stale (Save disabled until Parse is pressed again). On a return visit the boxes are emptied (they cannot show the saved setup, which is stored as structured data), a hint explains how to replace it, and Parse is disabled while the Goals box is empty. This stops "Parse then Save" from silently replacing a saved setup with the example.
- **Today page**: refetches when the tab becomes visible again and once a minute when the plan's date is no longer the local date, so a tab left open past midnight never acts on yesterday's block ids. `useApi` gained a request sequence (a late response can never overwrite newer data, including data set by an action) and keeps the last data when a refresh fails (the page shows the plan plus a "Could not refresh" note). Action errors appear in a fixed box at the bottom next to the toast; a failed action clears the previous success toast.
- **Accessibility**: the change toast's live region is always mounted; focus moves to the reason field when Postpone opens, back to Postpone on "Never mind", and to the card after an action removes its buttons.
- **Small**: the "Postponed 3x" hint only shows on blocks that are still planned; `describeConstraint` has no trailing comma and says "1 evening"; unknown routes redirect to `/`; `engines.node >= 22.12`; `strict: true`; `preview.proxy` mirrors `server.proxy`.

Tests: 88 (10 files). Known small gaps, accepted: a narrow race where `GET /api/setup` resolves after Parse was already pressed on the example text (see ledger); the timezone-sensitive rollover tests only bite outside UTC.
