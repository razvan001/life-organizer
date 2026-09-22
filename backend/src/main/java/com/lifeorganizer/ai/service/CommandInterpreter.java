package com.lifeorganizer.ai.service;

import com.lifeorganizer.ai.client.ClaudeCommandClient;
import com.lifeorganizer.ai.client.MalformedClaudeReplyException;
import com.lifeorganizer.ai.model.ClaudeCommandResult;
import com.lifeorganizer.ai.model.InterpretedCommand;
import com.lifeorganizer.ai.model.ProposedEvent;
import com.lifeorganizer.ai.model.ProposedEventType;
import com.lifeorganizer.domain.Block;
import com.lifeorganizer.error.AiNotConfiguredException;
import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.plan.dto.ApplyEventRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns one sentence of free text into zero or more validated engine events. Never mutates the plan -
 * every event it returns still has to go through {@code PlanService.simulateEvents}/{@code applyEvents}.
 */
@Service
public class CommandInterpreter {

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
        Only ever target blocks that are still PLANNED. Never invent a blockId. If the text does not clearly
        map to at least one of these events against a real block, set understood to false and explain why in
        message instead of guessing. Propose at most 5 events. Keep rationale to one short sentence.
        """;

    private final ObjectProvider<ClaudeCommandClient> clientProvider;

    public CommandInterpreter(ObjectProvider<ClaudeCommandClient> clientProvider) {
        this.clientProvider = clientProvider;
    }

    public InterpretedCommand interpret(String text, LocalDate today, List<Block> todaysBlocks) {
        ClaudeCommandClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new AiNotConfiguredException("AI features are not configured");
        }

        ClaudeCommandResult raw;
        try {
            raw = client.interpret(SYSTEM_PROMPT, userMessage(text, today, todaysBlocks));
        } catch (MalformedClaudeReplyException e) {
            return InterpretedCommand.notUnderstood("I couldn't understand that.");
        }

        if (!raw.understood()) {
            return InterpretedCommand.notUnderstood(raw.message().orElse("I couldn't understand that."));
        }
        if (raw.events().isEmpty() || raw.events().size() > MAX_EVENTS) {
            return InterpretedCommand.notUnderstood("That would need too many changes at once, or none at all.");
        }

        Set<String> knownIds = new LinkedHashSet<>();
        for (Block block : todaysBlocks) {
            knownIds.add(block.id());
        }

        List<ApplyEventRequest> events = new ArrayList<>();
        for (ProposedEvent proposed : raw.events()) {
            String blockId = proposed.blockId().orElse(null);
            if (blockId != null && !knownIds.contains(blockId)) {
                return InterpretedCommand.notUnderstood("I couldn't find a matching block in today's plan.");
            }
            if (proposed.type() == ProposedEventType.ADD_ADHOC) {
                String date = proposed.date().orElse(null);
                if (date != null && !date.isBlank() && !date.equals(today.toString())) {
                    return InterpretedCommand.notUnderstood("I can only add events to today's plan.");
                }
            }
            ApplyEventRequest request = new ApplyEventRequest(proposed.type().name(), blockId,
                proposed.reason().orElse(null), proposed.date().orElse(null), proposed.title().orElse(null),
                proposed.start().orElse(null), proposed.end().orElse(null), proposed.energy().orElse(null));
            try {
                request.toEvent(today);
            } catch (InvalidRequestException e) {
                return InterpretedCommand.notUnderstood("I couldn't turn that into a valid change: " + e.getMessage());
            }
            events.add(request);
        }

        return InterpretedCommand.understood(raw.rationale().orElse(""), events);
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
