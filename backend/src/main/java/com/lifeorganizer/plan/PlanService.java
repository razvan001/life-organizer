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
