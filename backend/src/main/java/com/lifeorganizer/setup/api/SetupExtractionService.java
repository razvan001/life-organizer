package com.lifeorganizer.setup.api;

import com.lifeorganizer.ai.client.MalformedLlmReplyException;
import com.lifeorganizer.ai.client.SetupExtractionClient;
import com.lifeorganizer.ai.model.SetupExtractionResult;
import com.lifeorganizer.ai.model.SetupExtractionResult.ExtractedGoal;
import com.lifeorganizer.domain.Category;
import com.lifeorganizer.error.AiNotConfiguredException;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.persistence.SetupStore;
import com.lifeorganizer.setup.api.dto.CategoryDto;
import com.lifeorganizer.setup.api.dto.ConstraintDto;
import com.lifeorganizer.setup.api.dto.GoalDto;
import com.lifeorganizer.setup.api.dto.SetupParseResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a free-form brain dump into the same preview shape the regex parser produces. Nothing is saved:
 * the user reviews the preview and saves it like any other. The model's reply is never trusted - every
 * value is defaulted or dropped (with a warning) here, never rejected, because this is only a preview.
 */
@Service
public class SetupExtractionService {

    private static final Logger log = LoggerFactory.getLogger(SetupExtractionService.class);

    private static final int DEFAULT_PRIORITY = 3;
    private static final int DEFAULT_BLOCK_MINUTES = 60;
    private static final Set<String> ENERGIES = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Set<String> PREFERRED_TIMES = Set.of("MORNING", "EVENING", "ANY");

    private static final String SYSTEM_PROMPT = """
        You turn a messy, free-form brain dump of someone's goals, routines and constraints into a
        structured setup: categories, goals, and constraints. Only these three things exist; never
        invent a "task" concept.

        A category is a broad area of life (for example Health, Career, Relationships). A goal belongs
        to exactly one category and has: priority 1 (least) to 5 (most important, guess 3 if unclear);
        deadline yyyy-MM-dd or null; defaultBlockMinutes, how long one session usually takes (guess 60);
        energy LOW, MEDIUM or HIGH that a session requires (guess MEDIUM); preferredTime MORNING,
        EVENING or ANY (guess ANY).

        A constraint is exactly one of four types (never invent another):
        - FIXED_BLOCK: a recurring fixed time slot. Fields: label, start (HH:mm), end (HH:mm),
          days (MONDAY..SUNDAY).
        - FREQUENCY_QUOTA: do one goal N times a week, M minutes each. Fields: label, timesPerWeek,
          sessionMinutes, goalId (must be the id of a goal you output).
        - DURATION_QUOTA: spend N minutes a week in total on a category. Fields: label, minutesPerWeek,
          categoryId (must be the id of a category).
        - EVENING_QUOTA: reserve N evenings a week for a category. Fields: label, eveningsPerWeek,
          categoryId.
        Fill only the fields the type uses; leave every other field null.

        You are given the categories that already exist, with their ids. If a goal clearly belongs to
        one, reuse that exact id as categoryId and do not repeat that category in your output. Only
        invent a new category (lowercase-hyphenated id) when nothing existing fits.

        Every id must use only lowercase letters, digits and hyphens, and be unique within your answer.

        Be conservative: if something is too vague or ambiguous, do not guess. Leave it out and add one
        sentence to "warnings" explaining what you skipped and why.
        """;

    private final ObjectProvider<SetupExtractionClient> clientProvider;
    private final SetupStore setupStore;

    public SetupExtractionService(ObjectProvider<SetupExtractionClient> clientProvider, SetupStore setupStore) {
        this.clientProvider = clientProvider;
        this.setupStore = setupStore;
    }

    public SetupParseResponse extract(String text) {
        SetupExtractionClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new AiNotConfiguredException("AI features are not configured");
        }
        Map<String, String> existing = new LinkedHashMap<>();
        for (Category category : setupStore.loadTree().categories()) {
            existing.put(category.id(), category.name());
        }
        log.info("Extracting setup from {} characters of text ({} existing categories)", text.length(), existing.size());

