# Life Organizer — Phase 1b Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the Phase 1a engine into a running backend: Postgres persistence, lazy per-day plan generation that survives restarts, and the REST API the React UI will call — fully covered by automated tests. No UI in this plan (the React frontend is a separate follow-up plan that consumes this API).

**Architecture:** A new `persistence` package holds JPA entities, Spring Data repositories, static domain⇄entity mappers, and two stores (`SetupStore`, `PlanStore`) that speak only in Phase 1a domain types. A `PlanService` orchestrates the *unchanged* Phase 1a engine (`WeekAllocator`, `DayScheduler`, `Replanner`) on top of those stores: the first request for a date generates and persists that day's blocks; later requests read them back; replan events (done/postpone/cancel) are applied to persisted rows by diffing. Thin controllers (`/api/setup/*`, `/api/plan/*`, `/api/config`) translate to string-only DTOs and RFC 7807 `ProblemDetail` errors.

**Tech Stack:** Java 21, Spring Boot 4.1.1 (Spring Framework 7.0.9, Hibernate 7.4.5, Jackson 3, Testcontainers 2.0.5 — all BOM-managed), Maven, Flyway, Postgres 17 (`pgvector/pgvector:pg17`), JUnit 5, AssertJ, MockMvc, Mockito.

## Global Constraints

- Java 21; package root `com.lifeorganizer`. **Never add a `<version>` to a BOM-managed dependency** (Boot's parent manages Testcontainers, Flyway, Postgres driver, Mockito, etc.).
- **Do not import Spring/JPA in `com.lifeorganizer.domain`, `.setup` (the parsers), or `.engine`.** The only Phase 1a edit in this plan is Task 1 (a pure-Java engine fix). Everything Spring-aware lives in `persistence`, `plan`, `setup.api`, `config`, `api`, `error`.
- **Verified Boot 4 test packages (use exactly these; several moved from Boot 3):**
  - `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`
  - `org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase`
  - `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` and `.AutoConfigureMockMvc`
  - `org.springframework.boot.testcontainers.service.connection.ServiceConnection`
  - `org.springframework.boot.test.context.TestConfiguration`, `org.springframework.boot.test.context.SpringBootTest`
  - `org.springframework.test.context.bean.override.mockito.MockitoBean` (Spring Framework 7 — replaces `@MockBean`)
  - `org.testcontainers.postgresql.PostgreSQLContainer` (Testcontainers 2.x — non-generic, artifact names `testcontainers-postgresql` / `testcontainers-junit-jupiter`)
  - `org.springframework.http.ProblemDetail`, `org.springframework.web.bind.annotation.RestControllerAdvice`
- **Every DB-backed test class carries this exact verified boilerplate** (a Docker daemon must be running for `./mvnw test` from Task 2 onward):
  ```java
  @DataJpaTest
  @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
  @Testcontainers
  class SomethingTest {
      @Container
      @ServiceConnection
      static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");
      ...
  }
  ```
  (`@DataJpaTest` runs Flyway, honours `spring.jpa.hibernate.ddl-auto=validate`, and rolls each test back. `@Import({...})` pulls in non-repository beans such as stores/services.)
- **Enums are stored as plain `String` columns** (`Enum.name()` / `Enum.valueOf`) converted in the mappers — no `@Enumerated`, no Hibernate enum types.
- **Deterministic ordering:** Phase 1a breaks ties by *input order* (goal order, constraint order — the shared day-cursor in `WeekAllocator` makes constraint order change results). Therefore every ordered collection (`category`, `goal`, `plan_constraint`, `day_warning`) has an explicit `position` column and is **always** read with `ORDER BY position`. Blocks are read `ORDER BY start_time, id`.
- **DTOs contain only `String`, `int`, `Integer`, `boolean` and `List`** — times are `"HH:mm"`, dates are `"yyyy-MM-dd"`. No java.time types cross the JSON boundary (removes all Jackson-3 date-format ambiguity).
- **All "today" logic uses the injected `java.time.Clock`.** Tests inject a fixed clock; never call `LocalDate.now()` without a clock.
- `spring.jpa.open-in-view=false`, `spring.jpa.hibernate.ddl-auto=validate`.
- Commit message trailer on every commit: `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`.

## Spec deltas (deliberate deviations from `docs/superpowers/specs/2026-09-19-life-organizer-phase1b-design.md`, found while verifying the design against Postgres and the Phase 1a engine)

| Spec said | This plan does | Why |
|---|---|---|
| table `constraint` | table **`plan_constraint`** | `constraint` is a reserved word in Postgres |
| no `position` columns | **`position INTEGER`** on `category`, `goal`, `plan_constraint`, `day_warning` | Phase 1a tie-breaking is order-dependent; DB row order is undefined without `ORDER BY` |
| `block.date`, FK `block.goal_id` → goal | column **`block_date`**; **no FK** on `block.goal_id` | `PUT /api/setup` replaces all goals; an FK from historic blocks would make that fail once any day is generated. A block is a historical record. |
| (no warnings storage) | new **`day_warning`** table | scheduler/allocator warnings (e.g. "Dating / events did not fit", "could not fit 60 of its 480 weekly minutes") would otherwise vanish on the second load of a day |
| `PlanService` talks to repositories | new **`SetupStore` / `PlanStore`** components between them | keeps `PlanService` pure orchestration in Phase 1a domain types |
| — | **`PlanStore.lockGeneration()`** (Postgres advisory lock) around first-time day generation | found while thinking about the frontend: React `<StrictMode>` fires the very first `GET /api/plan/today` twice in parallel; both would insert the same block ids and one request would fail with a primary-key violation. Verified in a scratch project that the lock serializes two transactions. |
| — | **Task 1**: fix `Replanner.addAdhoc` | discovered while designing: `AddAdhoc` left the `FREE` block underneath the new ad-hoc block (two overlapping rows), and a later postpone could land on top of it. Persistence would freeze that ghost time into the DB. |

**Known limitation (documented, not fixed here):** the Phase 1a `WeekAllocator` does not know what was already done earlier in the week (the spec's "DONE units count against quota" is not implemented in the engine). Because days are generated lazily, editing Setup mid-week can double-count or skip a little quota for the *current* week; already-generated days are never regenerated. Also `PostponePolicy` has no notion of "past" (no `Clock`), and cancelling a block frees its slot as a plain `FREE` block rather than auto-refilling it.

## File structure

```
docker-compose.yml                                      (Task 2)
backend/pom.xml                                         (Task 2: +3 test deps)
backend/src/main/resources/application.properties      (Task 2)
backend/src/main/resources/db/migration/V1__phase1b_schema.sql   (Task 2)
backend/src/main/java/com/lifeorganizer/
├── engine/Replanner.java                               (Task 1: modify)
├── persistence/
│   ├── CategoryEntity, GoalEntity, ConstraintEntity (+FixedBlock/FrequencyQuota/DurationQuota/EveningQuota Entity)   (Task 3)
│   ├── CategoryRepository, GoalRepository, ConstraintRepository                                                    (Task 3)
│   ├── BlockEntity, DayWarningEntity, BlockRepository, DayWarningRepository                                        (Task 4)
│   ├── mapper/GoalMapper, ConstraintMapper, BlockMapper                                                            (Task 5)
│   ├── SetupStore                                                                                                  (Task 6)
│   └── PlanStore                                                                                                   (Task 7)
├── error/SetupRequiredException, UnknownBlockException                                     (Task 8)
├── error/InvalidRequestException                                                            (Task 10)
├── config/EngineConfig                                                                      (Task 8)
├── plan/PlanService, plan/AppliedEvent                                                      (Tasks 8-9)
├── api/TimeFormats                                                                          (Task 10)
├── setup/api/SetupService, setup/api/SetupDtoMapper, setup/api/dto/*                        (Task 10)
├── plan/PlanController, plan/dto/*, plan/PlanDtoMapper                                      (Task 11)
├── config/GlobalExceptionHandler, config/ConfigController                                   (Task 11)
└── setup/api/SetupController                                                                (Task 12)
backend/src/test/java/com/lifeorganizer/
├── support/ExampleSetup.java                                                                (Task 6: shared fixture)
└── ...one test class per production class group, listed in each task
```

---

### Task 1: Engine follow-up — `AddAdhoc` must consume the free time it overlaps

**Files:**
- Modify: `backend/src/main/java/com/lifeorganizer/engine/Replanner.java`
- Modify: `backend/src/test/java/com/lifeorganizer/engine/ReplannerTest.java`

**Interfaces:**
- Consumes: `Block`, `BlockKind`, `BlockStatus`, `DayPlan`, `AddAdhoc`, `ReplanResult` (Phase 1a, unchanged).
- Produces: `Replanner.apply(weekPlans, new AddAdhoc(date, title, start, end))` now replaces every `PLANNED` block of kind `FREE` that overlaps the ad-hoc range with the free time left *before* and/or *after* it (ids `<freeId>-before` and `<freeId>-after`, kind `FREE`, title `"Free"`). Non-`FREE` blocks are never touched (an ad-hoc block may still overlap a goal block; that is a conflict for `ConflictDetector`, not something `Replanner` resolves in this phase).

- [ ] **Step 1: Write the failing tests**

In `backend/src/test/java/com/lifeorganizer/engine/ReplannerTest.java`, add these three test methods immediately after the existing `addAdhocInsertsNewBlock` test (the class already has the `mon` field, the `replanner` field and the `weekOf` helper):

```java
    @Test
    void addAdhocConsumesTheFreeTimeItOverlaps() {
        Block free = new Block("free-1", mon, LocalTime.of(20, 0), LocalTime.of(22, 30), "Free",
            BlockKind.FREE, null, BlockStatus.PLANNED, 0, false, null);
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(free), List.of()));

        ReplanResult result = replanner.apply(week, new AddAdhoc(mon, "Padel", LocalTime.of(21, 0), LocalTime.of(22, 0)));

        List<Block> blocks = result.updatedPlans().get(mon).blocks();
        assertThat(blocks).hasSize(3);
        assertThat(blocks).noneMatch(b -> b.id().equals("free-1"));
        assertThat(blocks).anyMatch(b -> b.id().equals("free-1-before") && b.kind() == BlockKind.FREE
            && b.start().equals(LocalTime.of(20, 0)) && b.end().equals(LocalTime.of(21, 0)));
        assertThat(blocks).anyMatch(b -> b.id().equals("free-1-after") && b.kind() == BlockKind.FREE
            && b.start().equals(LocalTime.of(22, 0)) && b.end().equals(LocalTime.of(22, 30)));
        assertThat(blocks).anyMatch(b -> b.kind() == BlockKind.ADHOC && b.title().equals("Padel"));
    }

    @Test
    void addAdhocThatExactlyCoversAFreeBlockRemovesIt() {
        Block free = new Block("free-1", mon, LocalTime.of(21, 0), LocalTime.of(22, 30), "Free",
            BlockKind.FREE, null, BlockStatus.PLANNED, 0, false, null);
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(free), List.of()));

        ReplanResult result = replanner.apply(week, new AddAdhoc(mon, "Padel", LocalTime.of(21, 0), LocalTime.of(22, 30)));

        List<Block> blocks = result.updatedPlans().get(mon).blocks();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).kind()).isEqualTo(BlockKind.ADHOC);
    }

    @Test
    void addAdhocLeavesNonFreeBlocksUntouched() {
        Block work = new Block("work", mon, LocalTime.of(8, 30), LocalTime.of(17, 0), "Work",
            BlockKind.FIXED, null, BlockStatus.PLANNED, 0, false, null);
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(work), List.of()));

        ReplanResult result = replanner.apply(week, new AddAdhoc(mon, "Call", LocalTime.of(9, 0), LocalTime.of(10, 0)));

        List<Block> blocks = result.updatedPlans().get(mon).blocks();
        assertThat(blocks).hasSize(2);
        assertThat(blocks).contains(work);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=ReplannerTest`
Expected: FAIL — `addAdhocConsumesTheFreeTimeItOverlaps` fails (`hasSize(3)` — the current code returns 2 blocks: the untouched `free-1` plus the ad-hoc block) and `addAdhocThatExactlyCoversAFreeBlockRemovesIt` fails (`hasSize(1)` — currently 2). `addAdhocLeavesNonFreeBlocksUntouched` already passes.

- [ ] **Step 3: Implement the fix**

In `backend/src/main/java/com/lifeorganizer/engine/Replanner.java`:

1. Add `import java.time.LocalTime;` directly under the existing `import java.time.LocalDate;`.
2. Replace the whole `addAdhoc` method with the version below and add the `freeFragment` helper right after it (both inside the `Replanner` class):

```java
    private ReplanResult addAdhoc(Map<LocalDate, DayPlan> weekPlans, AddAdhoc event) {
        DayPlan plan = weekPlans.get(event.date());
        if (plan == null) {
            return new ReplanResult(weekPlans, List.of("No plan exists for " + event.date()));
        }
        Block adhoc = new Block("adhoc-" + event.date() + "-" + event.start(), event.date(), event.start(), event.end(),
            event.title(), BlockKind.ADHOC, null, BlockStatus.PLANNED, 0, false, null);

        // The ad-hoc block takes over any free time it overlaps: each overlapped FREE block is
        // replaced by whatever free time is left before and/or after the ad-hoc range.
        List<Block> blocks = new ArrayList<>();
        for (Block existing : plan.blocks()) {
            if (existing.kind() == BlockKind.FREE && existing.status() == BlockStatus.PLANNED && existing.overlaps(adhoc)) {
                if (existing.start().isBefore(adhoc.start())) {
                    blocks.add(freeFragment(existing, existing.id() + "-before", existing.start(), adhoc.start()));
                }
                if (existing.end().isAfter(adhoc.end())) {
                    blocks.add(freeFragment(existing, existing.id() + "-after", adhoc.end(), existing.end()));
                }
            } else {
                blocks.add(existing);
            }
        }
        blocks.add(adhoc);

        Map<LocalDate, DayPlan> updated = new LinkedHashMap<>(weekPlans);
        updated.put(event.date(), new DayPlan(plan.date(), blocks, plan.warnings()));
        return new ReplanResult(updated, List.of("Added " + event.title() + " at " + event.start()));
    }

    private static Block freeFragment(Block source, String id, LocalTime start, LocalTime end) {
        return new Block(id, source.date(), start, end, "Free", BlockKind.FREE, null,
            BlockStatus.PLANNED, 0, false, null);
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=ReplannerTest`
Expected: PASS — 12 tests (the 9 existing + 3 new), including the untouched `addAdhocInsertsNewBlock`.

- [ ] **Step 5: Run the full suite**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS, 76 tests (73 existing + 3 new), no Docker needed yet.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "fix: AddAdhoc consumes the free time it overlaps" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: Infrastructure — docker-compose, Testcontainers dependencies, datasource config, Flyway schema

**Files:**
- Create: `docker-compose.yml` (repo root)
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.properties`
- Create: `backend/src/main/resources/db/migration/V1__phase1b_schema.sql`
- Test: `backend/src/test/java/com/lifeorganizer/persistence/SchemaMigrationTest.java`

**Interfaces:**
- Produces: a Postgres schema with tables `category`, `goal`, `plan_constraint`, `block`, `day_warning` exactly as in the SQL below (later tasks' JPA entities are validated against it by `ddl-auto=validate`); Testcontainers test dependencies; a runnable local database via `docker compose up -d`.

- [ ] **Step 1: Create `docker-compose.yml`**

```yaml
services:
  db:
    image: pgvector/pgvector:pg17
    container_name: life-organizer-db
    environment:
      POSTGRES_DB: lifeorganizer
      POSTGRES_USER: lifeorganizer
      POSTGRES_PASSWORD: lifeorganizer
    ports:
      - "5432:5432"
    volumes:
      - life-organizer-pgdata:/var/lib/postgresql/data

volumes:
  life-organizer-pgdata:
```

- [ ] **Step 2: Add the test dependencies to `backend/pom.xml`**

Insert these three dependencies immediately before the closing `</dependencies>` tag (after the existing `spring-boot-starter-webmvc-test` dependency). No `<version>` elements — the Boot BOM manages them.

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-testcontainers</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-postgresql</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-junit-jupiter</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 3: Replace `backend/src/main/resources/application.properties`**

```properties
spring.application.name=backend

spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/lifeorganizer}
spring.datasource.username=${DB_USER:lifeorganizer}
spring.datasource.password=${DB_PASSWORD:lifeorganizer}

spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
```

- [ ] **Step 4: Write the failing schema test**

Create `backend/src/test/java/com/lifeorganizer/persistence/SchemaMigrationTest.java`:

```java
package com.lifeorganizer.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class SchemaMigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void migrationCreatesEveryTable() {
        List<String> tables = jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE'",
            String.class);

        assertThat(tables).contains("category", "goal", "plan_constraint", "block", "day_warning");
    }

    @Test
    void orderedTablesHaveAPositionColumn() {
        for (String table : List.of("category", "goal", "plan_constraint", "day_warning")) {
            Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_name = ? and column_name = 'position'",
                Integer.class, table);
            assertThat(count).as("position column on " + table).isEqualTo(1);
        }
    }

    @Test
    void blockGoalIdIsIntentionallyNotAForeignKey() {
        // A block is a historical record. Replacing the setup deletes goals; an FK here would make that fail.
        Integer foreignKeys = jdbc.queryForObject(
            "select count(*) from information_schema.table_constraints where table_name = 'block' and constraint_type = 'FOREIGN KEY'",
            Integer.class);

        assertThat(foreignKeys).isZero();
    }

    @Test
    void constraintTableReferencesGoalAndCategory() {
        Integer foreignKeys = jdbc.queryForObject(
            "select count(*) from information_schema.table_constraints where table_name = 'plan_constraint' and constraint_type = 'FOREIGN KEY'",
            Integer.class);

        assertThat(foreignKeys).isEqualTo(2);
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=SchemaMigrationTest`
Expected: FAIL — the container starts, Flyway finds no migrations, so `migrationCreatesEveryTable` fails (tables missing). (The first run also downloads the Testcontainers jars and pulls `pgvector/pgvector:pg17`, so allow a few minutes.)

- [ ] **Step 6: Create the Flyway migration**

Create `backend/src/main/resources/db/migration/V1__phase1b_schema.sql`:

```sql
CREATE TABLE category (
    id       VARCHAR(255) PRIMARY KEY,
    name     VARCHAR(255) NOT NULL,
    position INTEGER      NOT NULL
);

CREATE TABLE goal (
    id                    VARCHAR(255) PRIMARY KEY,
    category_id           VARCHAR(255) NOT NULL REFERENCES category (id),
    name                  VARCHAR(255) NOT NULL,
    priority              INTEGER      NOT NULL,
    deadline              DATE         NULL,
    default_block_minutes INTEGER      NOT NULL,
    energy                VARCHAR(20)  NOT NULL,
    preferred_time        VARCHAR(20)  NOT NULL,
    position              INTEGER      NOT NULL
);

-- Single table for the four Constraint variants; columns unused by a variant stay NULL.
-- (The table is not called "constraint": that is a reserved word in Postgres.)
CREATE TABLE plan_constraint (
    id                BIGSERIAL    PRIMARY KEY,
    type              VARCHAR(30)  NOT NULL,
    label             VARCHAR(255) NOT NULL,
    position          INTEGER      NOT NULL,
    start_time        TIME         NULL,
    end_time          TIME         NULL,
    days              VARCHAR(100) NULL,
    times_per_week    INTEGER      NULL,
    session_minutes   INTEGER      NULL,
    goal_id           VARCHAR(255) NULL REFERENCES goal (id),
    minutes_per_week  INTEGER      NULL,
    category_id       VARCHAR(255) NULL REFERENCES category (id),
    evenings_per_week INTEGER      NULL
);

-- goal_id is deliberately NOT a foreign key: a block is a historical record and
-- replacing the setup (which deletes goals) must never fail because blocks exist.
CREATE TABLE block (
    id             VARCHAR(255) PRIMARY KEY,
    block_date     DATE         NOT NULL,
    start_time     TIME         NOT NULL,
    end_time       TIME         NOT NULL,
    title          VARCHAR(255) NOT NULL,
    kind           VARCHAR(20)  NOT NULL,
    goal_id        VARCHAR(255) NULL,
    status         VARCHAR(20)  NOT NULL,
    postpone_count INTEGER      NOT NULL,
    flagged        BOOLEAN      NOT NULL,
    reason         VARCHAR(500) NULL
);
CREATE INDEX idx_block_date ON block (block_date);

CREATE TABLE day_warning (
    id           BIGSERIAL    PRIMARY KEY,
    warning_date DATE         NOT NULL,
    position     INTEGER      NOT NULL,
    message      VARCHAR(500) NOT NULL
);
CREATE INDEX idx_day_warning_date ON day_warning (warning_date);
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd backend && ./mvnw -q test -Dtest=SchemaMigrationTest`
Expected: PASS — 4 tests green.

- [ ] **Step 8: Run the full suite**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS, 80 tests (76 + 4).

- [ ] **Step 9: Commit**

```bash
git add docker-compose.yml backend
git commit -m "feat: add Postgres schema, Testcontainers setup and docker-compose" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: Setup-side entities and repositories (category, goal, constraints)

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/persistence/CategoryEntity.java`
- Create: `backend/src/main/java/com/lifeorganizer/persistence/GoalEntity.java`
- Create: `backend/src/main/java/com/lifeorganizer/persistence/ConstraintEntity.java`
- Create: `backend/src/main/java/com/lifeorganizer/persistence/FixedBlockEntity.java`
- Create: `backend/src/main/java/com/lifeorganizer/persistence/FrequencyQuotaEntity.java`
- Create: `backend/src/main/java/com/lifeorganizer/persistence/DurationQuotaEntity.java`
- Create: `backend/src/main/java/com/lifeorganizer/persistence/EveningQuotaEntity.java`
- Create: `backend/src/main/java/com/lifeorganizer/persistence/CategoryRepository.java`
- Create: `backend/src/main/java/com/lifeorganizer/persistence/GoalRepository.java`
- Create: `backend/src/main/java/com/lifeorganizer/persistence/ConstraintRepository.java`
- Test: `backend/src/test/java/com/lifeorganizer/persistence/SetupRepositoriesTest.java`

**Interfaces:**
- Consumes: the Task 2 schema.
- Produces (used by Tasks 5-6):
  - `CategoryEntity(String id, String name, int position)` with `getId()/getName()/getPosition()`.
  - `GoalEntity(String id, CategoryEntity category, String name, int priority, LocalDate deadline, int defaultBlockMinutes, String energy, String preferredTime, int position)` with getters `getId, getCategory, getName, getPriority, getDeadline, getDefaultBlockMinutes, getEnergy, getPreferredTime, getPosition`.
  - abstract `ConstraintEntity` (`getId(): Long`, `getLabel()`, `getPosition()`) and subclasses: `FixedBlockEntity(String label, int position, LocalTime startTime, LocalTime endTime, String days)`; `FrequencyQuotaEntity(String label, int position, int timesPerWeek, int sessionMinutes, String goalId)`; `DurationQuotaEntity(String label, int position, int minutesPerWeek, String categoryId)`; `EveningQuotaEntity(String label, int position, int eveningsPerWeek, String categoryId)`; each with the obvious getters (`getStartTime, getEndTime, getDays`; `getTimesPerWeek, getSessionMinutes, getGoalId`; `getMinutesPerWeek, getCategoryId`; `getEveningsPerWeek, getCategoryId`). Boxed `Integer` getters for the nullable columns.
  - `CategoryRepository`, `GoalRepository`, `ConstraintRepository`, each `JpaRepository<…>` with `findAllByOrderByPositionAsc()`.

- [ ] **Step 1: Write the failing repository test**

Create `backend/src/test/java/com/lifeorganizer/persistence/SetupRepositoriesTest.java`:

```java
package com.lifeorganizer.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class SetupRepositoriesTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @Autowired
    CategoryRepository categories;

    @Autowired
    GoalRepository goals;

    @Autowired
    ConstraintRepository constraints;

    @PersistenceContext
    EntityManager entityManager;

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void categoriesComeBackInPositionOrderNotInsertionOrIdOrder() {
        // insertion order: a, b   |   id order: a, b   |   position order: b, a
        categories.save(new CategoryEntity("a", "A", 1));
        categories.save(new CategoryEntity("b", "B", 0));
        flushAndClear();

        assertThat(categories.findAllByOrderByPositionAsc())
            .extracting(CategoryEntity::getId)
            .containsExactly("b", "a");
    }

    @Test
    void goalsRoundTripWithTheirCategoryAndAnOptionalDeadline() {
        CategoryEntity career = categories.save(new CategoryEntity("career", "Career", 0));
        goals.save(new GoalEntity("career-cert", career, "Claude certification", 5,
            LocalDate.of(2026, 12, 1), 90, "HIGH", "EVENING", 1));
        goals.save(new GoalEntity("career-azure", career, "Azure AI", 3,
            null, 60, "MEDIUM", "ANY", 0));
        flushAndClear();

        List<GoalEntity> found = goals.findAllByOrderByPositionAsc();

        assertThat(found).extracting(GoalEntity::getId).containsExactly("career-azure", "career-cert");
        assertThat(found.get(0).getDeadline()).isNull();
        GoalEntity cert = found.get(1);
        assertThat(cert.getCategory().getName()).isEqualTo("Career");
        assertThat(cert.getName()).isEqualTo("Claude certification");
        assertThat(cert.getPriority()).isEqualTo(5);
        assertThat(cert.getDeadline()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(cert.getDefaultBlockMinutes()).isEqualTo(90);
        assertThat(cert.getEnergy()).isEqualTo("HIGH");
        assertThat(cert.getPreferredTime()).isEqualTo("EVENING");
    }

    @Test
    void everyConstraintSubtypeRoundTripsInPositionOrder() {
        categories.save(new CategoryEntity("career", "Career", 0));
        categories.save(new CategoryEntity("social", "Social", 1));
        CategoryEntity health = categories.save(new CategoryEntity("health", "Health", 2));
        goals.save(new GoalEntity("health-gym", health, "Gym", 3, null, 60, "MEDIUM", "ANY", 0));
        categories.flush();
        goals.flush();

        constraints.save(new EveningQuotaEntity("Social", 3, 2, "social"));
        constraints.save(new FixedBlockEntity("Work", 0, LocalTime.of(8, 30), LocalTime.of(17, 0), "MONDAY,TUESDAY"));
        constraints.save(new DurationQuotaEntity("Learning", 2, 300, "career"));
        constraints.save(new FrequencyQuotaEntity("Gym", 1, 4, 60, "health-gym"));
        flushAndClear();

        List<ConstraintEntity> all = constraints.findAllByOrderByPositionAsc();

        assertThat(all).extracting(ConstraintEntity::getLabel).containsExactly("Work", "Gym", "Learning", "Social");

        FixedBlockEntity work = (FixedBlockEntity) all.get(0);
        assertThat(work.getStartTime()).isEqualTo(LocalTime.of(8, 30));
        assertThat(work.getEndTime()).isEqualTo(LocalTime.of(17, 0));
        assertThat(work.getDays()).isEqualTo("MONDAY,TUESDAY");

        FrequencyQuotaEntity gym = (FrequencyQuotaEntity) all.get(1);
        assertThat(gym.getTimesPerWeek()).isEqualTo(4);
        assertThat(gym.getSessionMinutes()).isEqualTo(60);
        assertThat(gym.getGoalId()).isEqualTo("health-gym");

        DurationQuotaEntity learning = (DurationQuotaEntity) all.get(2);
        assertThat(learning.getMinutesPerWeek()).isEqualTo(300);
        assertThat(learning.getCategoryId()).isEqualTo("career");

        EveningQuotaEntity social = (EveningQuotaEntity) all.get(3);
        assertThat(social.getEveningsPerWeek()).isEqualTo(2);
        assertThat(social.getCategoryId()).isEqualTo("social");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=SetupRepositoriesTest`
Expected: FAIL — compilation error, none of the entity/repository classes exist.

- [ ] **Step 3: Create the entities**

`backend/src/main/java/com/lifeorganizer/persistence/CategoryEntity.java`:

```java
package com.lifeorganizer.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "category")
public class CategoryEntity {

    @Id
    private String id;

    private String name;

    private int position;

    protected CategoryEntity() {
    }

    public CategoryEntity(String id, String name, int position) {
        this.id = id;
        this.name = name;
        this.position = position;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getPosition() {
        return position;
    }
}
```

`backend/src/main/java/com/lifeorganizer/persistence/GoalEntity.java`:

```java
package com.lifeorganizer.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(name = "goal")
public class GoalEntity {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "category_id")
    private CategoryEntity category;

    private String name;

    private int priority;

    private LocalDate deadline;

    @Column(name = "default_block_minutes")
    private int defaultBlockMinutes;

    private String energy;

    @Column(name = "preferred_time")
    private String preferredTime;

    private int position;

    protected GoalEntity() {
    }

    public GoalEntity(String id, CategoryEntity category, String name, int priority, LocalDate deadline,
                      int defaultBlockMinutes, String energy, String preferredTime, int position) {
        this.id = id;
        this.category = category;
        this.name = name;
        this.priority = priority;
        this.deadline = deadline;
        this.defaultBlockMinutes = defaultBlockMinutes;
        this.energy = energy;
        this.preferredTime = preferredTime;
        this.position = position;
    }

    public String getId() {
        return id;
    }

    public CategoryEntity getCategory() {
        return category;
    }

    public String getName() {
        return name;
    }

    public int getPriority() {
        return priority;
    }

    public LocalDate getDeadline() {
        return deadline;
    }

    public int getDefaultBlockMinutes() {
        return defaultBlockMinutes;
    }

    public String getEnergy() {
        return energy;
    }

    public String getPreferredTime() {
        return preferredTime;
    }

    public int getPosition() {
        return position;
    }
}
```

`backend/src/main/java/com/lifeorganizer/persistence/ConstraintEntity.java`:

```java
package com.lifeorganizer.persistence;

import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;

/**
 * One row per Constraint, all four variants in a single table ({@code plan_constraint}) with a
 * {@code type} discriminator. Mirrors the sealed Phase 1a {@code Constraint} interface.
 */
@Entity
@Table(name = "plan_constraint")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "type", length = 30)
public abstract class ConstraintEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String label;

    private int position;

    protected ConstraintEntity() {
    }

    protected ConstraintEntity(String label, int position) {
        this.label = label;
        this.position = position;
    }

    public Long getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public int getPosition() {
        return position;
    }
}
```

`backend/src/main/java/com/lifeorganizer/persistence/FixedBlockEntity.java`:

```java
package com.lifeorganizer.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import java.time.LocalTime;

