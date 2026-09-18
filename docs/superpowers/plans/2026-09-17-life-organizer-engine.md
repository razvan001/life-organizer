# Life Organizer — Engine (Phase 1a) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the pure-Java domain model, text parsers, and scheduling engine for Life Organizer — no Spring, no database, no HTTP. Fully covered by JUnit tests. This is Phase 1a of the design spec at `docs/superpowers/specs/2026-09-17-life-organizer-design.md`; Phase 1b (persistence, REST API, React UI) is a separate plan that consumes this engine.

**Architecture:** `domain` holds immutable value types (records/sealed interfaces, no behavior beyond validation). `setup` holds two regex-based text parsers that turn the user's pasted goals tree and constraints into domain objects. `engine` holds the scheduling pipeline: `GoalWeighting` (split a category's weekly minutes across its goals by priority) → `WeekAllocator` (turn constraints into daily `Unit`s) → `DayScheduler` (place units into a day's free time, producing a `DayPlan`) → `ConflictDetector` (flag overlaps/unfittable quotas) → `Replanner`/`PostponePolicy` (apply user-triggered changes to an existing week of plans).

**Tech Stack:** Java 21 (records, sealed interfaces, pattern matching in `switch`), Maven, JUnit 5, AssertJ. No Spring annotations anywhere in this plan's code.

## Global Constraints

- Java 21, package root `com.lifeorganizer`.
- `domain`, `setup`, and `engine` packages contain **zero** Spring/framework imports — plain Java only, per spec "Engine (pure functions, injected `Clock`)".
- Scheduling grid is 30 minutes (`EngineSettings.gridMinutes() == 30`); every placed block's duration is a multiple of 30.
- Deadline boost: ×2 if `daysLeft <= 7`, ×1.5 if `daysLeft <= 30`, else ×1 (exact values from spec).
- Deterministic tie-breaking everywhere: when two goals/units have equal score, the one that appears **earlier** in its input list wins — never rely on `HashMap`/`HashSet` iteration order for anything that affects output.
- Every record validates its own invariants in a compact constructor (fail fast, no separate validator classes).
- Tests build fixture dates with `LocalDate.of(2026, 9, 14).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))` (or similar) rather than hardcoding a day-of-week guess for a raw date — this is guaranteed correct regardless of the actual 2026 calendar.

---

### Task 1: Backend scaffold + domain primitives (Slug, Category, Energy, PreferredTime)

**Files:**
- Create: `backend/pom.xml` (generated)
- Create: `backend/src/main/java/com/lifeorganizer/BackendApplication.java` (generated)
- Create: `backend/src/main/java/com/lifeorganizer/domain/Slug.java`
- Create: `backend/src/main/java/com/lifeorganizer/domain/Category.java`
- Create: `backend/src/main/java/com/lifeorganizer/domain/Energy.java`
- Create: `backend/src/main/java/com/lifeorganizer/domain/PreferredTime.java`
- Test: `backend/src/test/java/com/lifeorganizer/domain/SlugTest.java`
- Test: `backend/src/test/java/com/lifeorganizer/domain/CategoryTest.java`

**Interfaces:**
- Produces: `Slug.of(String text) -> String`; `Category(String id, String name)` record with static `Category.of(String name) -> Category`; `enum Energy { LOW, MEDIUM, HIGH }`; `enum PreferredTime { MORNING, EVENING, ANY }`.

- [ ] **Step 1: Generate the Spring Boot project skeleton**

Run from `C:\Users\razva\projects\life-organizer`:

```bash
curl https://start.spring.io/starter.zip \
  -d type=maven-project \
  -d language=java \
  -d javaVersion=21 \
  -d groupId=com.lifeorganizer \
  -d artifactId=backend \
  -d name=backend \
  -d packageName=com.lifeorganizer \
  -d dependencies=web,data-jpa,flyway,validation,postgresql \
  -o backend.zip
```

Then unpack it and remove the zip:

```bash
unzip backend.zip -d backend
rm backend.zip
```

This creates `backend/pom.xml`, `backend/mvnw`, `backend/mvnw.cmd`, `backend/src/main/java/com/lifeorganizer/BackendApplication.java`, and `backend/src/main/resources/application.properties`. Leave all generated files as-is.

- [ ] **Step 2: Verify the scaffold compiles**

Run: `cd backend && ./mvnw -q compile`
Expected: no output, exit code 0 (BUILD SUCCESS). If `./mvnw` is not executable on Windows, use `cmd /c mvnw.cmd -q compile` instead.

- [ ] **Step 3: Write the failing test for Slug**

Create `backend/src/test/java/com/lifeorganizer/domain/SlugTest.java`:

```java
package com.lifeorganizer.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SlugTest {

    @Test
    void lowercasesAndDashesSpaces() {
        assertThat(Slug.of("Claude certification")).isEqualTo("claude-certification");
    }

    @Test
    void collapsesNonAlphanumericRuns() {
        assertThat(Slug.of("Padel / football")).isEqualTo("padel-football");
    }

    @Test
    void trimsLeadingAndTrailingDashes() {
        assertThat(Slug.of("  RAG showcase!  ")).isEqualTo("rag-showcase");
    }

    @Test
    void fallsBackToItemWhenNothingAlphanumeric() {
        assertThat(Slug.of("///")).isEqualTo("item");
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=SlugTest`
Expected: FAIL — compilation error, `Slug` does not exist.

- [ ] **Step 5: Implement Slug**

Create `backend/src/main/java/com/lifeorganizer/domain/Slug.java`:

```java
package com.lifeorganizer.domain;

public final class Slug {

    private Slug() {}

    public static String of(String text) {
        String slug = text.toLowerCase().trim()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+|-+$)", "");
        return slug.isEmpty() ? "item" : slug;
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd backend && ./mvnw -q test -Dtest=SlugTest`
Expected: PASS, 4 tests green.

- [ ] **Step 7: Write the failing test for Category**

Create `backend/src/test/java/com/lifeorganizer/domain/CategoryTest.java`:

```java
package com.lifeorganizer.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CategoryTest {

    @Test
    void ofDerivesSlugId() {
        Category c = Category.of("Career");
        assertThat(c.id()).isEqualTo("career");
        assertThat(c.name()).isEqualTo("Career");
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> new Category("x", " "))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 8: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=CategoryTest`
Expected: FAIL — `Category` does not exist.

- [ ] **Step 9: Implement Category, Energy, PreferredTime**

Create `backend/src/main/java/com/lifeorganizer/domain/Category.java`:

```java
package com.lifeorganizer.domain;

import java.util.Objects;

public record Category(String id, String name) {

    public Category {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Category name must not be blank");
        }
    }

    public static Category of(String name) {
        return new Category(Slug.of(name), name);
    }
}
```

Create `backend/src/main/java/com/lifeorganizer/domain/Energy.java`:

```java
package com.lifeorganizer.domain;

public enum Energy { LOW, MEDIUM, HIGH }
```

Create `backend/src/main/java/com/lifeorganizer/domain/PreferredTime.java`:

```java
package com.lifeorganizer.domain;

public enum PreferredTime { MORNING, EVENING, ANY }
```

- [ ] **Step 10: Run the tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=SlugTest,CategoryTest`
Expected: PASS, 6 tests green.

- [ ] **Step 11: Commit**

```bash
git add backend .gitignore
git commit -m "feat: scaffold backend and add Slug/Category/Energy/PreferredTime" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: Goal, GoalTree, Constraint types, EngineSettings

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/domain/Goal.java`
- Create: `backend/src/main/java/com/lifeorganizer/domain/GoalTree.java`
- Create: `backend/src/main/java/com/lifeorganizer/domain/Constraint.java`
- Create: `backend/src/main/java/com/lifeorganizer/domain/FixedBlock.java`
- Create: `backend/src/main/java/com/lifeorganizer/domain/FrequencyQuota.java`
- Create: `backend/src/main/java/com/lifeorganizer/domain/DurationQuota.java`
- Create: `backend/src/main/java/com/lifeorganizer/domain/EveningQuota.java`
- Create: `backend/src/main/java/com/lifeorganizer/domain/EngineSettings.java`
- Test: `backend/src/test/java/com/lifeorganizer/domain/GoalTest.java`
- Test: `backend/src/test/java/com/lifeorganizer/domain/GoalTreeTest.java`

**Interfaces:**
- Consumes: `Category` (Task 1).
- Produces: `Goal(String id, Category category, String name, int priority, LocalDate deadline, int defaultBlockMinutes, Energy energy, PreferredTime preferredTime)` + `Goal.withDefaults(String id, Category category, String name)`; `GoalTree(List<Category> categories, List<Goal> goals)` + `goalsIn(Category)`; sealed `Constraint` with `FixedBlock`, `FrequencyQuota`, `DurationQuota`, `EveningQuota`; `EngineSettings(int dinnerMinutesAfterWork, int windDownMinutesBeforeSleep, int minBlockMinutes, int gridMinutes, LocalTime eveningAnchor)` + `EngineSettings.defaults()` (`eveningAnchor` defaults to 20:00 — the fixed clock time evening-quota units such as Social anchor to, per Task 6/7).

- [ ] **Step 1: Write the failing tests for Goal**

Create `backend/src/test/java/com/lifeorganizer/domain/GoalTest.java`:

```java
package com.lifeorganizer.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoalTest {

    @Test
    void withDefaultsUsesPriorityThreeAndMediumEnergy() {
        Category career = Category.of("Career");
        Goal goal = Goal.withDefaults("career-claude-certification", career, "Claude certification");

        assertThat(goal.priority()).isEqualTo(3);
        assertThat(goal.deadline()).isNull();
        assertThat(goal.defaultBlockMinutes()).isEqualTo(60);
        assertThat(goal.energy()).isEqualTo(Energy.MEDIUM);
        assertThat(goal.preferredTime()).isEqualTo(PreferredTime.ANY);
    }

    @Test
    void rejectsPriorityOutOfRange() {
        Category career = Category.of("Career");
        assertThatThrownBy(() -> new Goal("id", career, "name", 6, null, 60, Energy.MEDIUM, PreferredTime.ANY))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=GoalTest`
Expected: FAIL — `Goal` does not exist.

- [ ] **Step 3: Implement Goal**

Create `backend/src/main/java/com/lifeorganizer/domain/Goal.java`:

```java
package com.lifeorganizer.domain;

import java.time.LocalDate;
import java.util.Objects;

public record Goal(
    String id,
    Category category,
    String name,
    int priority,
    LocalDate deadline,
    int defaultBlockMinutes,
    Energy energy,
    PreferredTime preferredTime
) {

    public Goal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(energy, "energy");
        Objects.requireNonNull(preferredTime, "preferredTime");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Goal name must not be blank");
        }
        if (priority < 1 || priority > 5) {
            throw new IllegalArgumentException("priority must be between 1 and 5, was " + priority);
        }
        if (defaultBlockMinutes <= 0) {
            throw new IllegalArgumentException("defaultBlockMinutes must be positive");
        }
    }

    public static Goal withDefaults(String id, Category category, String name) {
        return new Goal(id, category, name, 3, null, 60, Energy.MEDIUM, PreferredTime.ANY);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw -q test -Dtest=GoalTest`
Expected: PASS, 2 tests green.

- [ ] **Step 5: Write the failing test for GoalTree**

Create `backend/src/test/java/com/lifeorganizer/domain/GoalTreeTest.java`:

```java
package com.lifeorganizer.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GoalTreeTest {

    @Test
    void goalsInFiltersByCategory() {
        Category career = Category.of("Career");
        Category health = Category.of("Health");
        Goal cert = Goal.withDefaults("career-cert", career, "Claude certification");
        Goal gym = Goal.withDefaults("health-gym", health, "Gym");
        GoalTree tree = new GoalTree(List.of(career, health), List.of(cert, gym));

        assertThat(tree.goalsIn(career)).containsExactly(cert);
        assertThat(tree.goalsIn(health)).containsExactly(gym);
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=GoalTreeTest`
Expected: FAIL — `GoalTree` does not exist.

- [ ] **Step 7: Implement GoalTree, Constraint hierarchy, EngineSettings**

Create `backend/src/main/java/com/lifeorganizer/domain/GoalTree.java`:

```java
package com.lifeorganizer.domain;

import java.util.List;
import java.util.Objects;

public record GoalTree(List<Category> categories, List<Goal> goals) {

    public GoalTree {
        Objects.requireNonNull(categories, "categories");
        Objects.requireNonNull(goals, "goals");
        categories = List.copyOf(categories);
        goals = List.copyOf(goals);
    }

    public List<Goal> goalsIn(Category category) {
        return goals.stream().filter(g -> g.category().equals(category)).toList();
    }
}
```

*(Amended after Task 2's review: the original draft omitted the compact constructor, breaking the Global Constraint that every record validates its own invariants — fixed per human decision to null-check and defensively copy both lists.)*

Create `backend/src/main/java/com/lifeorganizer/domain/Constraint.java`:

```java
package com.lifeorganizer.domain;

public sealed interface Constraint
    permits FixedBlock, FrequencyQuota, DurationQuota, EveningQuota {

    String label();
}
```

Create `backend/src/main/java/com/lifeorganizer/domain/FixedBlock.java`:

```java
package com.lifeorganizer.domain;

import java.time.LocalTime;
import java.time.DayOfWeek;
import java.util.Set;
import java.util.Objects;

public record FixedBlock(String label, LocalTime start, LocalTime end, Set<DayOfWeek> days) implements Constraint {

    public FixedBlock {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (days == null || days.isEmpty()) {
            throw new IllegalArgumentException("days must not be empty");
        }
    }

    /** True for overnight ranges such as Sleep 23:30-07:00, where end is numerically before start. */
    public boolean isOvernight() {
        return end.isBefore(start);
    }
}
```

Create `backend/src/main/java/com/lifeorganizer/domain/FrequencyQuota.java`:

```java
package com.lifeorganizer.domain;

import java.util.Objects;

public record FrequencyQuota(String label, int timesPerWeek, int sessionMinutes, String goalId) implements Constraint {

    public FrequencyQuota {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(goalId, "goalId");
        if (timesPerWeek <= 0) throw new IllegalArgumentException("timesPerWeek must be positive");
        if (sessionMinutes <= 0) throw new IllegalArgumentException("sessionMinutes must be positive");
    }
}
```

Create `backend/src/main/java/com/lifeorganizer/domain/DurationQuota.java`:

```java
package com.lifeorganizer.domain;

import java.util.Objects;

public record DurationQuota(String label, int minutesPerWeek, String categoryId) implements Constraint {

    public DurationQuota {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(categoryId, "categoryId");
        if (minutesPerWeek <= 0) throw new IllegalArgumentException("minutesPerWeek must be positive");
    }
}
```

Create `backend/src/main/java/com/lifeorganizer/domain/EveningQuota.java`:

```java
package com.lifeorganizer.domain;

import java.util.Objects;

public record EveningQuota(String label, int eveningsPerWeek, String categoryId) implements Constraint {

    public EveningQuota {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(categoryId, "categoryId");
        if (eveningsPerWeek <= 0) throw new IllegalArgumentException("eveningsPerWeek must be positive");
    }
}
```

Create `backend/src/main/java/com/lifeorganizer/domain/EngineSettings.java`:

```java
package com.lifeorganizer.domain;

import java.time.LocalTime;
import java.util.Objects;

public record EngineSettings(
    int dinnerMinutesAfterWork,
    int windDownMinutesBeforeSleep,
    int minBlockMinutes,
    int gridMinutes,
    LocalTime eveningAnchor
) {

    public EngineSettings {
        Objects.requireNonNull(eveningAnchor, "eveningAnchor");
        if (dinnerMinutesAfterWork < 0 || windDownMinutesBeforeSleep < 0) {
            throw new IllegalArgumentException("routine offsets must not be negative");
        }
        if (minBlockMinutes <= 0 || gridMinutes <= 0) {
            throw new IllegalArgumentException("minBlockMinutes and gridMinutes must be positive");
        }
        if (minBlockMinutes % gridMinutes != 0) {
            throw new IllegalArgumentException("minBlockMinutes must be a multiple of gridMinutes");
        }
    }

    public static EngineSettings defaults() {
        return new EngineSettings(60, 60, 30, 30, LocalTime.of(20, 0));
    }
}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=GoalTest,GoalTreeTest`
Expected: PASS, 3 tests green.

- [ ] **Step 9: Commit**

```bash
git add backend
git commit -m "feat: add Goal, GoalTree, Constraint hierarchy, EngineSettings" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: GoalTreeParser

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/setup/GoalTreeParser.java`
- Test: `backend/src/test/java/com/lifeorganizer/setup/GoalTreeParserTest.java`

**Interfaces:**
- Consumes: `Category.of`, `Goal.withDefaults`, `GoalTree` (Task 1, 2).
- Produces: `new GoalTreeParser().parse(String text) -> GoalTree`. Goal ids are `category.id() + "-" + Slug.of(goalName)`.

- [ ] **Step 1: Write the failing test with the user's exact goal tree**

Create `backend/src/test/java/com/lifeorganizer/setup/GoalTreeParserTest.java`:

```java
package com.lifeorganizer.setup;

import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.GoalTree;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GoalTreeParserTest {

    private static final String USER_GOAL_TREE = """
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

    @Test
    void parsesSixCategoriesInOrder() {
        GoalTree tree = new GoalTreeParser().parse(USER_GOAL_TREE);

        assertThat(tree.categories()).extracting(Category::name)
            .containsExactly("Career", "Projects", "Health", "Social", "Finance", "Hobbies");
    }

    @Test
    void parsesGoalsUnderCareerInOrder() {
        GoalTree tree = new GoalTreeParser().parse(USER_GOAL_TREE);
        Category career = tree.categories().get(0);

        assertThat(tree.goalsIn(career)).extracting(Goal::name)
            .containsExactly("Claude certification", "Azure AI", "System design");
    }

    @Test
    void derivesIdsFromCategoryAndSlug() {
        GoalTree tree = new GoalTreeParser().parse(USER_GOAL_TREE);

        assertThat(tree.goals()).extracting(Goal::id)
            .contains("health-gym", "career-claude-certification", "projects-rag-showcase");
    }

    @Test
    void parsesAllNineGoalsWithDefaults() {
        GoalTree tree = new GoalTreeParser().parse(USER_GOAL_TREE);

        assertThat(tree.goals()).hasSize(9);
        assertThat(tree.goals()).allMatch(g -> g.priority() == 3);
    }

    @Test
    void handlesPlainIndentTreeToo() {
        String indentTree = """
            Goals
            - Health
              - Gym
              - Padel
            """;

        GoalTree tree = new GoalTreeParser().parse(indentTree);

        assertThat(tree.categories()).extracting(Category::name).containsExactly("Health");
        assertThat(tree.goals()).extracting(Goal::name).containsExactly("Gym", "Padel");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=GoalTreeParserTest`
Expected: FAIL — `GoalTreeParser` does not exist.

- [ ] **Step 3: Implement GoalTreeParser**

Create `backend/src/main/java/com/lifeorganizer/setup/GoalTreeParser.java`:

```java
package com.lifeorganizer.setup;

import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.GoalTree;
import com.lifeorganizer.domain.Slug;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a goals tree written with box-drawing characters (├──/└──/│) or a plain
 * "- " / two-space indent tree. Depth is measured in 4-character indent groups:
 * depth 1 = category, depth 2 = goal. Deeper levels are ignored in Phase 1.
 */
public final class GoalTreeParser {

    private static final Pattern LINE = Pattern.compile("^((?:[│├└─\\-\\s]{2}){0,}?)([│├└─][─\\s]|[-]\\s)?\\s*(.*)$");
    private static final Pattern INDENT_GROUP = Pattern.compile("[│├└─\\s]{4}|-\\s{2}|\\s{2}-\\s");

    public GoalTree parse(String text) {
        List<Category> categories = new ArrayList<>();
        List<Goal> goals = new ArrayList<>();
        Category currentCategory = null;

        for (String rawLine : text.split("\\R")) {
            if (rawLine.isBlank()) {
                continue;
            }
            ParsedLine parsed = parseLine(rawLine);
            if (parsed.label.isEmpty()) {
                continue;
            }
            if (parsed.depth <= 0) {
                continue; // root "Goals" line
            }
            if (parsed.depth == 1) {
                currentCategory = Category.of(parsed.label);
                categories.add(currentCategory);
            } else if (parsed.depth == 2) {
                if (currentCategory == null) {
                    throw new IllegalArgumentException("Goal '" + parsed.label + "' has no parent category");
                }
                String goalId = currentCategory.id() + "-" + Slug.of(parsed.label);
                goals.add(Goal.withDefaults(goalId, currentCategory, parsed.label));
            }
            // depth > 2 ignored in Phase 1
        }
        return new GoalTree(categories, goals);
    }

    private record ParsedLine(int depth, String label) {}

    private ParsedLine parseLine(String rawLine) {
        // Strip a leading dash-based marker ("- " or "  - ") first, since it is a
        // simpler, unambiguous alternative to the box-drawing tree.
        String line = rawLine;
        int dashDepth = 0;
        Matcher dashIndent = Pattern.compile("^(\\s*)- (.*)$").matcher(line);
        if (dashIndent.matches()) {
            int spaces = dashIndent.group(1).length();
            dashDepth = spaces / 2 + 1;
            return new ParsedLine(dashDepth, dashIndent.group(2).trim());
        }

        // Box-drawing tree: count 4-character indent/connector groups from the left.
        Matcher m = Pattern.compile("^((?:[│├└─\\s]{4})*)(.*)$").matcher(line);
        if (m.matches()) {
            int depth = m.group(1).length() / 4;
            return new ParsedLine(depth, m.group(2).trim());
        }
        return new ParsedLine(0, line.trim());
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw -q test -Dtest=GoalTreeParserTest`
Expected: PASS, 5 tests green. If `handlesPlainIndentTreeToo` fails on depth, adjust `dashDepth` computation (2-space indent per level) until it passes — the box-drawing branch must not change.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: parse goals tree from box-drawing or indent text" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 4: ConstraintParser

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/setup/ConstraintParseResult.java`
- Create: `backend/src/main/java/com/lifeorganizer/setup/ConstraintParser.java`
- Test: `backend/src/test/java/com/lifeorganizer/setup/ConstraintParserTest.java`

**Interfaces:**
- Consumes: `GoalTree`, `Category`, `Goal`, `FixedBlock`, `FrequencyQuota`, `DurationQuota`, `EveningQuota` (Task 2), `GoalTreeParser` (Task 3, test only).
- Produces: `ConstraintParseResult(List<Constraint> constraints, List<String> warnings)`; `new ConstraintParser().parse(String text, GoalTree tree) -> ConstraintParseResult`.

- [ ] **Step 1: Write the failing test with the user's exact constraints**

Create `backend/src/test/java/com/lifeorganizer/setup/ConstraintParserTest.java`:

```java
package com.lifeorganizer.setup;

import com.lifeorganizer.domain.*;
import org.junit.jupiter.api.Test;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import static org.assertj.core.api.Assertions.assertThat;

class ConstraintParserTest {

    private static final String USER_GOAL_TREE = """
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

    private static final String USER_CONSTRAINTS = """
        Work:       08:30-17:00
        Gym:        4x/week
        Sleep:      23:30-07:00
        Learning:   5h/week
        Side project: 4h/week
        Social:     2 evenings/week
        """;

    private final GoalTree tree = new GoalTreeParser().parse(USER_GOAL_TREE);

    @Test
    void parsesWorkAsWeekdayFixedBlock() {
        ConstraintParseResult result = new ConstraintParser().parse(USER_CONSTRAINTS, tree);

        FixedBlock work = (FixedBlock) result.constraints().stream()
            .filter(c -> c.label().equals("Work")).findFirst().orElseThrow();
        assertThat(work.start()).isEqualTo(LocalTime.of(8, 30));
        assertThat(work.end()).isEqualTo(LocalTime.of(17, 0));
        assertThat(work.days()).isEqualTo(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
    }

    @Test
    void parsesSleepAsDailyOvernightFixedBlock() {
        ConstraintParseResult result = new ConstraintParser().parse(USER_CONSTRAINTS, tree);

        FixedBlock sleep = (FixedBlock) result.constraints().stream()
            .filter(c -> c.label().equals("Sleep")).findFirst().orElseThrow();
        assertThat(sleep.start()).isEqualTo(LocalTime.of(23, 30));
        assertThat(sleep.end()).isEqualTo(LocalTime.of(7, 0));
        assertThat(sleep.isOvernight()).isTrue();
        assertThat(sleep.days()).isEqualTo(EnumSet.allOf(DayOfWeek.class));
    }

    @Test
    void parsesGymAsFrequencyQuotaOnTheGymGoal() {
        ConstraintParseResult result = new ConstraintParser().parse(USER_CONSTRAINTS, tree);

        FrequencyQuota gym = (FrequencyQuota) result.constraints().stream()
            .filter(c -> c.label().equals("Gym")).findFirst().orElseThrow();
        assertThat(gym.timesPerWeek()).isEqualTo(4);
        assertThat(gym.sessionMinutes()).isEqualTo(60);
        assertThat(gym.goalId()).isEqualTo("health-gym");
    }

    @Test
    void parsesLearningAsDurationQuotaOnCareer() {
        ConstraintParseResult result = new ConstraintParser().parse(USER_CONSTRAINTS, tree);

        DurationQuota learning = (DurationQuota) result.constraints().stream()
            .filter(c -> c.label().equals("Learning")).findFirst().orElseThrow();
        assertThat(learning.minutesPerWeek()).isEqualTo(300);
        assertThat(learning.categoryId()).isEqualTo("career");
    }

    @Test
    void parsesSideProjectAsDurationQuotaOnProjects() {
        ConstraintParseResult result = new ConstraintParser().parse(USER_CONSTRAINTS, tree);

        DurationQuota sideProject = (DurationQuota) result.constraints().stream()
            .filter(c -> c.label().equals("Side project")).findFirst().orElseThrow();
        assertThat(sideProject.minutesPerWeek()).isEqualTo(240);
        assertThat(sideProject.categoryId()).isEqualTo("projects");
    }

    @Test
    void parsesSocialAsEveningQuota() {
        ConstraintParseResult result = new ConstraintParser().parse(USER_CONSTRAINTS, tree);

        EveningQuota social = (EveningQuota) result.constraints().stream()
            .filter(c -> c.label().equals("Social")).findFirst().orElseThrow();
        assertThat(social.eveningsPerWeek()).isEqualTo(2);
        assertThat(social.categoryId()).isEqualTo("social");
    }

    @Test
    void producesNoWarningsForWellFormedInput() {
        ConstraintParseResult result = new ConstraintParser().parse(USER_CONSTRAINTS, tree);

        assertThat(result.warnings()).isEmpty();
        assertThat(result.constraints()).hasSize(6);
    }

    @Test
    void warnsOnUnrecognizedLabel() {
        ConstraintParseResult result = new ConstraintParser().parse("Meditation: 3x/week", tree);

        assertThat(result.constraints()).isEmpty();
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).contains("Meditation");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=ConstraintParserTest`
Expected: FAIL — `ConstraintParser` does not exist.

- [ ] **Step 3: Implement ConstraintParseResult and ConstraintParser**

Create `backend/src/main/java/com/lifeorganizer/setup/ConstraintParseResult.java`:

```java
package com.lifeorganizer.setup;

import com.lifeorganizer.domain.Constraint;
import java.util.List;

public record ConstraintParseResult(List<Constraint> constraints, List<String> warnings) {}
```

Create `backend/src/main/java/com/lifeorganizer/setup/ConstraintParser.java`:

```java
package com.lifeorganizer.setup;

import com.lifeorganizer.domain.*;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses one "Label: value" constraint per line into a FixedBlock, FrequencyQuota,
 * DurationQuota, or EveningQuota, resolved against an already-parsed GoalTree.
 */
public final class ConstraintParser {

    private static final Pattern LINE = Pattern.compile("^([^:]+):\\s*(.+)$");
    private static final Pattern TIME_RANGE =
        Pattern.compile("(\\d{1,2}:\\d{2})\\s*[\\u2013-]\\s*(\\d{1,2}:\\d{2})");
    private static final Pattern FREQUENCY = Pattern.compile("(\\d+)\\s*x\\s*/\\s*week", Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION =
        Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*h\\s*/\\s*week", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVENINGS =
        Pattern.compile("(\\d+)\\s*evenings?\\s*/\\s*week", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> CATEGORY_ALIASES = Map.of(
        "learning", "career",
        "side project", "projects"
    );

    public ConstraintParseResult parse(String text, GoalTree tree) {
        List<Constraint> constraints = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (String rawLine : text.split("\\R")) {
            if (rawLine.isBlank()) {
                continue;
            }
            Matcher lineMatcher = LINE.matcher(rawLine.trim());
            if (!lineMatcher.matches()) {
                warnings.add("Unrecognized line: " + rawLine.trim());
                continue;
            }
            String label = lineMatcher.group(1).trim();
            String value = lineMatcher.group(2).trim();
            parseValue(label, value, tree, constraints, warnings);
        }
        return new ConstraintParseResult(constraints, warnings);
    }

    private void parseValue(String label, String value, GoalTree tree,
                             List<Constraint> constraints, List<String> warnings) {
        Matcher time = TIME_RANGE.matcher(value);
        if (time.find()) {
            LocalTime start = LocalTime.parse(pad(time.group(1)));
            LocalTime end = LocalTime.parse(pad(time.group(2)));
            Set<DayOfWeek> days = label.equalsIgnoreCase("work")
                ? EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)
                : EnumSet.allOf(DayOfWeek.class);
            constraints.add(new FixedBlock(label, start, end, days));
            return;
        }

        Matcher freq = FREQUENCY.matcher(value);
        if (freq.find()) {
            Goal goal = resolveGoal(label, tree);
            if (goal == null) {
                warnings.add("No goal found for frequency constraint: " + label);
                return;
            }
            constraints.add(new FrequencyQuota(label, Integer.parseInt(freq.group(1)), goal.defaultBlockMinutes(), goal.id()));
            return;
        }

        Matcher dur = DURATION.matcher(value);
        if (dur.find()) {
            Category category = resolveCategory(label, tree);
            if (category == null) {
                warnings.add("No category found for duration constraint: " + label);
                return;
            }
            int minutes = (int) Math.round(Double.parseDouble(dur.group(1)) * 60);
            constraints.add(new DurationQuota(label, minutes, category.id()));
            return;
        }

        Matcher eve = EVENINGS.matcher(value);
        if (eve.find()) {
            Category category = resolveCategory(label, tree);
            if (category == null) {
                warnings.add("No category found for evening constraint: " + label);
                return;
            }
            constraints.add(new EveningQuota(label, Integer.parseInt(eve.group(1)), category.id()));
            return;
        }

        warnings.add("Unrecognized value for '" + label + "': " + value);
    }

    private static String pad(String hhmm) {
        String[] parts = hhmm.split(":");
        return String.format("%02d:%s", Integer.parseInt(parts[0]), parts[1]);
    }

    private Category resolveCategory(String label, GoalTree tree) {
        for (Category c : tree.categories()) {
            if (c.name().equalsIgnoreCase(label)) {
                return c;
            }
        }
        String aliasId = CATEGORY_ALIASES.get(label.toLowerCase());
        if (aliasId != null) {
            for (Category c : tree.categories()) {
                if (c.id().equals(aliasId)) {
                    return c;
                }
            }
        }
        return null;
    }

    private Goal resolveGoal(String label, GoalTree tree) {
        for (Goal g : tree.goals()) {
            if (g.name().equalsIgnoreCase(label)) {
                return g;
            }
        }
        for (Goal g : tree.goals()) {
            if (g.name().toLowerCase().contains(label.toLowerCase())) {
                return g;
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw -q test -Dtest=ConstraintParserTest`
Expected: PASS, 8 tests green.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: parse constraints text into typed quotas and fixed blocks" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 5: GoalWeighting

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/engine/GoalAllocation.java`
- Create: `backend/src/main/java/com/lifeorganizer/engine/GoalWeighting.java`
- Test: `backend/src/test/java/com/lifeorganizer/engine/GoalWeightingTest.java`

**Interfaces:**
- Consumes: `Goal`, `EngineSettings` (Task 2).
- Produces: `GoalAllocation(Goal goal, int minutesPerWeek)`; `new GoalWeighting().allocate(List<Goal> goalsInCategory, int categoryMinutesPerWeek, LocalDate today, int gridMinutes) -> List<GoalAllocation>`, one entry per input goal, same order as input, minutes always a multiple of `gridMinutes` and summing to `categoryMinutesPerWeek` (rounded down to the grid).

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/lifeorganizer/engine/GoalWeightingTest.java`:

```java
package com.lifeorganizer.engine;

import com.lifeorganizer.domain.Category;
import com.lifeorganizer.domain.Goal;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GoalWeightingTest {

    private final Category career = Category.of("Career");
    private final LocalDate today = LocalDate.of(2026, 9, 14);

    @Test
    void splitsEvenlyWhenAllPrioritiesEqual() {
        Goal a = Goal.withDefaults("a", career, "A");
        Goal b = Goal.withDefaults("b", career, "B");

        List<GoalAllocation> result = new GoalWeighting().allocate(List.of(a, b), 300, today, 30);

        assertThat(result).extracting(GoalAllocation::minutesPerWeek).containsExactly(150, 150);
    }

    @Test
    void higherPriorityGetsMoreMinutes() {
        Goal high = new Goal("high", career, "High", 5, null, 60, com.lifeorganizer.domain.Energy.MEDIUM, com.lifeorganizer.domain.PreferredTime.ANY);
        Goal low = new Goal("low", career, "Low", 1, null, 60, com.lifeorganizer.domain.Energy.MEDIUM, com.lifeorganizer.domain.PreferredTime.ANY);

        List<GoalAllocation> result = new GoalWeighting().allocate(List.of(high, low), 360, today, 30);

        assertThat(result.get(0).minutesPerWeek()).isGreaterThan(result.get(1).minutesPerWeek());
        assertThat(result.get(0).minutesPerWeek() + result.get(1).minutesPerWeek()).isEqualTo(360);
    }

    @Test
    void nearDeadlineGoalGetsDoubleBoost() {
        Goal urgent = new Goal("urgent", career, "Urgent", 3, today.plusDays(5), 60, com.lifeorganizer.domain.Energy.MEDIUM, com.lifeorganizer.domain.PreferredTime.ANY);
        Goal normal = new Goal("normal", career, "Normal", 3, null, 60, com.lifeorganizer.domain.Energy.MEDIUM, com.lifeorganizer.domain.PreferredTime.ANY);

        List<GoalAllocation> result = new GoalWeighting().allocate(List.of(urgent, normal), 300, today, 30);

        // weight urgent=3*2=6, normal=3*1=3, total=9, 10 grid units of 30m:
        // raw 6.67/3.33 -> floor 6/3 (sum 9, remainder 1) -> remainder to urgent (larger fraction) -> 7/3 units.
        assertThat(result.get(0).minutesPerWeek()).isEqualTo(210);
        assertThat(result.get(1).minutesPerWeek()).isEqualTo(90);
    }

    @Test
    void allocationsAreMultiplesOfTheGrid() {
        Goal a = Goal.withDefaults("a", career, "A");
        Goal b = Goal.withDefaults("b", career, "B");
        Goal c = Goal.withDefaults("c", career, "C");

        List<GoalAllocation> result = new GoalWeighting().allocate(List.of(a, b, c), 300, today, 30);

        assertThat(result).allMatch(r -> r.minutesPerWeek() % 30 == 0);
        assertThat(result.stream().mapToInt(GoalAllocation::minutesPerWeek).sum()).isEqualTo(300);
    }

    @Test
    void emptyGoalListReturnsEmptyAllocation() {
        assertThat(new GoalWeighting().allocate(List.of(), 300, today, 30)).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=GoalWeightingTest`
Expected: FAIL — `GoalAllocation`/`GoalWeighting` do not exist.

- [ ] **Step 3: Implement GoalAllocation and GoalWeighting**

Create `backend/src/main/java/com/lifeorganizer/engine/GoalAllocation.java`:

```java
package com.lifeorganizer.engine;

import com.lifeorganizer.domain.Goal;

public record GoalAllocation(Goal goal, int minutesPerWeek) {}
```

Create `backend/src/main/java/com/lifeorganizer/engine/GoalWeighting.java`:

```java
package com.lifeorganizer.engine;

import com.lifeorganizer.domain.Goal;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Splits a category's weekly minute budget across its goals, weighted by
 * priority (1-5) with a boost for goals whose deadline is close. Ties (equal
 * weight) are broken by input order, never by hash-based iteration.
 */
public final class GoalWeighting {

    public List<GoalAllocation> allocate(List<Goal> goalsInCategory, int categoryMinutesPerWeek,
                                          LocalDate today, int gridMinutes) {
        if (goalsInCategory.isEmpty() || categoryMinutesPerWeek <= 0) {
            return List.of();
        }

        int goalCount = goalsInCategory.size();
        double[] weights = new double[goalCount];
        double totalWeight = 0;
        for (int i = 0; i < goalCount; i++) {
            weights[i] = weightOf(goalsInCategory.get(i), today);
            totalWeight += weights[i];
        }

        int totalUnits = categoryMinutesPerWeek / gridMinutes;
        double[] rawUnits = new double[goalCount];
        int[] flooredUnits = new int[goalCount];
        int flooredTotal = 0;
        for (int i = 0; i < goalCount; i++) {
            rawUnits[i] = totalUnits * (weights[i] / totalWeight);
            flooredUnits[i] = (int) Math.floor(rawUnits[i]);
            flooredTotal += flooredUnits[i];
        }

        int remainder = totalUnits - flooredTotal;
        List<Integer> byFractionDesc = IntStream.range(0, goalCount).boxed()
            .sorted((a, b) -> Double.compare(rawUnits[b] - flooredUnits[b], rawUnits[a] - flooredUnits[a]))
            .toList();
        for (int i = 0; i < remainder; i++) {
            flooredUnits[byFractionDesc.get(i)]++;
        }

        List<GoalAllocation> result = new ArrayList<>();
        for (int i = 0; i < goalCount; i++) {
            result.add(new GoalAllocation(goalsInCategory.get(i), flooredUnits[i] * gridMinutes));
        }
        return result;
    }

    private double weightOf(Goal goal, LocalDate today) {
        double boost = 1.0;
        if (goal.deadline() != null) {
            long daysLeft = ChronoUnit.DAYS.between(today, goal.deadline());
            if (daysLeft <= 7) {
                boost = 2.0;
            } else if (daysLeft <= 30) {
                boost = 1.5;
            }
        }
        return goal.priority() * boost;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw -q test -Dtest=GoalWeightingTest`
Expected: PASS, 5 tests green.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add priority-weighted GoalWeighting with deadline boost" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 6: Block/DayPlan domain types + WeekAllocator

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/domain/BlockKind.java`
- Create: `backend/src/main/java/com/lifeorganizer/domain/BlockStatus.java`
- Create: `backend/src/main/java/com/lifeorganizer/domain/Block.java`
- Create: `backend/src/main/java/com/lifeorganizer/domain/DayPlan.java`
- Create: `backend/src/main/java/com/lifeorganizer/engine/Unit.java`
- Create: `backend/src/main/java/com/lifeorganizer/engine/WeekPlan.java`
- Create: `backend/src/main/java/com/lifeorganizer/engine/WeekAllocator.java`
- Test: `backend/src/test/java/com/lifeorganizer/engine/WeekAllocatorTest.java`

**Interfaces:**
- Consumes: `Goal`, `Category`, `FrequencyQuota`, `DurationQuota`, `EveningQuota`, `EngineSettings` (Task 2), `GoalWeighting` (Task 5).
- Produces: `enum BlockKind { FIXED, ROUTINE, GOAL, FREE, ADHOC }`; `enum BlockStatus { PLANNED, DONE, CANCELLED, POSTPONED }`; `Block(String id, LocalDate date, LocalTime start, LocalTime end, String title, BlockKind kind, String goalId, BlockStatus status, int postponeCount, boolean flagged, String reason)`; `DayPlan(LocalDate date, List<Block> blocks, List<String> warnings)`; `Unit(String id, Goal goal, int minutes, PreferredTime preferredTime, LocalTime anchorStart)` (`anchorStart` nullable); `WeekPlan(Map<LocalDate, List<Unit>> unitsByDate, List<String> warnings)`; `new WeekAllocator(GoalWeighting, EngineSettings).allocate(List<Goal> goals, List<Constraint> constraints, LocalDate weekStart) -> WeekPlan` where `weekStart` is a Monday and the map has exactly 7 keys, Monday..Sunday.

- [ ] **Step 1: Implement the Block/DayPlan domain types (no test — pure data, exercised by later tests)**

Create `backend/src/main/java/com/lifeorganizer/domain/BlockKind.java`:

```java
package com.lifeorganizer.domain;

public enum BlockKind { FIXED, ROUTINE, GOAL, FREE, ADHOC }
```

Create `backend/src/main/java/com/lifeorganizer/domain/BlockStatus.java`:

```java
package com.lifeorganizer.domain;

public enum BlockStatus { PLANNED, DONE, CANCELLED, POSTPONED }
```

Create `backend/src/main/java/com/lifeorganizer/domain/Block.java`:

```java
package com.lifeorganizer.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

public record Block(
    String id,
    LocalDate date,
    LocalTime start,
    LocalTime end,
    String title,
    BlockKind kind,
    String goalId,
    BlockStatus status,
    int postponeCount,
    boolean flagged,
    String reason
) {

    public Block {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start for block " + id);
        }
        if (postponeCount < 0) {
            throw new IllegalArgumentException("postponeCount must not be negative");
        }
    }

    public int durationMinutes() {
        return (int) java.time.Duration.between(start, end).toMinutes();
    }

    public boolean overlaps(Block other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }
}
```

Create `backend/src/main/java/com/lifeorganizer/domain/DayPlan.java`:

```java
package com.lifeorganizer.domain;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record DayPlan(LocalDate date, List<Block> blocks, List<String> warnings) {

    public DayPlan {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(warnings, "warnings");
        blocks = List.copyOf(blocks);
        warnings = List.copyOf(warnings);
    }

    public List<Block> blocksInOrder() {
        return blocks.stream().sorted(Comparator.comparing(Block::start)).toList();
    }
}
```

- [ ] **Step 2: Write the failing tests for WeekAllocator**

Create `backend/src/test/java/com/lifeorganizer/engine/WeekAllocatorTest.java`:

```java
package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;
import org.junit.jupiter.api.Test;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class WeekAllocatorTest {

    private final LocalDate monday = LocalDate.of(2026, 9, 14).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    private final Category health = Category.of("Health");
    private final Category career = Category.of("Career");
    private final WeekAllocator allocator = new WeekAllocator(new GoalWeighting(), EngineSettings.defaults());

    @Test
    void spreadsFrequencyQuotaAcrossFourDistinctWeekDays() {
        Goal gym = Goal.withDefaults("health-gym", health, "Gym");
        FrequencyQuota quota = new FrequencyQuota("Gym", 4, 60, "health-gym");

        WeekPlan plan = allocator.allocate(List.of(gym), List.of(quota), monday);

        long gymDays = plan.unitsByDate().values().stream()
            .filter(units -> units.stream().anyMatch(u -> u.goal().id().equals("health-gym")))
            .count();
        assertThat(gymDays).isEqualTo(4);
        assertThat(plan.unitsByDate()).hasSize(7);
    }

    @Test
    void splitsDurationQuotaAcrossGoalsByPriorityThenIntoDailyChunks() {
        Goal cert = new Goal("career-cert", career, "Claude certification", 5, null, 60, Energy.MEDIUM, PreferredTime.EVENING);
        Goal azure = new Goal("career-azure", career, "Azure AI", 3, null, 60, Energy.MEDIUM, PreferredTime.EVENING);
        DurationQuota learning = new DurationQuota("Learning", 300, "career");

        WeekPlan plan = allocator.allocate(List.of(cert, azure), List.of(learning), monday);

        int certMinutes = plan.unitsByDate().values().stream()
            .flatMap(List::stream)
            .filter(u -> u.goal().id().equals("career-cert"))
            .mapToInt(Unit::minutes).sum();
        int azureMinutes = plan.unitsByDate().values().stream()
            .flatMap(List::stream)
            .filter(u -> u.goal().id().equals("career-azure"))
            .mapToInt(Unit::minutes).sum();

        assertThat(certMinutes).isGreaterThan(azureMinutes);
        assertThat(certMinutes + azureMinutes).isEqualTo(300);
        // no single day gets more than one 60-minute chunk of the same goal
        plan.unitsByDate().values().forEach(units ->
            assertThat(units.stream().filter(u -> u.goal().id().equals("career-cert")).count()).isLessThanOrEqualTo(1));
    }

    @Test
    void warnsWhenQuotaReferencesUnknownGoal() {
        FrequencyQuota quota = new FrequencyQuota("Gym", 4, 60, "does-not-exist");

        WeekPlan plan = allocator.allocate(List.of(), List.of(quota), monday);

        assertThat(plan.warnings()).anyMatch(w -> w.contains("does-not-exist"));
        assertThat(plan.unitsByDate().values()).allMatch(List::isEmpty);
    }

    @Test
    void everyWeekdayKeyIsPresentEvenWithNoConstraints() {
        WeekPlan plan = allocator.allocate(List.of(), List.of(), monday);

        Set<DayOfWeek> days = plan.unitsByDate().keySet().stream().map(LocalDate::getDayOfWeek).collect(java.util.stream.Collectors.toSet());
        assertThat(days).isEqualTo(EnumSet.allOf(DayOfWeek.class));
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=WeekAllocatorTest`
Expected: FAIL — `Unit`/`WeekPlan`/`WeekAllocator` do not exist.

- [ ] **Step 4: Implement Unit, WeekPlan, WeekAllocator**

Create `backend/src/main/java/com/lifeorganizer/engine/Unit.java`:

```java
package com.lifeorganizer.engine;

import com.lifeorganizer.domain.Goal;
import com.lifeorganizer.domain.PreferredTime;
import java.time.LocalTime;
import java.util.Objects;

/**
 * A chunk of a goal's weekly time to be placed on a specific day. `anchorStart`
 * is null for most units (DayScheduler picks the best-fitting free gap), but is
 * set to a fixed clock time for evening-quota units (e.g. Social always anchors
 * to EngineSettings.eveningAnchor()) so they land at a conventional hour rather
 * than immediately filling whatever gap is free first.
 */
public record Unit(String id, Goal goal, int minutes, PreferredTime preferredTime, LocalTime anchorStart) {

    public Unit {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(preferredTime, "preferredTime");
        if (minutes <= 0) {
            throw new IllegalArgumentException("minutes must be positive");
        }
    }
}
```

Create `backend/src/main/java/com/lifeorganizer/engine/WeekPlan.java`:

```java
package com.lifeorganizer.engine;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record WeekPlan(Map<LocalDate, List<Unit>> unitsByDate, List<String> warnings) {

    public WeekPlan {
        Objects.requireNonNull(unitsByDate, "unitsByDate");
        Objects.requireNonNull(warnings, "warnings");
        // Collections.unmodifiableMap(new LinkedHashMap<>(...)), not Map.copyOf: Map.copyOf
        // does not preserve insertion order, which would silently break the
        // Monday->Sunday iteration order WeekAllocator builds this map with.
        unitsByDate = Collections.unmodifiableMap(new LinkedHashMap<>(unitsByDate));
        warnings = List.copyOf(warnings);
    }
}
```

*(Amended after Task 6's review: the original draft omitted validation on `DayPlan`/`WeekPlan` and silently dropped overflow minutes in `allocateDuration` — both fixed per human decision. A first attempt at the `WeekPlan` fix used `Map.copyOf`, which broke the mandated `LinkedHashMap` ordering; corrected to the order-preserving wrap above.)*

Create `backend/src/main/java/com/lifeorganizer/engine/WeekAllocator.java`:

```java
package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;

import java.time.LocalDate;
import java.util.*;

/**
 * Turns weekly constraints into per-day Units. Frequency and evening quotas are
 * spread across the week using an evenly-spaced index formula; duration quotas
 * are first split across goals by GoalWeighting, then chunked into daily
 * sessions of at most the goal's defaultBlockMinutes, one chunk per goal per day.
 */
public final class WeekAllocator {

    private final GoalWeighting goalWeighting;
    private final EngineSettings settings;

    public WeekAllocator(GoalWeighting goalWeighting, EngineSettings settings) {
        this.goalWeighting = goalWeighting;
        this.settings = settings;
    }

    public WeekPlan allocate(List<Goal> goals, List<Constraint> constraints, LocalDate weekStart) {
        Map<LocalDate, List<Unit>> unitsByDate = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            unitsByDate.put(weekStart.plusDays(i), new ArrayList<>());
        }
        List<String> warnings = new ArrayList<>();
        Map<String, Goal> goalsById = goals.stream().collect(java.util.stream.Collectors.toMap(Goal::id, g -> g));
        Map<String, List<Goal>> goalsByCategoryId = new LinkedHashMap<>();
        for (Goal g : goals) {
            goalsByCategoryId.computeIfAbsent(g.category().id(), k -> new ArrayList<>()).add(g);
        }

        for (Constraint constraint : constraints) {
            switch (constraint) {
                case FrequencyQuota freq -> allocateFrequency(freq, goalsById, weekStart, unitsByDate, warnings);
                case DurationQuota dur -> allocateDuration(dur, goalsByCategoryId, weekStart, unitsByDate, warnings);
                case EveningQuota eve -> allocateEvening(eve, goalsByCategoryId, weekStart, unitsByDate, warnings);
                case FixedBlock fixed -> { /* placed directly by DayScheduler, not a Unit */ }
            }
        }
        return new WeekPlan(unitsByDate, warnings);
    }

    private void allocateFrequency(FrequencyQuota freq, Map<String, Goal> goalsById, LocalDate weekStart,
                                    Map<LocalDate, List<Unit>> unitsByDate, List<String> warnings) {
        Goal goal = goalsById.get(freq.goalId());
        if (goal == null) {
            warnings.add("Frequency quota '" + freq.label() + "' references unknown goal id " + freq.goalId());
            return;
        }
        for (int dayIndex : spreadIndices(freq.timesPerWeek(), 7)) {
            LocalDate date = weekStart.plusDays(dayIndex);
            unitsByDate.get(date).add(new Unit(goal.id() + "-" + date, goal, freq.sessionMinutes(), goal.preferredTime(), null));
        }
    }

    private void allocateEvening(EveningQuota eve, Map<String, List<Goal>> goalsByCategoryId, LocalDate weekStart,
                                  Map<LocalDate, List<Unit>> unitsByDate, List<String> warnings) {
        List<Goal> candidates = goalsByCategoryId.get(eve.categoryId());
        if (candidates == null || candidates.isEmpty()) {
            warnings.add("Evening quota '" + eve.label() + "' references unknown or empty category " + eve.categoryId());
            return;
        }
        Goal representative = candidates.get(0);
        int[] indices = spreadIndices(eve.eveningsPerWeek(), 7);
        for (int dayIndex : indices) {
            LocalDate date = weekStart.plusDays(dayIndex);
            unitsByDate.get(date).add(new Unit(representative.id() + "-evening-" + date, representative,
                representative.defaultBlockMinutes(), PreferredTime.EVENING, settings.eveningAnchor()));
        }
    }

    private void allocateDuration(DurationQuota dur, Map<String, List<Goal>> goalsByCategoryId, LocalDate weekStart,
                                   Map<LocalDate, List<Unit>> unitsByDate, List<String> warnings) {
        List<Goal> candidates = goalsByCategoryId.get(dur.categoryId());
        if (candidates == null || candidates.isEmpty()) {
            warnings.add("Duration quota '" + dur.label() + "' references unknown or empty category " + dur.categoryId());
            return;
        }
        List<GoalAllocation> allocations = goalWeighting.allocate(candidates, dur.minutesPerWeek(), weekStart, settings.gridMinutes());

        for (GoalAllocation allocation : allocations) {
            int remaining = allocation.minutesPerWeek();
            int dayOffset = 0;
            while (remaining > 0 && dayOffset < 7) {
                LocalDate date = weekStart.plusDays(dayOffset % 7);
                int chunk = Math.min(allocation.goal().defaultBlockMinutes(), remaining);
                unitsByDate.get(date).add(new Unit(allocation.goal().id() + "-chunk-" + date, allocation.goal(), chunk, allocation.goal().preferredTime(), null));
                remaining -= chunk;
                dayOffset++;
            }
            if (remaining > 0) {
                warnings.add(allocation.goal().name() + " could not fit " + remaining + " of its "
                    + allocation.minutesPerWeek() + " weekly minutes (ran out of days)");
            }
        }
    }

    /** Evenly spaced day-of-week indices (0=Monday..6=Sunday) for placing `count` occurrences across `totalDays`. */
    private int[] spreadIndices(int count, int totalDays) {
        int[] indices = new int[count];
        for (int i = 0; i < count; i++) {
            indices[i] = (int) Math.round(i * (double) totalDays / count) % totalDays;
        }
        return indices;
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=WeekAllocatorTest`
Expected: PASS, 4 tests green. If `spreadsFrequencyQuotaAcrossFourDistinctWeekDays` fails because `spreadIndices` produced a duplicate index for `count=4,totalDays=7`, change `spreadIndices` to de-duplicate by advancing to the next free index — verify no other test regresses.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat: add Block/DayPlan types and WeekAllocator" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 7: DayScheduler + golden test

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/engine/DayScheduler.java`
- Test: `backend/src/test/java/com/lifeorganizer/engine/DaySchedulerTest.java`

**Interfaces:**
- Consumes: `Unit`, `Block`, `BlockKind`, `BlockStatus`, `DayPlan`, `FixedBlock`, `EngineSettings` (Tasks 2, 6).
- Produces: `new DayScheduler(EngineSettings).schedule(LocalDate date, List<Unit> units, List<Constraint> constraints, List<Block> alreadyPlacedToday) -> DayPlan`. `alreadyPlacedToday` lets a caller pre-seed DONE/ADHOC blocks (empty list for a fresh day). Units are placed in the given list order (callers pass units pre-sorted by priority — WeekAllocator already emits higher-priority goals' units first via GoalAllocation order). A unit with a non-null `anchorStart` is placed exactly at that clock time if some free gap contains it; otherwise (and for every unit with a null `anchorStart`) it fills the best-fitting free gap by `preferredTime`.

**Design note on the golden test below:** the user's example output is illustrative, not an exhaustive minute-by-minute spec — it doesn't show every leftover Free fragment (e.g. nothing is shown between "Social" ending and "Wind down" starting). The golden test therefore asserts the named, timed activities the user actually specified (including the 19:00 Free block they explicitly called out) rather than the full block list, so it doesn't fail on additional Free fragments the scheduler legitimately produces elsewhere in the day.

- [ ] **Step 1: Write the golden test with the user's exact example**

Create `backend/src/test/java/com/lifeorganizer/engine/DaySchedulerTest.java`:

```java
package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;
import org.junit.jupiter.api.Test;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumSet;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DaySchedulerTest {

    private final LocalDate monday = LocalDate.of(2026, 9, 14).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    private final DayScheduler scheduler = new DayScheduler(EngineSettings.defaults());

    private static final EnumSet<DayOfWeek> WEEKDAYS = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
    private static final EnumSet<DayOfWeek> DAILY = EnumSet.allOf(DayOfWeek.class);

    @Test
    void producesTheExampleTimelineShape() {
        Category health = Category.of("Health");
        Category career = Category.of("Career");
        Category social = Category.of("Social");

        Goal gym = Goal.withDefaults("health-gym", health, "Gym");
        Goal cert = new Goal("career-cert", career, "Claude certification", 5, null, 60, Energy.MEDIUM, PreferredTime.EVENING);
        Goal socialGoal = Goal.withDefaults("social-dating", social, "Dating / events");

        List<Constraint> constraints = List.of(
            new FixedBlock("Work", LocalTime.of(8, 30), LocalTime.of(17, 0), WEEKDAYS),
            new FixedBlock("Sleep", LocalTime.of(23, 30), LocalTime.of(7, 0), DAILY)
        );

        List<Unit> units = List.of(
            new Unit("gym-unit", gym, 60, PreferredTime.MORNING, null),
            new Unit("cert-unit", cert, 60, PreferredTime.EVENING, null),
            new Unit("social-unit", socialGoal, 60, PreferredTime.EVENING, LocalTime.of(20, 0))
        );

        DayPlan plan = scheduler.schedule(monday, units, constraints, List.of());
        List<Block> nonFree = plan.blocksInOrder().stream().filter(b -> b.kind() != BlockKind.FREE).toList();

        // The user's example is illustrative (it doesn't show every leftover Free
        // fragment), so this asserts the named, timed activities they specified,
        // not the full block list.
        assertThat(nonFree).extracting(b -> b.start() + " " + b.title()).containsExactly(
            "07:00 Gym",
            "08:30 Work",
            "17:00 Dinner / break",
            "18:00 Claude certification",
            "20:00 Dating / events",
            "22:30 Wind down"
        );

        Block certBlock = nonFree.stream().filter(b -> b.title().equals("Claude certification")).findFirst().orElseThrow();
        assertThat(certBlock.durationMinutes()).isEqualTo(60);
        assertThat(certBlock.end()).isEqualTo(LocalTime.of(19, 0));

        // The example explicitly calls out a 19:00 Free slot between cert and social.
        assertThat(plan.blocksInOrder()).anyMatch(b -> b.kind() == BlockKind.FREE && b.start().equals(LocalTime.of(19, 0)));
    }

    @Test
    void unfilledGapsBecomeFreeBlocksOfAtLeastGridMinutes() {
        List<Constraint> constraints = List.of(
            new FixedBlock("Sleep", LocalTime.of(23, 30), LocalTime.of(7, 0), DAILY)
        );

        DayPlan plan = scheduler.schedule(monday, List.of(), constraints, List.of());

        List<Block> freeBlocks = plan.blocksInOrder().stream().filter(b -> b.kind() == BlockKind.FREE).toList();
        assertThat(freeBlocks).hasSize(1);
        assertThat(freeBlocks.get(0).start()).isEqualTo(LocalTime.of(7, 0));
        assertThat(freeBlocks.get(0).end()).isEqualTo(LocalTime.of(22, 30));
        assertThat(plan.blocksInOrder()).anyMatch(b -> b.kind() == BlockKind.ROUTINE && b.title().equals("Wind down"));
    }

    @Test
    void alreadyPlacedBlocksAreKeptAndNotOverlapped() {
        Block doneGym = new Block("done-gym", monday, LocalTime.of(7, 0), LocalTime.of(8, 0), "Gym",
            BlockKind.GOAL, "health-gym", BlockStatus.DONE, 0, false, null);
        List<Constraint> constraints = List.of(
            new FixedBlock("Sleep", LocalTime.of(23, 30), LocalTime.of(7, 0), DAILY)
        );

        DayPlan plan = scheduler.schedule(monday, List.of(), constraints, List.of(doneGym));

        assertThat(plan.blocksInOrder()).contains(doneGym);
        assertThat(plan.blocksInOrder()).noneMatch(b -> !b.id().equals("done-gym") && b.overlaps(doneGym));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=DaySchedulerTest`
Expected: FAIL — `DayScheduler` does not exist.

- [ ] **Step 3: Implement DayScheduler**

Create `backend/src/main/java/com/lifeorganizer/engine/DayScheduler.java`:

```java
package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * Places one day's Units into the free time left after fixed blocks (Work, ...)
 * and routine blocks (Dinner after work, Wind down before sleep). The active
 * window for the day is bounded by the "Sleep" FixedBlock: it runs from Sleep's
 * end time (wake time) to Sleep's start time (bedtime) and is never rendered as
 * a block itself. Leftover gaps of at least minBlockMinutes become FREE blocks.
 */
public final class DayScheduler {

    private final EngineSettings settings;

    public DayScheduler(EngineSettings settings) {
        this.settings = settings;
    }

    public DayPlan schedule(LocalDate date, List<Unit> units, List<Constraint> constraints, List<Block> alreadyPlacedToday) {
        List<String> warnings = new ArrayList<>();
        FixedBlock sleep = findSleepBlock(constraints);
        LocalTime dayStart = sleep != null ? sleep.end() : LocalTime.of(7, 0);
        LocalTime dayEnd = sleep != null ? sleep.start() : LocalTime.of(23, 0);

        List<Block> placed = new ArrayList<>(alreadyPlacedToday);
        DayOfWeek dow = date.getDayOfWeek();

        FixedBlock workBlock = null;
        for (Constraint c : constraints) {
            if (c instanceof FixedBlock fb && fb != sleep && fb.days().contains(dow)) {
                String id = "fixed-" + Slug.of(fb.label()) + "-" + date;
                placed.add(new Block(id, date, fb.start(), fb.end(), fb.label(), BlockKind.FIXED, null,
                    BlockStatus.PLANNED, 0, false, null));
                if (fb.label().equalsIgnoreCase("work")) {
                    workBlock = fb;
                }
            }
        }

        if (workBlock != null) {
            LocalTime dinnerStart = workBlock.end();
            LocalTime dinnerEnd = dinnerStart.plusMinutes(settings.dinnerMinutesAfterWork());
            if (!dinnerEnd.isAfter(dayEnd)) {
                placed.add(new Block("routine-dinner-" + date, date, dinnerStart, dinnerEnd, "Dinner / break",
                    BlockKind.ROUTINE, null, BlockStatus.PLANNED, 0, false, null));
            }
        }
        LocalTime windDownStart = dayEnd.minusMinutes(settings.windDownMinutesBeforeSleep());
        placed.add(new Block("routine-winddown-" + date, date, windDownStart, dayEnd, "Wind down",
            BlockKind.ROUTINE, null, BlockStatus.PLANNED, 0, false, null));

        for (Unit unit : units) {
            List<Interval> freeGaps = freeGaps(dayStart, dayEnd, placed);
            LocalTime start = pickStart(freeGaps, unit);
            if (start == null) {
                warnings.add("No room today for " + unit.goal().name());
                continue;
            }
            LocalTime end = start.plusMinutes(unit.minutes());
            placed.add(new Block(unit.id(), date, start, end, unit.goal().name(), BlockKind.GOAL, unit.goal().id(),
                BlockStatus.PLANNED, 0, false, null));
        }

        for (Interval gap : freeGaps(dayStart, dayEnd, placed)) {
            int minutes = (int) Duration.between(gap.start(), gap.end()).toMinutes();
            if (minutes >= settings.minBlockMinutes()) {
                placed.add(new Block("free-" + date + "-" + gap.start(), date, gap.start(), gap.end(), "Free",
                    BlockKind.FREE, null, BlockStatus.PLANNED, 0, false, null));
            }
        }

        return new DayPlan(date, placed, warnings);
    }

    private FixedBlock findSleepBlock(List<Constraint> constraints) {
        for (Constraint c : constraints) {
            if (c instanceof FixedBlock fb && fb.label().equalsIgnoreCase("sleep")) {
                return fb;
            }
        }
        return null;
    }

    private record Interval(LocalTime start, LocalTime end) {
        Interval {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException("end must be after start");
            }
        }
    }

    // Amended after Task 7's review: the original draft omitted validation on the
    // private Interval record - fixed per human decision, matching Block's pattern.
    // Both construction sites in freeGaps() are guarded by an isAfter check
    // immediately above them, so this can never actually throw - it's the same
    // "records validate invariants" discipline applied even to a private helper type.

    private List<Interval> freeGaps(LocalTime dayStart, LocalTime dayEnd, List<Block> placed) {
        List<Block> sorted = placed.stream().sorted(Comparator.comparing(Block::start)).toList();
        List<Interval> gaps = new ArrayList<>();
        LocalTime cursor = dayStart;
        for (Block b : sorted) {
            if (b.start().isAfter(cursor)) {
                gaps.add(new Interval(cursor, b.start()));
            }
            if (b.end().isAfter(cursor)) {
                cursor = b.end();
            }
        }
        if (dayEnd.isAfter(cursor)) {
            gaps.add(new Interval(cursor, dayEnd));
        }
        return gaps;
    }

    /**
     * Chooses the start time for a unit. A non-null anchorStart wins if some gap
     * can hold the full duration starting no earlier than the anchor and ending
     * no later than the gap's end; otherwise (or if the anchor doesn't fit
     * anywhere) the best-fitting gap is chosen by preferredTime and filled from
     * its start.
     */
    private LocalTime pickStart(List<Interval> gaps, Unit unit) {
        List<Interval> fitting = gaps.stream()
            .filter(g -> Duration.between(g.start(), g.end()).toMinutes() >= unit.minutes())
            .toList();
        if (fitting.isEmpty()) {
            return null;
        }
        if (unit.anchorStart() != null) {
            for (Interval g : fitting) {
                LocalTime latestStart = g.end().minusMinutes(unit.minutes());
                if (!unit.anchorStart().isBefore(g.start()) && !unit.anchorStart().isAfter(latestStart)) {
                    return unit.anchorStart();
                }
            }
            // anchor time isn't free in any gap; fall through to best-fit below
        }
        Comparator<Interval> byPreference = Comparator.comparing((Interval g) -> preferenceScore(g, unit.preferredTime()));
        return fitting.stream().min(byPreference).map(Interval::start).orElse(null);
    }

    private int preferenceScore(Interval gap, PreferredTime preferredTime) {
        boolean morning = gap.start().isBefore(LocalTime.NOON);
        return switch (preferredTime) {
            case MORNING -> morning ? 0 : 1;
            case EVENING -> morning ? 1 : 0;
            case ANY -> 0;
        };
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw -q test -Dtest=DaySchedulerTest`
Expected: PASS, 3 tests green. If the golden test's ordering or gap sizes are off by 30 minutes, check `dayEnd`/`windDownStart` arithmetic first — `settings.windDownMinutesBeforeSleep()` must be subtracted from `dayEnd` (23:30), not added.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add DayScheduler, matching the user's example timeline" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 8: Conflict types + ConflictDetector

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/domain/ConflictType.java`
- Create: `backend/src/main/java/com/lifeorganizer/domain/Conflict.java`
- Create: `backend/src/main/java/com/lifeorganizer/engine/ConflictDetector.java`
- Test: `backend/src/test/java/com/lifeorganizer/engine/ConflictDetectorTest.java`

**Interfaces:**
- Consumes: `DayPlan`, `Block` (Task 6), `WeekPlan` (Task 6).
- Produces: `enum ConflictType { OVERLAP, QUOTA_UNFITTABLE, DEADLINE_AT_RISK }`; `Conflict(ConflictType type, String message, List<String> relatedBlockIds)`; `new ConflictDetector().detectInDay(DayPlan) -> List<Conflict>`; `new ConflictDetector().detectUnfittable(WeekPlan, Map<String, Integer> requestedMinutesByGoalId) -> List<Conflict>` (a goal is unfittable if its total scheduled minutes in the week fall short of its requested minutes).

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/lifeorganizer/engine/ConflictDetectorTest.java`:

```java
package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ConflictDetectorTest {

    private final LocalDate date = LocalDate.of(2026, 9, 14);
    private final ConflictDetector detector = new ConflictDetector();

    @Test
    void detectsOverlappingBlocks() {
        Block a = new Block("a", date, LocalTime.of(9, 0), LocalTime.of(10, 0), "A", BlockKind.GOAL, "g1", BlockStatus.PLANNED, 0, false, null);
        Block b = new Block("b", date, LocalTime.of(9, 30), LocalTime.of(10, 30), "B", BlockKind.GOAL, "g2", BlockStatus.PLANNED, 0, false, null);
        DayPlan plan = new DayPlan(date, List.of(a, b), List.of());

        List<Conflict> conflicts = detector.detectInDay(plan);

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).type()).isEqualTo(ConflictType.OVERLAP);
        assertThat(conflicts.get(0).relatedBlockIds()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void noConflictWhenBlocksDoNotOverlap() {
        Block a = new Block("a", date, LocalTime.of(9, 0), LocalTime.of(10, 0), "A", BlockKind.GOAL, "g1", BlockStatus.PLANNED, 0, false, null);
        Block b = new Block("b", date, LocalTime.of(10, 0), LocalTime.of(11, 0), "B", BlockKind.GOAL, "g2", BlockStatus.PLANNED, 0, false, null);
        DayPlan plan = new DayPlan(date, List.of(a, b), List.of());

        assertThat(detector.detectInDay(plan)).isEmpty();
    }

    @Test
    void detectsUnfittableGoalAcrossTheWeek() {
        Category career = Category.of("Career");
        Goal cert = Goal.withDefaults("career-cert", career, "Claude certification");
        Unit unit = new Unit("u1", cert, 60, PreferredTime.EVENING, null);
        WeekPlan weekPlan = new WeekPlan(Map.of(date, List.of(unit)), List.of());

        List<Conflict> conflicts = detector.detectUnfittable(weekPlan, Map.of("career-cert", 300));

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).type()).isEqualTo(ConflictType.QUOTA_UNFITTABLE);
        assertThat(conflicts.get(0).message()).contains("Claude certification");
    }

    @Test
    void noUnfittableConflictWhenRequestedMinutesAreMet() {
        Category career = Category.of("Career");
        Goal cert = Goal.withDefaults("career-cert", career, "Claude certification");
        Unit unit = new Unit("u1", cert, 300, PreferredTime.EVENING, null);
        WeekPlan weekPlan = new WeekPlan(Map.of(date, List.of(unit)), List.of());

        assertThat(detector.detectUnfittable(weekPlan, Map.of("career-cert", 300))).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=ConflictDetectorTest`
Expected: FAIL — `ConflictType`/`Conflict`/`ConflictDetector` do not exist.

- [ ] **Step 3: Implement Conflict types and ConflictDetector**

Create `backend/src/main/java/com/lifeorganizer/domain/ConflictType.java`:

```java
package com.lifeorganizer.domain;

public enum ConflictType { OVERLAP, QUOTA_UNFITTABLE, DEADLINE_AT_RISK }
```

Create `backend/src/main/java/com/lifeorganizer/domain/Conflict.java`:

```java
package com.lifeorganizer.domain;

import java.util.List;
import java.util.Objects;

public record Conflict(ConflictType type, String message, List<String> relatedBlockIds) {

    public Conflict {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(relatedBlockIds, "relatedBlockIds");
        relatedBlockIds = List.copyOf(relatedBlockIds);
    }
}
```

*(Amended after Task 8's review: the original draft omitted the defensive copy on `relatedBlockIds` - fixed per human decision, matching `DayPlan`/`GoalTree`'s pattern.)*

Create `backend/src/main/java/com/lifeorganizer/engine/ConflictDetector.java`:

```java
package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;

import java.util.*;

public final class ConflictDetector {

    public List<Conflict> detectInDay(DayPlan plan) {
        List<Conflict> conflicts = new ArrayList<>();
        List<Block> blocks = plan.blocksInOrder();
        for (int i = 0; i < blocks.size(); i++) {
            for (int j = i + 1; j < blocks.size(); j++) {
                Block a = blocks.get(i);
                Block b = blocks.get(j);
                if (a.overlaps(b)) {
                    conflicts.add(new Conflict(ConflictType.OVERLAP,
                        a.title() + " overlaps with " + b.title() + " on " + plan.date(),
                        List.of(a.id(), b.id())));
                }
            }
        }
        return conflicts;
    }

    public List<Conflict> detectUnfittable(WeekPlan weekPlan, Map<String, Integer> requestedMinutesByGoalId) {
        Map<String, Integer> scheduledByGoalId = new LinkedHashMap<>();
        Map<String, Goal> goalById = new LinkedHashMap<>();
        for (List<Unit> units : weekPlan.unitsByDate().values()) {
            for (Unit unit : units) {
                scheduledByGoalId.merge(unit.goal().id(), unit.minutes(), Integer::sum);
                goalById.putIfAbsent(unit.goal().id(), unit.goal());
            }
        }

        List<Conflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, Integer> requested : requestedMinutesByGoalId.entrySet()) {
            int scheduled = scheduledByGoalId.getOrDefault(requested.getKey(), 0);
            if (scheduled < requested.getValue()) {
                String name = goalById.containsKey(requested.getKey()) ? goalById.get(requested.getKey()).name() : requested.getKey();
                conflicts.add(new Conflict(ConflictType.QUOTA_UNFITTABLE,
                    name + " is short " + (requested.getValue() - scheduled) + " minutes this week",
                    List.of(requested.getKey())));
            }
        }
        return conflicts;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=ConflictDetectorTest`
Expected: PASS, 4 tests green.

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat: add Conflict types and ConflictDetector" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 9: PlanEvent, PostponePolicy, Replanner

**Files:**
- Create: `backend/src/main/java/com/lifeorganizer/engine/PlanEvent.java`
- Create: `backend/src/main/java/com/lifeorganizer/engine/CancelBlock.java`
- Create: `backend/src/main/java/com/lifeorganizer/engine/PostponeBlock.java`
- Create: `backend/src/main/java/com/lifeorganizer/engine/MarkDone.java`
- Create: `backend/src/main/java/com/lifeorganizer/engine/SetEnergy.java`
- Create: `backend/src/main/java/com/lifeorganizer/engine/AddAdhoc.java`
- Create: `backend/src/main/java/com/lifeorganizer/engine/ReplanResult.java`
- Create: `backend/src/main/java/com/lifeorganizer/engine/PostponePolicy.java`
- Create: `backend/src/main/java/com/lifeorganizer/engine/Replanner.java`
- Test: `backend/src/test/java/com/lifeorganizer/engine/ReplannerTest.java`

**Interfaces:**
- Consumes: `Block`, `BlockKind`, `BlockStatus`, `DayPlan`, `Energy` (Task 2, 6).
- Produces: sealed `PlanEvent` with `CancelBlock(String blockId)`, `PostponeBlock(String blockId, String reason)`, `MarkDone(String blockId)`, `SetEnergy(String blockId, Energy level)`, `AddAdhoc(LocalDate date, String title, LocalTime start, LocalTime end)`; `ReplanResult(Map<LocalDate, DayPlan> updatedPlans, List<String> changes)`; `new Replanner(new PostponePolicy()).apply(Map<LocalDate, DayPlan> weekPlans, PlanEvent event) -> ReplanResult`.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/lifeorganizer/engine/ReplannerTest.java`:

```java
package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ReplannerTest {

    private final LocalDate mon = LocalDate.of(2026, 9, 14);
    private final LocalDate tue = mon.plusDays(1);
    private final Replanner replanner = new Replanner(new PostponePolicy());

    @Test
    void cancelMarksBlockCancelled() {
        Block padel = new Block("padel", mon, LocalTime.of(19, 0), LocalTime.of(20, 30), "Padel",
            BlockKind.ADHOC, null, BlockStatus.PLANNED, 0, false, null);
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(padel), List.of()));

        ReplanResult result = replanner.apply(week, new CancelBlock("padel"));

        Block updated = findBlock(result, mon, "padel");
        assertThat(updated.status()).isEqualTo(BlockStatus.CANCELLED);
        assertThat(result.changes()).anyMatch(c -> c.contains("Padel"));
    }

    @Test
    void postponeMovesToALaterFreeSlotTheSameDay() {
        Block certBlock = new Block("cert", mon, LocalTime.of(18, 0), LocalTime.of(19, 0), "Claude certification",
            BlockKind.GOAL, "career-cert", BlockStatus.PLANNED, 0, false, null);
        Block freeSlot = new Block("free-1", mon, LocalTime.of(20, 0), LocalTime.of(22, 30), "Free",
            BlockKind.FREE, null, BlockStatus.PLANNED, 0, false, null);
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(certBlock, freeSlot), List.of()));

        ReplanResult result = replanner.apply(week, new PostponeBlock("cert", "not in the mood"));

        DayPlan monPlan = result.updatedPlans().get(mon);
        Block original = monPlan.blocks().stream().filter(b -> b.id().equals("cert")).findFirst().orElseThrow();
        assertThat(original.status()).isEqualTo(BlockStatus.POSTPONED);
        Block moved = monPlan.blocks().stream()
            .filter(b -> b.goalId() != null && b.goalId().equals("career-cert") && b.status() == BlockStatus.PLANNED)
            .findFirst().orElseThrow();
        assertThat(moved.start()).isEqualTo(LocalTime.of(20, 0));
        assertThat(moved.postponeCount()).isEqualTo(1);
    }

    @Test
    void postponeMovesToNextDayWhenTodayHasNoRoom() {
        Block certBlock = new Block("cert", mon, LocalTime.of(18, 0), LocalTime.of(19, 0), "Claude certification",
            BlockKind.GOAL, "career-cert", BlockStatus.PLANNED, 0, false, null);
        DayPlan monPlan = new DayPlan(mon, List.of(certBlock), List.of());
        Block tueFree = new Block("free-tue", tue, LocalTime.of(18, 0), LocalTime.of(19, 0), "Free",
            BlockKind.FREE, null, BlockStatus.PLANNED, 0, false, null);
        DayPlan tuePlan = new DayPlan(tue, List.of(tueFree), List.of());
        Map<LocalDate, DayPlan> week = new LinkedHashMap<>();
        week.put(mon, monPlan);
        week.put(tue, tuePlan);

        ReplanResult result = replanner.apply(week, new PostponeBlock("cert", "tired"));

        Block moved = result.updatedPlans().get(tue).blocks().stream()
            .filter(b -> "career-cert".equals(b.goalId()) && b.status() == BlockStatus.PLANNED)
            .findFirst().orElseThrow();
        assertThat(moved.start()).isEqualTo(LocalTime.of(18, 0));
    }

    @Test
    void thirdPostponeFlagsTheBlock() {
        Block certBlock = new Block("cert", mon, LocalTime.of(18, 0), LocalTime.of(19, 0), "Claude certification",
            BlockKind.GOAL, "career-cert", BlockStatus.PLANNED, 2, false, null);
        Block freeSlot = new Block("free-1", mon, LocalTime.of(20, 0), LocalTime.of(22, 30), "Free",
            BlockKind.FREE, null, BlockStatus.PLANNED, 0, false, null);
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(certBlock, freeSlot), List.of()));

        ReplanResult result = replanner.apply(week, new PostponeBlock("cert", "again"));

        Block moved = result.updatedPlans().get(mon).blocks().stream()
            .filter(b -> "career-cert".equals(b.goalId()) && b.status() == BlockStatus.PLANNED)
            .findFirst().orElseThrow();
        assertThat(moved.postponeCount()).isEqualTo(3);
        assertThat(moved.flagged()).isTrue();
    }

    @Test
    void markDoneSetsStatusDone() {
        Block gym = new Block("gym", mon, LocalTime.of(7, 0), LocalTime.of(8, 0), "Gym",
            BlockKind.GOAL, "health-gym", BlockStatus.PLANNED, 0, false, null);
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(gym), List.of()));

        ReplanResult result = replanner.apply(week, new MarkDone("gym"));

        assertThat(findBlock(result, mon, "gym").status()).isEqualTo(BlockStatus.DONE);
    }

    @Test
    void addAdhocInsertsNewBlock() {
        Map<LocalDate, DayPlan> week = weekOf(mon, new DayPlan(mon, List.of(), List.of()));

        ReplanResult result = replanner.apply(week, new AddAdhoc(mon, "Padel", LocalTime.of(19, 0), LocalTime.of(20, 30)));

        assertThat(result.updatedPlans().get(mon).blocks()).anyMatch(b -> b.title().equals("Padel") && b.kind() == BlockKind.ADHOC);
    }

    private Map<LocalDate, DayPlan> weekOf(LocalDate date, DayPlan plan) {
        Map<LocalDate, DayPlan> map = new LinkedHashMap<>();
        map.put(date, plan);
        return map;
    }

    private Block findBlock(ReplanResult result, LocalDate date, String id) {
        return result.updatedPlans().get(date).blocks().stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=ReplannerTest`
Expected: FAIL — `PlanEvent` and friends do not exist.

- [ ] **Step 3: Implement PlanEvent hierarchy, ReplanResult, PostponePolicy, Replanner**

Create `backend/src/main/java/com/lifeorganizer/engine/PlanEvent.java`:

```java
package com.lifeorganizer.engine;

public sealed interface PlanEvent
    permits CancelBlock, PostponeBlock, MarkDone, SetEnergy, AddAdhoc {}
```

Create `backend/src/main/java/com/lifeorganizer/engine/CancelBlock.java`:

```java
package com.lifeorganizer.engine;

import java.util.Objects;

public record CancelBlock(String blockId) implements PlanEvent {

    public CancelBlock {
        Objects.requireNonNull(blockId, "blockId");
    }
}
```

Create `backend/src/main/java/com/lifeorganizer/engine/PostponeBlock.java`:

```java
package com.lifeorganizer.engine;

import java.util.Objects;

public record PostponeBlock(String blockId, String reason) implements PlanEvent {

    public PostponeBlock {
        Objects.requireNonNull(blockId, "blockId");
        // reason is optional/nullable - no check.
    }
}
```

Create `backend/src/main/java/com/lifeorganizer/engine/MarkDone.java`:

```java
package com.lifeorganizer.engine;

import java.util.Objects;

public record MarkDone(String blockId) implements PlanEvent {

    public MarkDone {
        Objects.requireNonNull(blockId, "blockId");
    }
}
```

Create `backend/src/main/java/com/lifeorganizer/engine/SetEnergy.java`:

```java
package com.lifeorganizer.engine;

import com.lifeorganizer.domain.Energy;
import java.util.Objects;

public record SetEnergy(String blockId, Energy level) implements PlanEvent {

    public SetEnergy {
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(level, "level");
    }
}
```

Create `backend/src/main/java/com/lifeorganizer/engine/AddAdhoc.java`:

```java
package com.lifeorganizer.engine;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

public record AddAdhoc(LocalDate date, String title, LocalTime start, LocalTime end) implements PlanEvent {

    public AddAdhoc {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
    }
}
```

Create `backend/src/main/java/com/lifeorganizer/engine/ReplanResult.java`:

```java
package com.lifeorganizer.engine;

import com.lifeorganizer.domain.DayPlan;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ReplanResult(Map<LocalDate, DayPlan> updatedPlans, List<String> changes) {

    public ReplanResult {
        Objects.requireNonNull(updatedPlans, "updatedPlans");
        Objects.requireNonNull(changes, "changes");
        // Order-preserving, per the WeekPlan lesson: Map.copyOf does not preserve
        // LinkedHashMap insertion order.
        updatedPlans = Collections.unmodifiableMap(new LinkedHashMap<>(updatedPlans));
        changes = List.copyOf(changes);
    }
}
```

*(Amended after Task 9's review: the original draft omitted validation on all six new records - fixed per human decision, matching the codebase's established pattern. The implementer correctly flagged the gap rather than deciding unilaterally.)*

Create `backend/src/main/java/com/lifeorganizer/engine/PostponePolicy.java`:

```java
package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * Decides where a postponed block lands: a later free slot the same day, else
 * the first free slot on a later day already present in the given week. The
 * original block is marked POSTPONED in place; a new PLANNED block is created
 * at the target slot carrying postponeCount + 1. At postponeCount 3 the new
 * block is flagged.
 */
public final class PostponePolicy {

    public ReplanResult postpone(Map<LocalDate, DayPlan> weekPlans, PostponeBlock event) {
        LocalDate originDate = findBlockDate(weekPlans, event.blockId());
        if (originDate == null) {
            return new ReplanResult(weekPlans, List.of("Block " + event.blockId() + " not found"));
        }
        DayPlan originPlan = weekPlans.get(originDate);
        Block original = originPlan.blocks().stream().filter(b -> b.id().equals(event.blockId())).findFirst().orElseThrow();

        List<LocalDate> candidateDates = new ArrayList<>();
        candidateDates.add(originDate);
        weekPlans.keySet().stream().filter(d -> d.isAfter(originDate)).sorted().forEach(candidateDates::add);

        for (LocalDate candidateDate : candidateDates) {
            DayPlan candidatePlan = weekPlans.get(candidateDate);
            Block freeSlot = findFreeSlotAfter(candidatePlan, original, candidateDate.equals(originDate) ? original.end() : LocalTime.MIN);
            if (freeSlot != null) {
                return moveInto(weekPlans, originDate, candidateDate, original, freeSlot, event.reason());
            }
        }

        Map<LocalDate, DayPlan> unchanged = new LinkedHashMap<>(weekPlans);
        return new ReplanResult(unchanged, List.of("No room this week to reschedule " + original.title()));
    }

    private Block findFreeSlotAfter(DayPlan plan, Block original, LocalTime notBefore) {
        return plan.blocksInOrder().stream()
            .filter(b -> b.kind() == BlockKind.FREE)
            .filter(b -> !b.start().isBefore(notBefore))
            .filter(b -> b.durationMinutes() >= original.durationMinutes())
            .findFirst()
            .orElse(null);
    }

    private ReplanResult moveInto(Map<LocalDate, DayPlan> weekPlans, LocalDate originDate, LocalDate targetDate,
                                   Block original, Block freeSlot, String reason) {
        Map<LocalDate, DayPlan> updated = new LinkedHashMap<>(weekPlans);

        Block postponedOriginal = new Block(original.id(), original.date(), original.start(), original.end(), original.title(),
            original.kind(), original.goalId(), BlockStatus.POSTPONED, original.postponeCount(), original.flagged(), reason);
        updated.put(originDate, replaceBlock(updated.get(originDate), original.id(), postponedOriginal));

        int newPostponeCount = original.postponeCount() + 1;
        LocalTime newStart = freeSlot.start();
        LocalTime newEnd = newStart.plusMinutes(original.durationMinutes());
        Block moved = new Block(original.id() + "-p" + newPostponeCount, targetDate, newStart, newEnd, original.title(),
            original.kind(), original.goalId(), BlockStatus.PLANNED, newPostponeCount, newPostponeCount >= 3, null);

        DayPlan targetPlan = updated.get(targetDate);
        List<Block> targetBlocks = new ArrayList<>(targetPlan.blocks());
        if (targetDate.equals(originDate)) {
            targetBlocks = updated.get(targetDate).blocks();
            targetBlocks = new ArrayList<>(targetBlocks);
        }
        targetBlocks.removeIf(b -> b.id().equals(freeSlot.id()));
        splitRemainingFree(freeSlot, moved).ifPresent(targetBlocks::add);
        targetBlocks.add(moved);
        updated.put(targetDate, new DayPlan(targetDate, targetBlocks, targetPlan.warnings()));

        String change = "Moved " + original.title() + " " + original.start() + " -> " + targetDate + " " + newStart;
        return new ReplanResult(updated, List.of(change));
    }

    private Optional<Block> splitRemainingFree(Block freeSlot, Block moved) {
        if (moved.end().isBefore(freeSlot.end())) {
            return Optional.of(new Block(freeSlot.id() + "-rest", freeSlot.date(), moved.end(), freeSlot.end(), "Free",
                BlockKind.FREE, null, BlockStatus.PLANNED, 0, false, null));
        }
        return Optional.empty();
    }

    private DayPlan replaceBlock(DayPlan plan, String blockId, Block replacement) {
        List<Block> blocks = new ArrayList<>(plan.blocks());
        blocks.replaceAll(b -> b.id().equals(blockId) ? replacement : b);
        return new DayPlan(plan.date(), blocks, plan.warnings());
    }

    private LocalDate findBlockDate(Map<LocalDate, DayPlan> weekPlans, String blockId) {
        for (Map.Entry<LocalDate, DayPlan> entry : weekPlans.entrySet()) {
            if (entry.getValue().blocks().stream().anyMatch(b -> b.id().equals(blockId))) {
                return entry.getKey();
            }
        }
        return null;
    }
}
```

Create `backend/src/main/java/com/lifeorganizer/engine/Replanner.java`:

```java
package com.lifeorganizer.engine;

import com.lifeorganizer.domain.*;

import java.time.LocalDate;
import java.util.*;

/**
 * Applies a single PlanEvent to a week of plans with minimal change: only the
 * affected block (and, for postpone, the target slot) is touched.
 */
public final class Replanner {

    private final PostponePolicy postponePolicy;

    public Replanner(PostponePolicy postponePolicy) {
        this.postponePolicy = postponePolicy;
    }

    public ReplanResult apply(Map<LocalDate, DayPlan> weekPlans, PlanEvent event) {
        return switch (event) {
            case CancelBlock c -> setStatus(weekPlans, c.blockId(), BlockStatus.CANCELLED, "Cancelled");
            case MarkDone d -> setStatus(weekPlans, d.blockId(), BlockStatus.DONE, "Marked done");
            case PostponeBlock p -> postponePolicy.postpone(weekPlans, p);
            case SetEnergy e -> setEnergy(weekPlans, e);
            case AddAdhoc a -> addAdhoc(weekPlans, a);
        };
    }

    private ReplanResult setStatus(Map<LocalDate, DayPlan> weekPlans, String blockId, BlockStatus status, String verb) {
        for (Map.Entry<LocalDate, DayPlan> entry : weekPlans.entrySet()) {
            DayPlan plan = entry.getValue();
            Optional<Block> match = plan.blocks().stream().filter(b -> b.id().equals(blockId)).findFirst();
            if (match.isPresent()) {
                Block original = match.get();
                Block updatedBlock = new Block(original.id(), original.date(), original.start(), original.end(), original.title(),
                    original.kind(), original.goalId(), status, original.postponeCount(), original.flagged(), original.reason());
                List<Block> blocks = new ArrayList<>(plan.blocks());
                blocks.replaceAll(b -> b.id().equals(blockId) ? updatedBlock : b);
                Map<LocalDate, DayPlan> updated = new LinkedHashMap<>(weekPlans);
                updated.put(entry.getKey(), new DayPlan(plan.date(), blocks, plan.warnings()));
                return new ReplanResult(updated, List.of(verb + " " + original.title()));
            }
        }
        return new ReplanResult(weekPlans, List.of("Block " + blockId + " not found"));
    }

    private ReplanResult setEnergy(Map<LocalDate, DayPlan> weekPlans, SetEnergy event) {
        // Phase 1: recorded as a change note only; energy-based re-sorting of
        // remaining units is added once PlanService (Phase 1b) can re-run WeekAllocator.
        return new ReplanResult(weekPlans, List.of("Energy set to " + event.level() + " for rest of day"));
    }

    private ReplanResult addAdhoc(Map<LocalDate, DayPlan> weekPlans, AddAdhoc event) {
        DayPlan plan = weekPlans.get(event.date());
        if (plan == null) {
            return new ReplanResult(weekPlans, List.of("No plan exists for " + event.date()));
        }
        Block adhoc = new Block("adhoc-" + event.date() + "-" + event.start(), event.date(), event.start(), event.end(),
            event.title(), BlockKind.ADHOC, null, BlockStatus.PLANNED, 0, false, null);
        List<Block> blocks = new ArrayList<>(plan.blocks());
        blocks.add(adhoc);
        Map<LocalDate, DayPlan> updated = new LinkedHashMap<>(weekPlans);
        updated.put(event.date(), new DayPlan(plan.date(), blocks, plan.warnings()));
        return new ReplanResult(updated, List.of("Added " + event.title() + " at " + event.start()));
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=ReplannerTest`
Expected: PASS, 6 tests green. If `postponeMovesToNextDayWhenTodayHasNoRoom` fails because a same-day free slot was found unexpectedly, check that Monday's `DayPlan` in the test truly has no `FREE` block after 19:00 — the fixture only contains `certBlock`.

- [ ] **Step 5: Run the full test suite**

Run: `cd backend && ./mvnw -q test`
Expected: BUILD SUCCESS, all tests across every task green.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat: add PlanEvent hierarchy, PostponePolicy, and Replanner" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```
