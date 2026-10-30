# Backlog

Improvements noticed while working on something else. Each one is left out of the change where it was found.
Pick one up like any other work: anything marked "needs spec" goes through `docs/superpowers/specs/` first.
Remove an entry when it ships, or when it turns out not to matter.

Entry format: title, date found, problem with evidence, fix idea, size (small / medium / needs spec).

## Day summaries are one chunk, so a single-goal query misses the day

- **Found:** 2026-10-30, while adding the search relevance floor (`f29be51`).
- **Problem:** `DaySummarizer` turns a whole day into one line, and `MemoryIndexer.indexDay` embeds it as one
  chunk. A day with four goals gives one diluted vector. "system design" scored Fri 2026-09-25 at 0.107, although
  that day has a System design block. Since the 0.3 floor (`MemorySearch.MIN_SCORE`) such days drop out of
  results completely.
- **Fix idea:** index one chunk per GOAL / ADHOC block (`Fri 2026-09-25: System design not marked done 18:00`)
  and keep the full-day chunk for broad queries ("what happened last Friday"). `MemoryChunkStore.replace` already
  takes several chunks, and `MemorySearch` already keeps one hit per source. Put a format version in the content
  hash, or unchanged days are skipped and keep the old single chunk. The snippet then shows the matching block
  line instead of the whole day.
- **Size:** medium, needs spec (changes how past days are searched and what the snippet shows).

## Long goal ids can overflow the block id column

- **Found:** before 2026-10-03 (carried over from the Phase 1b review), not re-checked since the goal form setup.
- **Problem:** `SetupDtoMapper` caps category and goal names at 255 chars, and goal ids are
  `<categoryId>-<slug(name)>`. Block ids embed the goal id plus the date (and `~2` suffixes), and `block.id` is
  `VARCHAR(255)`, so a goal id near 255 chars fails at plan generation with a 500, not a 400 at setup save.
- **Fix idea:** reject at setup save any goal id that leaves too little room for the block id suffix, or cap the
  slug length. Write a failing test first, to confirm the overflow still happens.
- **Size:** small.

## SET_ENERGY is an engine no-op

- **Found:** before 2026-10-03.
- **Problem:** `Replanner` accepts `SetEnergy` and changes nothing. The chat agent can propose it, and the UI then
  shows a change message for a change that does nothing.
- **Fix idea:** decide what energy should do (for example, move demanding goals to later or shorten sessions), or
  stop offering it to the agent until it does something.
- **Size:** needs spec.

## applyEvents does not take the generation lock

- **Found:** 2026-10-03, weekly review final review (left as-is at the time).
- **Problem:** `PlanService.getOrGenerate` takes a Postgres advisory lock, so day generation is serialized. Changes
  are not: `applyEvents` reads, replans and diffs blocks without a lock, so two batches on the same day at once
  could each persist a diff against stale blocks. Single user on localhost, so unlikely.
- **Fix idea:** take the same lock in `applyEvents`, with a `@SpringBootTest` that runs two calls in parallel.
- **Size:** small.