@Entity
@DiscriminatorValue("FIXED_BLOCK")
public class FixedBlockEntity extends ConstraintEntity {

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    /** Comma-joined DayOfWeek names in week order, e.g. {@code MONDAY,TUESDAY}. */
    private String days;

    protected FixedBlockEntity() {
    }

    public FixedBlockEntity(String label, int position, LocalTime startTime, LocalTime endTime, String days) {
        super(label, position);
        this.startTime = startTime;
        this.endTime = endTime;
        this.days = days;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public String getDays() {
        return days;
    }
}
```

`backend/src/main/java/com/lifeorganizer/persistence/FrequencyQuotaEntity.java`:

```java
package com.lifeorganizer.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue("FREQUENCY_QUOTA")
public class FrequencyQuotaEntity extends ConstraintEntity {

    @Column(name = "times_per_week")
    private Integer timesPerWeek;

    @Column(name = "session_minutes")
    private Integer sessionMinutes;

    @Column(name = "goal_id")
    private String goalId;

    protected FrequencyQuotaEntity() {
    }

    public FrequencyQuotaEntity(String label, int position, int timesPerWeek, int sessionMinutes, String goalId) {
        super(label, position);
        this.timesPerWeek = timesPerWeek;
        this.sessionMinutes = sessionMinutes;
        this.goalId = goalId;
    }

    public Integer getTimesPerWeek() {
        return timesPerWeek;
    }

    public Integer getSessionMinutes() {
        return sessionMinutes;
    }

    public String getGoalId() {
        return goalId;
    }
}
```

`backend/src/main/java/com/lifeorganizer/persistence/DurationQuotaEntity.java`:

```java
package com.lifeorganizer.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue("DURATION_QUOTA")
public class DurationQuotaEntity extends ConstraintEntity {

    @Column(name = "minutes_per_week")
    private Integer minutesPerWeek;

    @Column(name = "category_id")
    private String categoryId;

    protected DurationQuotaEntity() {
    }

    public DurationQuotaEntity(String label, int position, int minutesPerWeek, String categoryId) {
        super(label, position);
        this.minutesPerWeek = minutesPerWeek;
        this.categoryId = categoryId;
    }

    public Integer getMinutesPerWeek() {
        return minutesPerWeek;
    }

    public String getCategoryId() {
        return categoryId;
    }
}
```

`backend/src/main/java/com/lifeorganizer/persistence/EveningQuotaEntity.java`:

```java
package com.lifeorganizer.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue("EVENING_QUOTA")
public class EveningQuotaEntity extends ConstraintEntity {

    @Column(name = "evenings_per_week")
    private Integer eveningsPerWeek;

    // Sibling single-table subclasses may map the same column (DurationQuotaEntity does too) - verified on Hibernate 7.
    @Column(name = "category_id")
    private String categoryId;

    protected EveningQuotaEntity() {
    }

    public EveningQuotaEntity(String label, int position, int eveningsPerWeek, String categoryId) {
        super(label, position);
        this.eveningsPerWeek = eveningsPerWeek;
        this.categoryId = categoryId;
    }

    public Integer getEveningsPerWeek() {
        return eveningsPerWeek;
    }

    public String getCategoryId() {
        return categoryId;
    }
}
```

- [ ] **Step 4: Create the repositories**

`backend/src/main/java/com/lifeorganizer/persistence/CategoryRepository.java`:

```java
package com.lifeorganizer.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<CategoryEntity, String> {

    List<CategoryEntity> findAllByOrderByPositionAsc();
}
```

`backend/src/main/java/com/lifeorganizer/persistence/GoalRepository.java`:

```java
package com.lifeorganizer.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GoalRepository extends JpaRepository<GoalEntity, String> {

    List<GoalEntity> findAllByOrderByPositionAsc();
}
```

`backend/src/main/java/com/lifeorganizer/persistence/ConstraintRepository.java`:

```java
package com.lifeorganizer.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConstraintRepository extends JpaRepository<ConstraintEntity, Long> {

    List<ConstraintEntity> findAllByOrderByPositionAsc();
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && ./mvnw -q test -Dtest=SetupRepositoriesTest`
Expected: PASS — 3 tests green. This also proves the entities agree with the Flyway schema (`ddl-auto=validate` would fail context startup otherwise).

- [ ] **Step 6: Run the full suite**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS, 83 tests.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat: add category, goal and constraint entities and repositories" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 4: Plan-side entities and repositories (block, day warning)

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/persistence/BlockEntity.java`
- Create: `backend/src/main/java/com/lifeorganizer/persistence/DayWarningEntity.java`
- Create: `backend/src/main/java/com/lifeorganizer/persistence/BlockRepository.java`
- Create: `backend/src/main/java/com/lifeorganizer/persistence/DayWarningRepository.java`
- Test: `backend/src/test/java/com/lifeorganizer/persistence/PlanRepositoriesTest.java`

**Interfaces:**
- Consumes: the Task 2 schema.
- Produces (used by Tasks 5, 7):
  - `BlockEntity(String id, LocalDate date, LocalTime startTime, LocalTime endTime, String title, String kind, String goalId, String status, int postponeCount, boolean flagged, String reason)` with getters `getId, getDate, getStartTime, getEndTime, getTitle, getKind, getGoalId, getStatus, getPostponeCount, isFlagged, getReason`.
  - `DayWarningEntity(LocalDate date, int position, String message)` with `getDate, getPosition, getMessage`.
  - `BlockRepository extends JpaRepository<BlockEntity, String>`: `List<BlockEntity> findByDateOrderByStartTimeAscIdAsc(LocalDate date)`.
  - `DayWarningRepository extends JpaRepository<DayWarningEntity, Long>`: `List<DayWarningEntity> findByDateOrderByPositionAsc(LocalDate date)`, `void deleteByDate(LocalDate date)`.

- [ ] **Step 1: Write the failing repository test**

Create `backend/src/test/java/com/lifeorganizer/persistence/PlanRepositoriesTest.java`:

```java
package com.lifeorganizer.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PlanRepositoriesTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private static final LocalDate MON = LocalDate.of(2026, 9, 14);
    private static final LocalDate TUE = MON.plusDays(1);

    @Autowired
    BlockRepository blocks;

    @Autowired
    DayWarningRepository warnings;

    @PersistenceContext
    EntityManager entityManager;

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static BlockEntity block(String id, LocalDate date, LocalTime start, LocalTime end) {
        return new BlockEntity(id, date, start, end, "Title " + id, "GOAL", "goal-1", "PLANNED", 0, false, null);
    }

    @Test
    void blocksAreFilteredByDateAndOrderedByStartTimeThenId() {
        blocks.save(block("b", MON, LocalTime.of(7, 0), LocalTime.of(8, 0)));
        blocks.save(block("a", MON, LocalTime.of(7, 0), LocalTime.of(8, 0)));
        blocks.save(block("late", MON, LocalTime.of(20, 0), LocalTime.of(21, 0)));
        blocks.save(block("early", MON, LocalTime.of(6, 0), LocalTime.of(6, 30)));
        blocks.save(block("other-day", TUE, LocalTime.of(7, 0), LocalTime.of(8, 0)));
        flushAndClear();

        assertThat(blocks.findByDateOrderByStartTimeAscIdAsc(MON))
            .extracting(BlockEntity::getId)
            .containsExactly("early", "a", "b", "late");
    }

    @Test
    void everyBlockFieldRoundTripsIncludingNulls() {
        blocks.save(new BlockEntity("full", MON, LocalTime.of(18, 0), LocalTime.of(19, 0), "Claude certification",
            "GOAL", "career-claude-certification", "POSTPONED", 2, true, "tired"));
        blocks.save(new BlockEntity("bare", MON, LocalTime.of(22, 30), LocalTime.of(23, 30), "Wind down",
            "ROUTINE", null, "PLANNED", 0, false, null));
        flushAndClear();

        List<BlockEntity> found = blocks.findByDateOrderByStartTimeAscIdAsc(MON);

        BlockEntity full = found.get(0);
        assertThat(full.getId()).isEqualTo("full");
        assertThat(full.getDate()).isEqualTo(MON);
        assertThat(full.getStartTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(full.getEndTime()).isEqualTo(LocalTime.of(19, 0));
        assertThat(full.getTitle()).isEqualTo("Claude certification");
        assertThat(full.getKind()).isEqualTo("GOAL");
        assertThat(full.getGoalId()).isEqualTo("career-claude-certification");
        assertThat(full.getStatus()).isEqualTo("POSTPONED");
        assertThat(full.getPostponeCount()).isEqualTo(2);
        assertThat(full.isFlagged()).isTrue();
        assertThat(full.getReason()).isEqualTo("tired");

        BlockEntity bare = found.get(1);
        assertThat(bare.getGoalId()).isNull();
        assertThat(bare.getReason()).isNull();
        assertThat(bare.isFlagged()).isFalse();
    }

    @Test
    void savingAnExistingIdUpdatesTheRow() {
        blocks.save(block("x", MON, LocalTime.of(7, 0), LocalTime.of(8, 0)));
        flushAndClear();

        blocks.save(new BlockEntity("x", MON, LocalTime.of(7, 0), LocalTime.of(8, 0), "Title x",
            "GOAL", "goal-1", "DONE", 0, false, null));
        flushAndClear();

        List<BlockEntity> found = blocks.findByDateOrderByStartTimeAscIdAsc(MON);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getStatus()).isEqualTo("DONE");
    }

    @Test
    void warningsAreOrderedByPositionAndCanBeDeletedPerDate() {
        warnings.save(new DayWarningEntity(MON, 1, "second"));
        warnings.save(new DayWarningEntity(MON, 0, "first"));
        warnings.save(new DayWarningEntity(TUE, 0, "other day"));
        flushAndClear();

        assertThat(warnings.findByDateOrderByPositionAsc(MON))
            .extracting(DayWarningEntity::getMessage)
            .containsExactly("first", "second");

        warnings.deleteByDate(MON);
        flushAndClear();

        assertThat(warnings.findByDateOrderByPositionAsc(MON)).isEmpty();
        assertThat(warnings.findByDateOrderByPositionAsc(TUE)).hasSize(1);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=PlanRepositoriesTest`
Expected: FAIL — compilation error (`BlockEntity`, `DayWarningEntity`, repositories do not exist).

- [ ] **Step 3: Create the entities**

`backend/src/main/java/com/lifeorganizer/persistence/BlockEntity.java`:

```java
package com.lifeorganizer.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "block")
public class BlockEntity {

    @Id
    private String id;

    @Column(name = "block_date")
    private LocalDate date;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    private String title;

    private String kind;

    /** Not a foreign key on purpose - see the schema migration. */
    @Column(name = "goal_id")
    private String goalId;

    private String status;

    @Column(name = "postpone_count")
    private int postponeCount;

    private boolean flagged;

    private String reason;

    protected BlockEntity() {
    }

    public BlockEntity(String id, LocalDate date, LocalTime startTime, LocalTime endTime, String title, String kind,
                       String goalId, String status, int postponeCount, boolean flagged, String reason) {
        this.id = id;
        this.date = date;
        this.startTime = startTime;
        this.endTime = endTime;
        this.title = title;
        this.kind = kind;
        this.goalId = goalId;
        this.status = status;
        this.postponeCount = postponeCount;
        this.flagged = flagged;
        this.reason = reason;
    }

    public String getId() {
        return id;
    }

    public LocalDate getDate() {
        return date;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public String getTitle() {
        return title;
    }

    public String getKind() {
        return kind;
    }

    public String getGoalId() {
        return goalId;
    }

    public String getStatus() {
        return status;
    }

    public int getPostponeCount() {
        return postponeCount;
    }

    public boolean isFlagged() {
        return flagged;
    }

    public String getReason() {
        return reason;
    }
}
```

`backend/src/main/java/com/lifeorganizer/persistence/DayWarningEntity.java`:

```java
package com.lifeorganizer.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(name = "day_warning")
public class DayWarningEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "warning_date")
    private LocalDate date;

    private int position;

    private String message;

    protected DayWarningEntity() {
    }

    public DayWarningEntity(LocalDate date, int position, String message) {
        this.date = date;
        this.position = position;
        this.message = message;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getDate() {
        return date;
    }

    public int getPosition() {
        return position;
    }

    public String getMessage() {
        return message;
    }
}
```

- [ ] **Step 4: Create the repositories**

`backend/src/main/java/com/lifeorganizer/persistence/BlockRepository.java`:

```java
package com.lifeorganizer.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface BlockRepository extends JpaRepository<BlockEntity, String> {

    List<BlockEntity> findByDateOrderByStartTimeAscIdAsc(LocalDate date);
}
```

`backend/src/main/java/com/lifeorganizer/persistence/DayWarningRepository.java`:

```java
package com.lifeorganizer.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface DayWarningRepository extends JpaRepository<DayWarningEntity, Long> {

    List<DayWarningEntity> findByDateOrderByPositionAsc(LocalDate date);

    void deleteByDate(LocalDate date);
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && ./mvnw -q test -Dtest=PlanRepositoriesTest`
Expected: PASS — 4 tests green.

- [ ] **Step 6: Run the full suite**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS, 87 tests.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat: add block and day-warning entities and repositories" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 5: Domain ⇄ entity mappers

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/persistence/mapper/GoalMapper.java`
- Create: `backend/src/main/java/com/lifeorganizer/persistence/mapper/ConstraintMapper.java`
- Create: `backend/src/main/java/com/lifeorganizer/persistence/mapper/BlockMapper.java`
- Test: `backend/src/test/java/com/lifeorganizer/persistence/mapper/GoalMapperTest.java`
- Test: `backend/src/test/java/com/lifeorganizer/persistence/mapper/ConstraintMapperTest.java`
- Test: `backend/src/test/java/com/lifeorganizer/persistence/mapper/BlockMapperTest.java`

**Interfaces:**
- Consumes: Phase 1a domain records (`Category`, `Goal`, `Constraint` and its four variants, `Block`, enums `Energy`, `PreferredTime`, `BlockKind`, `BlockStatus`) and the Task 3-4 entities.
- Produces (static, no Spring, no DB — pure Java):
  - `GoalMapper.toEntity(Category, int position): CategoryEntity`; `GoalMapper.toDomain(CategoryEntity): Category`; `GoalMapper.toEntity(Goal, CategoryEntity, int position): GoalEntity`; `GoalMapper.toDomain(GoalEntity): Goal`.
  - `ConstraintMapper.toEntity(Constraint, int position): ConstraintEntity`; `ConstraintMapper.toDomain(ConstraintEntity): Constraint`.
  - `BlockMapper.toEntity(Block): BlockEntity`; `BlockMapper.toDomain(BlockEntity): Block`.
  - Round trip law: `toDomain(toEntity(x))` equals `x` for every domain value.

- [ ] **Step 1: Write the failing mapper tests**

`backend/src/test/java/com/lifeorganizer/persistence/mapper/GoalMapperTest.java`:

```java
package com.lifeorganizer.persistence.mapper;

import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.Energy;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.PreferredTime;
import com.lifeorganizer.persistence.CategoryEntity;
import com.lifeorganizer.persistence.GoalEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class GoalMapperTest {

    @Test
    void categoryRoundTripsAndKeepsItsPosition() {
        Category career = Category.of("Career");

        CategoryEntity entity = GoalMapper.toEntity(career, 4);

        assertThat(entity.getId()).isEqualTo("career");
        assertThat(entity.getName()).isEqualTo("Career");
        assertThat(entity.getPosition()).isEqualTo(4);
        assertThat(GoalMapper.toDomain(entity)).isEqualTo(career);
    }

    @Test
    void goalWithADeadlineRoundTrips() {
        Category career = Category.of("Career");
        Goal goal = new Goal("career-claude-certification", career, "Claude certification", 5,
            LocalDate.of(2026, 12, 1), 90, Energy.HIGH, PreferredTime.EVENING);

        GoalEntity entity = GoalMapper.toEntity(goal, GoalMapper.toEntity(career, 0), 7);

        assertThat(entity.getEnergy()).isEqualTo("HIGH");
        assertThat(entity.getPreferredTime()).isEqualTo("EVENING");
        assertThat(entity.getPosition()).isEqualTo(7);
        assertThat(GoalMapper.toDomain(entity)).isEqualTo(goal);
    }

    @Test
    void goalWithoutADeadlineRoundTrips() {
        Category health = Category.of("Health");
        Goal goal = Goal.withDefaults("health-gym", health, "Gym");

        GoalEntity entity = GoalMapper.toEntity(goal, GoalMapper.toEntity(health, 0), 0);

        assertThat(entity.getDeadline()).isNull();
        assertThat(GoalMapper.toDomain(entity)).isEqualTo(goal);
    }
}
```

`backend/src/test/java/com/lifeorganizer/persistence/mapper/ConstraintMapperTest.java`:

```java
package com.lifeorganizer.persistence.mapper;

import com.lifeorganizer.domain.Constraint;
import com.lifeorganizer.domain.DurationQuota;
import com.lifeorganizer.domain.EveningQuota;
import com.lifeorganizer.domain.FixedBlock;
import com.lifeorganizer.domain.FrequencyQuota;
import com.lifeorganizer.persistence.ConstraintEntity;
import com.lifeorganizer.persistence.DurationQuotaEntity;
import com.lifeorganizer.persistence.EveningQuotaEntity;
import com.lifeorganizer.persistence.FixedBlockEntity;
import com.lifeorganizer.persistence.FrequencyQuotaEntity;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConstraintMapperTest {

    private static Constraint roundTrip(Constraint constraint) {
        return ConstraintMapper.toDomain(ConstraintMapper.toEntity(constraint, 0));
    }

    @Test
    void fixedBlockRoundTrips() {
        Constraint work = new FixedBlock("Work", LocalTime.of(8, 30), LocalTime.of(17, 0),
            EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));

        assertThat(roundTrip(work)).isEqualTo(work);
    }

    @Test
    void overnightFixedBlockRoundTrips() {
        Constraint sleep = new FixedBlock("Sleep", LocalTime.of(23, 30), LocalTime.of(7, 0),
            EnumSet.allOf(DayOfWeek.class));

        assertThat(roundTrip(sleep)).isEqualTo(sleep);
    }

    @Test
    void fixedBlockDaysAreStoredInWeekOrderNoMatterHowTheSetIsOrdered() {
        Set<DayOfWeek> days = Set.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY);
        FixedBlock block = new FixedBlock("Odd", LocalTime.of(9, 0), LocalTime.of(10, 0), days);

        FixedBlockEntity entity = (FixedBlockEntity) ConstraintMapper.toEntity(block, 0);

        assertThat(entity.getDays()).isEqualTo("MONDAY,WEDNESDAY,SUNDAY");
    }

