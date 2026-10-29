package com.lifeorganizer.persistence.mapper;

import com.lifeorganizer.domain.Constraint;
import com.lifeorganizer.domain.FixedBlock;
import com.lifeorganizer.persistence.ConstraintEntity;
import com.lifeorganizer.persistence.FixedBlockEntity;

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
        };
    }

    public static Constraint toDomain(ConstraintEntity entity) {
        if (entity instanceof FixedBlockEntity f) {
            return new FixedBlock(f.getLabel(), f.getStartTime(), f.getEndTime(), csvToDays(f.getDays()));
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
