# Life Organizer

A personal day planner. Paste your goals and your constraints once; the app builds today's plan,
and re-plans when the day changes (mark done, postpone, cancel, add an event).

**Status:** the scheduling engine (Phase 1a), the backend (Phase 1b: Postgres persistence and the REST API),
the React UI, and a natural-language assistant (Phase 2: turn a sentence into plan changes via Groq) are
done. Later phases add more of the AI layer (a planning agent, RAG).

## What you need

- Java 21
- Docker Desktop (running) — for the local database and for the automated tests
- Node.js 22.12 or newer (tested with 24) — for the React UI

## Run it

```bash
# 1. start Postgres (pgvector image, port 5432)
docker compose up -d

# 2. start the backend on http://localhost:8080
cd backend
./mvnw spring-boot:run          # Windows PowerShell: .\mvnw.cmd spring-boot:run
```

Stop with `Ctrl+C`; `docker compose down` stops the database (add `-v` to wipe its data).
If port 5432 is already in use, stop your local Postgres or change the port mapping in `docker-compose.yml`
and set `DB_URL` accordingly.

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
   edit the text and press Parse again. **Use example text** puts the example back into the two boxes.
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

## Try the API

```bash
# 1. turn your pasted text into structured data (nothing is saved yet)
curl -s -X POST localhost:8080/api/setup/parse -H "Content-Type: application/json" -d '{
  "goalsText": "Goals\n├── Career\n│   ├── Claude certification\n│   └── Azure AI\n├── Health\n│   └── Gym\n└── Social\n    └── Dating / events\n",
  "constraintsText": "Work: 08:30-17:00\nGym: 4x/week\nSleep: 23:30-07:00\nLearning: 5h/week\nSocial: 2 evenings/week\n"
}' > parsed.json

# 2. save it (the parse response can be sent back as-is)
curl -s -X PUT localhost:8080/api/setup -H "Content-Type: application/json" -d @parsed.json

# 3. see today's plan
curl -s localhost:8080/api/plan/today

# 4. act on it - use a block id from step 3
curl -s -X POST localhost:8080/api/plan/today/events -H "Content-Type: application/json" \
  -d '{"type":"MARK_DONE","blockId":"<id>"}'
curl -s -X POST localhost:8080/api/plan/today/events -H "Content-Type: application/json" \
  -d '{"type":"POSTPONE_BLOCK","blockId":"<id>","reason":"not in the mood"}'
```

(On Windows PowerShell use `curl.exe` instead of `curl`, and put the JSON in a file with `-d @file.json`.)

Restart the backend and call `GET /api/plan/today` again: your done/postponed state is still there.

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/setup/parse` | goals text + constraints text → structured setup + warnings (no save) |
| PUT | `/api/setup` | save the (possibly edited) setup; returns what was stored |
| GET | `/api/setup` | the stored setup |
| GET | `/api/plan/today` | today's blocks (generated on first request, then persisted) |
| POST | `/api/plan/today/events` | `MARK_DONE`, `POSTPONE_BLOCK`, `CANCEL_BLOCK`, `SET_ENERGY`, `ADD_ADHOC` |
| GET | `/api/config` | `{aiEnabled, ragEnabled}` - `aiEnabled` reflects whether `GROQ_API_KEY` is configured; `ragEnabled` is false for now |
| POST | `/api/assistant/command` | turn one sentence of free text into a proposed change (see "Assistant" below) |
| POST | `/api/setup/extract` | turn a free-form brain dump into a goals/constraints preview (not saved; needs `GROQ_API_KEY`) |
| POST | `/api/plan/today/events/batch` | apply several plan events at once (used by the assistant's Apply) |

Times are `"HH:mm"`, dates `"yyyy-MM-dd"`. A goal's `deadline` is optional: send `deadline: null` (or omit it,
or send an empty string) for no deadline. Errors are RFC 7807 `application/problem+json`
(`404` no setup yet, `422` unknown block id, `400` invalid request, `502` assistant unavailable, `503` AI
features are not configured).

## Assistant (optional)

Set `GROQ_API_KEY` before starting the backend to turn on the natural-language command box (and the brain-dump box on the Setup page) at the
bottom of Today (get a free key at https://console.groq.com):

```bash
export GROQ_API_KEY=gsk_...      # PowerShell: $env:GROQ_API_KEY="gsk_..."
cd backend && ./mvnw spring-boot:run
```

Type a sentence like "padel got canceled" or "I'm tired, postpone the gym" and press Send. You'll see a
one-line rationale and a list of what would change; press Apply to actually make the change, or Reject to
discard it and try again. Without the key, `GET /api/config` reports `aiEnabled:false` and the box does not
appear - every other feature is unaffected. The model used is `openai/gpt-oss-120b` (configurable via the
`app.ai.model` property).

## How planning works

- **Goals** get a priority (1-5); each weekly quota (e.g. "Learning: 5h/week") is split across its goals by
  priority, with a boost for goals whose deadline is close.
- A day is **generated the first time it is requested** and then stored. Editing your setup later never
  rewrites a day that was already generated. Generation is lazy in two more places: a postpone can generate
  and persist the remaining days of that week while it looks for room, and an ad-hoc event with a future
  date generates that day.
- **Postpone** moves a block to a later free slot today, else to the next day with room this week. The
  third postpone flags the block. Fixed blocks, routine blocks (Dinner / break, Wind down) and completed
  blocks are never postponed, and an already-postponed or cancelled block cannot be postponed again. If no
  slot is free this week the plan is left unchanged and the response carries a "No room this week" note.

## Known limitations

- Editing the setup mid-week can double-count or skip a little quota for the current week (already-done work
  is not yet subtracted from the weekly allocation).
- A postponed block has no notion of "the past" yet (the engine has no clock).
- Cancelling a block frees its slot; it is not automatically refilled with the next-best task.
- `SET_ENERGY` is accepted but does not change the plan yet.

## Tests

```bash
cd backend && ./mvnw test    # needs Docker running; spins up a throwaway Postgres per test class
# Windows PowerShell: cd backend; .\mvnw.cmd test
```

## Design documents

- `docs/superpowers/specs/` — design specs (Phase 1, Phase 1b)
- `docs/superpowers/plans/` — implementation plans