    @Test
    void frequencyQuotaRoundTrips() {
        Constraint gym = new FrequencyQuota("Gym", 4, 60, "health-gym");

        assertThat(roundTrip(gym)).isEqualTo(gym);
    }

    @Test
    void durationQuotaRoundTrips() {
        Constraint learning = new DurationQuota("Learning", 300, "career");

        assertThat(roundTrip(learning)).isEqualTo(learning);
    }

    @Test
    void eveningQuotaRoundTrips() {
        Constraint social = new EveningQuota("Social", 2, "social");

        assertThat(roundTrip(social)).isEqualTo(social);
    }

    @Test
    void entityCarriesTheTypeSpecificClassAndThePosition() {
        ConstraintEntity gym = ConstraintMapper.toEntity(new FrequencyQuota("Gym", 4, 60, "health-gym"), 3);
        ConstraintEntity learning = ConstraintMapper.toEntity(new DurationQuota("Learning", 300, "career"), 4);
        ConstraintEntity social = ConstraintMapper.toEntity(new EveningQuota("Social", 2, "social"), 5);

        assertThat(gym).isInstanceOf(FrequencyQuotaEntity.class);
        assertThat(gym.getPosition()).isEqualTo(3);
        assertThat(learning).isInstanceOf(DurationQuotaEntity.class);
        assertThat(learning.getPosition()).isEqualTo(4);
        assertThat(social).isInstanceOf(EveningQuotaEntity.class);
        assertThat(social.getLabel()).isEqualTo("Social");
    }
}
```

`backend/src/test/java/com/lifeorganizer/persistence/mapper/BlockMapperTest.java`:

```java
package com.lifeorganizer.persistence.mapper;

import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.persistence.BlockEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class BlockMapperTest {

    private static final LocalDate MON = LocalDate.of(2026, 9, 14);

    @Test
    void blockWithEveryFieldSetRoundTrips() {
        Block block = new Block("cert-p1", MON, LocalTime.of(21, 0), LocalTime.of(22, 0), "Claude certification",
            BlockKind.GOAL, "career-claude-certification", BlockStatus.POSTPONED, 2, true, "tired");

        BlockEntity entity = BlockMapper.toEntity(block);

        assertThat(entity.getKind()).isEqualTo("GOAL");
        assertThat(entity.getStatus()).isEqualTo("POSTPONED");
        assertThat(entity.getPostponeCount()).isEqualTo(2);
        assertThat(entity.isFlagged()).isTrue();
        assertThat(BlockMapper.toDomain(entity)).isEqualTo(block);
    }

    @Test
    void blockWithNullGoalAndReasonRoundTrips() {
        Block block = new Block("routine-winddown-2026-09-14", MON, LocalTime.of(22, 30), LocalTime.of(23, 30),
            "Wind down", BlockKind.ROUTINE, null, BlockStatus.PLANNED, 0, false, null);

        assertThat(BlockMapper.toDomain(BlockMapper.toEntity(block))).isEqualTo(block);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest='GoalMapperTest,ConstraintMapperTest,BlockMapperTest'`
Expected: FAIL — compilation error, the mapper classes do not exist.

- [ ] **Step 3: Implement the mappers**

`backend/src/main/java/com/lifeorganizer/persistence/mapper/GoalMapper.java`:

```java
package com.lifeorganizer.persistence.mapper;

import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.Energy;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.PreferredTime;
import com.lifeorganizer.persistence.CategoryEntity;
import com.lifeorganizer.persistence.GoalEntity;

public final class GoalMapper {

    private GoalMapper() {
    }

    public static CategoryEntity toEntity(Category category, int position) {
        return new CategoryEntity(category.id(), category.name(), position);
    }

    public static Category toDomain(CategoryEntity entity) {
        return new Category(entity.getId(), entity.getName());
    }

    public static GoalEntity toEntity(Goal goal, CategoryEntity category, int position) {
        return new GoalEntity(goal.id(), category, goal.name(), goal.priority(), goal.deadline(),
            goal.defaultBlockMinutes(), goal.energy().name(), goal.preferredTime().name(), position);
    }

    public static Goal toDomain(GoalEntity entity) {
        return new Goal(entity.getId(), toDomain(entity.getCategory()), entity.getName(), entity.getPriority(),
            entity.getDeadline(), entity.getDefaultBlockMinutes(), Energy.valueOf(entity.getEnergy()),
            PreferredTime.valueOf(entity.getPreferredTime()));
    }
}
```

`backend/src/main/java/com/lifeorganizer/persistence/mapper/ConstraintMapper.java`:

```java
package com.lifeorganizer.persistence.mapper;

import com.lifeorganizer.domain.Constraint;
import com.lifeorganizer.domain.DurationQuota;
import com.lifeorganizer.domain.EveningQuota;
import com.lifeorganizer.domain.FixedBlock;
import com.lifeorganizer.domain.FrequencyQuota;
import com.lifeorganizer.persistence.ConstraintEntity;
import com.lifeorganizer.persistence.DurationQuotaEntity;
import com.lifeorganizer.persistence.EveningQuotaEntity;
import com.lifeorganizer.persistence.FixedBlockEntity;
import com.lifeorganizer.persistence.FrequencyQuotaEntity;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

public final class ConstraintMapper {

    private ConstraintMapper() {
    }

    public static ConstraintEntity toEntity(Constraint constraint, int position) {
        return switch (constraint) {
            case FixedBlock f -> new FixedBlockEntity(f.label(), position, f.start(), f.end(), daysToCsv(f.days()));
            case FrequencyQuota q -> new FrequencyQuotaEntity(q.label(), position, q.timesPerWeek(),
                q.sessionMinutes(), q.goalId());
            case DurationQuota q -> new DurationQuotaEntity(q.label(), position, q.minutesPerWeek(), q.categoryId());
            case EveningQuota q -> new EveningQuotaEntity(q.label(), position, q.eveningsPerWeek(), q.categoryId());
        };
    }

    public static Constraint toDomain(ConstraintEntity entity) {
        if (entity instanceof FixedBlockEntity f) {
            return new FixedBlock(f.getLabel(), f.getStartTime(), f.getEndTime(), csvToDays(f.getDays()));
        }
        if (entity instanceof FrequencyQuotaEntity q) {
            return new FrequencyQuota(q.getLabel(), q.getTimesPerWeek(), q.getSessionMinutes(), q.getGoalId());
        }
        if (entity instanceof DurationQuotaEntity q) {
            return new DurationQuota(q.getLabel(), q.getMinutesPerWeek(), q.getCategoryId());
        }
        if (entity instanceof EveningQuotaEntity q) {
            return new EveningQuota(q.getLabel(), q.getEveningsPerWeek(), q.getCategoryId());
        }
        throw new IllegalStateException("Unknown constraint entity type " + entity.getClass().getName());
    }

    /** Week order (Monday first), so the stored value never depends on Set iteration order. */
    private static String daysToCsv(Set<DayOfWeek> days) {
        return days.stream().sorted().map(DayOfWeek::name).collect(Collectors.joining(","));
    }

    private static Set<DayOfWeek> csvToDays(String csv) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String part : csv.split(",")) {
            days.add(DayOfWeek.valueOf(part.trim()));
        }
        return days;
    }
}
```

`backend/src/main/java/com/lifeorganizer/persistence/mapper/BlockMapper.java`:

```java
package com.lifeorganizer.persistence.mapper;

import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.persistence.BlockEntity;

public final class BlockMapper {

    private BlockMapper() {
    }

    public static BlockEntity toEntity(Block block) {
        return new BlockEntity(block.id(), block.date(), block.start(), block.end(), block.title(),
            block.kind().name(), block.goalId(), block.status().name(), block.postponeCount(),
            block.flagged(), block.reason());
    }

