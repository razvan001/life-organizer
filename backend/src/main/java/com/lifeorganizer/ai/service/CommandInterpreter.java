package com.lifeorganizer.ai.service;

import com.lifeorganizer.ai.client.LlmCommandClient;
import com.lifeorganizer.ai.client.MalformedLlmReplyException;
import com.lifeorganizer.ai.model.LlmCommandResult;
import com.lifeorganizer.ai.model.InterpretedCommand;
import com.lifeorganizer.ai.model.ProposedEvent;
import com.lifeorganizer.ai.model.ProposedEventType;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.domain.BlockKind;
import com.lifeorganizer.error.AiNotConfiguredException;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.plan.dto.ApplyEventRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one sentence of free text into zero or more validated engine events. Never mutates the plan -
 * every event it returns still has to go through {@code PlanService.simulateEvents}/{@code applyEvents}.
 */
@Service
public class CommandInterpreter {

    private static final Logger log = LoggerFactory.getLogger(CommandInterpreter.class);
    private static final int MAX_EVENTS = 5;

    private static final String SYSTEM_PROMPT = """
        You help the user adjust their daily plan by turning one sentence of free text into zero or more
        structured events. You are given today's date and today's blocks (id, title, kind, status, start,
        end). You may only propose these event types, and every blockId you use must be exactly one of the
        ids given to you:
        - CANCEL_BLOCK: cancel a block (blockId required)
        - POSTPONE_BLOCK: postpone a block later today or later this week (blockId required, reason optional)
        - MARK_DONE: mark a block done (blockId required)
        - SET_ENERGY: set a block's energy to LOW or MEDIUM (blockId and energy required)
        - ADD_ADHOC: add a new block (date yyyy-MM-dd, title, start and end HH:mm all required)
        Only ever target blocks that are still PLANNED. Never target a block whose kind is FREE - that is
        leftover open time, not a real commitment, so it can never be cancelled, postponed, marked done or
        have its energy set. Never invent a blockId. If the text does not clearly
        map to at least one of these events against a real block, set understood to false and explain why in
        message instead of guessing. Propose at most 5 events. Keep rationale to one short sentence.
        """;

    private final ObjectProvider<LlmCommandClient> clientProvider;

    public CommandInterpreter(ObjectProvider<LlmCommandClient> clientProvider) {
        this.clientProvider = clientProvider;
    }

    public InterpretedCommand interpret(String text, LocalDate today, List<Block> todaysBlocks) {
        log.info("Interpreting command for {}: \"{}\"", today, text);
        LlmCommandClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new AiNotConfiguredException("AI features are not configured");
        }

        LlmCommandResult raw;
        try {
            raw = client.interpret(SYSTEM_PROMPT, userMessage(text, today, todaysBlocks));
        } catch (MalformedLlmReplyException e) {
            log.warn("Downgrading to not-understood: malformed reply ({})", e.getMessage());
            return InterpretedCommand.notUnderstood("I couldn't understand that.");
        }

        if (!raw.understood()) {
            log.info("Model reported not understood: {}", raw.message());
            return InterpretedCommand.notUnderstood(raw.message() != null ? raw.message() : "I couldn't understand that.");
        }
        if (raw.events().isEmpty() || raw.events().size() > MAX_EVENTS) {
            log.warn("Downgrading to not-understood: {} events proposed (0 or more than {} not allowed)",
                raw.events().size(), MAX_EVENTS);
            return InterpretedCommand.notUnderstood("That would need too many changes at once, or none at all.");
        }

        Map<String, Block> knownBlocks = new LinkedHashMap<>();
        for (Block block : todaysBlocks) {
            knownBlocks.put(block.id(), block);
        }

        List<ApplyEventRequest> events = new ArrayList<>();
        for (ProposedEvent proposed : raw.events()) {
            String blockId = proposed.blockId();
            Block target = blockId == null ? null : knownBlocks.get(blockId);
            if (blockId != null && target == null) {
                log.warn("Downgrading to not-understood: unknown blockId '{}' (known ids: {})", blockId, knownBlocks.keySet());
                return InterpretedCommand.notUnderstood("I couldn't find a matching block in today's plan.");
            }
            // FREE blocks are leftover gaps, not real commitments: never a sensible target, and not
            // stable across a replan (e.g. an earlier ADD_ADHOC in the same batch can split or remove one).
            if (target != null && target.kind() == BlockKind.FREE) {
                log.warn("Downgrading to not-understood: blockId '{}' is a FREE block, not a valid target", blockId);
                return InterpretedCommand.notUnderstood("I couldn't find a matching block in today's plan.");
            }
            if (proposed.type() == ProposedEventType.ADD_ADHOC) {
                String date = proposed.date();
                if (date != null && !date.isBlank() && !date.equals(today.toString())) {
                    log.warn("Downgrading to not-understood: ADD_ADHOC date '{}' is not today ({})", date, today);
                    return InterpretedCommand.notUnderstood("I can only add events to today's plan.");
                }
            }
            ApplyEventRequest request = new ApplyEventRequest(proposed.type().name(), blockId,
                proposed.reason(), proposed.date(), proposed.title(),
                proposed.start(), proposed.end(), proposed.energy());
            try {
                request.toEvent(today);
            } catch (InvalidRequestException e) {
                log.warn("Downgrading to not-understood: engine rejected the event ({})", e.getMessage());
                return InterpretedCommand.notUnderstood("I couldn't turn that into a valid change: " + e.getMessage());
            }
            events.add(request);
        }

        log.info("Understood: {} event(s) proposed", events.size());
        return InterpretedCommand.understood(raw.rationale() != null ? raw.rationale() : "", events);
    }

    private String userMessage(String text, LocalDate today, List<Block> todaysBlocks) {
        StringBuilder sb = new StringBuilder();
        sb.append("Today is ").append(today).append(".\n");
        sb.append("Today's blocks:\n");
        for (Block block : todaysBlocks) {
            sb.append("- id=").append(block.id())
                .append(" title=\"").append(block.title()).append('"')
                .append(" kind=").append(block.kind())
                .append(" status=").append(block.status())
                .append(" start=").append(block.start())
                .append(" end=").append(block.end())
                .append('\n');
        }
        sb.append("\nUser's text: ").append(text);
        return sb.toString();
    }
}
