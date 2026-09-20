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
