package com.lifeorganizer.plan;

import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.domain.BlockStatus;
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
import java.util.ArrayList;
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

    /**
     * Like {@link #applyEvent}, but for several events at once and without persisting anything - used
     * to build a preview/proposal. Events are chained: each one sees the plan as the previous one left it.
     * Plain {@code @Transactional} (not read-only): {@link #loadDaysFor} can still lazily generate and
     * persist a day the first time it is touched (e.g. a postpone looking at later days of the week for
     * room) - that write is pre-existing, correct behavior, and a read-only transaction would make
     * Postgres reject it. This method still never calls {@link PlanStore#applyChanges}, so it never
     * persists a plan-event change.
     */
    @Transactional
    public AppliedEvent simulateEvents(List<PlanEvent> events, LocalDate today) {
        Map<LocalDate, DayPlan> current = new LinkedHashMap<>();
        List<String> allChanges = new ArrayList<>();
        for (PlanEvent event : events) {
            LocalDate anchor = dateOf(event);
            for (Map.Entry<LocalDate, DayPlan> entry : loadDaysFor(event, anchor).entrySet()) {
                current.putIfAbsent(entry.getKey(), entry.getValue());
            }
            ReplanResult result = replanner.apply(current, event);
            current = new LinkedHashMap<>(result.updatedPlans());
            allChanges.addAll(result.changes());
        }
        DayPlan todayPlan = current.getOrDefault(today, getOrGenerate(today));
        return new AppliedEvent(todayPlan, allChanges);
    }

    /**
     * Applies every event and persists all of them in one transaction: if any event fails, none of the
     * batch's changes are written. Plain REQUIRED (the default, same as every other method here): a
     * controller caller has no ambient transaction, so this opens one real transaction and a failure rolls
     * it back immediately. It may also join a caller's transaction (ChatService.apply), in which case an
     * engine failure marks that transaction rollback-only and rolls back the caller's work too.
     */
    @Transactional
    public AppliedEvent applyEvents(List<PlanEvent> events, LocalDate today) {
        Map<LocalDate, DayPlan> current = new LinkedHashMap<>();
        List<String> allChanges = new ArrayList<>();
        for (PlanEvent event : events) {
            LocalDate anchor = dateOf(event);
            for (Map.Entry<LocalDate, DayPlan> entry : loadDaysFor(event, anchor).entrySet()) {
                current.putIfAbsent(entry.getKey(), entry.getValue());
            }
            ReplanResult result = replanner.apply(current, event);
            planStore.applyChanges(current, result.updatedPlans());
            current = new LinkedHashMap<>(result.updatedPlans());
            allChanges.addAll(result.changes());
        }
        return new AppliedEvent(getOrGenerate(today), allChanges);
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