    public static Block toDomain(BlockEntity entity) {
        return new Block(entity.getId(), entity.getDate(), entity.getStartTime(), entity.getEndTime(),
            entity.getTitle(), BlockKind.valueOf(entity.getKind()), entity.getGoalId(),
            BlockStatus.valueOf(entity.getStatus()), entity.getPostponeCount(), entity.isFlagged(),
            entity.getReason());
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest='GoalMapperTest,ConstraintMapperTest,BlockMapperTest'`
Expected: PASS — 12 tests green (3 + 7 + 2).

- [ ] **Step 5: Run the full suite**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS, 99 tests.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat: add domain-to-entity mappers for goals, constraints and blocks" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

### Task 6: `SetupStore` (persist and load goals + constraints as domain types) and the shared test fixture

**Files:**
- Create: `backend/src/test/java/com/lifeorganizer/support/ExampleSetup.java` (test-only fixture, reused by Tasks 8-13)
- Create: `backend/src/main/java/com/lifeorganizer/persistence/SetupStore.java`
- Test: `backend/src/test/java/com/lifeorganizer/persistence/SetupStoreTest.java`

**Interfaces:**
- Consumes: Task 3 repositories, Task 5 mappers, Phase 1a `GoalTree`, `Goal`, `Category`, `Constraint`, `GoalTreeParser`, `ConstraintParser`.
- Produces:
  - `ExampleSetup.GOALS_TEXT`, `ExampleSetup.CONSTRAINTS_TEXT` (the user's own example), `ExampleSetup.MONDAY` (`2026-09-14`), `ExampleSetup.tree(): GoalTree`, `ExampleSetup.constraints(): List<Constraint>` (parsed with the real Phase 1a parsers; 6 categories, 9 goals, 6 constraints, no warnings).
  - `@Component SetupStore(CategoryRepository, GoalRepository, ConstraintRepository)` with:
    - `GoalTree loadTree()` — categories and goals in declared (`position`) order; empty tree when nothing saved.
    - `List<Constraint> loadConstraints()` — in declared order.
    - `boolean isEmpty()` — true when no category, goal or constraint row exists.
    - `void replace(GoalTree tree, List<Constraint> constraints)` — `@Transactional`; deletes everything (constraints, then goals, then categories) and inserts the new setup with `position` = list index. Throws `IllegalArgumentException` ("… unknown category …") if a goal's category is not in `tree.categories()`. It does **not** touch `block` or `day_warning` rows.

- [ ] **Step 1: Create the shared fixture**

`backend/src/test/java/com/lifeorganizer/support/ExampleSetup.java`:

```java
package com.lifeorganizer.support;

import com.lifeorganizer.domain.Constraint;
import com.lifeorganizer.domain.GoalTree;
import com.lifeorganizer.setup.ConstraintParser;
import com.lifeorganizer.setup.GoalTreeParser;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/** The user's own example from the original design spec, parsed with the real Phase 1a parsers. */
public final class ExampleSetup {

    public static final String GOALS_TEXT = """
        Goals
        ├── Career
        │   ├── Claude certification
        │   ├── Azure AI
        │   └── System design
        ├── Projects
        │   └── RAG showcase
        ├── Health
        │   ├── Gym
        │   └── Padel / football
        ├── Social
        │   └── Dating / events
        ├── Finance
        │   └── Investing
        └── Hobbies
            └── Chess
        """;

    public static final String CONSTRAINTS_TEXT = """
        Work:       08:30-17:00
        Gym:        4x/week
        Sleep:      23:30-07:00
        Learning:   5h/week
        Side project: 4h/week
        Social:     2 evenings/week
        """;

    /** A fixed Monday so week-based expectations are stable. */
    public static final LocalDate MONDAY =
        LocalDate.of(2026, 9, 14).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

    private ExampleSetup() {
    }

    public static GoalTree tree() {
        return new GoalTreeParser().parse(GOALS_TEXT);
    }

    public static List<Constraint> constraints() {
        return new ConstraintParser().parse(CONSTRAINTS_TEXT, tree()).constraints();
    }
}
```

- [ ] **Step 2: Write the failing store test**

`backend/src/test/java/com/lifeorganizer/persistence/SetupStoreTest.java`:

```java
package com.lifeorganizer.persistence;

import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.Constraint;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.GoalTree;
import com.lifeorganizer.setup.ConstraintParser;
import com.lifeorganizer.setup.GoalTreeParser;
import com.lifeorganizer.support.ExampleSetup;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(SetupStore.class)
class SetupStoreTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @Autowired
    SetupStore store;

    @PersistenceContext
    EntityManager entityManager;

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void anEmptyDatabaseHasNoSetup() {
        assertThat(store.isEmpty()).isTrue();
        assertThat(store.loadTree()).isEqualTo(new GoalTree(List.of(), List.of()));
        assertThat(store.loadConstraints()).isEmpty();
    }

    @Test
    void theExampleSetupRoundTripsInDeclaredOrder() {
        GoalTree tree = ExampleSetup.tree();
        List<Constraint> constraints = ExampleSetup.constraints();

        store.replace(tree, constraints);
        flushAndClear();

        assertThat(store.isEmpty()).isFalse();
        // Declared order (Career, Projects, Health, ...) is deliberately not alphabetical.
        assertThat(store.loadTree()).isEqualTo(tree);
        assertThat(store.loadConstraints()).isEqualTo(constraints);
    }

    @Test
    void replacingOverwritesThePreviousSetupCompletely() {
        store.replace(ExampleSetup.tree(), ExampleSetup.constraints());

        GoalTree smaller = new GoalTreeParser().parse("Goals\n├── Health\n│   └── Gym\n");
        List<Constraint> oneConstraint = new ConstraintParser().parse("Gym: 3x/week\n", smaller).constraints();
        store.replace(smaller, oneConstraint);
        flushAndClear();

        assertThat(store.loadTree()).isEqualTo(smaller);
        assertThat(store.loadConstraints()).isEqualTo(oneConstraint);
    }

    @Test
    void aGoalWhoseCategoryIsNotInTheTreeIsRejected() {
        Category known = Category.of("Health");
        Category other = Category.of("Other");
        GoalTree bad = new GoalTree(List.of(known), List.of(Goal.withDefaults("other-x", other, "X")));

        assertThatThrownBy(() -> store.replace(bad, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown category");
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=SetupStoreTest`
Expected: FAIL — compilation error, `SetupStore` does not exist.

- [ ] **Step 4: Implement `SetupStore`**

`backend/src/main/java/com/lifeorganizer/persistence/SetupStore.java`:

```java
package com.lifeorganizer.persistence;

import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.Constraint;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.GoalTree;
import com.lifeorganizer.persistence.mapper.ConstraintMapper;
import com.lifeorganizer.persistence.mapper.GoalMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Persists and loads the user's goals and constraints, speaking only Phase 1a domain types. */
@Component
public class SetupStore {

    private final CategoryRepository categories;
    private final GoalRepository goals;
    private final ConstraintRepository constraints;

    @PersistenceContext
    private EntityManager entityManager;

    public SetupStore(CategoryRepository categories, GoalRepository goals, ConstraintRepository constraints) {
        this.categories = categories;
        this.goals = goals;
        this.constraints = constraints;
    }

    @Transactional(readOnly = true)
    public boolean isEmpty() {
        return categories.count() == 0 && goals.count() == 0 && constraints.count() == 0;
    }

    @Transactional(readOnly = true)
    public GoalTree loadTree() {
        List<Category> loadedCategories = categories.findAllByOrderByPositionAsc().stream()
            .map(GoalMapper::toDomain)
            .toList();
        List<Goal> loadedGoals = goals.findAllByOrderByPositionAsc().stream()
            .map(GoalMapper::toDomain)
            .toList();
        return new GoalTree(loadedCategories, loadedGoals);
    }

    @Transactional(readOnly = true)
    public List<Constraint> loadConstraints() {
        return constraints.findAllByOrderByPositionAsc().stream()
            .map(ConstraintMapper::toDomain)
            .toList();
    }

    /**
     * Replaces the whole setup. Child rows are deleted first (constraints reference goals and
     * categories, goals reference categories). Never touches block or day_warning rows.
     */
    @Transactional
    public void replace(GoalTree tree, List<Constraint> newConstraints) {
        constraints.deleteAllInBatch();
        goals.deleteAllInBatch();
        categories.deleteAllInBatch();
        // Bulk deletes bypass the persistence context; clear it so no stale instance is merged over the fresh rows.
        entityManager.flush();
        entityManager.clear();

        Map<String, CategoryEntity> savedCategories = new HashMap<>();
        int position = 0;
        for (Category category : tree.categories()) {
            savedCategories.put(category.id(), categories.save(GoalMapper.toEntity(category, position++)));
        }
        categories.flush();

        position = 0;
        for (Goal goal : tree.goals()) {
            CategoryEntity category = savedCategories.get(goal.category().id());
            if (category == null) {
                throw new IllegalArgumentException(
                    "Goal '" + goal.id() + "' refers to unknown category '" + goal.category().id() + "'");
            }
            goals.save(GoalMapper.toEntity(goal, category, position++));
        }
        goals.flush();

        position = 0;
        for (Constraint constraint : newConstraints) {
            constraints.save(ConstraintMapper.toEntity(constraint, position++));
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && ./mvnw -q test -Dtest=SetupStoreTest`
Expected: PASS — 4 tests green. (`replacingOverwritesThePreviousSetupCompletely` reuses the ids `health` and `health-gym` from the first setup, which proves the delete-then-insert works inside a single transaction.)

- [ ] **Step 6: Run the full suite**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS, 103 tests.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat: add SetupStore and the shared example-setup test fixture" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 7: `PlanStore` (persist and load day plans, apply replan diffs)

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/persistence/PlanStore.java`
- Test: `backend/src/test/java/com/lifeorganizer/persistence/PlanStoreTest.java`

**Interfaces:**
- Consumes: Task 4 repositories, Task 5 `BlockMapper`, Phase 1a `Block`, `DayPlan`.
- Produces: `@Component PlanStore(BlockRepository, DayWarningRepository, JdbcTemplate)` with:
  - `void lockGeneration()` — `@Transactional`; takes a Postgres transaction-scoped advisory lock (`pg_advisory_xact_lock`) that is released automatically at commit/rollback. `PlanService` calls it before generating a missing day so two concurrent first requests (React `<StrictMode>` fires the very first load twice) cannot both insert the same block ids. Re-entrant within one transaction. (Verified in a scratch project: a second transaction blocks until the first commits.)
  - `Optional<DayPlan> findDay(LocalDate)` — empty when no block row exists for that date ("this day has not been generated"); otherwise a `DayPlan` whose blocks are ordered by start time then id and whose warnings are ordered by position.
  - `void saveNewDay(DayPlan)` — `@Transactional`; saves the blocks and the warnings. If two blocks share an id, later ones get the suffix `~2`, `~3`, … so no block is silently lost. Warnings longer than 500 characters are truncated.
  - `void applyChanges(Map<LocalDate, DayPlan> before, Map<LocalDate, DayPlan> after)` — `@Transactional`; for every date present in **both** maps, deletes blocks whose id disappeared and saves (upserts) blocks that are new or changed (record equality). Dates that appear only in `after` are ignored.
  - `Optional<LocalDate> findBlockDate(String blockId)`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/lifeorganizer/persistence/PlanStoreTest.java`:

```java
package com.lifeorganizer.persistence;

import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.DayPlan;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(PlanStore.class)
class PlanStoreTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private static final LocalDate MON = LocalDate.of(2026, 9, 14);
    private static final LocalDate TUE = MON.plusDays(1);

    @Autowired
    PlanStore store;

    @PersistenceContext
    EntityManager entityManager;

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static Block block(String id, LocalDate date, int startHour, BlockStatus status) {
        return new Block(id, date, LocalTime.of(startHour, 0), LocalTime.of(startHour + 1, 0), "Title " + id,
            BlockKind.GOAL, "goal-1", status, 0, false, null);
    }

    @Test
    void findDayIsEmptyWhenNothingWasSaved() {
        assertThat(store.findDay(MON)).isEmpty();
    }

    @Test
    void savedBlocksAndWarningsComeBackInOrder() {
        Block late = block("late", MON, 18, BlockStatus.PLANNED);
        Block early = block("early", MON, 7, BlockStatus.PLANNED);

        store.saveNewDay(new DayPlan(MON, List.of(late, early), List.of("first warning", "second warning")));
        flushAndClear();

        DayPlan loaded = store.findDay(MON).orElseThrow();
        assertThat(loaded.date()).isEqualTo(MON);
        assertThat(loaded.blocks()).containsExactly(early, late);
        assertThat(loaded.warnings()).containsExactly("first warning", "second warning");
    }

    @Test
    void blocksWithTheSameIdAreMadeUniqueInsteadOfLosingOne() {
        Block first = block("dup", MON, 7, BlockStatus.PLANNED);
        Block second = block("dup", MON, 9, BlockStatus.PLANNED);
        Block third = block("dup", MON, 11, BlockStatus.PLANNED);

        store.saveNewDay(new DayPlan(MON, List.of(first, second, third), List.of()));
        flushAndClear();

        assertThat(store.findDay(MON).orElseThrow().blocks())
            .extracting(Block::id)
            .containsExactly("dup", "dup~2", "dup~3");
    }

    @Test
    void overlongWarningsAreTruncatedTo500Characters() {
        String huge = "x".repeat(700);

        store.saveNewDay(new DayPlan(MON, List.of(block("a", MON, 7, BlockStatus.PLANNED)), List.of(huge)));
        flushAndClear();

        assertThat(store.findDay(MON).orElseThrow().warnings().get(0)).hasSize(500);
    }

    @Test
    void applyChangesUpdatesAddsAndRemovesBlocks() {
        Block a = block("a", MON, 7, BlockStatus.PLANNED);
        Block b = block("b", MON, 9, BlockStatus.PLANNED);
        Block c = block("c", MON, 11, BlockStatus.PLANNED);
        DayPlan before = new DayPlan(MON, List.of(a, b, c), List.of());
        store.saveNewDay(before);
        flushAndClear();

        Block aDone = block("a", MON, 7, BlockStatus.DONE);
        Block d = block("d", MON, 13, BlockStatus.PLANNED);
        DayPlan after = new DayPlan(MON, List.of(aDone, c, d), List.of());
        store.applyChanges(Map.of(MON, before), Map.of(MON, after));
        flushAndClear();

        assertThat(store.findDay(MON).orElseThrow().blocks()).containsExactly(aDone, c, d);
    }

    @Test
    void applyChangesIgnoresDatesThatWereNotLoaded() {
        Block a = block("a", MON, 7, BlockStatus.PLANNED);
        DayPlan monday = new DayPlan(MON, List.of(a), List.of());
        store.saveNewDay(monday);
        flushAndClear();

        DayPlan stray = new DayPlan(TUE, List.of(block("t", TUE, 7, BlockStatus.PLANNED)), List.of());
        store.applyChanges(Map.of(MON, monday), Map.of(MON, monday, TUE, stray));
        flushAndClear();

        assertThat(store.findDay(TUE)).isEmpty();
    }

    @Test
    void findBlockDateReturnsTheDateOfAKnownBlockOnly() {
        store.saveNewDay(new DayPlan(TUE, List.of(block("t", TUE, 7, BlockStatus.PLANNED)), List.of()));
        flushAndClear();

        assertThat(store.findBlockDate("t")).contains(TUE);
        assertThat(store.findBlockDate("missing")).isEmpty();
    }

    @Test
    void theGenerationLockCanBeTakenRepeatedlyInsideOneTransaction() {
        // Advisory locks are re-entrant within a session; this proves the SQL runs on a real Postgres.
        assertThatCode(() -> {
            store.lockGeneration();
            store.lockGeneration();
        }).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=PlanStoreTest`
Expected: FAIL — compilation error, `PlanStore` does not exist.

- [ ] **Step 3: Implement `PlanStore`**

`backend/src/main/java/com/lifeorganizer/persistence/PlanStore.java`:

```java
package com.lifeorganizer.persistence;

import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.persistence.mapper.BlockMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Persists generated day plans and applies replan results by diffing. A date with no block rows
 * counts as "not generated yet" (every generated day contains at least its Wind down block).
 */
@Component
public class PlanStore {

    private static final int MAX_WARNING_LENGTH = 500;
    private static final long GENERATION_LOCK_KEY = 42_001L;

    private final BlockRepository blocks;
    private final DayWarningRepository warnings;
    private final JdbcTemplate jdbc;

    public PlanStore(BlockRepository blocks, DayWarningRepository warnings, JdbcTemplate jdbc) {
        this.blocks = blocks;
        this.warnings = warnings;
        this.jdbc = jdbc;
    }

    /**
     * Serializes first-time day generation across concurrent requests. The lock is transaction-scoped:
     * Postgres releases it at commit or rollback, so a waiting request wakes up to find the day stored.
     */
    @Transactional
    public void lockGeneration() {
        jdbc.execute("select pg_advisory_xact_lock(" + GENERATION_LOCK_KEY + ")");
    }

    @Transactional(readOnly = true)
    public Optional<DayPlan> findDay(LocalDate date) {
        List<BlockEntity> rows = blocks.findByDateOrderByStartTimeAscIdAsc(date);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<Block> dayBlocks = rows.stream().map(BlockMapper::toDomain).toList();
        List<String> dayWarnings = warnings.findByDateOrderByPositionAsc(date).stream()
            .map(DayWarningEntity::getMessage)
            .toList();
        return Optional.of(new DayPlan(date, dayBlocks, dayWarnings));
    }

    @Transactional
    public void saveNewDay(DayPlan plan) {
        blocks.saveAll(withUniqueIds(plan.blocks()).stream().map(BlockMapper::toEntity).toList());
        warnings.deleteByDate(plan.date());
        int position = 0;
        for (String warning : plan.warnings()) {
            warnings.save(new DayWarningEntity(plan.date(), position++, truncate(warning)));
        }
    }

    @Transactional
    public void applyChanges(Map<LocalDate, DayPlan> before, Map<LocalDate, DayPlan> after) {
        for (Map.Entry<LocalDate, DayPlan> entry : after.entrySet()) {
            DayPlan old = before.get(entry.getKey());
            if (old != null) {
                saveDifferences(old, entry.getValue());
            }
        }
    }

    @Transactional(readOnly = true)
    public Optional<LocalDate> findBlockDate(String blockId) {
        return blocks.findById(blockId).map(BlockEntity::getDate);
    }

    private void saveDifferences(DayPlan old, DayPlan updated) {
        Map<String, Block> oldById = byId(old.blocks());
        Map<String, Block> newById = byId(updated.blocks());

        List<String> removed = oldById.keySet().stream()
            .filter(id -> !newById.containsKey(id))
            .toList();
        List<BlockEntity> changed = newById.values().stream()
            .filter(block -> !block.equals(oldById.get(block.id())))
            .map(BlockMapper::toEntity)
            .toList();

        if (!removed.isEmpty()) {
            blocks.deleteAllById(removed);
        }
        if (!changed.isEmpty()) {
            blocks.saveAll(changed);
        }
    }

    /** Last one wins if a replan ever produced two blocks with one id. */
    private static Map<String, Block> byId(List<Block> list) {
        Map<String, Block> map = new LinkedHashMap<>();
        for (Block block : list) {
            map.put(block.id(), block);
        }
        return map;
    }

    /**
     * Two units can legitimately produce the same block id (for example two duration quotas that
     * land one goal on the same day). Keep every block by suffixing later duplicates with ~2, ~3, ...
     */
    private static List<Block> withUniqueIds(List<Block> planned) {
        Set<String> seen = new HashSet<>();
        List<Block> result = new ArrayList<>();
        for (Block block : planned) {
            String id = block.id();
            int suffix = 2;
            while (!seen.add(id)) {
                id = block.id() + "~" + suffix++;
            }
            result.add(id.equals(block.id()) ? block : withId(block, id));
        }
        return result;
    }

    private static Block withId(Block block, String id) {
        return new Block(id, block.date(), block.start(), block.end(), block.title(), block.kind(), block.goalId(),
            block.status(), block.postponeCount(), block.flagged(), block.reason());
    }

    private static String truncate(String warning) {
        return warning.length() <= MAX_WARNING_LENGTH ? warning : warning.substring(0, MAX_WARNING_LENGTH);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw -q test -Dtest=PlanStoreTest`
Expected: PASS — 8 tests green.

- [ ] **Step 5: Run the full suite**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS, 111 tests.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat: add PlanStore for persisting day plans and replan diffs" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 8: Engine wiring, domain errors, and `PlanService.getOrGenerate` (lazy per-day generation)

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/error/SetupRequiredException.java`
- Create: `backend/src/main/java/com/lifeorganizer/error/UnknownBlockException.java`
- Create: `backend/src/main/java/com/lifeorganizer/config/EngineConfig.java`
- Create: `backend/src/main/java/com/lifeorganizer/plan/PlanService.java`
- Test: `backend/src/test/java/com/lifeorganizer/plan/PlanServiceGenerationTest.java`

**Interfaces:**
- Consumes: `SetupStore` (Task 6), `PlanStore` (Task 7), Phase 1a `WeekAllocator`, `DayScheduler`, `Replanner`, `PostponePolicy`, `GoalWeighting`, `EngineSettings`.
- Produces:
  - `SetupRequiredException(String message)` and `UnknownBlockException(String message)` — plain `RuntimeException`s (mapped to HTTP 404 / 422 in Task 11).
  - `@Configuration EngineConfig` exposing beans: `Clock clock()` (`Clock.systemDefaultZone()`), `EngineSettings`, `GoalWeighting`, `WeekAllocator`, `DayScheduler`, `PostponePolicy`, `Replanner` (the last two are consumed from Task 9).
  - `@Service PlanService(SetupStore, PlanStore, WeekAllocator, DayScheduler)` with `@Transactional DayPlan getOrGenerate(LocalDate date)`: returns the persisted day if it exists; otherwise takes `PlanStore.lockGeneration()`, looks again (a concurrent request may have just stored it), and only then — throwing `SetupRequiredException` when no setup was ever saved — runs the Phase 1a engine for that single day (`WeekAllocator.allocate` for the Monday-based week containing `date`, then `DayScheduler.schedule` for `date`), persists it via `PlanStore.saveNewDay`, and returns the persisted result. Generation warnings = the week allocator's warnings followed by the scheduler's, de-duplicated, order preserved. Package-private static `mondayOf(LocalDate)`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/lifeorganizer/plan/PlanServiceGenerationTest.java`:

```java
package com.lifeorganizer.plan;

import com.lifeorganizer.config.EngineConfig;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.Constraint;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.domain.GoalTree;
import com.lifeorganizer.error.SetupRequiredException;
import com.lifeorganizer.persistence.BlockEntity;
import com.lifeorganizer.persistence.BlockRepository;
import com.lifeorganizer.persistence.PlanStore;
import com.lifeorganizer.persistence.SetupStore;
import com.lifeorganizer.setup.ConstraintParser;
import com.lifeorganizer.setup.GoalTreeParser;
import com.lifeorganizer.support.ExampleSetup;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({PlanService.class, SetupStore.class, PlanStore.class, EngineConfig.class})
class PlanServiceGenerationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @Autowired
    PlanService planService;

    @Autowired
    SetupStore setupStore;

    @Autowired
    PlanStore planStore;

    @Autowired
    BlockRepository blockRepository;

    @PersistenceContext
    EntityManager entityManager;

    private void saveExampleSetup() {
        setupStore.replace(ExampleSetup.tree(), ExampleSetup.constraints());
    }

    private static List<String> summary(DayPlan plan) {
        return plan.blocksInOrder().stream().map(b -> b.start() + " " + b.title()).toList();
    }

    @Test
    void firstRequestForADayGeneratesTheExampleMonday() {
        saveExampleSetup();

        DayPlan plan = planService.getOrGenerate(ExampleSetup.MONDAY);

        assertThat(summary(plan)).containsExactly(
            "07:00 Gym",
            "08:00 Free",
            "08:30 Work",
            "17:00 Dinner / break",
            "18:00 Claude certification",
            "19:00 RAG showcase",
            "20:00 Dating / events",
            "21:00 Free",
            "22:30 Wind down");
        assertThat(plan.warnings()).isEmpty();
    }

    @Test
    void generatesALaterDayOfTheSameWeek() {
        saveExampleSetup();

        DayPlan tuesday = planService.getOrGenerate(ExampleSetup.MONDAY.plusDays(1));

        assertThat(summary(tuesday)).containsExactly(
            "07:00 Claude certification",
            "08:00 Free",
            "08:30 Work",
            "17:00 Dinner / break",
            "18:00 RAG showcase",
            "19:00 Free",
            "22:30 Wind down");
    }

    @Test
    void theSecondRequestReadsThePersistedDayInsteadOfRegeneratingIt() {
        saveExampleSetup();
        DayPlan first = planService.getOrGenerate(ExampleSetup.MONDAY);
        Block gym = first.blocks().stream().filter(b -> b.title().equals("Gym")).findFirst().orElseThrow();

        // simulate a change made later by a replan event
        blockRepository.save(new BlockEntity(gym.id(), gym.date(), gym.start(), gym.end(), gym.title(),
            gym.kind().name(), gym.goalId(), "DONE", 0, false, null));
        entityManager.flush();
        entityManager.clear();

        DayPlan second = planService.getOrGenerate(ExampleSetup.MONDAY);

        Block reloadedGym = second.blocks().stream().filter(b -> b.id().equals(gym.id())).findFirst().orElseThrow();
        assertThat(reloadedGym.status()).isEqualTo(BlockStatus.DONE);
    }

    @Test
    void onlyTheRequestedDayIsGenerated() {
        saveExampleSetup();

        planService.getOrGenerate(ExampleSetup.MONDAY);

        assertThat(planStore.findDay(ExampleSetup.MONDAY.plusDays(1))).isEmpty();
    }

    @Test
    void throwsWhenNoSetupWasEverSaved() {
        assertThatThrownBy(() -> planService.getOrGenerate(ExampleSetup.MONDAY))
            .isInstanceOf(SetupRequiredException.class);
    }

    @Test
    void generationWarningsArePersistedAndReturnedOnTheNextLoad() {
        // 8h of learning for a single goal cannot fit into 7 daily 60-minute chunks.
        GoalTree tree = new GoalTreeParser().parse("Goals\n├── Career\n│   └── Claude certification\n");
        List<Constraint> constraints = new ConstraintParser()
            .parse("Sleep: 23:30-07:00\nLearning: 8h/week\n", tree).constraints();
        setupStore.replace(tree, constraints);

        DayPlan first = planService.getOrGenerate(ExampleSetup.MONDAY);

        assertThat(first.warnings())
            .containsExactly("Claude certification could not fit 60 of its 480 weekly minutes (ran out of days)");
        assertThat(summary(first)).containsExactly("07:00 Claude certification", "08:00 Free", "22:30 Wind down");

        entityManager.flush();
        entityManager.clear();
        DayPlan second = planService.getOrGenerate(ExampleSetup.MONDAY);

        assertThat(second.warnings()).isEqualTo(first.warnings());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=PlanServiceGenerationTest`
Expected: FAIL — compilation error (`PlanService`, `EngineConfig`, `SetupRequiredException` do not exist).

- [ ] **Step 3: Create the two exceptions**

`backend/src/main/java/com/lifeorganizer/error/SetupRequiredException.java`:

```java
package com.lifeorganizer.error;

/** No goals/constraints have been saved yet, so no plan can be generated. */
public class SetupRequiredException extends RuntimeException {

    public SetupRequiredException(String message) {
        super(message);
    }
}
```

`backend/src/main/java/com/lifeorganizer/error/UnknownBlockException.java`:

```java
package com.lifeorganizer.error;

/** A plan event referred to a block id that does not exist. */
public class UnknownBlockException extends RuntimeException {

    public UnknownBlockException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Create `EngineConfig`**

The Phase 1a engine classes are framework-free, so they are wired here rather than annotated.

`backend/src/main/java/com/lifeorganizer/config/EngineConfig.java`:

```java
package com.lifeorganizer.config;

import com.lifeorganizer.domain.EngineSettings;
import com.lifeorganizer.engine.DayScheduler;
import com.lifeorganizer.engine.GoalWeighting;
import com.lifeorganizer.engine.PostponePolicy;
import com.lifeorganizer.engine.Replanner;
import com.lifeorganizer.engine.WeekAllocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Wires the framework-free Phase 1a engine into the Spring context. */
@Configuration
public class EngineConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public EngineSettings engineSettings() {
        return EngineSettings.defaults();
    }

    @Bean
    public GoalWeighting goalWeighting() {
        return new GoalWeighting();
    }

    @Bean
    public WeekAllocator weekAllocator(GoalWeighting goalWeighting, EngineSettings settings) {
        return new WeekAllocator(goalWeighting, settings);
    }

    @Bean
    public DayScheduler dayScheduler(EngineSettings settings) {
        return new DayScheduler(settings);
    }

    @Bean
    public PostponePolicy postponePolicy() {
        return new PostponePolicy();
    }

    @Bean
    public Replanner replanner(PostponePolicy postponePolicy) {
        return new Replanner(postponePolicy);
    }
}
```

- [ ] **Step 5: Create `PlanService` (generation only for now)**

`backend/src/main/java/com/lifeorganizer/plan/PlanService.java`:

```java
package com.lifeorganizer.plan;

import com.lifeorganizer.domain.Constraint;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.domain.GoalTree;
import com.lifeorganizer.engine.DayScheduler;
import com.lifeorganizer.engine.WeekAllocator;
import com.lifeorganizer.engine.WeekPlan;
import com.lifeorganizer.error.SetupRequiredException;
import com.lifeorganizer.persistence.PlanStore;
import com.lifeorganizer.persistence.SetupStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates the Phase 1a engine on top of the stores. A day is generated lazily the first time it
 * is requested and is never regenerated afterwards.
 */
@Service
public class PlanService {

    private final SetupStore setupStore;
    private final PlanStore planStore;
    private final WeekAllocator weekAllocator;
    private final DayScheduler dayScheduler;

    public PlanService(SetupStore setupStore, PlanStore planStore, WeekAllocator weekAllocator,
                       DayScheduler dayScheduler) {
        this.setupStore = setupStore;
        this.planStore = planStore;
        this.weekAllocator = weekAllocator;
        this.dayScheduler = dayScheduler;
    }

    @Transactional
    public DayPlan getOrGenerate(LocalDate date) {
        Optional<DayPlan> existing = planStore.findDay(date);
        if (existing.isPresent()) {
            return existing.get();
        }
        // Two requests can find the day missing at the same moment (React <StrictMode> fires the very
        // first load twice). Take the generation lock, then look again: the winner has stored the day.
        planStore.lockGeneration();
        return planStore.findDay(date).orElseGet(() -> {
            planStore.saveNewDay(generate(date));
            return planStore.findDay(date)
                .orElseThrow(() -> new IllegalStateException("Day " + date + " was not persisted"));
        });
    }

    static LocalDate mondayOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private DayPlan generate(LocalDate date) {
        if (setupStore.isEmpty()) {
            throw new SetupRequiredException("No setup saved yet - paste your goals and constraints first");
        }
        GoalTree tree = setupStore.loadTree();
        List<Constraint> constraints = setupStore.loadConstraints();

        WeekPlan week = weekAllocator.allocate(tree.goals(), constraints, mondayOf(date));
        DayPlan day = dayScheduler.schedule(date, week.unitsByDate().get(date), constraints, List.of());

        Set<String> warnings = new LinkedHashSet<>(week.warnings());
        warnings.addAll(day.warnings());
        return new DayPlan(date, day.blocks(), List.copyOf(warnings));
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd backend && ./mvnw -q test -Dtest=PlanServiceGenerationTest`
Expected: PASS — 6 tests green.

- [ ] **Step 7: Run the full suite**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS, 117 tests.

- [ ] **Step 8: Commit**

```bash
git add backend
git commit -m "feat: add PlanService with lazy per-day plan generation and engine wiring" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 9: `PlanService.applyEvent` (done / postpone / cancel / add-ad-hoc, persisted by diff)

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/plan/AppliedEvent.java`
- Modify: `backend/src/main/java/com/lifeorganizer/plan/PlanService.java`
- Test: `backend/src/test/java/com/lifeorganizer/plan/PlanServiceEventsTest.java`

**Interfaces:**
- Consumes: Task 8 `PlanService`, `Replanner` bean, `PlanStore.findBlockDate/applyChanges`, Phase 1a `PlanEvent` variants (`CancelBlock(String blockId)`, `PostponeBlock(String blockId, String reason)`, `MarkDone(String blockId)`, `SetEnergy(String blockId, Energy level)`, `AddAdhoc(LocalDate date, String title, LocalTime start, LocalTime end)`), `ReplanResult(Map<LocalDate, DayPlan> updatedPlans, List<String> changes)`, `UnknownBlockException`.
- Produces:
  - `record AppliedEvent(DayPlan today, List<String> changes)`.
  - `PlanService.applyEvent(PlanEvent event, LocalDate today): AppliedEvent` — `@Transactional`. Resolves the date the event refers to (`AddAdhoc` → its own date; the other four → the date of the block with that id, else `UnknownBlockException("No block with id '<id>'")`). Loads that day via `getOrGenerate`; for `PostponeBlock` also loads (generating if needed) every later day of the same Monday-based week so a postpone can find room tomorrow. Runs `Replanner.apply`, persists only the differences via `PlanStore.applyChanges`, and returns today's plan (`getOrGenerate(today)`) plus the replanner's change messages. Refused events (e.g. postponing a fixed or completed block) change nothing and return the refusal message in `changes`.
  - The constructor gains a fifth parameter `Replanner replanner` (Spring injects it; no other code constructs `PlanService`).

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/lifeorganizer/plan/PlanServiceEventsTest.java`:

```java
package com.lifeorganizer.plan;

import com.lifeorganizer.config.EngineConfig;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.engine.AddAdhoc;
import com.lifeorganizer.engine.CancelBlock;
import com.lifeorganizer.engine.MarkDone;
import com.lifeorganizer.engine.PostponeBlock;
import com.lifeorganizer.error.SetupRequiredException;
import com.lifeorganizer.error.UnknownBlockException;
import com.lifeorganizer.persistence.PlanStore;
import com.lifeorganizer.persistence.SetupStore;
import com.lifeorganizer.support.ExampleSetup;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({PlanService.class, SetupStore.class, PlanStore.class, EngineConfig.class})
class PlanServiceEventsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private static final LocalDate MON = ExampleSetup.MONDAY;
    private static final LocalDate TUE = MON.plusDays(1);
    private static final LocalDate SUN = MON.plusDays(6);

    @Autowired
    PlanService planService;

    @Autowired
    SetupStore setupStore;

    @Autowired
    PlanStore planStore;

    @PersistenceContext
    EntityManager entityManager;

    private void saveExampleSetup() {
        setupStore.replace(ExampleSetup.tree(), ExampleSetup.constraints());
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private String idOf(DayPlan plan, String title) {
        return plan.blocks().stream()
            .filter(b -> b.title().equals(title) && b.status() == BlockStatus.PLANNED)
            .findFirst().orElseThrow().id();
    }

    private Block block(DayPlan plan, String id) {
        return plan.blocks().stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void markDoneIsPersisted() {
        saveExampleSetup();
        String gymId = idOf(planService.getOrGenerate(MON), "Gym");

        AppliedEvent applied = planService.applyEvent(new MarkDone(gymId), MON);
        flushAndClear();

        assertThat(applied.changes()).containsExactly("Marked done Gym");
        assertThat(block(applied.today(), gymId).status()).isEqualTo(BlockStatus.DONE);
        assertThat(block(planStore.findDay(MON).orElseThrow(), gymId).status()).isEqualTo(BlockStatus.DONE);
    }

    @Test
    void cancelPersistsTheFreedSlot() {
        saveExampleSetup();
        String gymId = idOf(planService.getOrGenerate(MON), "Gym");

        AppliedEvent applied = planService.applyEvent(new CancelBlock(gymId), MON);
        flushAndClear();

        assertThat(applied.changes()).containsExactly("Cancelled Gym - slot is now free");
        DayPlan reloaded = planStore.findDay(MON).orElseThrow();
        assertThat(block(reloaded, gymId).status()).isEqualTo(BlockStatus.CANCELLED);
        Block freed = block(reloaded, gymId + "-freed");
        assertThat(freed.kind()).isEqualTo(BlockKind.FREE);
        assertThat(freed.start()).isEqualTo(LocalTime.of(7, 0));
        assertThat(freed.end()).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    void postponeMovesTheBlockToALaterFreeSlotOnTheSameDay() {
        saveExampleSetup();
        String certId = idOf(planService.getOrGenerate(MON), "Claude certification");

        AppliedEvent applied = planService.applyEvent(new PostponeBlock(certId, "tired"), MON);
        flushAndClear();

        assertThat(applied.changes()).containsExactly("Moved Claude certification 18:00 -> 2026-09-14 21:00");
        DayPlan reloaded = planStore.findDay(MON).orElseThrow();

        Block original = block(reloaded, certId);
        assertThat(original.status()).isEqualTo(BlockStatus.POSTPONED);
        assertThat(original.reason()).isEqualTo("tired");

        Block moved = block(reloaded, certId + "-p1");
        assertThat(moved.status()).isEqualTo(BlockStatus.PLANNED);
        assertThat(moved.start()).isEqualTo(LocalTime.of(21, 0));
        assertThat(moved.end()).isEqualTo(LocalTime.of(22, 0));
        assertThat(moved.postponeCount()).isEqualTo(1);

        // the free slot it used was split: the consumed block is gone, the remainder is kept
        assertThat(reloaded.blocks()).noneMatch(b -> b.id().equals("free-2026-09-14-21:00"));
        Block rest = block(reloaded, "free-2026-09-14-21:00-rest");
        assertThat(rest.start()).isEqualTo(LocalTime.of(22, 0));
        assertThat(rest.end()).isEqualTo(LocalTime.of(22, 30));
    }

    @Test
    void postponingTwiceMovesTheBlockToTheNextDayAndPersistsBothDays() {
        saveExampleSetup();
        String certId = idOf(planService.getOrGenerate(MON), "Claude certification");
        planService.applyEvent(new PostponeBlock(certId, "tired"), MON);
        String firstMoveId = certId + "-p1";

        // A postpone looks at the rest of the week for room, so it generates the later days.
        assertThat(planStore.findDay(SUN)).isPresent();

        AppliedEvent applied = planService.applyEvent(new PostponeBlock(firstMoveId, "still tired"), MON);
        flushAndClear();

        assertThat(applied.changes()).containsExactly("Moved Claude certification 21:00 -> 2026-09-15 19:00");

        DayPlan monday = planStore.findDay(MON).orElseThrow();
        assertThat(block(monday, firstMoveId).status()).isEqualTo(BlockStatus.POSTPONED);

        DayPlan tuesday = planStore.findDay(TUE).orElseThrow();
        Block secondMove = block(tuesday, firstMoveId + "-p2");
        assertThat(secondMove.start()).isEqualTo(LocalTime.of(19, 0));
        assertThat(secondMove.end()).isEqualTo(LocalTime.of(20, 0));
        assertThat(secondMove.postponeCount()).isEqualTo(2);
        assertThat(secondMove.flagged()).isFalse();
        assertThat(tuesday.blocks()).noneMatch(b -> b.id().equals("free-2026-09-15-19:00"));
        assertThat(block(tuesday, "free-2026-09-15-19:00-rest").start()).isEqualTo(LocalTime.of(20, 0));
    }

    @Test
    void theThirdPostponeFlagsTheBlock() {
        saveExampleSetup();
        String certId = idOf(planService.getOrGenerate(MON), "Claude certification");
        planService.applyEvent(new PostponeBlock(certId, "1"), MON);
        planService.applyEvent(new PostponeBlock(certId + "-p1", "2"), MON);

        planService.applyEvent(new PostponeBlock(certId + "-p1-p2", "3"), MON);
        flushAndClear();

        Block third = block(planStore.findDay(TUE).orElseThrow(), certId + "-p1-p2-p3");
        assertThat(third.postponeCount()).isEqualTo(3);
        assertThat(third.flagged()).isTrue();
        assertThat(third.start()).isEqualTo(LocalTime.of(20, 0));
    }

    @Test
    void postponeRefusesAFixedBlockAndChangesNothing() {
        saveExampleSetup();
        String workId = idOf(planService.getOrGenerate(MON), "Work");

        AppliedEvent applied = planService.applyEvent(new PostponeBlock(workId, "nope"), MON);
        flushAndClear();

        assertThat(applied.changes()).containsExactly("Cannot postpone a FIXED block (Work)");
        Block work = block(planStore.findDay(MON).orElseThrow(), workId);
        assertThat(work.status()).isEqualTo(BlockStatus.PLANNED);
        assertThat(work.start()).isEqualTo(LocalTime.of(8, 30));
    }

    @Test
    void postponeRefusesACompletedBlock() {
        saveExampleSetup();
        String certId = idOf(planService.getOrGenerate(MON), "Claude certification");
        planService.applyEvent(new MarkDone(certId), MON);

        AppliedEvent applied = planService.applyEvent(new PostponeBlock(certId, "x"), MON);

        assertThat(applied.changes()).containsExactly("Cannot postpone a completed block (Claude certification)");
    }

    @Test
    void anUnknownBlockIdIsRejected() {
        saveExampleSetup();
        planService.getOrGenerate(MON);

        assertThatThrownBy(() -> planService.applyEvent(new CancelBlock("nope"), MON))
            .isInstanceOf(UnknownBlockException.class)
            .hasMessageContaining("nope");
    }

    @Test
    void eventsThatDoNotNeedRoomDoNotGenerateOtherDays() {
        saveExampleSetup();
        String gymId = idOf(planService.getOrGenerate(MON), "Gym");

        planService.applyEvent(new MarkDone(gymId), MON);

        assertThat(planStore.findDay(TUE)).isEmpty();
    }

    @Test
    void addAdhocOnADayThatIsNotGeneratedYetGeneratesItAndTakesOverItsFreeTime() {
        saveExampleSetup();

        AppliedEvent applied = planService.applyEvent(
            new AddAdhoc(TUE, "Padel", LocalTime.of(21, 0), LocalTime.of(22, 30)), MON);
        flushAndClear();

        assertThat(applied.changes()).containsExactly("Added Padel at 21:00");
        assertThat(applied.today().date()).isEqualTo(MON);

        DayPlan tuesday = planStore.findDay(TUE).orElseThrow();
        Block padel = block(tuesday, "adhoc-2026-09-15-21:00");
        assertThat(padel.kind()).isEqualTo(BlockKind.ADHOC);
        // Tuesday's free block 19:00-22:30 lost the 21:00-22:30 it overlaps; 19:00-21:00 is left.
        assertThat(tuesday.blocks()).noneMatch(b -> b.id().equals("free-2026-09-15-19:00"));
        Block before = block(tuesday, "free-2026-09-15-19:00-before");
        assertThat(before.start()).isEqualTo(LocalTime.of(19, 0));
        assertThat(before.end()).isEqualTo(LocalTime.of(21, 0));
    }

    @Test
    void eventsBeforeAnySetupAreRejected() {
        assertThatThrownBy(() -> planService.applyEvent(
            new AddAdhoc(MON, "Padel", LocalTime.of(21, 0), LocalTime.of(22, 0)), MON))
            .isInstanceOf(SetupRequiredException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=PlanServiceEventsTest`
Expected: FAIL — compilation error (`AppliedEvent` and `PlanService.applyEvent` do not exist).

- [ ] **Step 3: Create `AppliedEvent`**

`backend/src/main/java/com/lifeorganizer/plan/AppliedEvent.java`:

```java
package com.lifeorganizer.plan;

import com.lifeorganizer.domain.DayPlan;

import java.util.List;

/** The outcome of applying one plan event: today's plan afterwards plus the replanner's change messages. */
public record AppliedEvent(DayPlan today, List<String> changes) {
}
```

- [ ] **Step 4: Extend `PlanService`**

Replace `backend/src/main/java/com/lifeorganizer/plan/PlanService.java` with the following complete file (adds the `Replanner` constructor parameter and `applyEvent`; `getOrGenerate`, `mondayOf` and `generate` are unchanged):

```java
package com.lifeorganizer.plan;

import com.lifeorganizer.domain.Constraint;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.domain.GoalTree;
import com.lifeorganizer.engine.AddAdhoc;
import com.lifeorganizer.engine.CancelBlock;
import com.lifeorganizer.engine.DayScheduler;
import com.lifeorganizer.engine.MarkDone;
import com.lifeorganizer.engine.PlanEvent;
import com.lifeorganizer.engine.PostponeBlock;
import com.lifeorganizer.engine.ReplanResult;
import com.lifeorganizer.engine.Replanner;
import com.lifeorganizer.engine.SetEnergy;
import com.lifeorganizer.engine.WeekAllocator;
import com.lifeorganizer.engine.WeekPlan;
import com.lifeorganizer.error.SetupRequiredException;
import com.lifeorganizer.error.UnknownBlockException;
import com.lifeorganizer.persistence.PlanStore;
import com.lifeorganizer.persistence.SetupStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates the Phase 1a engine on top of the stores. A day is generated lazily the first time it
 * is requested and is never regenerated afterwards; replan events are applied to persisted rows by diffing.
 */
@Service
public class PlanService {

    private final SetupStore setupStore;
    private final PlanStore planStore;
    private final WeekAllocator weekAllocator;
    private final DayScheduler dayScheduler;
    private final Replanner replanner;

    public PlanService(SetupStore setupStore, PlanStore planStore, WeekAllocator weekAllocator,
                       DayScheduler dayScheduler, Replanner replanner) {
        this.setupStore = setupStore;
        this.planStore = planStore;
        this.weekAllocator = weekAllocator;
        this.dayScheduler = dayScheduler;
        this.replanner = replanner;
    }

    @Transactional
    public DayPlan getOrGenerate(LocalDate date) {
        Optional<DayPlan> existing = planStore.findDay(date);
        if (existing.isPresent()) {
            return existing.get();
        }
        // Two requests can find the day missing at the same moment (React <StrictMode> fires the very
        // first load twice). Take the generation lock, then look again: the winner has stored the day.
        planStore.lockGeneration();
        return planStore.findDay(date).orElseGet(() -> {
            planStore.saveNewDay(generate(date));
            return planStore.findDay(date)
                .orElseThrow(() -> new IllegalStateException("Day " + date + " was not persisted"));
        });
    }

    @Transactional
    public AppliedEvent applyEvent(PlanEvent event, LocalDate today) {
        LocalDate anchor = dateOf(event);
        Map<LocalDate, DayPlan> loaded = loadDaysFor(event, anchor);

        ReplanResult result = replanner.apply(loaded, event);
        planStore.applyChanges(loaded, result.updatedPlans());

        return new AppliedEvent(getOrGenerate(today), result.changes());
    }

    static LocalDate mondayOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private LocalDate dateOf(PlanEvent event) {
        return switch (event) {
            case AddAdhoc a -> a.date();
            case CancelBlock c -> dateOfBlock(c.blockId());
            case PostponeBlock p -> dateOfBlock(p.blockId());
            case MarkDone d -> dateOfBlock(d.blockId());
            case SetEnergy e -> dateOfBlock(e.blockId());
        };
    }

    private LocalDate dateOfBlock(String blockId) {
        return planStore.findBlockDate(blockId)
            .orElseThrow(() -> new UnknownBlockException("No block with id '" + blockId + "'"));
    }

    /**
     * The days the replanner needs to see. Always the event's own day; for a postpone also every later
     * day of the same week, generated on demand, because that is where a postponed block may find room.
     */
    private Map<LocalDate, DayPlan> loadDaysFor(PlanEvent event, LocalDate anchor) {
        Map<LocalDate, DayPlan> days = new LinkedHashMap<>();
        days.put(anchor, getOrGenerate(anchor));
        if (event instanceof PostponeBlock) {
            LocalDate sunday = mondayOf(anchor).plusDays(6);
            for (LocalDate date = anchor.plusDays(1); !date.isAfter(sunday); date = date.plusDays(1)) {
                days.put(date, getOrGenerate(date));
            }
        }
        return days;
    }

    private DayPlan generate(LocalDate date) {
        if (setupStore.isEmpty()) {
            throw new SetupRequiredException("No setup saved yet - paste your goals and constraints first");
        }
        GoalTree tree = setupStore.loadTree();
        List<Constraint> constraints = setupStore.loadConstraints();

        WeekPlan week = weekAllocator.allocate(tree.goals(), constraints, mondayOf(date));
        DayPlan day = dayScheduler.schedule(date, week.unitsByDate().get(date), constraints, List.of());

        Set<String> warnings = new LinkedHashSet<>(week.warnings());
        warnings.addAll(day.warnings());
        return new DayPlan(date, day.blocks(), List.copyOf(warnings));
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest='PlanServiceEventsTest,PlanServiceGenerationTest'`
Expected: PASS — 11 + 6 tests green. (`PlanServiceGenerationTest` must still pass: the constructor change is transparent because Spring injects the `Replanner` bean from `EngineConfig`.)

- [ ] **Step 6: Run the full suite**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS, 128 tests.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat: apply done/postpone/cancel/add-adhoc events to persisted plans" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

### Task 10: Setup API layer — request errors, time formats, DTOs, mapper, `SetupService`

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/error/InvalidRequestException.java`
- Create: `backend/src/main/java/com/lifeorganizer/api/TimeFormats.java`
- Create: `backend/src/main/java/com/lifeorganizer/setup/api/dto/CategoryDto.java`
- Create: `backend/src/main/java/com/lifeorganizer/setup/api/dto/GoalDto.java`
- Create: `backend/src/main/java/com/lifeorganizer/setup/api/dto/ConstraintDto.java`
- Create: `backend/src/main/java/com/lifeorganizer/setup/api/dto/SetupPayload.java`
- Create: `backend/src/main/java/com/lifeorganizer/setup/api/dto/SetupParseRequest.java`
- Create: `backend/src/main/java/com/lifeorganizer/setup/api/dto/SetupParseResponse.java`
- Create: `backend/src/main/java/com/lifeorganizer/setup/api/SetupDtoMapper.java`
- Create: `backend/src/main/java/com/lifeorganizer/setup/api/SetupService.java`
- Test: `backend/src/test/java/com/lifeorganizer/api/TimeFormatsTest.java`
- Test: `backend/src/test/java/com/lifeorganizer/setup/api/SetupDtoMapperTest.java`
- Test: `backend/src/test/java/com/lifeorganizer/setup/api/SetupServiceTest.java`

**Interfaces:**
- Consumes: `SetupStore` (Task 6), Phase 1a `GoalTreeParser.parse(String): GoalTree`, `ConstraintParser.parse(String, GoalTree): ConstraintParseResult`, domain records.
- Produces:
  - `InvalidRequestException(String message)` — `RuntimeException` for HTTP 400 (mapped in Task 11).
  - `TimeFormats`: `format(LocalTime): "HH:mm"`, `format(LocalDate): "yyyy-MM-dd"`, `parseTime(String value, String field): LocalTime` and `parseDate(String value, String field): LocalDate` (strict; blank/invalid → `InvalidRequestException("<field> must be HH:mm, was '<value>'")` / `"<field> is required (HH:mm)"` / `"<field> must be yyyy-MM-dd, was '<value>'"`).
  - Records: `CategoryDto(String id, String name)`; `GoalDto(String id, String categoryId, String name, int priority, String deadline, int defaultBlockMinutes, String energy, String preferredTime)`; `ConstraintDto(String type, String label, String start, String end, List<String> days, Integer timesPerWeek, Integer sessionMinutes, String goalId, Integer minutesPerWeek, String categoryId, Integer eveningsPerWeek)` where `type` is one of `FIXED_BLOCK`, `FREQUENCY_QUOTA`, `DURATION_QUOTA`, `EVENING_QUOTA` and unused fields are `null`; `SetupPayload(List<CategoryDto> categories, List<GoalDto> goals, List<ConstraintDto> constraints)`; `SetupParseRequest(String goalsText, String constraintsText)`; `SetupParseResponse(List<CategoryDto> categories, List<GoalDto> goals, List<ConstraintDto> constraints, List<String> warnings)`.
  - `SetupDtoMapper` (static): `toDto(Category)`, `toDto(Goal)`, `toDto(Constraint)`, `toDomain(CategoryDto)`, `toDomain(GoalDto, Map<String, Category>)`, `toDomain(ConstraintDto)`. Every invalid input becomes an `InvalidRequestException` with a readable message.
  - `@Service SetupService(SetupStore)`: `SetupParseResponse parse(SetupParseRequest)`, `@Transactional(readOnly = true) SetupPayload load()`, `@Transactional void save(SetupPayload)`. `save` validates before touching the database (duplicate category/goal ids, goal → unknown category, quota → unknown goal/category, all domain invariants) and treats `null` lists as empty.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/lifeorganizer/api/TimeFormatsTest.java`:

```java
package com.lifeorganizer.api;

import com.lifeorganizer.error.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeFormatsTest {

    @Test
    void formatsTimesAsHourMinute() {
        assertThat(TimeFormats.format(LocalTime.of(7, 0))).isEqualTo("07:00");
        assertThat(TimeFormats.format(LocalTime.of(23, 30))).isEqualTo("23:30");
    }

    @Test
    void formatsDatesAsIso() {
        assertThat(TimeFormats.format(LocalDate.of(2026, 9, 14))).isEqualTo("2026-09-14");
    }

    @Test
    void parsesStrictHourMinute() {
        assertThat(TimeFormats.parseTime("08:30", "start")).isEqualTo(LocalTime.of(8, 30));
    }

    @Test
    void rejectsASingleDigitHour() {
        assertThatThrownBy(() -> TimeFormats.parseTime("8:30", "start"))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("start must be HH:mm, was '8:30'");
    }

    @Test
    void rejectsAnOutOfRangeTimeIncludingTwentyFourHundred() {
        assertThatThrownBy(() -> TimeFormats.parseTime("25:00", "end"))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("end must be HH:mm");
        assertThatThrownBy(() -> TimeFormats.parseTime("24:00", "end"))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("end must be HH:mm, was '24:00'");
    }

    @Test
    void rejectsBlankAndNullTimes() {
        assertThatThrownBy(() -> TimeFormats.parseTime("  ", "start"))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("start is required (HH:mm)");
        assertThatThrownBy(() -> TimeFormats.parseTime(null, "start"))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("start is required (HH:mm)");
    }

    @Test
    void parsesIsoDatesAndRejectsOtherFormats() {
        assertThat(TimeFormats.parseDate("2026-09-14", "date")).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThatThrownBy(() -> TimeFormats.parseDate("14/09/2026", "date"))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("date must be yyyy-MM-dd, was '14/09/2026'");
    }
}
```

`backend/src/test/java/com/lifeorganizer/setup/api/SetupDtoMapperTest.java`:

```java
package com.lifeorganizer.setup.api;

import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.Constraint;
import com.lifeorganizer.domain.DurationQuota;
import com.lifeorganizer.domain.Energy;
import com.lifeorganizer.domain.EveningQuota;
import com.lifeorganizer.domain.FixedBlock;
import com.lifeorganizer.domain.FrequencyQuota;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.PreferredTime;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.setup.api.dto.ConstraintDto;
import com.lifeorganizer.setup.api.dto.GoalDto;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SetupDtoMapperTest {

    private static final Category CAREER = Category.of("Career");

    private static Constraint roundTrip(Constraint constraint) {
        return SetupDtoMapper.toDomain(SetupDtoMapper.toDto(constraint));
    }

    @Test
    void everyConstraintTypeRoundTripsThroughItsDto() {
        List<Constraint> all = List.of(
            new FixedBlock("Work", LocalTime.of(8, 30), LocalTime.of(17, 0), EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)),
            new FixedBlock("Sleep", LocalTime.of(23, 30), LocalTime.of(7, 0), EnumSet.allOf(DayOfWeek.class)),
            new FrequencyQuota("Gym", 4, 60, "health-gym"),
            new DurationQuota("Learning", 300, "career"),
            new EveningQuota("Social", 2, "social"));

        for (Constraint constraint : all) {
            assertThat(roundTrip(constraint)).isEqualTo(constraint);
        }
    }

    @Test
    void fixedBlockDtoUsesHourMinuteTimesAndWeekOrderedDayNames() {
        ConstraintDto dto = SetupDtoMapper.toDto(new FixedBlock("Work", LocalTime.of(8, 30), LocalTime.of(17, 0),
            EnumSet.of(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY)));

        assertThat(dto).isEqualTo(new ConstraintDto("FIXED_BLOCK", "Work", "08:30", "17:00",
            List.of("MONDAY", "WEDNESDAY"), null, null, null, null, null, null));
    }

    @Test
    void quotaDtosCarryOnlyTheirOwnFields() {
        assertThat(SetupDtoMapper.toDto(new FrequencyQuota("Gym", 4, 60, "health-gym")))
            .isEqualTo(new ConstraintDto("FREQUENCY_QUOTA", "Gym", null, null, null, 4, 60, "health-gym", null, null, null));
        assertThat(SetupDtoMapper.toDto(new DurationQuota("Learning", 300, "career")))
            .isEqualTo(new ConstraintDto("DURATION_QUOTA", "Learning", null, null, null, null, null, null, 300, "career", null));
        assertThat(SetupDtoMapper.toDto(new EveningQuota("Social", 2, "social")))
            .isEqualTo(new ConstraintDto("EVENING_QUOTA", "Social", null, null, null, null, null, null, null, "social", 2));
    }

    @Test
    void goalRoundTripsThroughItsDtoIncludingTheDeadline() {
        Goal goal = new Goal("career-cert", CAREER, "Claude certification", 5, LocalDate.of(2026, 12, 1), 90,
            Energy.HIGH, PreferredTime.EVENING);

        GoalDto dto = SetupDtoMapper.toDto(goal);

        assertThat(dto).isEqualTo(new GoalDto("career-cert", "career", "Claude certification", 5, "2026-12-01", 90,
            "HIGH", "EVENING"));
        assertThat(SetupDtoMapper.toDomain(dto, Map.of("career", CAREER))).isEqualTo(goal);
    }

    @Test
    void unknownConstraintTypeIsRejected() {
        ConstraintDto dto = new ConstraintDto("WAT", "X", null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> SetupDtoMapper.toDomain(dto))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("Unknown constraint type 'WAT'");
    }

    @Test
    void aMissingQuotaFieldNamesTheField() {
        ConstraintDto dto = new ConstraintDto("FREQUENCY_QUOTA", "Gym", null, null, null, null, 60, "health-gym", null, null, null);

        assertThatThrownBy(() -> SetupDtoMapper.toDomain(dto))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("timesPerWeek is required");
    }

    @Test
    void aBadTimeAndABadDayNameAreRejected() {
        ConstraintDto badTime = new ConstraintDto("FIXED_BLOCK", "Work", "8:30", "17:00", List.of("MONDAY"),
            null, null, null, null, null, null);
        ConstraintDto badDay = new ConstraintDto("FIXED_BLOCK", "Work", "08:30", "17:00", List.of("FUNDAY"),
            null, null, null, null, null, null);

        assertThatThrownBy(() -> SetupDtoMapper.toDomain(badTime))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("start must be HH:mm");
        assertThatThrownBy(() -> SetupDtoMapper.toDomain(badDay))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("days must be one of");
    }

    @Test
    void aGoalWithAnUnknownCategoryIsRejected() {
        GoalDto dto = new GoalDto("x", "nope", "X", 3, null, 60, "MEDIUM", "ANY");

        assertThatThrownBy(() -> SetupDtoMapper.toDomain(dto, Map.of("career", CAREER)))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("Goal 'x' refers to unknown category 'nope'");
    }

    @Test
    void aDomainRuleViolationBecomesAReadableError() {
        GoalDto dto = new GoalDto("x", "career", "X", 9, null, 60, "MEDIUM", "ANY");

        assertThatThrownBy(() -> SetupDtoMapper.toDomain(dto, Map.of("career", CAREER)))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("goal 'x': priority must be between 1 and 5, was 9");
    }

    @Test
    void aBadEnergyNamesTheAllowedValues() {
        GoalDto dto = new GoalDto("x", "career", "X", 3, null, 60, "SUPER", "ANY");

        assertThatThrownBy(() -> SetupDtoMapper.toDomain(dto, Map.of("career", CAREER)))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("energy must be one of [LOW, MEDIUM, HIGH], was 'SUPER'");
    }
}
```

`backend/src/test/java/com/lifeorganizer/setup/api/SetupServiceTest.java`:

```java
package com.lifeorganizer.setup.api;

import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.persistence.SetupStore;
import com.lifeorganizer.setup.api.dto.CategoryDto;
import com.lifeorganizer.setup.api.dto.ConstraintDto;
import com.lifeorganizer.setup.api.dto.GoalDto;
import com.lifeorganizer.setup.api.dto.SetupParseRequest;
import com.lifeorganizer.setup.api.dto.SetupParseResponse;
import com.lifeorganizer.setup.api.dto.SetupPayload;
import com.lifeorganizer.support.ExampleSetup;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({SetupService.class, SetupStore.class})
class SetupServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @Autowired
    SetupService service;

    @PersistenceContext
    EntityManager entityManager;

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private SetupParseResponse parseExample() {
        return service.parse(new SetupParseRequest(ExampleSetup.GOALS_TEXT, ExampleSetup.CONSTRAINTS_TEXT));
    }

    private static SetupPayload payloadOf(SetupParseResponse parsed) {
        return new SetupPayload(parsed.categories(), parsed.goals(), parsed.constraints());
    }

    @Test
    void parseTurnsTheExampleIntoStructuredData() {
        SetupParseResponse response = parseExample();

        assertThat(response.categories()).extracting(CategoryDto::name)
            .containsExactly("Career", "Projects", "Health", "Social", "Finance", "Hobbies");
        assertThat(response.goals()).hasSize(9);
        assertThat(response.goals()).contains(new GoalDto("health-gym", "health", "Gym", 3, null, 60, "MEDIUM", "ANY"));
        assertThat(response.constraints()).hasSize(6);
        assertThat(response.constraints().get(0)).isEqualTo(new ConstraintDto("FIXED_BLOCK", "Work", "08:30", "17:00",
            List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"), null, null, null, null, null, null));
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void parseReportsUnmatchedConstraintLinesAsWarnings() {
        SetupParseResponse response = service.parse(new SetupParseRequest(ExampleSetup.GOALS_TEXT, "Meditation: 3x/week\n"));

        assertThat(response.constraints()).isEmpty();
        assertThat(response.warnings()).containsExactly("No goal found for frequency constraint: Meditation");
    }

    @Test
    void parseRejectsAGoalWithoutACategory() {
        assertThatThrownBy(() -> service.parse(new SetupParseRequest("Goals\n│   ├── Orphan\n", "")))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("Goal 'Orphan' has no parent category");
    }

    @Test
    void parseAcceptsBlankInput() {
        SetupParseResponse response = service.parse(new SetupParseRequest(null, null));

        assertThat(response.categories()).isEmpty();
        assertThat(response.goals()).isEmpty();
        assertThat(response.constraints()).isEmpty();
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void savingThenLoadingRoundTripsTheParsedSetup() {
        SetupPayload payload = payloadOf(parseExample());

        service.save(payload);
        flushAndClear();

        assertThat(service.load()).isEqualTo(payload);
    }

    @Test
    void savingReplacesThePreviousSetup() {
        service.save(payloadOf(parseExample()));
        SetupPayload small = new SetupPayload(
            List.of(new CategoryDto("health", "Health")),
            List.of(new GoalDto("health-gym", "health", "Gym", 3, null, 60, "MEDIUM", "ANY")),
            List.of());

        service.save(small);
        flushAndClear();

        assertThat(service.load()).isEqualTo(small);
    }

    @Test
    void editedPrioritiesAreKept() {
        SetupPayload parsed = payloadOf(parseExample());
        List<GoalDto> edited = new ArrayList<>();
        for (GoalDto goal : parsed.goals()) {
            edited.add(goal.id().equals("health-gym")
                ? new GoalDto(goal.id(), goal.categoryId(), goal.name(), 5, goal.deadline(),
                    goal.defaultBlockMinutes(), goal.energy(), goal.preferredTime())
                : goal);
        }

        service.save(new SetupPayload(parsed.categories(), edited, parsed.constraints()));
        flushAndClear();

        assertThat(service.load().goals()).filteredOn(g -> g.id().equals("health-gym"))
            .extracting(GoalDto::priority).containsExactly(5);
    }

    @Test
    void nullListsAreTreatedAsEmpty() {
        service.save(new SetupPayload(null, null, null));
        flushAndClear();

        assertThat(service.load()).isEqualTo(new SetupPayload(List.of(), List.of(), List.of()));
    }

    @Test
    void aDuplicateGoalIdIsRejected() {
        GoalDto gym = new GoalDto("health-gym", "health", "Gym", 3, null, 60, "MEDIUM", "ANY");
        SetupPayload payload = new SetupPayload(List.of(new CategoryDto("health", "Health")), List.of(gym, gym), List.of());

        assertThatThrownBy(() -> service.save(payload))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("Duplicate goal id 'health-gym'");
    }

    @Test
    void aDuplicateCategoryIdIsRejected() {
        CategoryDto health = new CategoryDto("health", "Health");
        SetupPayload payload = new SetupPayload(List.of(health, health), List.of(), List.of());

        assertThatThrownBy(() -> service.save(payload))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("Duplicate category id 'health'");
    }

    @Test
    void aGoalWithAnUnknownCategoryIsRejected() {
        GoalDto gym = new GoalDto("health-gym", "nope", "Gym", 3, null, 60, "MEDIUM", "ANY");
        SetupPayload payload = new SetupPayload(List.of(new CategoryDto("health", "Health")), List.of(gym), List.of());

        assertThatThrownBy(() -> service.save(payload))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("Goal 'health-gym' refers to unknown category 'nope'");
    }

    @Test
    void aQuotaThatPointsAtAnUnknownGoalOrCategoryIsRejected() {
        CategoryDto health = new CategoryDto("health", "Health");
        ConstraintDto frequency = new ConstraintDto("FREQUENCY_QUOTA", "Gym", null, null, null, 4, 60, "nope", null, null, null);
        ConstraintDto duration = new ConstraintDto("DURATION_QUOTA", "Learning", null, null, null, null, null, null, 300, "nope", null);

        assertThatThrownBy(() -> service.save(new SetupPayload(List.of(health), List.of(), List.of(frequency))))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("Constraint 'Gym' refers to unknown goal 'nope'");
        assertThatThrownBy(() -> service.save(new SetupPayload(List.of(health), List.of(), List.of(duration))))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("Constraint 'Learning' refers to unknown category 'nope'");
    }

    @Test
    void aRejectedSaveKeepsThePreviousSetup() {
        SetupPayload good = payloadOf(parseExample());
        service.save(good);
        GoalDto bad = new GoalDto("x", "career", "X", 9, null, 60, "MEDIUM", "ANY");

        assertThatThrownBy(() -> service.save(new SetupPayload(good.categories(), List.of(bad), List.of())))
            .isInstanceOf(InvalidRequestException.class);
        flushAndClear();

        assertThat(service.load()).isEqualTo(good);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest='TimeFormatsTest,SetupDtoMapperTest,SetupServiceTest'`
Expected: FAIL — compilation errors (none of the production classes exist).

- [ ] **Step 3: Create the exception and `TimeFormats`**

`backend/src/main/java/com/lifeorganizer/error/InvalidRequestException.java`:

```java
package com.lifeorganizer.error;

/** The caller sent something invalid (bad JSON values, broken rules). Maps to HTTP 400. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
```

`backend/src/main/java/com/lifeorganizer/api/TimeFormats.java`:

```java
package com.lifeorganizer.api;

import com.lifeorganizer.error.InvalidRequestException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/** The only place that converts between java.time values and the API's string formats. */
public final class TimeFormats {

    // STRICT on purpose: the default SMART style silently accepts "24:00" as midnight (verified).
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm")
        .withResolverStyle(ResolverStyle.STRICT);

    private TimeFormats() {
    }

    public static String format(LocalTime time) {
        return time.format(HH_MM);
    }

    public static String format(LocalDate date) {
        return date.toString();
    }

    public static LocalTime parseTime(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(field + " is required (HH:mm)");
        }
        try {
            return LocalTime.parse(value, HH_MM);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException(field + " must be HH:mm, was '" + value + "'");
        }
    }

    public static LocalDate parseDate(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(field + " is required (yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException(field + " must be yyyy-MM-dd, was '" + value + "'");
        }
    }
}
```

- [ ] **Step 4: Create the DTO records**

Each file is in package `com.lifeorganizer.setup.api.dto`.

`CategoryDto.java`:

```java
package com.lifeorganizer.setup.api.dto;

public record CategoryDto(String id, String name) {
}
```

`GoalDto.java`:

```java
package com.lifeorganizer.setup.api.dto;

public record GoalDto(String id, String categoryId, String name, int priority, String deadline,
                      int defaultBlockMinutes, String energy, String preferredTime) {
}
```

`ConstraintDto.java`:

```java
package com.lifeorganizer.setup.api.dto;

import java.util.List;

/**
 * One flat shape for all four constraint variants. {@code type} is FIXED_BLOCK, FREQUENCY_QUOTA,
 * DURATION_QUOTA or EVENING_QUOTA; the fields a variant does not use are null.
 */
public record ConstraintDto(String type, String label, String start, String end, List<String> days,
                            Integer timesPerWeek, Integer sessionMinutes, String goalId,
                            Integer minutesPerWeek, String categoryId, Integer eveningsPerWeek) {
}
```

`SetupPayload.java`:

```java
package com.lifeorganizer.setup.api.dto;

import java.util.List;

/** The whole setup: what {@code GET /api/setup} returns and {@code PUT /api/setup} accepts. */
public record SetupPayload(List<CategoryDto> categories, List<GoalDto> goals, List<ConstraintDto> constraints) {
}
```

`SetupParseRequest.java`:

```java
package com.lifeorganizer.setup.api.dto;

public record SetupParseRequest(String goalsText, String constraintsText) {
}
```

`SetupParseResponse.java`:

```java
package com.lifeorganizer.setup.api.dto;

import java.util.List;

public record SetupParseResponse(List<CategoryDto> categories, List<GoalDto> goals,
                                 List<ConstraintDto> constraints, List<String> warnings) {
}
```

- [ ] **Step 5: Create `SetupDtoMapper`**

`backend/src/main/java/com/lifeorganizer/setup/api/SetupDtoMapper.java`:

```java
package com.lifeorganizer.setup.api;

import com.lifeorganizer.api.TimeFormats;
import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.Constraint;
import com.lifeorganizer.domain.DurationQuota;
import com.lifeorganizer.domain.Energy;
import com.lifeorganizer.domain.EveningQuota;
import com.lifeorganizer.domain.FixedBlock;
import com.lifeorganizer.domain.FrequencyQuota;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.PreferredTime;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.setup.api.dto.CategoryDto;
import com.lifeorganizer.setup.api.dto.ConstraintDto;
import com.lifeorganizer.setup.api.dto.GoalDto;

import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Converts between the Phase 1a domain records and the string-only setup DTOs. */
public final class SetupDtoMapper {

    private SetupDtoMapper() {
    }

    public static CategoryDto toDto(Category category) {
        return new CategoryDto(category.id(), category.name());
    }

    public static GoalDto toDto(Goal goal) {
        return new GoalDto(goal.id(), goal.category().id(), goal.name(), goal.priority(),
            goal.deadline() == null ? null : TimeFormats.format(goal.deadline()),
            goal.defaultBlockMinutes(), goal.energy().name(), goal.preferredTime().name());
    }

    public static ConstraintDto toDto(Constraint constraint) {
        return switch (constraint) {
            case FixedBlock f -> new ConstraintDto("FIXED_BLOCK", f.label(),
                TimeFormats.format(f.start()), TimeFormats.format(f.end()),
                f.days().stream().sorted().map(DayOfWeek::name).toList(),
                null, null, null, null, null, null);
            case FrequencyQuota q -> new ConstraintDto("FREQUENCY_QUOTA", q.label(), null, null, null,
                q.timesPerWeek(), q.sessionMinutes(), q.goalId(), null, null, null);
            case DurationQuota q -> new ConstraintDto("DURATION_QUOTA", q.label(), null, null, null,
                null, null, null, q.minutesPerWeek(), q.categoryId(), null);
            case EveningQuota q -> new ConstraintDto("EVENING_QUOTA", q.label(), null, null, null,
                null, null, null, null, q.categoryId(), q.eveningsPerWeek());
        };
    }

    public static Category toDomain(CategoryDto dto) {
        return build("category '" + dto.id() + "'", () -> new Category(dto.id(), dto.name()));
    }

    public static Goal toDomain(GoalDto dto, Map<String, Category> categoriesById) {
        Category category = categoriesById.get(dto.categoryId());
        if (category == null) {
            throw new InvalidRequestException(
                "Goal '" + dto.id() + "' refers to unknown category '" + dto.categoryId() + "'");
        }
        return build("goal '" + dto.id() + "'", () -> new Goal(dto.id(), category, dto.name(), dto.priority(),
            dto.deadline() == null ? null : TimeFormats.parseDate(dto.deadline(), "deadline"),
            dto.defaultBlockMinutes(),
            parseEnum(Energy.class, dto.energy(), "energy"),
            parseEnum(PreferredTime.class, dto.preferredTime(), "preferredTime")));
    }

    public static Constraint toDomain(ConstraintDto dto) {
        if (dto.type() == null) {
            throw new InvalidRequestException("Constraint '" + dto.label() + "' has no type");
        }
        String what = "constraint '" + dto.label() + "'";
        return switch (dto.type()) {
            case "FIXED_BLOCK" -> build(what, () -> new FixedBlock(dto.label(),
                TimeFormats.parseTime(dto.start(), "start"), TimeFormats.parseTime(dto.end(), "end"),
                parseDays(dto.days())));
            case "FREQUENCY_QUOTA" -> build(what, () -> new FrequencyQuota(dto.label(),
                required(dto.timesPerWeek(), "timesPerWeek"), required(dto.sessionMinutes(), "sessionMinutes"),
                dto.goalId()));
            case "DURATION_QUOTA" -> build(what, () -> new DurationQuota(dto.label(),
                required(dto.minutesPerWeek(), "minutesPerWeek"), dto.categoryId()));
            case "EVENING_QUOTA" -> build(what, () -> new EveningQuota(dto.label(),
                required(dto.eveningsPerWeek(), "eveningsPerWeek"), dto.categoryId()));
            default -> throw new InvalidRequestException("Unknown constraint type '" + dto.type() + "'");
        };
    }

    /** Domain constructors validate themselves; turn their failures into readable request errors. */
    private static <T> T build(String what, Supplier<T> factory) {
        try {
            return factory.get();
        } catch (NullPointerException e) {
            throw new InvalidRequestException(what + ": missing required value (" + e.getMessage() + ")");
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(what + ": " + e.getMessage());
        }
    }

    private static int required(Integer value, String field) {
        if (value == null) {
            throw new InvalidRequestException(field + " is required");
        }
        return value;
    }

    private static Set<DayOfWeek> parseDays(List<String> names) {
        if (names == null || names.isEmpty()) {
            throw new InvalidRequestException("days must not be empty");
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String name : names) {
            days.add(parseEnum(DayOfWeek.class, name, "days"));
        }
        return days;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        if (value == null) {
            throw new InvalidRequestException(field + " is required");
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(field + " must be one of "
                + Arrays.toString(type.getEnumConstants()) + ", was '" + value + "'");
        }
    }
}
```

- [ ] **Step 6: Create `SetupService`**

`backend/src/main/java/com/lifeorganizer/setup/api/SetupService.java`:

```java
package com.lifeorganizer.setup.api;

import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.Constraint;
import com.lifeorganizer.domain.DurationQuota;
import com.lifeorganizer.domain.EveningQuota;
import com.lifeorganizer.domain.FixedBlock;
import com.lifeorganizer.domain.FrequencyQuota;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.GoalTree;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.persistence.SetupStore;
import com.lifeorganizer.setup.ConstraintParseResult;
import com.lifeorganizer.setup.ConstraintParser;
import com.lifeorganizer.setup.GoalTreeParser;
import com.lifeorganizer.setup.api.dto.CategoryDto;
import com.lifeorganizer.setup.api.dto.ConstraintDto;
import com.lifeorganizer.setup.api.dto.GoalDto;
import com.lifeorganizer.setup.api.dto.SetupParseRequest;
import com.lifeorganizer.setup.api.dto.SetupParseResponse;
import com.lifeorganizer.setup.api.dto.SetupPayload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses pasted setup text, and validates then stores / loads the whole setup. */
@Service
public class SetupService {

    private final SetupStore setupStore;

    public SetupService(SetupStore setupStore) {
        this.setupStore = setupStore;
    }

    public SetupParseResponse parse(SetupParseRequest request) {
        String goalsText = request.goalsText() == null ? "" : request.goalsText();
        String constraintsText = request.constraintsText() == null ? "" : request.constraintsText();

        GoalTree tree;
        try {
            tree = new GoalTreeParser().parse(goalsText);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(e.getMessage());
        }
        ConstraintParseResult parsed = new ConstraintParser().parse(constraintsText, tree);

        return new SetupParseResponse(
            tree.categories().stream().map(SetupDtoMapper::toDto).toList(),
            tree.goals().stream().map(SetupDtoMapper::toDto).toList(),
            parsed.constraints().stream().map(SetupDtoMapper::toDto).toList(),
            parsed.warnings());
    }

    @Transactional(readOnly = true)
    public SetupPayload load() {
        GoalTree tree = setupStore.loadTree();
        return new SetupPayload(
            tree.categories().stream().map(SetupDtoMapper::toDto).toList(),
            tree.goals().stream().map(SetupDtoMapper::toDto).toList(),
            setupStore.loadConstraints().stream().map(SetupDtoMapper::toDto).toList());
    }

    /** Validates everything first, so a rejected request leaves the stored setup untouched. */
    @Transactional
    public void save(SetupPayload payload) {
        Map<String, Category> categoriesById = new LinkedHashMap<>();
        for (CategoryDto dto : orEmpty(payload.categories())) {
            Category category = SetupDtoMapper.toDomain(dto);
            if (categoriesById.put(category.id(), category) != null) {
                throw new InvalidRequestException("Duplicate category id '" + category.id() + "'");
            }
        }

        Map<String, Goal> goalsById = new LinkedHashMap<>();
        for (GoalDto dto : orEmpty(payload.goals())) {
            Goal goal = SetupDtoMapper.toDomain(dto, categoriesById);
            if (goalsById.put(goal.id(), goal) != null) {
                throw new InvalidRequestException("Duplicate goal id '" + goal.id() + "'");
            }
        }

        List<Constraint> constraints = new ArrayList<>();
        for (ConstraintDto dto : orEmpty(payload.constraints())) {
            Constraint constraint = SetupDtoMapper.toDomain(dto);
            verifyReferences(constraint, categoriesById, goalsById);
            constraints.add(constraint);
        }

        setupStore.replace(
            new GoalTree(new ArrayList<>(categoriesById.values()), new ArrayList<>(goalsById.values())),
            constraints);
    }

    private static void verifyReferences(Constraint constraint, Map<String, Category> categoriesById,
                                         Map<String, Goal> goalsById) {
        switch (constraint) {
            case FrequencyQuota q -> {
                if (!goalsById.containsKey(q.goalId())) {
                    throw new InvalidRequestException(
                        "Constraint '" + q.label() + "' refers to unknown goal '" + q.goalId() + "'");
                }
            }
            case DurationQuota q -> requireCategory(q.label(), q.categoryId(), categoriesById);
            case EveningQuota q -> requireCategory(q.label(), q.categoryId(), categoriesById);
            case FixedBlock f -> {
            }
        }
    }

    private static void requireCategory(String label, String categoryId, Map<String, Category> categoriesById) {
        if (!categoriesById.containsKey(categoryId)) {
            throw new InvalidRequestException(
                "Constraint '" + label + "' refers to unknown category '" + categoryId + "'");
        }
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest='TimeFormatsTest,SetupDtoMapperTest,SetupServiceTest'`
Expected: PASS — 7 + 10 + 13 = 30 tests green.

- [ ] **Step 8: Run the full suite**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS, 158 tests.

- [ ] **Step 9: Commit**

```bash
git add backend
git commit -m "feat: add setup DTOs, mapper and SetupService with validation" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 11: Plan API — DTOs, `PlanController`, `ConfigController`, error handling

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/plan/dto/BlockDto.java`
- Create: `backend/src/main/java/com/lifeorganizer/plan/dto/TodayPlanResponse.java`
- Create: `backend/src/main/java/com/lifeorganizer/plan/dto/ApplyEventRequest.java`
- Create: `backend/src/main/java/com/lifeorganizer/plan/dto/ApplyEventResponse.java`
- Create: `backend/src/main/java/com/lifeorganizer/plan/PlanDtoMapper.java`
- Create: `backend/src/main/java/com/lifeorganizer/plan/PlanController.java`
- Create: `backend/src/main/java/com/lifeorganizer/config/ConfigController.java`
- Create: `backend/src/main/java/com/lifeorganizer/config/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/com/lifeorganizer/plan/dto/ApplyEventRequestTest.java`
- Test: `backend/src/test/java/com/lifeorganizer/plan/PlanControllerTest.java`
- Test: `backend/src/test/java/com/lifeorganizer/config/ConfigControllerTest.java`

**Interfaces:**
- Consumes: `PlanService.getOrGenerate(LocalDate): DayPlan`, `PlanService.applyEvent(PlanEvent, LocalDate): AppliedEvent` (Tasks 8-9), `TimeFormats`, the three exceptions, Phase 1a `PlanEvent` variants and `Energy`.
- Produces (the JSON contract the React UI will use):
  - `GET /api/plan/today` → `200 {"date":"2026-09-14","blocks":[BlockDto…],"warnings":[…]}`; blocks sorted by start time. `BlockDto`: `{id, date, start:"HH:mm", end:"HH:mm", title, kind, goalId|null, status, postponeCount, flagged, reason|null}` with `kind ∈ FIXED|ROUTINE|GOAL|FREE|ADHOC`, `status ∈ PLANNED|DONE|CANCELLED|POSTPONED`.
  - `POST /api/plan/today/events` with body `ApplyEventRequest{type, blockId, reason, date, title, start, end, energy}` where `type ∈ CANCEL_BLOCK|MARK_DONE|POSTPONE_BLOCK|SET_ENERGY|ADD_ADHOC` (`reason` optional; `date` defaults to today for `ADD_ADHOC`; `energy ∈ LOW|MEDIUM|HIGH`) → `200 {"date","blocks","warnings","changes":[…]}` (today's plan after the event + change messages).
  - `GET /api/config` → `{"aiEnabled":false,"ragEnabled":false}`.
  - Errors are `application/problem+json` `ProblemDetail`s: `404 "Setup required"` (`SetupRequiredException`), `422 "Unknown block"` (`UnknownBlockException`), `400 "Invalid request"` (`InvalidRequestException`), `400 "Malformed request"` (unreadable/missing JSON body). Each has `status`, `title`, `detail`.
  - `ApplyEventRequest.toEvent(LocalDate today): PlanEvent` (throws `InvalidRequestException`); `PlanDtoMapper.toDto(Block)`, `toResponse(DayPlan): TodayPlanResponse`, `toResponse(AppliedEvent): ApplyEventResponse`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/lifeorganizer/plan/dto/ApplyEventRequestTest.java`:

```java
package com.lifeorganizer.plan.dto;

import com.lifeorganizer.domain.Energy;
import com.lifeorganizer.engine.AddAdhoc;
import com.lifeorganizer.engine.CancelBlock;
import com.lifeorganizer.engine.MarkDone;
import com.lifeorganizer.engine.PostponeBlock;
import com.lifeorganizer.engine.SetEnergy;
import com.lifeorganizer.error.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplyEventRequestTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);

    private static ApplyEventRequest request(String type, String blockId, String reason, String date, String title,
                                             String start, String end, String energy) {
        return new ApplyEventRequest(type, blockId, reason, date, title, start, end, energy);
    }

    @Test
    void mapsEveryEventType() {
        assertThat(request("CANCEL_BLOCK", "b1", null, null, null, null, null, null).toEvent(TODAY))
            .isEqualTo(new CancelBlock("b1"));
        assertThat(request("MARK_DONE", "b1", null, null, null, null, null, null).toEvent(TODAY))
            .isEqualTo(new MarkDone("b1"));
        assertThat(request("POSTPONE_BLOCK", "b1", "tired", null, null, null, null, null).toEvent(TODAY))
            .isEqualTo(new PostponeBlock("b1", "tired"));
        assertThat(request("SET_ENERGY", "b1", null, null, null, null, null, "LOW").toEvent(TODAY))
            .isEqualTo(new SetEnergy("b1", Energy.LOW));
    }

    @Test
    void aBlankPostponeReasonBecomesNull() {
        assertThat(request("POSTPONE_BLOCK", "b1", "  ", null, null, null, null, null).toEvent(TODAY))
            .isEqualTo(new PostponeBlock("b1", null));
    }

    @Test
    void addAdhocUsesTheGivenDateOrDefaultsToToday() {
        assertThat(request("ADD_ADHOC", null, null, "2026-09-16", "Padel", "19:00", "20:30", null).toEvent(TODAY))
            .isEqualTo(new AddAdhoc(LocalDate.of(2026, 9, 16), "Padel", LocalTime.of(19, 0), LocalTime.of(20, 30)));
        assertThat(request("ADD_ADHOC", null, null, null, "Padel", "19:00", "20:30", null).toEvent(TODAY))
            .isEqualTo(new AddAdhoc(TODAY, "Padel", LocalTime.of(19, 0), LocalTime.of(20, 30)));
    }

    @Test
    void aMissingOrUnknownTypeIsRejected() {
        assertThatThrownBy(() -> request(null, "b1", null, null, null, null, null, null).toEvent(TODAY))
            .isInstanceOf(InvalidRequestException.class).hasMessage("type is required");
        assertThatThrownBy(() -> request("EXPLODE", "b1", null, null, null, null, null, null).toEvent(TODAY))
            .isInstanceOf(InvalidRequestException.class).hasMessage("Unknown event type 'EXPLODE'");
    }

    @Test
    void aMissingBlockIdNamesTheField() {
        assertThatThrownBy(() -> request("MARK_DONE", null, null, null, null, null, null, null).toEvent(TODAY))
            .isInstanceOf(InvalidRequestException.class).hasMessage("blockId is required");
    }

    @Test
    void addAdhocRejectsBadTimesAndAnEndBeforeTheStart() {
        assertThatThrownBy(() -> request("ADD_ADHOC", null, null, null, "Padel", "7pm", "20:30", null).toEvent(TODAY))
            .isInstanceOf(InvalidRequestException.class).hasMessage("start must be HH:mm, was '7pm'");
        assertThatThrownBy(() -> request("ADD_ADHOC", null, null, null, "Padel", "20:30", "19:00", null).toEvent(TODAY))
            .isInstanceOf(InvalidRequestException.class).hasMessage("end must be after start");
        assertThatThrownBy(() -> request("ADD_ADHOC", null, null, null, null, "19:00", "20:30", null).toEvent(TODAY))
            .isInstanceOf(InvalidRequestException.class).hasMessage("title is required");
    }

    @Test
    void aBadEnergyNamesTheAllowedValues() {
        assertThatThrownBy(() -> request("SET_ENERGY", "b1", null, null, null, null, null, "TIRED").toEvent(TODAY))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessage("energy must be one of [LOW, MEDIUM, HIGH], was 'TIRED'");
    }

    @Test
    void overlongTextIsRejected() {
        assertThatThrownBy(() -> request("POSTPONE_BLOCK", "b1", "x".repeat(501), null, null, null, null, null).toEvent(TODAY))
            .isInstanceOf(InvalidRequestException.class).hasMessage("reason must be at most 500 characters");
        assertThatThrownBy(() -> request("ADD_ADHOC", null, null, null, "x".repeat(256), "19:00", "20:00", null).toEvent(TODAY))
            .isInstanceOf(InvalidRequestException.class).hasMessage("title must be at most 255 characters");
    }
}
```

`backend/src/test/java/com/lifeorganizer/plan/PlanControllerTest.java`:

```java
package com.lifeorganizer.plan;

import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.engine.AddAdhoc;
import com.lifeorganizer.engine.PlanEvent;
import com.lifeorganizer.engine.PostponeBlock;
import com.lifeorganizer.error.SetupRequiredException;
import com.lifeorganizer.error.UnknownBlockException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlanController.class)
@Import(PlanControllerTest.FixedClock.class)
class PlanControllerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PlanService planService;

    private static DayPlan samplePlan() {
        Block late = new Block("late", TODAY, LocalTime.of(18, 0), LocalTime.of(19, 0), "Claude certification",
            BlockKind.GOAL, "career-cert", BlockStatus.POSTPONED, 2, true, "tired");
        Block early = new Block("early", TODAY, LocalTime.of(7, 0), LocalTime.of(8, 0), "Gym",
            BlockKind.GOAL, "health-gym", BlockStatus.PLANNED, 0, false, null);
        return new DayPlan(TODAY, List.of(late, early), List.of("a warning"));
    }

    @Test
    void todayReturnsTheBlocksSortedByStartWithFormattedTimes() throws Exception {
        when(planService.getOrGenerate(TODAY)).thenReturn(samplePlan());

        mvc.perform(get("/api/plan/today"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.date").value("2026-09-14"))
            .andExpect(jsonPath("$.blocks.length()").value(2))
            .andExpect(jsonPath("$.blocks[0].id").value("early"))
            .andExpect(jsonPath("$.blocks[0].start").value("07:00"))
            .andExpect(jsonPath("$.blocks[0].end").value("08:00"))
            .andExpect(jsonPath("$.blocks[0].kind").value("GOAL"))
            .andExpect(jsonPath("$.blocks[0].status").value("PLANNED"))
            .andExpect(jsonPath("$.blocks[0].goalId").value("health-gym"))
            .andExpect(jsonPath("$.blocks[0].postponeCount").value(0))
            .andExpect(jsonPath("$.blocks[0].flagged").value(false))
            .andExpect(jsonPath("$.blocks[1].id").value("late"))
            .andExpect(jsonPath("$.blocks[1].status").value("POSTPONED"))
            .andExpect(jsonPath("$.blocks[1].postponeCount").value(2))
            .andExpect(jsonPath("$.blocks[1].flagged").value(true))
            .andExpect(jsonPath("$.blocks[1].reason").value("tired"))
            .andExpect(jsonPath("$.warnings[0]").value("a warning"));
    }

    @Test
    void todayIsNotFoundBeforeAnySetup() throws Exception {
        when(planService.getOrGenerate(TODAY)).thenThrow(new SetupRequiredException("No setup saved yet"));

        mvc.perform(get("/api/plan/today"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.title").value("Setup required"))
            .andExpect(jsonPath("$.detail").value("No setup saved yet"));
    }

    @Test
    void postingAnEventPassesItToTheServiceAndReturnsTodayPlusChanges() throws Exception {
        when(planService.applyEvent(any(PlanEvent.class), eq(TODAY)))
            .thenReturn(new AppliedEvent(samplePlan(), List.of("Moved Claude certification 18:00 -> 2026-09-14 21:00")));

        mvc.perform(post("/api/plan/today/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"POSTPONE_BLOCK\",\"blockId\":\"b1\",\"reason\":\"tired\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.date").value("2026-09-14"))
            .andExpect(jsonPath("$.blocks.length()").value(2))
            .andExpect(jsonPath("$.changes[0]").value("Moved Claude certification 18:00 -> 2026-09-14 21:00"));

        ArgumentCaptor<PlanEvent> captor = ArgumentCaptor.forClass(PlanEvent.class);
        verify(planService).applyEvent(captor.capture(), eq(TODAY));
        assertThat(captor.getValue()).isEqualTo(new PostponeBlock("b1", "tired"));
    }

    @Test
    void addAdhocWithoutADateDefaultsToToday() throws Exception {
        when(planService.applyEvent(any(PlanEvent.class), eq(TODAY)))
            .thenReturn(new AppliedEvent(samplePlan(), List.of("Added Padel at 19:00")));

        mvc.perform(post("/api/plan/today/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"ADD_ADHOC\",\"title\":\"Padel\",\"start\":\"19:00\",\"end\":\"20:30\"}"))
            .andExpect(status().isOk());

        ArgumentCaptor<PlanEvent> captor = ArgumentCaptor.forClass(PlanEvent.class);
        verify(planService).applyEvent(captor.capture(), eq(TODAY));
        assertThat(captor.getValue())
            .isEqualTo(new AddAdhoc(TODAY, "Padel", LocalTime.of(19, 0), LocalTime.of(20, 30)));
    }

    @Test
    void anUnknownEventTypeIsABadRequest() throws Exception {
        mvc.perform(post("/api/plan/today/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"EXPLODE\",\"blockId\":\"b1\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.title").value("Invalid request"))
            .andExpect(jsonPath("$.detail").value("Unknown event type 'EXPLODE'"));
    }

    @Test
    void aMissingBlockIdIsABadRequest() throws Exception {
        mvc.perform(post("/api/plan/today/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"MARK_DONE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("blockId is required"));
    }

    @Test
    void anUnknownBlockIsUnprocessable() throws Exception {
        when(planService.applyEvent(any(PlanEvent.class), eq(TODAY)))
            .thenThrow(new UnknownBlockException("No block with id 'nope'"));

        mvc.perform(post("/api/plan/today/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"CANCEL_BLOCK\",\"blockId\":\"nope\"}"))
            .andExpect(status().is(422))
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.status").value(422))
            .andExpect(jsonPath("$.title").value("Unknown block"))
            .andExpect(jsonPath("$.detail").value("No block with id 'nope'"));
    }

    @Test
    void malformedAndMissingBodiesAreBadRequests() throws Exception {
        mvc.perform(post("/api/plan/today/events").contentType(MediaType.APPLICATION_JSON).content("{not json"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.title").value("Malformed request"));

        mvc.perform(post("/api/plan/today/events").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Malformed request"));
    }
}
```

`backend/src/test/java/com/lifeorganizer/config/ConfigControllerTest.java`:

```java
package com.lifeorganizer.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConfigController.class)
class ConfigControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void reportsThatNoAiOrRagFeaturesAreEnabledYet() throws Exception {
        mvc.perform(get("/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.aiEnabled").value(false))
            .andExpect(jsonPath("$.ragEnabled").value(false));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest='ApplyEventRequestTest,PlanControllerTest,ConfigControllerTest'`
Expected: FAIL — compilation errors (none of the production classes exist).

- [ ] **Step 3: Create the plan DTOs**

Each file is in package `com.lifeorganizer.plan.dto`.

`BlockDto.java`:

```java
package com.lifeorganizer.plan.dto;

public record BlockDto(String id, String date, String start, String end, String title, String kind,
                       String goalId, String status, int postponeCount, boolean flagged, String reason) {
}
```

`TodayPlanResponse.java`:

```java
package com.lifeorganizer.plan.dto;

import java.util.List;

public record TodayPlanResponse(String date, List<BlockDto> blocks, List<String> warnings) {
}
```

`ApplyEventResponse.java`:

```java
package com.lifeorganizer.plan.dto;

import java.util.List;

public record ApplyEventResponse(String date, List<BlockDto> blocks, List<String> warnings, List<String> changes) {
}
```

`ApplyEventRequest.java`:

```java
package com.lifeorganizer.plan.dto;

import com.lifeorganizer.api.TimeFormats;
import com.lifeorganizer.domain.Energy;
import com.lifeorganizer.engine.AddAdhoc;
import com.lifeorganizer.engine.CancelBlock;
import com.lifeorganizer.engine.MarkDone;
import com.lifeorganizer.engine.PlanEvent;
import com.lifeorganizer.engine.PostponeBlock;
import com.lifeorganizer.engine.SetEnergy;
import com.lifeorganizer.error.InvalidRequestException;

import java.time.LocalDate;
import java.util.Arrays;

/**
 * One user action on the plan. {@code type} is CANCEL_BLOCK, MARK_DONE, POSTPONE_BLOCK, SET_ENERGY or
 * ADD_ADHOC; only the fields that type needs are read.
 */
public record ApplyEventRequest(String type, String blockId, String reason, String date, String title,
                                String start, String end, String energy) {

    private static final int MAX_ID = 255;
    private static final int MAX_TITLE = 255;
    private static final int MAX_REASON = 500;

    public PlanEvent toEvent(LocalDate today) {
        if (type == null || type.isBlank()) {
            throw new InvalidRequestException("type is required");
        }
        try {
            return switch (type) {
                case "CANCEL_BLOCK" -> new CancelBlock(required(blockId, "blockId", MAX_ID));
                case "MARK_DONE" -> new MarkDone(required(blockId, "blockId", MAX_ID));
                case "POSTPONE_BLOCK" -> new PostponeBlock(required(blockId, "blockId", MAX_ID),
                    optional(reason, "reason", MAX_REASON));
                case "SET_ENERGY" -> new SetEnergy(required(blockId, "blockId", MAX_ID), energyLevel());
                case "ADD_ADHOC" -> new AddAdhoc(
                    date == null || date.isBlank() ? today : TimeFormats.parseDate(date, "date"),
                    required(title, "title", MAX_TITLE),
                    TimeFormats.parseTime(start, "start"),
                    TimeFormats.parseTime(end, "end"));
                default -> throw new InvalidRequestException("Unknown event type '" + type + "'");
            };
        } catch (IllegalArgumentException e) {
            // an engine record's own rule, e.g. AddAdhoc: "end must be after start"
            throw new InvalidRequestException(e.getMessage());
        }
    }

    private Energy energyLevel() {
        if (energy == null || energy.isBlank()) {
            throw new InvalidRequestException("energy is required");
        }
        try {
            return Energy.valueOf(energy);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("energy must be one of "
                + Arrays.toString(Energy.values()) + ", was '" + energy + "'");
        }
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(field + " is required");
        }
        return limited(value, field, max);
    }

    private static String optional(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return limited(value, field, max);
    }

    private static String limited(String value, String field, int max) {
        if (value.length() > max) {
            throw new InvalidRequestException(field + " must be at most " + max + " characters");
        }
        return value;
    }
}
```

- [ ] **Step 4: Create `PlanDtoMapper` and `PlanController`**

`backend/src/main/java/com/lifeorganizer/plan/PlanDtoMapper.java`:

```java
package com.lifeorganizer.plan;

import com.lifeorganizer.api.TimeFormats;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.DayPlan;
import com.lifeorganizer.plan.dto.ApplyEventResponse;
import com.lifeorganizer.plan.dto.BlockDto;
import com.lifeorganizer.plan.dto.TodayPlanResponse;

import java.util.List;

/** Converts domain plans into the string-only JSON shapes; blocks are always sorted by start time. */
public final class PlanDtoMapper {

    private PlanDtoMapper() {
    }

    public static BlockDto toDto(Block block) {
        return new BlockDto(block.id(), TimeFormats.format(block.date()), TimeFormats.format(block.start()),
            TimeFormats.format(block.end()), block.title(), block.kind().name(), block.goalId(),
            block.status().name(), block.postponeCount(), block.flagged(), block.reason());
    }

    public static TodayPlanResponse toResponse(DayPlan plan) {
        return new TodayPlanResponse(TimeFormats.format(plan.date()), blocksOf(plan), plan.warnings());
    }

    public static ApplyEventResponse toResponse(AppliedEvent applied) {
        DayPlan today = applied.today();
        return new ApplyEventResponse(TimeFormats.format(today.date()), blocksOf(today), today.warnings(),
            applied.changes());
    }

    private static List<BlockDto> blocksOf(DayPlan plan) {
        return plan.blocksInOrder().stream().map(PlanDtoMapper::toDto).toList();
    }
}
```

`backend/src/main/java/com/lifeorganizer/plan/PlanController.java`:

```java
package com.lifeorganizer.plan;

import com.lifeorganizer.engine.PlanEvent;
import com.lifeorganizer.plan.dto.ApplyEventRequest;
import com.lifeorganizer.plan.dto.ApplyEventResponse;
import com.lifeorganizer.plan.dto.TodayPlanResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/plan")
public class PlanController {

    private final PlanService planService;
    private final Clock clock;

    public PlanController(PlanService planService, Clock clock) {
        this.planService = planService;
        this.clock = clock;
    }

    @GetMapping("/today")
    public TodayPlanResponse today() {
        return PlanDtoMapper.toResponse(planService.getOrGenerate(LocalDate.now(clock)));
    }

    @PostMapping("/today/events")
    public ApplyEventResponse applyEvent(@RequestBody ApplyEventRequest request) {
        LocalDate today = LocalDate.now(clock);
        PlanEvent event = request.toEvent(today);
        return PlanDtoMapper.toResponse(planService.applyEvent(event, today));
    }
}
```

- [ ] **Step 5: Create `ConfigController` and `GlobalExceptionHandler`**

`backend/src/main/java/com/lifeorganizer/config/ConfigController.java`:

```java
package com.lifeorganizer.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells the UI which optional features exist. Both stay false until the AI and RAG phases are built. */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    public record ConfigResponse(boolean aiEnabled, boolean ragEnabled) {
    }

    @GetMapping
    public ConfigResponse config() {
        return new ConfigResponse(false, false);
    }
}
```

`backend/src/main/java/com/lifeorganizer/config/GlobalExceptionHandler.java`:

```java
package com.lifeorganizer.config;

import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.error.SetupRequiredException;
import com.lifeorganizer.error.UnknownBlockException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns the app's own exceptions into RFC 7807 problem responses. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SetupRequiredException.class)
    public ProblemDetail setupRequired(SetupRequiredException e) {
        return problem(404, "Setup required", e.getMessage());
    }

    @ExceptionHandler(UnknownBlockException.class)
    public ProblemDetail unknownBlock(UnknownBlockException e) {
        return problem(422, "Unknown block", e.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail invalidRequest(InvalidRequestException e) {
        return problem(400, "Invalid request", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail unreadableBody(HttpMessageNotReadableException e) {
        return problem(400, "Malformed request", "The request body is missing or is not valid JSON");
    }

    private static ProblemDetail problem(int status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setTitle(title);
        return problem;
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest='ApplyEventRequestTest,PlanControllerTest,ConfigControllerTest'`
Expected: PASS — 8 + 8 + 1 = 17 tests green.

- [ ] **Step 7: Run the full suite**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS, 175 tests.

- [ ] **Step 8: Commit**

```bash
git add backend
git commit -m "feat: add plan API, config endpoint and RFC 7807 error handling" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 12: `SetupController`

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/setup/api/SetupController.java`
- Test: `backend/src/test/java/com/lifeorganizer/setup/api/SetupControllerTest.java`

**Interfaces:**
- Consumes: `SetupService.parse/load/save` (Task 10), the DTOs, `InvalidRequestException`, `GlobalExceptionHandler` (Task 11).
- Produces:
  - `POST /api/setup/parse` — body `SetupParseRequest{goalsText, constraintsText}` → `200 SetupParseResponse{categories, goals, constraints, warnings}` (no save).
  - `GET /api/setup` → `200 SetupPayload{categories, goals, constraints}` (empty lists when nothing is saved).
  - `PUT /api/setup` — body `SetupPayload` → saves, then returns `200` with the freshly loaded `SetupPayload`. Unknown JSON properties are ignored, so the UI may send the parse response back unchanged. Invalid content → `400` ProblemDetail (`"Invalid request"`).

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/lifeorganizer/setup/api/SetupControllerTest.java`:

```java
package com.lifeorganizer.setup.api;

import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.setup.api.dto.CategoryDto;
import com.lifeorganizer.setup.api.dto.ConstraintDto;
import com.lifeorganizer.setup.api.dto.GoalDto;
import com.lifeorganizer.setup.api.dto.SetupParseRequest;
import com.lifeorganizer.setup.api.dto.SetupParseResponse;
import com.lifeorganizer.setup.api.dto.SetupPayload;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SetupController.class)
class SetupControllerTest {

    private static final CategoryDto HEALTH = new CategoryDto("health", "Health");
    private static final GoalDto GYM = new GoalDto("health-gym", "health", "Gym", 3, null, 60, "MEDIUM", "ANY");
    private static final ConstraintDto WORK = new ConstraintDto("FIXED_BLOCK", "Work", "08:30", "17:00",
        List.of("MONDAY", "TUESDAY"), null, null, null, null, null, null);
    private static final SetupPayload PAYLOAD = new SetupPayload(List.of(HEALTH), List.of(GYM), List.of(WORK));

    private static final String PAYLOAD_JSON = """
        {"categories":[{"id":"health","name":"Health"}],
         "goals":[{"id":"health-gym","categoryId":"health","name":"Gym","priority":3,"deadline":null,
                   "defaultBlockMinutes":60,"energy":"MEDIUM","preferredTime":"ANY"}],
         "constraints":[{"type":"FIXED_BLOCK","label":"Work","start":"08:30","end":"17:00",
                         "days":["MONDAY","TUESDAY"]}]}
        """;

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SetupService setupService;

    @Test
    void parseReturnsTheStructuredSetupAndWarnings() throws Exception {
        when(setupService.parse(new SetupParseRequest("goals text", "constraints text")))
            .thenReturn(new SetupParseResponse(List.of(HEALTH), List.of(GYM), List.of(WORK), List.of("a warning")));

        mvc.perform(post("/api/setup/parse")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goalsText\":\"goals text\",\"constraintsText\":\"constraints text\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.categories[0].id").value("health"))
            .andExpect(jsonPath("$.goals[0].id").value("health-gym"))
            .andExpect(jsonPath("$.goals[0].priority").value(3))
            .andExpect(jsonPath("$.goals[0].energy").value("MEDIUM"))
            .andExpect(jsonPath("$.constraints[0].type").value("FIXED_BLOCK"))
            .andExpect(jsonPath("$.constraints[0].start").value("08:30"))
            .andExpect(jsonPath("$.constraints[0].days[1]").value("TUESDAY"))
            .andExpect(jsonPath("$.warnings[0]").value("a warning"));
    }

    @Test
    void parseOfBrokenTextIsABadRequest() throws Exception {
        when(setupService.parse(any(SetupParseRequest.class)))
            .thenThrow(new InvalidRequestException("Goal 'Orphan' has no parent category"));

        mvc.perform(post("/api/setup/parse")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goalsText\":\"x\",\"constraintsText\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.title").value("Invalid request"))
            .andExpect(jsonPath("$.detail").value("Goal 'Orphan' has no parent category"));
    }

    @Test
    void getReturnsTheStoredSetup() throws Exception {
        when(setupService.load()).thenReturn(PAYLOAD);

        mvc.perform(get("/api/setup"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.categories.length()").value(1))
            .andExpect(jsonPath("$.goals[0].name").value("Gym"))
            .andExpect(jsonPath("$.constraints[0].label").value("Work"));
    }

    @Test
    void putSavesThePayloadAndReturnsTheStoredSetup() throws Exception {
        when(setupService.load()).thenReturn(PAYLOAD);

        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON).content(PAYLOAD_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.goals[0].id").value("health-gym"));

        verify(setupService).save(PAYLOAD);
    }

    @Test
    void putIgnoresUnknownPropertiesSoTheParseResponseCanBeSentBackUnchanged() throws Exception {
        when(setupService.load()).thenReturn(PAYLOAD);
        String parseResponseShape = PAYLOAD_JSON.replaceFirst("\\{", "{\"warnings\":[\"kept?\"],");

        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON).content(parseResponseShape))
            .andExpect(status().isOk());

        verify(setupService).save(PAYLOAD);
    }

    @Test
    void putOfInvalidContentIsABadRequest() throws Exception {
        doThrow(new InvalidRequestException("Duplicate goal id 'x'")).when(setupService).save(any(SetupPayload.class));

        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON).content(PAYLOAD_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Invalid request"))
            .andExpect(jsonPath("$.detail").value("Duplicate goal id 'x'"));
    }

    @Test
    void malformedJsonIsABadRequest() throws Exception {
        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON).content("{not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Malformed request"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=SetupControllerTest`
Expected: FAIL — compilation error, `SetupController` does not exist.

- [ ] **Step 3: Implement `SetupController`**

`backend/src/main/java/com/lifeorganizer/setup/api/SetupController.java`:

```java
package com.lifeorganizer.setup.api;

import com.lifeorganizer.setup.api.dto.SetupParseRequest;
import com.lifeorganizer.setup.api.dto.SetupParseResponse;
import com.lifeorganizer.setup.api.dto.SetupPayload;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private final SetupService setupService;

    public SetupController(SetupService setupService) {
        this.setupService = setupService;
    }

    @PostMapping("/parse")
    public SetupParseResponse parse(@RequestBody SetupParseRequest request) {
        return setupService.parse(request);
    }

    @GetMapping
    public SetupPayload get() {
        return setupService.load();
    }

    @PutMapping
    public SetupPayload put(@RequestBody SetupPayload payload) {
        setupService.save(payload);
        return setupService.load();
    }
}
```

- [ ] **Step 3b: Run the test to verify it passes**

Run: `cd backend && ./mvnw -q test -Dtest=SetupControllerTest`
Expected: PASS — 7 tests green.

- [ ] **Step 4: Run the full suite**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS, 182 tests.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add setup controller (parse, load, save)" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 13: End-to-end API test and README

**Files:**
- Test: `backend/src/test/java/com/lifeorganizer/ApiEndToEndTest.java`
- Create: `README.md` (repo root)

**Interfaces:**
- Consumes: the whole backend (Tasks 1-12) running inside a real Spring context on a real Postgres container, driven through MockMvc.
- Produces: proof of the Phase 1b acceptance criteria — paste → parse → save → today's plan → done/postpone → state survives across requests, an edited setup never rewrites an already-generated day, and every error path returns a `ProblemDetail` — plus the README that tells a human how to run it.

- [ ] **Step 1: Write the end-to-end test**

`backend/src/test/java/com/lifeorganizer/ApiEndToEndTest.java`:

```java
package com.lifeorganizer;

import com.jayway.jsonpath.JsonPath;
import com.lifeorganizer.support.ExampleSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(ApiEndToEndTest.FixedClock.class)
class ApiEndToEndTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    /** "Today" is Monday 2026-09-14 for every test. */
    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("truncate table day_warning, block, plan_constraint, goal, category restart identity");
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static String jsonString(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    /** POST /api/setup/parse with the user's example and return the raw response body. */
    private String parseExample() throws Exception {
        String body = "{\"goalsText\":" + jsonString(ExampleSetup.GOALS_TEXT)
            + ",\"constraintsText\":" + jsonString(ExampleSetup.CONSTRAINTS_TEXT) + "}";
        MvcResult result = mvc.perform(post("/api/setup/parse")
                .contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8").content(body))
            .andExpect(status().isOk())
            .andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** Parses the example and saves it exactly as the UI would: by sending the parse response back. */
    private void saveExampleSetup() throws Exception {
        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON).content(parseExample()))
            .andExpect(status().isOk());
    }

    private String getToday() throws Exception {
        return mvc.perform(get("/api/plan/today"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String postEvent(String eventJson) throws Exception {
        return mvc.perform(post("/api/plan/today/events").contentType(MediaType.APPLICATION_JSON).content(eventJson))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String blockIdByTitle(String todayJson, String title) {
        List<String> ids = JsonPath.read(todayJson, "$.blocks[?(@.title=='" + title + "' && @.status=='PLANNED')].id");
        return ids.get(0);
    }

    // ---- tests --------------------------------------------------------------------------------

    @Test
    void todayIsNotFoundUntilASetupIsSaved() throws Exception {
        mvc.perform(get("/api/plan/today"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.title").value("Setup required"));
    }

    @Test
    void parseSaveAndLoadTheExampleSetup() throws Exception {
        String parsed = parseExample();
        assertThat((Integer) JsonPath.read(parsed, "$.categories.length()")).isEqualTo(6);
        assertThat((Integer) JsonPath.read(parsed, "$.goals.length()")).isEqualTo(9);
        assertThat((Integer) JsonPath.read(parsed, "$.constraints.length()")).isEqualTo(6);
        assertThat((List<?>) JsonPath.read(parsed, "$.warnings")).isEmpty();

        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON).content(parsed))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.categories.length()").value(6));

        mvc.perform(get("/api/setup"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.categories[0].name").value("Career"))
            .andExpect(jsonPath("$.goals.length()").value(9))
            .andExpect(jsonPath("$.constraints[0].label").value("Work"))
            .andExpect(jsonPath("$.constraints[0].start").value("08:30"));
    }

    @Test
    void todayShowsTheExampleDayInOrder() throws Exception {
        saveExampleSetup();

        mvc.perform(get("/api/plan/today"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.date").value("2026-09-14"))
            .andExpect(jsonPath("$.blocks.length()").value(9))
            .andExpect(jsonPath("$.blocks[0].title").value("Gym"))
            .andExpect(jsonPath("$.blocks[0].start").value("07:00"))
            .andExpect(jsonPath("$.blocks[2].title").value("Work"))
            .andExpect(jsonPath("$.blocks[2].kind").value("FIXED"))
            .andExpect(jsonPath("$.blocks[3].title").value("Dinner / break"))
            .andExpect(jsonPath("$.blocks[4].title").value("Claude certification"))
            .andExpect(jsonPath("$.blocks[4].start").value("18:00"))
            .andExpect(jsonPath("$.blocks[6].title").value("Dating / events"))
            .andExpect(jsonPath("$.blocks[6].start").value("20:00"))
            .andExpect(jsonPath("$.blocks[8].title").value("Wind down"))
            .andExpect(jsonPath("$.blocks[8].start").value("22:30"))
            .andExpect(jsonPath("$.warnings.length()").value(0));
    }

    @Test
    void parallelFirstRequestsForTodayAllSucceedAndAgree() throws Exception {
        // React <StrictMode> fires the very first load twice. Without the generation lock the second
        // request dies on a primary-key violation while both try to insert the same blocks.
        saveExampleSetup();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> getToday()));
            }
            List<String> bodies = new ArrayList<>();
            for (Future<String> future : futures) {
                bodies.add(future.get(60, TimeUnit.SECONDS));
            }

            assertThat(bodies).allMatch(body -> body.equals(bodies.get(0)));
            assertThat((Integer) JsonPath.read(bodies.get(0), "$.blocks.length()")).isEqualTo(9);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void doneAndPostponeSurviveAcrossRequests() throws Exception {
        saveExampleSetup();
        String today = getToday();
        String gymId = blockIdByTitle(today, "Gym");
        String certId = blockIdByTitle(today, "Claude certification");
        String workId = blockIdByTitle(today, "Work");

        // mark the gym session done
        String afterDone = postEvent("{\"type\":\"MARK_DONE\",\"blockId\":\"" + gymId + "\"}");
        assertThat((List<?>) JsonPath.read(afterDone, "$.changes")).containsExactly("Marked done Gym");
        assertThat((List<?>) JsonPath.read(afterDone, "$.blocks[?(@.id=='" + gymId + "')].status")).containsExactly("DONE");

        // a fixed block cannot be postponed - the engine says so and nothing changes
        String refused = postEvent("{\"type\":\"POSTPONE_BLOCK\",\"blockId\":\"" + workId + "\"}");
        assertThat((List<?>) JsonPath.read(refused, "$.changes")).containsExactly("Cannot postpone a FIXED block (Work)");

        // postpone the certification block - it moves to the free slot at 21:00
        String afterPostpone = postEvent(
            "{\"type\":\"POSTPONE_BLOCK\",\"blockId\":\"" + certId + "\",\"reason\":\"tired\"}");
        assertThat((List<?>) JsonPath.read(afterPostpone, "$.changes"))
            .containsExactly("Moved Claude certification 18:00 -> 2026-09-14 21:00");

        // a brand-new request reads everything back from the database
        String reloaded = getToday();
        assertThat((List<?>) JsonPath.read(reloaded, "$.blocks[?(@.id=='" + gymId + "')].status")).containsExactly("DONE");
        List<Map<String, Object>> certs = JsonPath.read(reloaded, "$.blocks[?(@.title=='Claude certification')]");
        assertThat(certs).hasSize(2);
        assertThat(certs).extracting(b -> b.get("status")).containsExactlyInAnyOrder("POSTPONED", "PLANNED");
        assertThat(certs).filteredOn(b -> b.get("status").equals("POSTPONED"))
            .extracting(b -> b.get("reason")).containsExactly("tired");
        assertThat(certs).filteredOn(b -> b.get("status").equals("PLANNED"))
            .extracting(b -> b.get("start")).containsExactly("21:00");
    }

    @Test
    void editingTheSetupNeverRewritesAnAlreadyGeneratedDay() throws Exception {
        saveExampleSetup();
        assertThat((Integer) JsonPath.read(getToday(), "$.blocks.length()")).isEqualTo(9);

        String smallSetup = "{\"categories\":[{\"id\":\"health\",\"name\":\"Health\"}],"
            + "\"goals\":[{\"id\":\"health-gym\",\"categoryId\":\"health\",\"name\":\"Gym\",\"priority\":3,"
            + "\"deadline\":null,\"defaultBlockMinutes\":60,\"energy\":\"MEDIUM\",\"preferredTime\":\"ANY\"}],"
            + "\"constraints\":[]}";
        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON).content(smallSetup))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.goals.length()").value(1));

        // the goals were replaced, but today's persisted blocks are untouched
        assertThat((Integer) JsonPath.read(getToday(), "$.blocks.length()")).isEqualTo(9);
    }

    @Test
    void addAdhocTakesOverTheFreeTimeItOverlaps() throws Exception {
        saveExampleSetup();
        getToday();

        String after = postEvent("{\"type\":\"ADD_ADHOC\",\"title\":\"Padel\",\"start\":\"21:00\",\"end\":\"22:00\"}");

        assertThat((List<?>) JsonPath.read(after, "$.changes")).containsExactly("Added Padel at 21:00");
        List<Map<String, Object>> padel = JsonPath.read(after, "$.blocks[?(@.kind=='ADHOC')]");
        assertThat(padel).hasSize(1);
        // Monday's free block 21:00-22:30 lost the hour Padel takes; only 22:00-22:30 is left of it
        // (the morning gap 08:00-08:30 is untouched).
        List<Map<String, Object>> freeBlocks = JsonPath.read(after, "$.blocks[?(@.kind=='FREE')]");
        assertThat(freeBlocks).extracting(b -> b.get("start") + "-" + b.get("end"))
            .containsExactlyInAnyOrder("08:00-08:30", "22:00-22:30");
    }

    @Test
    void errorsAreProblemDetails() throws Exception {
        saveExampleSetup();
        getToday();

        mvc.perform(post("/api/plan/today/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"CANCEL_BLOCK\",\"blockId\":\"does-not-exist\"}"))
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.title").value("Unknown block"));

        mvc.perform(post("/api/plan/today/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"EXPLODE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Invalid request"));

        mvc.perform(put("/api/setup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"categories\":[],\"goals\":[{\"id\":\"x\",\"categoryId\":\"nope\",\"name\":\"X\","
                    + "\"priority\":3,\"defaultBlockMinutes\":60,\"energy\":\"MEDIUM\",\"preferredTime\":\"ANY\"}],"
                    + "\"constraints\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Goal 'x' refers to unknown category 'nope'"));

        mvc.perform(post("/api/plan/today/events").contentType(MediaType.APPLICATION_JSON).content("{oops"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Malformed request"));
    }

    @Test
    void configReportsNoAiOrRagYet() throws Exception {
        mvc.perform(get("/api/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.aiEnabled").value(false))
            .andExpect(jsonPath("$.ragEnabled").value(false));
    }
}
```

- [ ] **Step 2: Run the test to verify it passes**

Run: `cd backend && ./mvnw -q test -Dtest=ApiEndToEndTest`
Expected: PASS — 9 tests green. (All production code already exists, so this test is expected to pass on first run — it is the acceptance test for Tasks 1-12. If a test fails, that is a genuine integration bug between two earlier tasks: fix the production code, not the test's expected values, which were captured from the real engine.)

- [ ] **Step 3: Run the full suite**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS, 191 tests, 0 failures (Docker must be running).

- [ ] **Step 4: Write `README.md`**

`README.md` (repo root):

````markdown
# Life Organizer

A personal day planner. Paste your goals and your constraints once; the app builds today's plan,
and re-plans when the day changes (mark done, postpone, cancel, add an event).

**Status:** the scheduling engine (Phase 1a) and the backend — Postgres persistence and the REST API
(Phase 1b) — are done. The React UI is the next plan. Until then you can drive the whole app with `curl`.

## What you need

- Java 21
- Docker Desktop (running) — for the local database and for the automated tests

## Run it

```bash
# 1. start Postgres (pgvector image, port 5432)
docker compose up -d

# 2. start the backend on http://localhost:8080
cd backend
./mvnw spring-boot:run
```

Stop with `Ctrl+C`; `docker compose down` stops the database (add `-v` to wipe its data).
If port 5432 is already in use, stop your local Postgres or change the port mapping in `docker-compose.yml`
and set `DB_URL` accordingly.

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
| GET | `/api/config` | `{aiEnabled, ragEnabled}` (both false for now) |

Times are `"HH:mm"`, dates `"yyyy-MM-dd"`. Errors are RFC 7807 `application/problem+json`
(`404` no setup yet, `422` unknown block id, `400` invalid request).

## How planning works

- **Goals** get a priority (1-5); each weekly quota (e.g. "Learning: 5h/week") is split across its goals by
  priority, with a boost for goals whose deadline is close.
- A day is **generated the first time it is requested** and then stored. Editing your setup later never
  rewrites a day that was already generated.
- **Postpone** moves a block to a later free slot today, else to the next day with room this week. The
  third postpone flags the block. Fixed blocks and completed blocks are never moved.

## Known limitations

- Editing the setup mid-week can double-count or skip a little quota for the current week (already-done work
  is not yet subtracted from the weekly allocation).
- A postponed block has no notion of "the past" yet (the engine has no clock).
- Cancelling a block frees its slot; it is not automatically refilled with the next-best task.
- `SET_ENERGY` is accepted but does not change the plan yet.

## Tests

```bash
cd backend && ./mvnw test    # needs Docker running; spins up a throwaway Postgres per test class
```

## Design documents

- `docs/superpowers/specs/` — design specs (Phase 1, Phase 1b)
- `docs/superpowers/plans/` — implementation plans
````

- [ ] **Step 5: Manual smoke run against the real database (human step — automated executors skip this)**

`ApiEndToEndTest` already covers this flow automatically. To see it live: run `docker compose up -d`, then `cd backend && ./mvnw spring-boot:run`, and walk through the four `curl` commands in the README. Expected: step 3 returns a JSON plan with `Work`, `Dinner / break`, `Wind down` and your goal blocks; after `MARK_DONE` the block shows `"status":"DONE"`; after restarting the backend, `GET /api/plan/today` still shows it. Stop the app with `Ctrl+C` and `docker compose down`.

- [ ] **Step 6: Commit**

```bash
git add README.md backend
git commit -m "test: add end-to-end API test; docs: add README" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```
