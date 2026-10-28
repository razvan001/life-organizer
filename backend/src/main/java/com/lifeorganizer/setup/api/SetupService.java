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
import com.lifeorganizer.setup.api.dto.CategoryDto;
import com.lifeorganizer.setup.api.dto.ConstraintDto;
import com.lifeorganizer.setup.api.dto.GoalDto;
import com.lifeorganizer.setup.api.dto.SetupPayload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validates then stores / loads the whole setup. */
@Service
public class SetupService {

    private final SetupStore setupStore;

    public SetupService(SetupStore setupStore) {
        this.setupStore = setupStore;
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
        for (CategoryDto dto : entries(payload.categories(), "categories")) {
            Category category = SetupDtoMapper.toDomain(dto);
            if (categoriesById.put(category.id(), category) != null) {
                throw new InvalidRequestException("Duplicate category id '" + category.id() + "'");
            }
        }

        Map<String, Goal> goalsById = new LinkedHashMap<>();
        for (GoalDto dto : entries(payload.goals(), "goals")) {
            Goal goal = SetupDtoMapper.toDomain(dto, categoriesById);
            if (goalsById.put(goal.id(), goal) != null) {
                throw new InvalidRequestException("Duplicate goal id '" + goal.id() + "'");
            }
        }

        List<Constraint> constraints = new ArrayList<>();
        for (ConstraintDto dto : entries(payload.constraints(), "constraints")) {
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

    /** A missing list means "none"; a null element inside a list is a malformed request, not a server error. */
    private static <T> List<T> entries(List<T> list, String name) {
        if (list == null) {
            return List.of();
        }
        if (list.stream().anyMatch(Objects::isNull)) {
            throw new InvalidRequestException(name + " must not contain null entries");
        }
        return list;
    }
}
