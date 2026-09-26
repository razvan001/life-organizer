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

    static final int MAX_TEXT_LENGTH = 255;

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
        limited(dto.id(), "category id");
        limited(dto.name(), "category name");
        return build("category '" + dto.id() + "'", () -> new Category(dto.id(), dto.name()));
    }

    public static Goal toDomain(GoalDto dto, Map<String, Category> categoriesById) {
        limited(dto.id(), "goal id");
        limited(dto.name(), "goal name");
        limited(dto.categoryId(), "goal categoryId");
        Category category = categoriesById.get(dto.categoryId());
        if (category == null) {
            throw new InvalidRequestException(
                "Goal '" + dto.id() + "' refers to unknown category '" + dto.categoryId() + "'");
        }
        return build("goal '" + dto.id() + "'", () -> new Goal(dto.id(), category, dto.name(), dto.priority(),
            dto.deadline() == null || dto.deadline().isBlank() ? null : TimeFormats.parseDate(dto.deadline(), "deadline"),
            dto.defaultBlockMinutes(),
            parseEnum(Energy.class, dto.energy(), "energy"),
            parseEnum(PreferredTime.class, dto.preferredTime(), "preferredTime")));
    }

    public static Constraint toDomain(ConstraintDto dto) {
        if (dto.type() == null) {
            throw new InvalidRequestException("Constraint '" + dto.label() + "' has no type");
        }
        limited(dto.label(), "constraint label");
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

    /** Text columns are varchar(255); reject longer values up front instead of failing in the database. */
    private static void limited(String value, String field) {
        if (value != null && value.length() > MAX_TEXT_LENGTH) {
            throw new InvalidRequestException(field + " must be at most " + MAX_TEXT_LENGTH + " characters");
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
