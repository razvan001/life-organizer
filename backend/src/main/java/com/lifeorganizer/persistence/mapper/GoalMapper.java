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
