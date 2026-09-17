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