        SetupExtractionResult raw;
        try {
            raw = client.extract(SYSTEM_PROMPT, userMessage(text, existing));
        } catch (MalformedLlmReplyException e) {
            log.warn("Extraction returned an unusable reply ({})", e.getMessage());
            return new SetupParseResponse(List.of(), List.of(), List.of(), List.of(
                "Could not extract anything from that text. Try rephrasing or use the text boxes instead."));
        }
        SetupParseResponse response = sanitize(raw, existing);
        log.info("Extracted {} categories, {} goals, {} constraints, {} warnings", response.categories().size(),
            response.goals().size(), response.constraints().size(), response.warnings().size());
        return response;
    }

    private SetupParseResponse sanitize(SetupExtractionResult raw, Map<String, String> existing) {
        List<String> warnings = new ArrayList<>();
        if (raw.warnings() != null) {
            raw.warnings().stream().filter(w -> w != null && !w.isBlank()).forEach(warnings::add);
        }
        log.debug("Model returned {} categories, {} goals, {} constraints, warnings={}",
            orEmpty(raw.categories()).size(), orEmpty(raw.goals()).size(), orEmpty(raw.constraints()).size(), warnings);

        // Categories the model returned, deduplicated. An id that already exists keeps the EXISTING name.
        Map<String, CategoryDto> categories = new LinkedHashMap<>();
        for (CategoryDto category : orEmpty(raw.categories())) {
            if (category == null || category.id() == null || category.name() == null
                || categories.containsKey(category.id())) {
                skip(warnings, "Skipped a category the assistant returned without a usable id or name.");
                continue;
            }
            CategoryDto resolved = existing.containsKey(category.id())
                ? new CategoryDto(category.id(), existing.get(category.id())) : category;
            try {
                SetupDtoMapper.toDomain(resolved);
            } catch (InvalidRequestException e) {
                skip(warnings, "Skipped category '" + category.id() + "': " + e.getMessage());
                continue;
            }
            categories.put(resolved.id(), resolved);
        }

        Map<String, GoalDto> goals = new LinkedHashMap<>();
        for (ExtractedGoal raw1 : orEmpty(raw.goals())) {
            if (raw1 == null) {
                continue;
            }
            String label = raw1.name() != null ? raw1.name() : raw1.id();
            CategoryDto category = categories.get(raw1.categoryId());
            if (category == null && raw1.categoryId() != null && existing.containsKey(raw1.categoryId())) {
                category = new CategoryDto(raw1.categoryId(), existing.get(raw1.categoryId()));
            }
            if (category == null) {
                skip(warnings, "Skipped goal '" + label + "': it refers to an unknown category.");
                continue;
            }
            if (raw1.id() == null || raw1.name() == null || goals.containsKey(raw1.id())) {
                skip(warnings, "Skipped goal '" + label + "': missing or duplicate id or name.");
                continue;
            }
            GoalDto goal = new GoalDto(raw1.id(), raw1.categoryId(), raw1.name(),
                raw1.priority() != null && raw1.priority() >= 1 && raw1.priority() <= 5
                    ? raw1.priority() : DEFAULT_PRIORITY,
                raw1.deadline() == null || raw1.deadline().isBlank() ? null : raw1.deadline(),
                raw1.defaultBlockMinutes() != null && raw1.defaultBlockMinutes() > 0
                    ? raw1.defaultBlockMinutes() : DEFAULT_BLOCK_MINUTES,
                raw1.energy() != null && ENERGIES.contains(raw1.energy()) ? raw1.energy() : "MEDIUM",
                raw1.preferredTime() != null && PREFERRED_TIMES.contains(raw1.preferredTime())
                    ? raw1.preferredTime() : "ANY");
            if (!goal.equals(new GoalDto(raw1.id(), raw1.categoryId(), raw1.name(), orZero(raw1.priority()),
                goal.deadline(), orZero(raw1.defaultBlockMinutes()), raw1.energy(), raw1.preferredTime()))) {
                log.debug("Goal '{}': model values adjusted to priority={} minutes={} energy={} time={} (model sent {}/{}/{}/{})",
                    goal.id(), goal.priority(), goal.defaultBlockMinutes(), goal.energy(), goal.preferredTime(),
                    raw1.priority(), raw1.defaultBlockMinutes(), raw1.energy(), raw1.preferredTime());
            }
            try {
                SetupDtoMapper.toDomain(goal,Map.of(category.id(), SetupDtoMapper.toDomain(category)));
            } catch (InvalidRequestException e) {
                skip(warnings, "Skipped goal '" + label + "': " + e.getMessage());
                continue;
            }
            goals.put(goal.id(), goal);
            categories.putIfAbsent(category.id(), category);
        }

        List<ConstraintDto> constraints = new ArrayList<>();
        for (ConstraintDto constraint : orEmpty(raw.constraints())) {
            if (constraint == null) {
                continue;
            }
            try {
                SetupDtoMapper.toDomain(constraint);
            } catch (InvalidRequestException e) {
                skip(warnings, "Skipped constraint '" + constraint.label() + "': " + e.getMessage());
                continue;
            }
            String problem = brokenReference(constraint, goals.keySet(), categories, existing);
            if (problem != null) {
                skip(warnings, "Skipped constraint '" + constraint.label() + "': " + problem);
                continue;
            }
            if (constraint.categoryId() != null && !categories.containsKey(constraint.categoryId())) {
                categories.put(constraint.categoryId(),
                    new CategoryDto(constraint.categoryId(), existing.get(constraint.categoryId())));
            }
            constraints.add(constraint);
        }
        return new SetupParseResponse(new ArrayList<>(categories.values()), new ArrayList<>(goals.values()),
            constraints, warnings);
    }

    private static void skip(List<String> warnings, String message) {
        log.warn("Extraction dropped an item: {}", message);
        warnings.add(message);
    }

    private static String brokenReference(ConstraintDto constraint, Set<String> goalIds,
                                          Map<String, CategoryDto> categories, Map<String, String> existing) {
        return switch (constraint.type()) {
            case "FREQUENCY_QUOTA" -> goalIds.contains(constraint.goalId()) ? null : "it refers to an unknown goal.";
            case "DURATION_QUOTA", "EVENING_QUOTA" ->
                categories.containsKey(constraint.categoryId()) || existing.containsKey(constraint.categoryId())
                    ? null : "it refers to an unknown category.";
            default -> null;
        };
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String userMessage(String text, Map<String, String> existing) {
        StringBuilder sb = new StringBuilder();
        if (existing.isEmpty()) {
            sb.append("No categories exist yet.\n");
        } else {
            sb.append("Existing categories:\n");
            existing.forEach((id, name) -> sb.append("- id=").append(id).append(" name=\"").append(name).append("\"\n"));
        }
        sb.append("\nText to extract from:\n").append(text);
        return sb.toString();
    }
}
