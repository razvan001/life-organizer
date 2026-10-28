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
