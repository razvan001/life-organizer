package com.lifeorganizer.ai.agent;

import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.engine.PlanEvent;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.plan.dto.ApplyEventRequest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks events the model wants to propose before they reach the engine: today only, real block ids, never
 * FREE blocks (open time, not a commitment; its id also changes when an ad-hoc block splits it), 1 to 5 events,
 * and every event must pass the engine's own request rules.
 */
public final class ProposedEventValidator {

    public static final int MAX_EVENTS = 5;

    private ProposedEventValidator() {
    }

    public static List<PlanEvent> validate(List<ApplyEventRequest> events, LocalDate today, List<Block> todaysBlocks) {
        if (events == null || events.isEmpty() || events.size() > MAX_EVENTS) {
            throw new InvalidRequestException("propose between 1 and " + MAX_EVENTS + " events");
        }
        Map<String, Block> known = new HashMap<>();
        for (Block block : todaysBlocks) {
            known.put(block.id(), block);
        }
        List<PlanEvent> result = new ArrayList<>();
        for (ApplyEventRequest request : events) {
            if (request == null) {
                throw new InvalidRequestException("an event is missing");
            }
            String blockId = request.blockId();
            if (blockId != null && !blockId.isBlank()) {
                Block target = known.get(blockId);
                if (target == null) {
                    throw new InvalidRequestException("unknown blockId '" + blockId + "': use an id from today's plan");
                }
                if (target.kind() == BlockKind.FREE) {
                    throw new InvalidRequestException(
                        "block '" + blockId + "' is free time, not something that can be changed");
                }
            }
            if ("ADD_ADHOC".equals(request.type()) && request.date() != null && !request.date().isBlank()
                && !request.date().equals(today.toString())) {
                throw new InvalidRequestException("ADD_ADHOC can only add to today (" + today + ")");
            }
            result.add(request.toEvent(today));
        }
        return result;
    }
}
